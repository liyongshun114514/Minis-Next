#!/usr/bin/env python3
"""Runtime-reply and failure-classification tests for the HTTP MCP transport.

Run: PYTHONDONTWRITEBYTECODE=1 python3 test_http_runtime.py
(stdlib unittest + httpx.MockTransport + unittest.mock; no network, no real
server, no credentials, no packages installed)

Four verified defects are pinned here, all on the ordinary (non-adversarial)
path:

  1. `_parse_response` picked "the LAST `data:` line that parses", so a
     trailing `notifications/...` event (no id) replaced the real `result`,
     and a JSON document wrapped over several `data:` lines within one event
     was never parsed at all. Replies are now selected by request id, and an
     event's `data:` lines are joined before parsing.
  2. `_is_session_error` matched the WORD "session" in any error, so a tool's
     own MCP_ERROR ("session quota exceeded") re-handshook and re-ran the
     side-effecting tool. Only HTTP 400/404 with explicit
     expired/unknown/missing-session wording now re-handshakes.
  3. A 3xx was parsed as if it were a payload. Redirects are not followed (a
     blind follow replays the request — Authorization included — to whatever
     host Location names) and the error names neither Location nor the URL.
  4. ImportError (socksio behind a SOCKS route), ValueError ("Unknown scheme
     for proxy URL …") and InvalidURL reached the caller as a raw traceback or
     a bogus CONNECTION_ERROR. They are now DEPENDENCY_ERROR / CONFIG_ERROR
     with fixed, secret-free text — including on the OAuth refresh path, which
     must not claim "re-authorize" when the fault is a package or a setting.

Everything is asserted through the public surface (call_tool / list_tools /
ping), plus the error code and message text.
"""

import contextlib
import json
import os
import sys
import unittest
from unittest import mock

# These tests pin the ANDROID transport, so they load that package explicitly
# instead of honouring MINIS_MCP_CLI_DIR: the iOS copy is a separate artifact
# that this change did not touch, and an ambient env var must not silently
# point this suite at a tree whose behaviour it does not describe. (CI selects
# files per platform; when this one runs, it is always against Android.)
# MINIS_MCP_RUNTIME_TESTS_PKG exists only for local verification against a
# candidate tree — CI never sets it.
_PKG = os.environ.get("MINIS_MCP_RUNTIME_TESTS_PKG") or os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..", "..",
    "src", "android", "app", "src", "main", "assets", "default_mount",
    "usr", "local", "lib", "minis-mcp-cli")
sys.path.insert(0, os.path.abspath(_PKG))

import httpx  # noqa: E402

import transport.http as http_mod  # noqa: E402
from transport.http import HTTPTransport, MCPError  # noqa: E402

URL = "https://mcp.example.test/mcp"
VERSION = "2025-06-18"
SSE = {"content-type": "text/event-stream"}
JSON = {"content-type": "application/json"}

# Where the module actually came from — printed by the suite's own header
# assertion below so a wrong tree fails loudly instead of passing quietly.
_UNDER_TEST = os.path.abspath(http_mod.__file__).replace(os.sep, "/")


def _json_body(body, result=None, error=None, status=200, headers=None):
    """A JSON-RPC reply echoing `body["id"]`."""
    out = {"jsonrpc": "2.0", "id": body.get("id")}
    if result is not None:
        out["result"] = result
    if error is not None:
        out["error"] = error
    return httpx.Response(status, headers=headers or JSON, text=json.dumps(out))


def _rpc_id(body):
    return body.get("id")


def _sse(*events):
    """Build an SSE body from field -> value dicts. Every event is terminated
    by a blank line, as the wire format requires."""
    out = []
    for event in events:
        lines = []
        for field, value in event.items():
            lines.append("%s: %s" % (field, value))
        out.append("\n".join(lines) + "\n\n")
    return "".join(out)


def _sse_reply(body, result):
    return _sse({"event": "message",
                 "data": json.dumps({"jsonrpc": "2.0", "id": _rpc_id(body),
                                     "result": result})})


