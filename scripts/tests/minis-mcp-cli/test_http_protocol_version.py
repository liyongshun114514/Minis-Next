#!/usr/bin/env python3
"""[T-mcp-http-protocol-version] Strict regression tests for the streamable-HTTP
MCP `MCP-Protocol-Version` negotiation.

Run: python3 test_http_protocol_version.py   (stdlib unittest + httpx
MockTransport; no network, no config, no credentials)

MCP 2025-06-18 makes the negotiated protocol version part of the TRANSPORT
state: `initialize` returns `result.protocolVersion`, and every later HTTP
request must carry it in the `MCP-Protocol-Version` header. The transport used
to (a) never send that header, (b) let a user-configured header of any casing
win, and (c) swallow failures of `notifications/initialized`. Each of those
turns into an opaque 400 from a strict server.

The fake server below is deliberately strict — it records a violation whenever
a request is off-spec and answers 400 for a missing/wrong version header, so a
regression fails loudly instead of silently:

  * `initialize` must NOT carry `Mcp-Session-Id` or `MCP-Protocol-Version`;
  * `notifications/initialized`, `tools/list` and `tools/call` must carry
    EXACTLY ONE `MCP-Protocol-Version`, equal to the negotiated value, whether
    or not a session id was assigned;
  * one handshake per transport lifetime (repeated list/call must not
    re-initialize);
  * reset / ping / session-expiry reconnect start a clean handshake — no stale
    header — and adopt the version the server returns that time;
  * a 400 on `notifications/initialized` propagates to the caller and leaves no
    half-open state (next call re-handshakes);
  * a bogus `result.protocolVersion` (missing / non-str / empty / not a legal
    header value) raises MCPError(PROTOCOL_ERROR) and rolls the session state
    back.

No date whitelist is asserted: a server may answer with any legal version
string (e.g. 2025-06-18, 2025-11-25, or a future one) and the transport must
adopt exactly what it was given.
"""

import json
import os
import sys
import unittest

# The package ships inside each app's default mount. The two copies are kept in
# step, but a platform can be published ahead of the other, so the default is
# the Android copy; set MINIS_MCP_CLI_DIR to test the iOS one
# (src/ios/default_mount/usr/local/lib/minis-mcp-cli).
_PKG = os.environ.get("MINIS_MCP_CLI_DIR") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "src", "android", "app", "src", "main", "assets", "default_mount",
    "usr", "local", "lib", "minis-mcp-cli")
sys.path.insert(0, os.path.abspath(_PKG))

import httpx  # noqa: E402

import transport.http as http_mod  # noqa: E402
from transport.http import HTTPTransport, MCPError  # noqa: E402

PROTOCOL_HEADER = "MCP-Protocol-Version"
SESSION_HEADER = "Mcp-Session-Id"

# Baseline versions a well-behaved server may answer with. Nothing downstream
# may assume these exact strings — they are only the fixtures.
BASE_VERSION = "2025-06-18"
ALT_VERSION = "2025-11-25"
FUTURE_VERSION = "2026-03-31"


class _Missing:
    """Sentinel: the server omits `result.protocolVersion` entirely."""

    def __repr__(self):
        return "MISSING"


MISSING = _Missing()


def _values(request, name):
    """All values of `name`, case-insensitively, in wire order.

    Uses multi_items() rather than headers.get() so a duplicated header (the
    exact failure mode of a surviving user-supplied case variant) shows up as
    two entries instead of being silently collapsed."""
    return [v for (k, v) in request.headers.multi_items()
            if k.lower() == name.lower()]


class _Recorded:
    """One request as the fake server saw it."""

    __slots__ = ("method", "protocol", "session", "body")

    def __init__(self, method, protocol, session, body):
        self.method = method
        self.protocol = protocol
        self.session = session
        self.body = body

    def __repr__(self):
        return "<%s protocol=%r session=%r>" % (
            self.method, self.protocol, self.session)


