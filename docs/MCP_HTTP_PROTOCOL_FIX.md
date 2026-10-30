# MCP Streamable HTTP version header

The HTTP transport previously omitted `MCP-Protocol-Version` after initialize and swallowed initialization-notification failures. A strict server could accept initialize (making ping appear healthy) and reject later requests with an empty HTTP 400 response.

Both Android and iOS bundled transports now retain the server's `result.protocolVersion`, send it on initialized notifications and subsequent calls, remove conflicting configured header variants, and clear it when a session resets. Invalid version values or notification failures abort the handshake and clear partial state. The offered version remains 2025-06-18; the negotiated response, rather than the offer, determines subsequent headers.

Verification:

- HTTP protocol/session suites: 26 passed against Android and 26 against iOS.
- Ubuntu MCP runtime checks: 6 passed; workflow resource checks: 5 passed.
- New protocol tests fail against the previous committed transport.
- Live check using the repository's Android transport: android-virtual-screen negotiated 2025-06-18, listed 19 tools, and returned a successful read-only get_status response. No screen was created or controlled.
- A discovery run of the entire legacy CLI test directory failed and exceeded its 120-second limit; it includes tests defaulting to the Windows package. That mixed run is not an acceptance result for this fix and its test process was stopped.

The cloud workflow runs the HTTP suites against both bundled copies using an isolated httpx 0.26 environment. Runtime dependency installation and interrupted dpkg state are separate from the protocol-header fix; no automatic dpkg repair is introduced. No installed system CLI or user server configuration is modified by this source change.

After updating the app and its guest overlay, restart the existing MCP daemon (`minis-mcp-cli shutdown`) before checking `tools`/`call`, so it loads the updated Python transport.