class _Server:
    """Minimal streamable-HTTP MCP server: JSON `initialize` handshake, then
    whatever `tools_reply`/`list_reply` returns. Records every method."""

    def __init__(self):
        self.calls = []
        self.session_id = "sess-1"
        self.tools_reply = None      # callable(body) -> httpx.Response
        self.list_reply = None
        self.every_request = None    # callable(body) -> httpx.Response

    def requests_of(self, method):
        return [m for m in self.calls if m == method]

    def handler(self, request):
        body = json.loads(request.content.decode("utf-8"))
        method = body.get("method")
        self.calls.append(method)
        if self.every_request is not None:
            return self.every_request(body)
        if method == "notifications/initialized":
            return httpx.Response(202, text="")
        if method == "initialize":
            return _json_body(body,
                              {"protocolVersion": VERSION,
                               "capabilities": {}, "serverInfo": {"name": "fake"}},
                              headers=dict(JSON, **{"Mcp-Session-Id": self.session_id}))
        if method == "tools/list":
            if self.list_reply is not None:
                return self.list_reply(body)
            return _json_body(body, {"tools": [{"name": "echo"}]})
        if method == "tools/call":
            if self.tools_reply is not None:
                return self.tools_reply(body)
            return _json_body(body, {"ok": True})
        return _json_body(body, {})


@contextlib.contextmanager
def serving(server):
    """Route transport.http's module-level `httpx.post` at a MockTransport."""
    client = httpx.Client(transport=httpx.MockTransport(server.handler))

    def _post(url, **kwargs):
        for key in ('proxy', 'proxies', 'trust_env'):
            kwargs.pop(key, None)
        return client.post(url, **kwargs)

    try:
        with mock.patch.object(http_mod.httpx, "post", side_effect=_post):
            yield server
    finally:
        client.close()


@contextlib.contextmanager
def post_raising(exc):
    """Make the next `httpx.post` raise `exc` — a transport-construction
    failure that never touches a socket."""
    with mock.patch.object(http_mod.httpx, "post", side_effect=exc):
        yield


def make_transport(server=None, name="runtime-test"):
    return HTTPTransport(dict(server or {"url": URL}), name)


class PackageUnderTestTests(unittest.TestCase):
    """Guard the claim, not just the code: a silent pass against a different
    (unfixed) tree would be worse than a failure, so assert which package the
    suite actually imported."""

    def test_android_package_with_runtime_fix(self):
        self.assertIn("/android/", _UNDER_TEST,
                      "these tests describe the Android transport, got %s" % _UNDER_TEST)
        self.assertTrue(hasattr(http_mod, "_parse_sse"),
                        "the SSE reply parser under test is missing")
        self.assertTrue(hasattr(http_mod, "_session_is_stale"),
                        "the session-staleness verdict under test is missing")


class ProxyRuntimeTests(unittest.TestCase):
    def test_proxy_constructor_accepts_selected_keyword_without_network(self):
        # Construct/close only: verifies the installed httpx/httpcore pair,
        # not reachability of a real proxy. Never performs a request.
        kwargs = http_mod.proxy_kwargs(URL, env={http_mod.MCP_PROXY_ENV: 'http://127.0.0.1:9'})
        with httpx.Client(**kwargs):
            pass
        self.assertFalse(kwargs['trust_env'])
        self.assertEqual(len(set(kwargs) & {'proxy', 'proxies'}), 1)

    def test_mcp_loopback_bypasses_proxy(self):
        for url in ('http://localhost/mcp', 'http://127.0.0.1/mcp', 'http://[::1]/mcp'):
            with self.subTest(url=url):
                self.assertEqual(http_mod.proxy_kwargs(url, env={
                    http_mod.MCP_PROXY_ENV: 'http://127.0.0.1:9'}), {'trust_env': False})


class ResponseIdentityTests(unittest.TestCase):
    def test_sse_ids_retain_type_and_value(self):
        for candidate in (True, 1.0, '1', 2, None):
            response = httpx.Response(200, headers=SSE, text=_sse({'data': json.dumps(
                {'jsonrpc': '2.0', 'id': candidate, 'result': {'wrong': True}})}))
            with self.subTest(candidate=candidate), self.assertRaises(MCPError):
                http_mod._parse_response(response, request_id=1)

    def test_mislabeled_json_body_cannot_bypass_id_matching(self):
        response = httpx.Response(200, headers=SSE, json={
            'jsonrpc': '2.0', 'id': 99, 'result': {'wrong': True}})
        with self.assertRaises(MCPError):
            http_mod._parse_response(response, request_id=1)

    def test_mislabeled_json_with_correct_id_remains_compatible(self):
        response = httpx.Response(200, headers=SSE, json={
            'jsonrpc': '2.0', 'id': 1, 'result': {'ok': True}})
        self.assertEqual(http_mod._parse_response(response, request_id=1)['result'], {'ok': True})

    def test_rpc_error_body_does_not_invalidate_transport_session(self):
        for status in (400, 404):
            body = json.dumps({'jsonrpc': '2.0', 'id': 1, 'error': {
                'code': -32602, 'message': 'invalid session parameter in tool arguments'}})
            self.assertFalse(http_mod._session_is_stale(status, body))


