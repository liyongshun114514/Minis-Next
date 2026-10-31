"""HTTP MCP transport — JSON-RPC 2.0 over HTTP POST.

Flow: initialize -> tools/list / tools/call. Supports the streamable-HTTP MCP
endpoints (single POST returning either application/json or an SSE
`text/event-stream` body, both of which we parse for the JSON-RPC reply).

`$ENV_VAR` references in headers (and URL) are expanded from the process
environment so secrets live in env, not the config file. 5-minute timeout.

Errors are raised as `MCPError(code, message)`; main.py renders the unified
{"error","code","server"} envelope.
"""

import json
import sys
import os
import re

try:
    import httpx
except ImportError:  # pragma: no cover - the sh wrapper installs httpx first
    httpx = None

TIMEOUT_SECONDS = 300  # 5 min

# $VAR and $$VAR both expand from the process env; the UI picker emits $$VAR to
# make a reference visually explicit. The optional second `$` is consumed in the
# same match (no double-expansion), so $$VAR / $${VAR} resolve identically to
# $VAR / ${VAR}.
_ENV_RE = re.compile(r"\$\$?\{?([A-Za-z_][A-Za-z0-9_]*)\}?")


# [FEAT-261021-14 G3] Settings > Network "MCP" scope. While its local entry
# runs, the app exports MINIS_MCP_PROXY = the entry's HTTP front with the
# `mcp` tag (http://mcp:<password>@127.0.0.1:<port>); the entry then sends
# each request direct, through the tailnet or through a proxy, as the MCP
# route says. The shell's own proxy variables are ignored for MCP traffic
# then: the MCP scope decides, not the shell's. Unset (the entry is off, or
# an older app) = exactly as before. A server on this device's loopback is
# never proxied.
MCP_PROXY_ENV = "MINIS_MCP_PROXY"


def proxy_kwargs(url, env=None):
    """httpx keyword arguments routing a request to `url` per the MCP scope."""
    proxy = (os.environ if env is None else env).get(MCP_PROXY_ENV, "").strip()
    if not proxy:
        return {}
    from urllib.parse import urlsplit
    try:
        host = (urlsplit(url).hostname or "").lower()
    except ValueError:
        host = ""
    if host in ("localhost", "::1") or host.startswith("127."):
        return {"trust_env": False}
    version = tuple(int(p) for p in re.findall(r"\d+", getattr(httpx, "__version__", "0"))[:2]) if httpx else (0,)
    # `proxy=` since httpx 0.26; `proxies=` before (removed in 0.28).
    key = "proxy" if version >= (0, 26) else "proxies"
    return {key: proxy, "trust_env": False}


class MCPError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code
        self.message = message
        # Set by the HTTP transport when the failure means "this MCP session id
        # is no longer usable" (see _session_is_stale). Only that explicit
        # verdict authorises a re-handshake + retry.
        self.session_stale = False


_warned_unset = set()


# --- Error classification ---------------------------------------------------
#
# httpx reports three very different failures through the same call, and
# collapsing them into CONNECTION_ERROR hid both an incomplete image and a
# broken setting behind "it did not connect":
#
#   * ImportError            - a package is missing (socksio behind a SOCKS
#                              route; httpcore behind httpx) -> DEPENDENCY_ERROR
#   * InvalidURL / ValueError- the url or proxy address cannot be used at all
#                              ("Unknown scheme for proxy URL URL('http://user:
#                              pass@host')" is a ValueError) -> CONFIG_ERROR
#   * httpx.HTTPError        - we reached something and it failed -> the
#                              existing CONNECTION_ERROR / TIMEOUT
#
# These are raised before a byte goes out, so they also must NOT be reported
# as AUTH_REQUIRED by the OAuth refresh path: the token was never even sent.
# Their text is a fixed sentence naming the problem class — never the original
# message, which quotes the proxy URL (password included) or a server URL that
# may carry a token in its userinfo/query, and never a `$ENV` value.