class StrictServer:
    """A streamable-HTTP MCP server that rejects off-spec requests.

    Configure with:
      versions        - protocolVersion to answer with per handshake, in order
                        (the last entry repeats); items may be MISSING or any
                        non-string to simulate a broken server.
      session_id      - str to mint on initialize, None for a stateless server.
      notify_failures - how many `notifications/initialized` calls to 400.
      session_drops   - how many post-init calls to reject as "no valid
                        session", i.e. the server forgot us (restart/eviction).
      session_drop_status - status for those rejections (default 400).
    """

    def __init__(self, versions=None, session_id="sess-strict-1",
                 notify_failures=0, session_drops=0, session_drop_status=400):
        self.versions = list(versions) if versions else [BASE_VERSION]
        self.session_id = session_id
        self.notify_failures = notify_failures
        self.session_drops = session_drops
        self.session_drop_status = session_drop_status
        self.handshake_count = 0
        self.negotiated = None
        self.requests = []
        self.violations = []

    # -- introspection helpers for the tests ------------------------------

    def methods(self):
        return [r.method for r in self.requests]

    def handshakes(self):
        return [r for r in self.requests if r.method == "initialize"]

    def after_handshake(self):
        return [r for r in self.requests if r.method != "initialize"]

    def requests_of(self, method):
        return [r for r in self.requests if r.method == method]

    # -- server behaviour --------------------------------------------------

    def _next_version(self):
        idx = min(self.handshake_count, len(self.versions) - 1)
        self.handshake_count += 1
        return self.versions[idx]

    def _violate(self, message):
        self.violations.append(message)

    def _check_version(self, method, proto):
        """Exactly one MCP-Protocol-Version, equal to what we negotiated."""
        if self.negotiated is None:
            if proto:
                self._violate("%s: unexpected %s=%r (nothing negotiated yet)"
                              % (method, PROTOCOL_HEADER, proto))
                return False
            return True
        if len(proto) != 1:
            self._violate("%s: expected exactly one %s header, got %r"
                          % (method, PROTOCOL_HEADER, proto))
            return False
        if proto[0] != self.negotiated:
            self._violate("%s: %s=%r but negotiated %r"
                          % (method, PROTOCOL_HEADER, proto[0],
                             self.negotiated))
            return False
        return True

    def _check_session(self, method, sid):
        if self.session_id is None:
            return True
        if sid != [self.session_id]:
            self._violate("%s: %s=%r but expected [%r]"
                          % (method, SESSION_HEADER, sid, self.session_id))
            return False
        return True

    def handler(self, request):
        body = json.loads(request.content.decode("utf-8"))
        method = body.get("method")
        proto = _values(request, PROTOCOL_HEADER)
        sid = _values(request, SESSION_HEADER)
        self.requests.append(_Recorded(method, proto, sid, body))

        if method == "initialize":
            return self._handle_initialize(body, proto, sid)

        if not self._check_version(method, proto):
            return _bad_request("Missing or invalid %s header"
                                % PROTOCOL_HEADER)

        if method == "notifications/initialized":
            if self.notify_failures > 0:
                self.notify_failures -= 1
                return _bad_request("notifications/initialized rejected")
            if not self._check_session(method, sid):
                return _bad_request("No valid session ID provided")
            return httpx.Response(202, text="")

        # Session eviction bites the tool calls, not the notification that
        # rightfully follows the handshake.
        if self.session_drops > 0:
            self.session_drops -= 1
            return httpx.Response(self.session_drop_status,
                                  text="Bad Request: No valid session ID "
                                       "provided")
        if not self._check_session(method, sid):
            return httpx.Response(400,
                                  text="Bad Request: No valid session ID "
                                       "provided")

        if method == "tools/list":
            return _json_ok(body, {"tools": [{"name": "echo"}]})
        if method == "tools/call":
            return _json_ok(body, {"ok": True,
                                   "args": body.get("params", {}).get(
                                       "arguments")})
        return _json_ok(body, {})

    def _handle_initialize(self, body, proto, sid):
        # A fresh handshake is anonymous AND version-less: the version is what
        # this very response is about to decide.
        if proto:
            self._violate("initialize: carried %s=%r (must be absent)"
                          % (PROTOCOL_HEADER, proto))
        if sid:
            self._violate("initialize: carried %s=%r (must be absent)"
                          % (SESSION_HEADER, sid))
        version = self._next_version()
        result = {"capabilities": {},
                  "serverInfo": {"name": "strict-fake", "version": "0.0.0"}}
        if version is not MISSING:
            result["protocolVersion"] = version
        # Remember what we just told the client; from now on that value is the
        # only acceptable header.
        self.negotiated = version if isinstance(version, str) else None
        headers = {"content-type": "application/json"}
        if self.session_id is not None:
            headers[SESSION_HEADER] = self.session_id
        return httpx.Response(200, headers=headers,
                              text=json.dumps({"jsonrpc": "2.0",
                                               "id": body.get("id"),
                                               "result": result}))