class SseReplyTests(unittest.TestCase):
    """(1) The reply that belongs to our request id is the only candidate."""

    def test_trailing_notification_does_not_overwrite_result(self):
        server = _Server()

        def reply(body):
            result = _sse_reply(body, {"ok": True, "tool": "echo"})
            notice = _sse({
                "event": "message",
                "data": json.dumps({"jsonrpc": "2.0",
                                    "method": "notifications/progress",
                                    "params": {"progress": 50}}),
            })
            return httpx.Response(200, headers=SSE, text=result + notice)

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {"n": 1})
        self.assertEqual(result, {"ok": True, "tool": "echo"},
                         "a trailing notification must not replace the result")

    def test_reply_for_another_request_id_is_ignored(self):
        server = _Server()

        def reply(body):
            ours = _sse_reply(body, {"ok": True, "tag": "ours"})
            other = _sse({"event": "message",
                          "data": json.dumps({"jsonrpc": "2.0", "id": 987654,
                                              "result": {"ok": True,
                                                         "tag": "someone-else"}})})
            return httpx.Response(200, headers=SSE, text=ours + other)

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {"n": 1})
        self.assertEqual(result.get("tag"), "ours",
                         "another request's reply must never stand in for ours")

    def test_reply_after_other_ids_is_still_found(self):
        server = _Server()

        def reply(body):
            other = _sse({"event": "message",
                          "data": json.dumps({"jsonrpc": "2.0", "id": 4242,
                                              "result": {"tag": "someone-else"}})})
            ours = _sse_reply(body, {"ok": True, "tag": "ours"})
            return httpx.Response(200, headers=SSE, text=other + "\n" + ours)

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {"n": 1})
        self.assertEqual(result.get("tag"), "ours")

    def test_multiline_data_within_one_event_is_joined(self):
        server = _Server()

        def reply(body):
            document = json.dumps({"jsonrpc": "2.0", "id": _rpc_id(body),
                                   "result": {"ok": True, "wrapped": True}})
            # Split where JSON whitespace is legal, exactly as a gateway that
            # hard-wraps long `data:` values does.
            head, tail = document.split(", ", 1)
            text = _sse({"event": "message"}, ) + \
                "data: %s,\ndata: %s\n\n" % (head, tail)
            return httpx.Response(200, headers=SSE, text=text)

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {"n": 1})
        self.assertEqual(result, {"ok": True, "wrapped": True},
                         "one event's data lines form one document")

    def test_error_for_our_id_is_raised(self):
        server = _Server()
        server.tools_reply = lambda body: httpx.Response(
            200, headers=SSE,
            text=_sse({"event": "message",
                       "data": json.dumps({"jsonrpc": "2.0", "id": _rpc_id(body),
                                           "error": {"code": -32602,
                                                     "message": "bad arguments"}})}))
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self.assertEqual(ctx.exception.code, "MCP_ERROR")
        self.assertIn("bad arguments", ctx.exception.message)

    def test_stream_without_our_reply_is_a_parse_error(self):
        server = _Server()
        server.tools_reply = lambda body: httpx.Response(
            200, headers=SSE,
            text=_sse({"event": "message",
                       "data": json.dumps({"jsonrpc": "2.0",
                                           "method": "notifications/message",
                                           "params": {"level": "info"}})}))
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self.assertEqual(ctx.exception.code, "PARSE_ERROR")

    def test_comments_event_and_id_fields_are_ignored(self):
        server = _Server()

        def reply(body):
            payload = json.dumps({"jsonrpc": "2.0", "id": _rpc_id(body),
                                  "result": {"ok": True, "after": "keepalive"}})
            text = (": keep-alive\n"
                    "event: message\n"
                    "id: 7\n"
                    "retry: 1000\n"
                    "data: not-json-at-all\n\n"
                    "event: message\n"
                    "data: %s\n\n" % payload)
            return httpx.Response(200, headers=SSE, text=text)

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {})
        self.assertEqual(result, {"ok": True, "after": "keepalive"})

    def test_plain_json_reply_unchanged(self):
        server = _Server()
        server.tools_reply = lambda body: _json_body(body, {"ok": True, "json": True})
        with serving(server):
            result = make_transport().call_tool("echo", {})
        self.assertEqual(result, {"ok": True, "json": True})