_DEPENDENCY_HINT = (
    "the MCP HTTP runtime in this shell image is incomplete (%s); install the "
    "MCP HTTP runtime in the Ubuntu guest (python3-httpx / python3-socksio) and retry"
)
_CONFIG_HINT = (
    "the MCP server URL or the proxy route for it is not a usable address (%s); "
    "fix that setting and retry"
)

# Raised while httpx builds the request/transport — never by a peer:
#   ImportError  - socksio (SOCKS route) or httpcore missing
#   InvalidURL   - malformed url; subclasses Exception, NOT ValueError
#   ValueError   - unknown proxy scheme ("Unknown scheme for proxy URL URL(...)"),
#                  unusable header value
_SETUP_EXCEPTIONS = (ImportError, ValueError, getattr(httpx, "InvalidURL", ValueError))


def _dependency_error(detail):
    return MCPError("DEPENDENCY_ERROR", _DEPENDENCY_HINT % detail)


def _config_error(detail):
    return MCPError("CONFIG_ERROR", _CONFIG_HINT % detail)


def _setup_error(exc):
    """Map a transport-construction failure onto DEPENDENCY_ERROR /
    CONFIG_ERROR. Reads the exception type (and, for ImportError, whether the
    missing piece is SOCKS support) but never repeats its message."""
    if isinstance(exc, ImportError):
        try:
            text = str(exc).lower()
        except Exception:  # noqa: BLE001 - a hostile __str__ must not mask this
            text = ""
        if "socks" in text:
            return _dependency_error("SOCKS proxy support 'socksio' is not installed")
        return _dependency_error("a Python package the HTTP transport needs is not installed")
    invalid_url = getattr(httpx, "InvalidURL", None) if httpx is not None else None
    if invalid_url is not None and isinstance(exc, invalid_url):
        return _config_error("the server URL is not a valid absolute URL")
    return _config_error("the server URL or proxy address uses an unusable scheme")


# Only a 400/404 whose body says the SESSION is gone justifies a re-handshake.
# 500/502/503, a bare 400, and any JSON-RPC business error are left alone.
_SESSION_STALE_STATUSES = (400, 404)

_SESSION_STALE_RE = re.compile(
    r"already initiali[sz]ed"
    r"|no valid session"
    r"|(?:missing|unknown|invalid|expired|absent|stale|terminated|closed)"
    r"[^.\n]{0,24}?session"
    r"|session[^.\n]{0,40}?(?:expired|not found|not recognized|unknown|invalid|"
    r"missing|terminated|closed|not provided|does not exist|no longer)",
    re.IGNORECASE,
)


def _session_is_stale(status, body_text):
    """True when this HTTP failure means "this session id is not usable".

    Deliberately narrow. Matching the WORD "session" anywhere (the old
    `_is_session_error`) also matched a tool's own error text — "session quota
    exceeded", "invalid session parameter" — which re-handshook and re-ran a
    side-effecting tool that had already run."""
    if status not in _SESSION_STALE_STATUSES:
        return False
    text = body_text or ""
    try:
        payload = json.loads(text)
    except ValueError:
        payload = None
    if isinstance(payload, dict) and payload.get("jsonrpc") == "2.0" and "error" in payload:
        return False  # A tool's own RPC failure is not a transport session signal.
    return _SESSION_STALE_RE.search(text[:4000]) is not None


def expand_env(value):
    """Replace $VAR / ${VAR} / $$VAR / $${VAR} with the environment value (empty
    if unset).

    An unset name - missing, or disabled in Settings -> Environment Variables,
    which keeps it out of the environment - still expands to "" so the call
    goes ahead, but says so once per name on stderr instead of failing later
    with a puzzling 401."""
    if not isinstance(value, str):
        return value

    def _sub(m):
        name = m.group(1)
        found = os.environ.get(name)
        if found is None:
            if name not in _warned_unset:
                _warned_unset.add(name)
                sys.stderr.write(
                    "minis-mcp: warning: $%s is not set (missing, or disabled in "
                    "Settings -> Environment Variables); using an empty string\n" % name
                )
            return ""
        return found

    return _ENV_RE.sub(_sub, value)