def _json_ok(body, result):
    return httpx.Response(200, headers={"content-type": "application/json"},
                          text=json.dumps({"jsonrpc": "2.0",
                                           "id": body.get("id"),
                                           "result": result}))


def _bad_request(message):
    return httpx.Response(400, text="Bad Request: %s" % message)


_REAL_CLIENT = httpx.Client


class _PatchedTransport:
    """Route the transport's HTTP calls through a MockTransport.

    transport.http calls the module-level `httpx.post`, so swap that for a
    closure over a Client bound to the mock transport. `httpx.Client` is
    shimmed too, so an implementation that builds a Client per request still
    stays on the fake server instead of opening a socket."""

    def __init__(self, server):
        self._server = server
        self._client = _REAL_CLIENT(
            transport=httpx.MockTransport(server.handler))
        self._orig_post = http_mod.httpx.post
        self._orig_client = http_mod.httpx.Client

    def __enter__(self):
        def _post(url, **kwargs):
            # The fake Client handles routing; Client.post has no proxy kwargs.
            for key in ('proxy', 'proxies', 'trust_env'):
                kwargs.pop(key, None)
            return self._client.post(url, **kwargs)

        def _client(*args, **kwargs):
            kwargs.setdefault("transport", httpx.MockTransport(
                self._server.handler))
            return _REAL_CLIENT(*args, **kwargs)

        http_mod.httpx.post = _post
        http_mod.httpx.Client = _client
        return self

    def __exit__(self, *exc):
        http_mod.httpx.post = self._orig_post
        http_mod.httpx.Client = self._orig_client
        self._client.close()
        return False


def _make_transport(server=None, headers=None):
    cfg = {"url": "https://example.test/mcp"}
    if headers is not None:
        cfg["headers"] = headers
    return HTTPTransport(cfg, "protocol-version-test")


def _reset(transport):
    """Drop the session + negotiated version.

    The transport exposes `_reset_session()`; a public `reset()` may be added,
    so prefer it when present."""
    reset = getattr(transport, "reset", None)
    if callable(reset):
        reset()
    else:
        transport._reset_session()


class _ProtocolTestCase(unittest.TestCase):
    """Shared assertions about the transport's invariants."""

    def assertProtocolHeader(self, record, expected):
        self.assertEqual(
            record.protocol, [expected],
            "%s must carry exactly one %s=%r, saw %r"
            % (record.method, PROTOCOL_HEADER, expected, record.protocol))

    def assertNoHandshakeLeak(self, server):
        self.assertEqual(
            server.violations, [],
            "strict server saw off-spec requests: %r" % (server.violations,))

    def assertNoProtocolHeader(self, record):
        self.assertEqual(
            record.protocol, [],
            "%s must not carry %s, saw %r"
            % (record.method, PROTOCOL_HEADER, record.protocol))

    def assertNoSessionHeader(self, record):
        self.assertEqual(
            record.session, [],
            "%s must not carry %s, saw %r"
            % (record.method, SESSION_HEADER, record.session))

    def assertFreshHandshake(self, record):
        """A handshake after a reset/expiry starts from nothing."""
        self.assertNoProtocolHeader(record)
        self.assertNoSessionHeader(record)

    def assertState(self, transport, initialized, version, session):
        self.assertIs(getattr(transport, "_initialized", None), initialized,
                      "_initialized should be %r" % (initialized,))
        self.assertEqual(getattr(transport, "_protocol_version", "MISSING"),
                         version,
                         "_protocol_version should be %r" % (version,))
        self.assertEqual(getattr(transport, "_session_id", "MISSING"), session,
                         "_session_id should be %r" % (session,))