class SessionStalenessTests(unittest.TestCase):
    """(2) A re-handshake needs an explicit session verdict on HTTP 400/404."""

    def test_business_error_mentioning_session_is_not_retried(self):
        server = _Server()
        server.tools_reply = lambda body: _json_body(
            body, error={"code": -32000,
                         "message": "session quota exceeded for this workspace"})
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {"n": 1})
        self.assertEqual(ctx.exception.code, "MCP_ERROR")
        self.assertIn("quota", ctx.exception.message)
        self.assertEqual(len(server.requests_of("initialize")), 1,
                         "a business error must not trigger a re-handshake")
        self.assertEqual(len(server.requests_of("tools/call")), 1,
                         "a side-effecting tool must not be re-run")

    def test_stale_session_400_rehandshakes_once(self):
        server = _Server()
        state = {"n": 0}

        def reply(body):
            state["n"] += 1
            if state["n"] == 1:
                return httpx.Response(400, text="Bad Request: No valid session ID provided")
            return _json_body(body, {"ok": True, "n": state["n"]})

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {"n": 1})
        self.assertTrue(result["ok"])
        self.assertEqual(len(server.requests_of("initialize")), 2,
                         "exactly one recovery handshake")

    def test_stale_session_404_unknown_is_recovered(self):
        server = _Server()
        state = {"n": 0}

        def reply(body):
            state["n"] += 1
            if state["n"] == 1:
                return httpx.Response(404, text="Unknown session id")
            return _json_body(body, {"ok": True})

        server.tools_reply = reply
        with serving(server):
            result = make_transport().call_tool("echo", {})
        self.assertTrue(result["ok"])
        self.assertEqual(len(server.requests_of("initialize")), 2)

    def test_bare_400_without_session_wording_does_not_rehandshake(self):
        server = _Server()
        server.tools_reply = lambda body: httpx.Response(
            400, text="Bad Request: invalid arguments for tools/call")
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self.assertEqual(ctx.exception.code, "CONNECTION_ERROR")
        self.assertEqual(len(server.requests_of("initialize")), 1)
        self.assertEqual(len(server.requests_of("tools/call")), 1)

    def test_503_mentioning_session_is_not_a_stale_session(self):
        server = _Server()
        server.tools_reply = lambda body: httpx.Response(
            503, text="Bad Gateway: session backend unavailable, retry later")
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self.assertEqual(ctx.exception.code, "CONNECTION_ERROR")
        self.assertEqual(len(server.requests_of("initialize")), 1)


class RedirectTests(unittest.TestCase):
    """(3) A 3xx is reported, never followed, never quoted."""

    SECRET_HOST = "redirect-target.example.test"
    SECRET_PATH = "leaked-token-value"

    def _location(self):
        return "https://%s/%s" % (self.SECRET_HOST, self.SECRET_PATH)

    def _assert_redirect_message(self, exc, status):
        self.assertEqual(exc.code, "CONNECTION_ERROR")
        msg = exc.message
        self.assertIn(str(status), msg)
        self.assertIn("redirect", msg.lower())
        self.assertIn("not followed", msg.lower())
        for forbidden in (self.SECRET_HOST, self.SECRET_PATH, "Location", "https://"):
            self.assertNotIn(forbidden, msg,
                             "the error must not echo Location or the URL")

    def test_call_tool_redirect_is_not_followed(self):
        server = _Server()
        server.every_request = lambda body: httpx.Response(
            307, headers={"Location": self._location()}, text="")
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self._assert_redirect_message(ctx.exception, 307)
        self.assertEqual(len(server.calls), 1,
                         "the redirect target must never be contacted")

    def test_handshake_redirect_is_reported(self):
        server = _Server()
        server.every_request = lambda body: httpx.Response(
            302, headers={"Location": self._location()}, text="")
        with serving(server):
            with self.assertRaises(MCPError) as ctx:
                make_transport().ping()
        self._assert_redirect_message(ctx.exception, 302)
        self.assertEqual(len(server.calls), 1)