def _expand_headers(headers):
    out = {}
    for k, v in (headers or {}).items():
        out[k] = expand_env(v)
    return out


def _sse_data_events(text):
    """Yield the `data` payload of each SSE event in `text`.

    An event ends at a blank line and may carry SEVERAL `data:` lines that
    together form one document (JSON split across lines, which real gateways
    do). The old parser looked at single lines only, so such a reply was
    dropped entirely; joining them per event is what the SSE spec says.

    Comment lines (`: keep-alive`), `event:`, `id:` and `retry:` carry no
    payload and are skipped.
    """
    data = []
    for raw in text.splitlines():
        line = raw.rstrip("\r")
        if not line.strip():
            if data:
                yield "\n".join(data)
                data = []
            continue
        if line.startswith(":"):
            continue
        field, sep, value = line.partition(":")
        if not sep or field.strip() != "data":
            continue
        if value.startswith(" "):
            value = value[1:]  # one optional space is part of the framing
        data.append(value)
    if data:
        yield "\n".join(data)


def _id_matches(candidate, request_id):
    """JSON-RPC ids must retain their value and type (True is not id 1)."""
    return type(candidate) is type(request_id) and candidate == request_id


def _parse_sse(text, request_id=None):
    """Pick the response that belongs to `request_id` out of an SSE stream.

    Only a document carrying OUR id and a `result`/`error` counts. A
    notification (no id — progress/log messages) or the reply to another
    request must never stand in for ours: choosing "the last payload that
    parses" let a trailing `notifications/progress` event replace the real
    result, and made multi-line payloads invisible.

    With no `request_id` (a caller that does not track ids) the last JSON-RPC
    object wins, as before.
    """
    fallback = None
    for payload in _sse_data_events(text):
        try:
            obj = json.loads(payload)
        except ValueError:
            continue
        if not isinstance(obj, dict):
            continue
        if request_id is None:
            fallback = obj
            continue
        if "id" not in obj or not _id_matches(obj.get("id"), request_id):
            continue
        if "method" in obj or ("result" not in obj and "error" not in obj):
            continue
        return obj
    if fallback is not None:
        return fallback
    # Compatibility: an endpoint that labels a plain JSON body as
    # text/event-stream (no `data:` framing at all).
    try:
        obj = json.loads(text)
    except ValueError:
        obj = None
    if isinstance(obj, dict) and (request_id is None or (
            _id_matches(obj.get("id"), request_id)
            and ("result" in obj or "error" in obj) and "method" not in obj)):
        return obj
    if request_id is None:
        raise MCPError("PARSE_ERROR", "no JSON-RPC payload in SSE stream")
    raise MCPError(
        "PARSE_ERROR",
        "no JSON-RPC response with id %s in the SSE stream" % (request_id,),
    )


def _parse_response(resp, request_id=None):
    """Extract the JSON-RPC object from either a JSON body or an SSE stream.

    `request_id` is the id of the request we just sent; on an SSE stream it
    selects our reply and nothing else (see `_parse_sse`)."""
    ctype = resp.headers.get("content-type", "")
    text = resp.text
    if "text/event-stream" in ctype:
        return _parse_sse(text, request_id)
    try:
        return json.loads(text)
    except ValueError as exc:
        raise MCPError("PARSE_ERROR", "invalid JSON response: %s" % exc)