class InitialStateTests(_ProtocolTestCase):
    def test_new_transport_has_no_protocol_version(self):
        t = _make_transport()
        self.assertTrue(hasattr(t, "_protocol_version"),
                        "HTTPTransport must expose _protocol_version")
        self.assertIsNone(t._protocol_version)
        self.assertIsNone(t._session_id)
        self.assertFalse(t._initialized)


class HandshakeTests(_ProtocolTestCase):
    def test_initialize_carries_no_session_or_version_header(self):
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.initialize()
        inits = server.handshakes()
        self.assertEqual(len(inits), 1)
        self.assertFreshHandshake(inits[0])
        self.assertNoHandshakeLeak(server)

    def test_initialize_adopts_server_version(self):
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            result = t.initialize()
            self.assertEqual(t._protocol_version, BASE_VERSION)
            self.assertTrue(t._initialized)
        self.assertEqual(result["protocolVersion"], BASE_VERSION)

    def test_adopts_older_version_from_server(self):
        # Server answers with the pre-2025-11-25 version: the transport must
        # use the SERVER's value, not whatever it offered.
        server = StrictServer(versions=["2025-03-26"])
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
        self.assertEqual(t._protocol_version, "2025-03-26")
        self.assertProtocolHeader(server.requests_of("tools/call")[-1],
                                  "2025-03-26")
        self.assertNoHandshakeLeak(server)

    def test_adopts_newer_version_from_server(self):
        server = StrictServer(versions=[ALT_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
        self.assertEqual(t._protocol_version, ALT_VERSION)
        self.assertProtocolHeader(server.requests_of("tools/call")[-1],
                                  ALT_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_adopts_future_version_from_server(self):
        # No date whitelist: a legal-looking version the client never heard of
        # must still be adopted verbatim.
        server = StrictServer(versions=[FUTURE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.list_tools()
            t.call_tool("echo", {"n": 2})
        self.assertEqual(t._protocol_version, FUTURE_VERSION)
        for record in server.after_handshake():
            self.assertProtocolHeader(record, FUTURE_VERSION)
        self.assertNoHandshakeLeak(server)


class HeaderPropagationTests(_ProtocolTestCase):
    def test_notification_and_calls_carry_negotiated_version(self):
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.list_tools()
            t.call_tool("echo", {"n": 1})
        methods = server.methods()
        self.assertEqual(methods,
                         ["initialize", "notifications/initialized",
                          "tools/list", "tools/call"])
        for record in server.after_handshake():
            self.assertProtocolHeader(record, BASE_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_version_header_sent_without_session(self):
        # Stateless server: no Mcp-Session-Id is ever issued, yet the protocol
        # version header is still mandatory.
        server = StrictServer(versions=[BASE_VERSION], session_id=None)
        with _PatchedTransport(server):
            t = _make_transport()
            t.list_tools()
            t.call_tool("echo", {"n": 1})
        self.assertIsNone(t._session_id)
        for record in server.requests:
            self.assertNoSessionHeader(record)
        for record in server.after_handshake():
            self.assertProtocolHeader(record, BASE_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_version_header_sent_with_session(self):
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
        self.assertEqual(t._session_id, server.session_id)
        call = server.requests_of("tools/call")[-1]
        self.assertProtocolHeader(call, BASE_VERSION)
        self.assertEqual(call.session, [server.session_id])
        self.assertNoHandshakeLeak(server)

    def test_user_config_header_cannot_override_negotiated_version(self):
        # Any casing, any old value: the user's copy must be dropped and
        # replaced by the single negotiated header.
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport(headers={
                "mcp-protocol-version": "2024-11-05",
                "MCP-PROTOCOL-VERSION": "2025-03-26",
                "X-Test-Tag": "protocol-version-test",
            })
            t.list_tools()
            t.call_tool("echo", {"n": 3})
        self.assertEqual(t._protocol_version, BASE_VERSION)
        self.assertNoHandshakeLeak(server)
        for record in server.requests:
            if record.method == "initialize":
                self.assertNoProtocolHeader(record)
            else:
                self.assertProtocolHeader(record, BASE_VERSION)

    def test_every_request_has_exactly_one_version_header(self):
        server = StrictServer(versions=[ALT_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.list_tools()
            t.call_tool("echo", {"n": 1})
            t.ping()
            t.list_tools()
        self.assertNoHandshakeLeak(server)
        for record in server.after_handshake():
            self.assertEqual(
                len(record.protocol), 1,
                "%s carried %r" % (record.method, record.protocol))


class SingleHandshakeTests(_ProtocolTestCase):
    def test_consecutive_list_and_calls_handshake_once(self):
        server = StrictServer(versions=[BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.list_tools()
            t.call_tool("echo", {"n": 1})
            t.call_tool("echo", {"n": 2})
            t.list_tools()
        self.assertEqual(len(server.handshakes()), 1,
                         "expected one handshake, got %d"
                         % len(server.handshakes()))
        self.assertEqual(
            len(server.requests_of("notifications/initialized")), 1)
        for record in server.after_handshake():
            self.assertProtocolHeader(record, BASE_VERSION)
        self.assertNoHandshakeLeak(server)


class RenegotiationTests(_ProtocolTestCase):
    def test_explicit_reinitialize_starts_a_fresh_handshake(self):
        # initialize() is a handshake, so it must never inherit the previous
        # one's session or version header even when no reset() happened first.
        server = StrictServer(versions=[BASE_VERSION, ALT_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.initialize()
            self.assertEqual(t._protocol_version, BASE_VERSION)

            t.initialize()

        inits = server.handshakes()
        self.assertEqual(len(inits), 2)
        self.assertFreshHandshake(inits[0])
        self.assertFreshHandshake(inits[1])
        self.assertEqual(t._protocol_version, ALT_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_reset_drops_version_and_renegotiates(self):
        server = StrictServer(versions=[BASE_VERSION, ALT_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
            self.assertEqual(t._protocol_version, BASE_VERSION)

            _reset(t)
            self.assertState(t, False, None, None)

            t.list_tools()   # must handshake again, with no stale header
        inits = server.handshakes()
        self.assertEqual(len(inits), 2)
        self.assertFreshHandshake(inits[1])
        self.assertEqual(t._protocol_version, ALT_VERSION)
        self.assertProtocolHeader(server.requests_of("tools/list")[-1],
                                  ALT_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_ping_renegotiates_without_old_header(self):
        server = StrictServer(versions=[BASE_VERSION, ALT_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})
            self.assertEqual(t._protocol_version, BASE_VERSION)

            self.assertTrue(t.ping())
            self.assertEqual(t._protocol_version, ALT_VERSION)

            # ping left a live handshake behind: no third one for this call.
            t.call_tool("echo", {"n": 2})
        inits = server.handshakes()
        self.assertEqual(len(inits), 2, "ping must renegotiate exactly once")
        self.assertFreshHandshake(inits[1])
        self.assertProtocolHeader(server.requests_of("tools/call")[-1],
                                  ALT_VERSION)
        self.assertNoHandshakeLeak(server)

    def test_session_expiry_reconnects_and_renegotiates(self):
        server = StrictServer(versions=[BASE_VERSION, ALT_VERSION],
                             session_drops=1)
        with _PatchedTransport(server):
            t = _make_transport()
            t.call_tool("echo", {"n": 1})   # handshake 1
            r = t.call_tool("echo", {"n": 2})  # 400 -> reconnect -> retry
        self.assertTrue(r["ok"])
        self.assertEqual(r["args"], {"n": 2})
        inits = server.handshakes()
        self.assertEqual(len(inits), 2)
        # The recovery handshake must not carry the dead session OR the old
        # version: the version is renegotiated from scratch.
        self.assertFreshHandshake(inits[1])
        self.assertEqual(t._protocol_version, ALT_VERSION)
        retried = server.requests_of("tools/call")[-1]
        self.assertProtocolHeader(retried, ALT_VERSION)
        self.assertEqual(retried.session, [server.session_id])
        self.assertNoHandshakeLeak(server)


class NotificationFailureTests(_ProtocolTestCase):
    def test_notification_400_propagates_and_rolls_back(self):
        server = StrictServer(versions=[BASE_VERSION], notify_failures=1)
        with _PatchedTransport(server):
            t = _make_transport()
            with self.assertRaises(MCPError) as ctx:
                t.initialize()
        self.assertIn("400", str(ctx.exception))
        # No half-open state: a failed handshake leaves nothing behind.
        self.assertState(t, False, None, None)
        self.assertNoHandshakeLeak(server)

    def test_after_notification_failure_next_call_rehandshakes(self):
        server = StrictServer(versions=[BASE_VERSION], notify_failures=1)
        with _PatchedTransport(server):
            t = _make_transport()
            with self.assertRaises(MCPError):
                t.call_tool("echo", {"n": 1})
            self.assertState(t, False, None, None)

            r = t.call_tool("echo", {"n": 2})
        self.assertTrue(r["ok"])
        self.assertEqual(r["args"], {"n": 2})
        inits = server.handshakes()
        self.assertEqual(len(inits), 2)
        for record in inits:
            self.assertFreshHandshake(record)
        self.assertEqual(t._protocol_version, BASE_VERSION)
        self.assertProtocolHeader(server.requests_of("tools/call")[-1],
                                  BASE_VERSION)
        self.assertNoHandshakeLeak(server)


class InvalidVersionTests(_ProtocolTestCase):
    """A bogus result.protocolVersion must be a protocol error, not a header
    we blindly echo back (that is how a CRLF injection or a silent empty value
    would reach the wire)."""

    CASES = [
        ("missing", MISSING),
        ("none", None),
        ("int", 20250618),
        ("dict", {"protocolVersion": BASE_VERSION}),
        ("list", [BASE_VERSION]),
        ("bool", True),
        ("empty", ""),
        ("whitespace_only", "   "),
        ("crlf", "2025-06-18\r\nX-Injected: 1"),
        ("lf", "2025-06-18\nX-Injected: 1"),
        ("cr", "2025-06-18\rX-Injected: 1"),
        ("nul", "2025-06-18\x00"),
        ("del", "2025-06-18\x7f"),
        ("non_ascii", "２０２５-０６-１８"),
    ]

    def _assert_protocol_error(self, version, label):
        server = StrictServer(versions=[version])
        with _PatchedTransport(server):
            t = _make_transport()
            with self.assertRaises(MCPError) as ctx:
                t.initialize()
            self.assertEqual(
                getattr(ctx.exception, "code", None), "PROTOCOL_ERROR",
                "%s: expected MCPError(PROTOCOL_ERROR), got %r"
                % (label, ctx.exception))
            # Rolled back: no session kept from the rejected handshake, no
            # version remembered, not "initialized".
            self.assertState(t, False, None, None)
            # And nothing was ever sent afterwards with that garbage.
            self.assertEqual(server.methods(), ["initialize"])
            for record in server.requests:
                self.assertNoProtocolHeader(record)

    def test_invalid_versions_raise_protocol_error(self):
        for label, version in self.CASES:
            with self.subTest(case=label):
                self._assert_protocol_error(version, label)

    def test_invalid_version_rolls_back_and_next_call_renegotiates(self):
        server = StrictServer(
            versions=["2025-06-18\r\nX-Injected: 1", BASE_VERSION])
        with _PatchedTransport(server):
            t = _make_transport()
            with self.assertRaises(MCPError) as ctx:
                t.initialize()
            self.assertEqual(ctx.exception.code, "PROTOCOL_ERROR")
            self.assertState(t, False, None, None)

            # The rejected handshake's session must not be reused.
            r = t.call_tool("echo", {"n": 7})
        self.assertTrue(r["ok"])
        inits = server.handshakes()
        self.assertEqual(len(inits), 2)
        for record in inits:
            self.assertFreshHandshake(record)
        self.assertEqual(t._protocol_version, BASE_VERSION)
        self.assertProtocolHeader(server.requests_of("tools/call")[-1],
                                  BASE_VERSION)
        self.assertNoHandshakeLeak(server)


if __name__ == "__main__":
    unittest.main(verbosity=2)