class SetupErrorTests(unittest.TestCase):
    """(4) DEPENDENCY_ERROR / CONFIG_ERROR, with text that leaks nothing."""

    def test_missing_httpx_is_a_dependency_error(self):
        with mock.patch.object(http_mod, "httpx", None):
            with self.assertRaises(MCPError) as ctx:
                make_transport().ping()
        self.assertEqual(ctx.exception.code, "DEPENDENCY_ERROR")
        self.assertIn("httpx", ctx.exception.message)

    def test_missing_socksio_is_a_dependency_error(self):
        exc = ImportError("Using SOCKS proxy, but the 'socksio' package is not "
                          "installed. Make sure to install httpx using "
                          "`pip install httpx[socks]`.")
        proxy = "socks5://mcp:pa55w0rd@127.0.0.1:11080"
        with mock.patch.dict(os.environ, {http_mod.MCP_PROXY_ENV: proxy}):
            with post_raising(exc):
                with self.assertRaises(MCPError) as ctx:
                    make_transport().ping()
        self.assertEqual(ctx.exception.code, "DEPENDENCY_ERROR")
        msg = ctx.exception.message
        self.assertIn("socksio", msg)
        for forbidden in ("pa55w0rd", "11080", "socks5://", "127.0.0.1"):
            self.assertNotIn(forbidden, msg, "proxy credentials must not surface")

    def test_invalid_url_is_a_config_error(self):
        with post_raising(httpx.InvalidURL("Invalid port: 99999")):
            with self.assertRaises(MCPError) as ctx:
                make_transport().call_tool("echo", {})
        self.assertEqual(ctx.exception.code, "CONFIG_ERROR")
        self.assertNotIn("99999", ctx.exception.message)

    def test_unusable_proxy_scheme_is_a_config_error(self):
        exc = ValueError("Unknown scheme for proxy URL "
                         "URL('http://mcp:sup3rs3cret@127.0.0.1:17890')")
        proxy = "http://mcp:sup3rs3cret@127.0.0.1:17890"
        with mock.patch.dict(os.environ, {http_mod.MCP_PROXY_ENV: proxy}):
            with post_raising(exc):
                with self.assertRaises(MCPError) as ctx:
                    make_transport().ping()
        self.assertEqual(ctx.exception.code, "CONFIG_ERROR")
        msg = ctx.exception.message
        for forbidden in ("sup3rs3cret", "17890", "127.0.0.1", "Unknown scheme"):
            self.assertNotIn(forbidden, msg)


class OAuthRefreshSetupErrorTests(unittest.TestCase):
    """(4b) A broken package or setting during refresh is not "unauthorized"."""

    TOKENS = {
        "access_token": "stale-access-token",
        "expires_at": 1,                     # long past -> refresh required
        "refresh_token": "refresh-token-value",
        "token_endpoint": "https://auth.example.test/token",
        "client_id": "client-id",
    }

    def _transport(self):
        return HTTPTransport({"url": URL,
                              "oauth": {"authorization_endpoint":
                                        "https://auth.example.test/authorize"}},
                             "oauth-runtime-test")

    @contextlib.contextmanager
    def _expired_tokens(self):
        with mock.patch.object(http_mod, "_load_oauth_tokens",
                               return_value=dict(self.TOKENS)):
            yield

    def test_refresh_missing_socksio_is_a_dependency_error(self):
        exc = ImportError("Using SOCKS proxy, but the 'socksio' package is not installed.")
        with self._expired_tokens():
            with post_raising(exc):
                with self.assertRaises(MCPError) as ctx:
                    self._transport().ping()
        self.assertEqual(ctx.exception.code, "DEPENDENCY_ERROR")
        self.assertNotIn("re-authorize", ctx.exception.message,
                         "a missing package is not an authorization problem")

    def test_refresh_unusable_token_endpoint_is_a_config_error(self):
        with self._expired_tokens():
            with post_raising(httpx.InvalidURL("Invalid port: 99999")):
                with self.assertRaises(MCPError) as ctx:
                    self._transport().ping()
        self.assertEqual(ctx.exception.code, "CONFIG_ERROR")

    def test_refresh_network_failure_still_asks_to_authorize(self):
        with self._expired_tokens():
            with post_raising(httpx.ConnectError("connection refused")):
                with self.assertRaises(MCPError) as ctx:
                    self._transport().ping()
        self.assertEqual(ctx.exception.code, "AUTH_REQUIRED")
        self.assertIn("re-authorize", ctx.exception.message)

    def test_timeout_is_unchanged(self):
        with post_raising(httpx.TimeoutException("too slow")):
            with self.assertRaises(MCPError) as ctx:
                make_transport().ping()
        self.assertEqual(ctx.exception.code, "TIMEOUT")


if __name__ == "__main__":
    unittest.main(verbosity=2)