# --- OAuth token bridge ------------------------------------------------------
#
# [T-mcp-static-oauth] Servers whose config carries an `oauth` object are
# authorized natively (the app runs the PKCE Authorization Code flow and owns
# the Keychain-backed credentials). The native side materializes a token
# bridge file the guest can read:
#
#     /var/minis/mcp-servers/oauth/<server>.json
#     { "access_token": "...", "expires_at": 1789999999,
#       "refresh_token": "...", "token_endpoint": "https://...",
#       "client_id": "...", "client_secret": "..." }        # secret optional
#
# The transport attaches `Authorization: Bearer <access_token>` and, on 401 or
# a token that is already past `expires_at`, performs a standard
# refresh_token grant against `token_endpoint` and rewrites the bridge file
# (so the native side and later calls see the fresh token). If refresh is
# impossible/fails, AUTH_REQUIRED tells the agent/user to re-authorize in
# Settings → MCP Integrations. The bridge file lives OUTSIDE servers.json on
# purpose: servers.json syncs across devices via iCloud, tokens must not.

OAUTH_DIR = "/var/minis/mcp-servers/oauth"


def _oauth_token_path(server_name):
    return os.path.join(OAUTH_DIR, "%s.json" % server_name)


def _authorize_deeplink(server_name):
    """[T-mcp-oauth-deeplink] Markdown link that jumps straight to the
    server's edit form in the app (where the Authorize button is). The agent
    relays error text verbatim, so chat renders this as a tappable link.
    Server names may contain URL-unsafe chars — percent-encode the path
    segment; the iOS/Android deep-link routers decode it back."""
    from urllib.parse import quote
    return "[Authorize](minis://settings/mcp-servers/%s)" % quote(server_name, safe="")


def _load_oauth_tokens(server_name):
    """Read the host-written OAuth bridge file for `server_name`.

    [issue #380] The three ways this returns None are NOT equivalent, and
    collapsing them is what made the bug invisible:

      * FileNotFoundError - genuinely not authorized yet, or the host wrote into
        an App Group this sandbox cannot see because /var/minis/mcp-servers is a
        local placeholder directory shadowing the symlink. Identical from here,
        so the absolute path is logged: an empty real directory at that path is
        the tell.
      * OSError - permission/IO. The file is there and unreadable, which is a
        very different problem from "not authorized" and used to look the same.
      * ValueError - present but corrupt (a truncated or partial write).

    Diagnostics go to stderr, never stdout: stdout carries the JSON the agent
    parses.
    """
    path = _oauth_token_path(server_name)
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        sys.stderr.write(
            "[minis-mcp-cli] no OAuth token for %r at %s "
            "(not authorized yet, or the host directory is not reachable from the sandbox)\n"
            % (server_name, path)
        )
        return None
    except OSError as exc:
        sys.stderr.write(
            "[minis-mcp-cli] OAuth token for %r at %s is unreadable: %s\n"
            % (server_name, path, exc)
        )
        return None
    except ValueError as exc:
        sys.stderr.write(
            "[minis-mcp-cli] OAuth token for %r at %s is corrupt: %s\n"
            % (server_name, path, exc)
        )
        return None


def _save_oauth_tokens(server_name, tokens):
    try:
        os.makedirs(OAUTH_DIR, exist_ok=True)
        tmp = _oauth_token_path(server_name) + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(tokens, f)
        os.replace(tmp, _oauth_token_path(server_name))
        try:
            os.chmod(_oauth_token_path(server_name), 0o600)
        except OSError:
            pass
    except OSError:
        pass  # best-effort; next call refreshes again


class HTTPTransport:
    def __init__(self, server, server_name):
        self.url = expand_env(server.get("url", ""))
        self.headers = _expand_headers(server.get("headers"))
        self.server_name = server_name
        self.oauth_cfg = server.get("oauth") if isinstance(server.get("oauth"), dict) else None
        self._id = 0
        self._session_id = None
        # [T-mcp-http-reinit] True once a handshake has completed for this
        # transport instance. The daemon reuses one HTTPTransport across calls,
        # so re-running initialize() every call made a STATEFUL streamable-HTTP
        # server (one that returns Mcp-Session-Id) reject the 2nd+ call with
        # HTTP 400 "Server already initialized". Tracked separately from
        # _session_id because a stateless server never sets _session_id yet
        # still must not be re-initialized.
        self._initialized = False
        # Streamable HTTP requires the negotiated version on every request
        # after initialize, including notifications/initialized.
        self._protocol_version = None

    def _next_id(self):
        self._id += 1
        return self._id

    # -- OAuth helpers -------------------------------------------------------

    def _oauth_access_token(self, force_refresh=False):
        """Current access token for an oauth server, refreshing if expired or
        forced. Raises AUTH_REQUIRED when no usable token can be produced."""
        tokens = _load_oauth_tokens(self.server_name)
        if not tokens or not tokens.get("access_token"):
            raise MCPError("AUTH_REQUIRED", (
                "server '%s' uses OAuth but has no stored token — tap %s to "
                "open its settings page and sign in"
                % (self.server_name, _authorize_deeplink(self.server_name))))
        import time as _time
        expired = False
        exp = tokens.get("expires_at")
        if isinstance(exp, (int, float)) and exp > 0:
            expired = _time.time() > (exp - 60)  # refresh 60s early
        if force_refresh or expired:
            refreshed = self._oauth_refresh(tokens)
            if refreshed:
                return refreshed["access_token"]
            if force_refresh or expired:
                raise MCPError("AUTH_REQUIRED", (
                    "server '%s' OAuth token expired and refresh failed — "
                    "tap %s to re-authorize"
                    % (self.server_name, _authorize_deeplink(self.server_name))))
        return tokens["access_token"]

    def _oauth_refresh(self, tokens):
        """refresh_token grant → rewrite the bridge file. Returns the new token
        dict or None.

        A missing package or an unusable proxy/URL is NOT an authorization
        problem: those raise DEPENDENCY_ERROR / CONFIG_ERROR here so the caller
        never reports "tap to re-authorize" for something re-authorizing cannot
        fix. Genuine grant failures (network error, 4xx, no access_token) still
        return None and become AUTH_REQUIRED."""
        if httpx is None:
            raise _dependency_error("the 'httpx' package is not installed")
        refresh_token = tokens.get("refresh_token")
        token_endpoint = tokens.get("token_endpoint")
        client_id = tokens.get("client_id")
        if not (refresh_token and token_endpoint and client_id):
            return None
        form = {
            "grant_type": "refresh_token",
            "refresh_token": refresh_token,
            "client_id": client_id,
        }
        if tokens.get("client_secret"):
            form["client_secret"] = tokens["client_secret"]
        # [T-mcp-oauth-resource] RFC 8707 Resource Indicator, required by the
        # MCP auth spec on every token request. The native side writes the
        # canonical server URI into the bridge file; reuse it verbatim.
        if tokens.get("resource"):
            form["resource"] = tokens["resource"]
        try:
            resp = httpx.post(token_endpoint, data=form, timeout=30,
                              **proxy_kwargs(token_endpoint))
        except _SETUP_EXCEPTIONS as exc:
            # Same split as _post: the token endpoint was never contacted.
            raise _setup_error(exc)
        except httpx.HTTPError:
            return None
        if resp.status_code >= 400:
            return None
        try:
            payload = resp.json()
        except ValueError:
            return None
        access = payload.get("access_token")
        if not access:
            return None
        import time as _time
        new_tokens = dict(tokens)
        new_tokens["access_token"] = access
        # Some providers rotate the refresh token on every grant.
        if payload.get("refresh_token"):
            new_tokens["refresh_token"] = payload["refresh_token"]
        if payload.get("expires_in"):
            try:
                new_tokens["expires_at"] = int(_time.time()) + int(payload["expires_in"])
            except (TypeError, ValueError):
                pass
        _save_oauth_tokens(self.server_name, new_tokens)
        return new_tokens

    def _post(self, method, params=None, notify=False, _oauth_retried=False):
        if httpx is None:
            raise _dependency_error("the 'httpx' package is not installed")
        body = {"jsonrpc": "2.0", "method": method}
        request_id = None
        if not notify:
            request_id = self._next_id()
            body["id"] = request_id
        if params is not None:
            body["params"] = params
        # Force the MCP Streamable HTTP (2025-03-26) required Accept and
        # Content-Type regardless of how the user configured server.headers.
        # setdefault is case-sensitive, so a user-supplied "accept" /
        # "content-type" (any casing) or an incomplete Accept that omits
        # text/event-stream would slip through and some gateways reject it
        # (e.g. 401 "oauth token is not found"). Drop any case-variant of these
        # keys and transport-owned session/version headers, then set canonical
        # values. A configured stale version must not override negotiation;
        # fresh handshakes must not inherit a configured session or version.
        # Other headers (Authorization, etc.) retain their casing and value.
        headers = {
            k: v for k, v in self.headers.items()
            if k.lower() not in ("accept", "content-type", "mcp-session-id", "mcp-protocol-version")
        }
        headers["Content-Type"] = "application/json"
        headers["Accept"] = "application/json, text/event-stream"
        # [T-mcp-static-oauth] OAuth servers get their Authorization from the
        # native-materialized token bridge, overriding any static header.
        if self.oauth_cfg is not None:
            headers["Authorization"] = "Bearer %s" % self._oauth_access_token(
                force_refresh=_oauth_retried)
        if self._session_id:
            headers["Mcp-Session-Id"] = self._session_id
        if self._protocol_version:
            headers["MCP-Protocol-Version"] = self._protocol_version
        try:
            resp = httpx.post(
                self.url, json=body, headers=headers, timeout=TIMEOUT_SECONDS,
                **proxy_kwargs(self.url)
            )
        except httpx.TimeoutException:
            raise MCPError("TIMEOUT", "request timed out after %ds" % TIMEOUT_SECONDS)
        except httpx.HTTPError as exc:
            raise MCPError("CONNECTION_ERROR", str(exc))
        except _SETUP_EXCEPTIONS as exc:
            # httpx.InvalidURL subclasses Exception, not ValueError, and both
            # reach us from transport construction:
            #   ImportError -> missing socksio (SOCKS route) / httpcore
            #   ValueError  -> "Unknown scheme for proxy URL URL('http://...')"
            raise _setup_error(exc)
        # Capture a session id handed back by the server (streamable-HTTP).
        sid = resp.headers.get("mcp-session-id")
        if sid:
            self._session_id = sid
        # [T-mcp-static-oauth] 401 on an oauth server: refresh once and retry
        # the same request; a second 401 falls through to AUTH_REQUIRED via
        # _oauth_access_token(force_refresh=True) on the retry, or to the
        # generic error below if refresh succeeded but the server still 401s.
        if resp.status_code == 401 and self.oauth_cfg is not None and not _oauth_retried:
            return self._post(method, params=params, notify=notify, _oauth_retried=True)
        # 3xx is a misconfiguration, not a payload: httpx does not follow
        # redirects here (follow_redirects stays default), and doing so blindly
        # would replay the request — headers, token included — to whatever host
        # the response names. Say so without echoing Location or the URL.
        if 300 <= resp.status_code < 400:
            raise MCPError(
                "CONNECTION_ERROR",
                "HTTP %d: the MCP endpoint redirected; redirects are not "
                "followed automatically — configure the server with its final "
                "URL" % resp.status_code,
            )
        if resp.status_code >= 400:
            err = MCPError(
                "CONNECTION_ERROR", "HTTP %d: %s" % (resp.status_code, resp.text[:200])
            )
            # Decide session staleness HERE, from the status + wording, and
            # carry it on the error. `_call_with_reconnect` then retries only
            # for this explicit verdict — never for a business MCP_ERROR that
            # merely contains the word "session".
            err.session_stale = _session_is_stale(resp.status_code, resp.text)
            raise err
        if notify:
            return None
        rpc = _parse_response(resp, request_id=request_id)
        if isinstance(rpc, dict) and rpc.get("error"):
            err = rpc["error"]
            raise MCPError("MCP_ERROR", err.get("message", json.dumps(err)))
        return rpc.get("result") if isinstance(rpc, dict) else rpc

    def initialize(self):
        # Every explicit handshake starts fresh, including ping/reconnect and
        # retrying an interrupted handshake. Never reuse the old version header.
        self._reset_session()
        try:
            result = self._post(
                "initialize",
                {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "minis-mcp-cli", "version": "1.0.0"},
                },
            )
            version = result.get("protocolVersion") if isinstance(result, dict) else None
            # Do not hard-code the offered version: a server can negotiate a
            # different one. Reject malformed values before using them in HTTP.
            if (not isinstance(version, str) or not version
                    or any(ord(c) < 33 or ord(c) > 126 for c in version)):
                raise MCPError("PROTOCOL_ERROR", "initialize returned an invalid protocolVersion")
            self._protocol_version = version
            self._post("notifications/initialized", notify=True)
        except Exception:
            # A failed notification is a failed handshake, not a healthy ping.
            # Clear the half-open state so the next attempt can start cleanly.
            self._reset_session()
            raise
        self._initialized = True
        return result

    def _ensure_initialized(self):
        """[T-mcp-http-reinit] Handshake once per transport lifetime, not once
        per call. A stateful streamable-HTTP server tracks the session and
        rejects a duplicate `initialize` (HTTP 400 "Server already
        initialized"); a stateless server tolerates re-init but gains nothing
        from it. Either way, one handshake is correct."""
        if not self._initialized:
            self.initialize()

    def _reset_session(self):
        """Forget the current session so the next call re-handshakes from
        scratch. Used for self-healing when the server reports the session is
        stale/unknown."""
        self._session_id = None
        self._protocol_version = None
        self._initialized = False

    @staticmethod
    def _is_session_error(err):
        """True only when the transport itself decided the session is stale.

        `_post` makes that call once, from the HTTP status (400/404) plus an
        explicit session-expiry / unknown / missing wording, and sets the flag.
        Text matching here used to accept ANY error mentioning "session" —
        including a tool's own MCP_ERROR ("session quota exceeded"), which then
        re-handshook and re-ran the whole tool. Recovery is now opt-in by the
        producer, never inferred from a business message.
        """
        return bool(getattr(err, "session_stale", False))

    def _call_with_reconnect(self, do_call):
        """Run `do_call` after ensuring a handshake, and if it fails with a
        session-state error, drop the session and retry ONCE from a fresh
        handshake. One retry only, so a persistently broken server surfaces the
        real error instead of looping."""
        self._ensure_initialized()
        try:
            return do_call()
        except MCPError as exc:
            if not self._is_session_error(exc):
                raise
            self._reset_session()
            self.initialize()
            return do_call()

    def list_tools(self):
        result = self._call_with_reconnect(lambda: self._post("tools/list"))
        return (result or {}).get("tools", [])

    def call_tool(self, tool, arguments):
        return self._call_with_reconnect(
            lambda: self._post("tools/call", {"name": tool, "arguments": arguments or {}})
        )

    def ping(self):
        """Reachability check via a fresh anonymous handshake.

        [T-mcp-http-reinit] Ping deliberately does NOT reuse the cached
        session: it drops any existing session first so a stateful server sees
        a clean initialize rather than a duplicate one (which it would reject),
        and it never leaves a half-open session behind for the real calls to
        trip over. Because it re-inits from scratch, the very next call_tool /
        list_tools reuses the session ping established — no wasted extra
        handshake."""
        self._reset_session()
        self.initialize()
        return True
