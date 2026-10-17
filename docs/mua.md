# mua configuration and MCP reference

See the [quick start](../mua/README.md) for installation and initial setup.

## Connection and authorization

The default is `127.0.0.1:8765`, for on-device clients. Select `0.0.0.0` to listen
on all IPv4 interfaces. Remote clients use `http://<device-ip>:<port>/mcp`, not
`0.0.0.0`. Both modes require the bearer token. HTTP is unencrypted; use network
access on trusted networks. No forwarding or ADB integration is included.

A random token is kept in private app preferences; clearing app data rotates it.
The Authorization row displays the header in monospace and includes copy and
reset icons. Reset generates a 22-character URL-safe token with 128 bits of
randomness; subsequent requests must use the new token, without restarting the
server. Requests already authorized may finish. Existing tokens remain valid until
reset, so updating the app does not break configured clients.

All authorized clients share one display and a serialized operation queue.
Stopping or losing Shizuku discards captured frames; after reconnection create a
new display. Android may stop the foreground service; restart it manually.

The **Client connection** card shows the loopback endpoint in local mode, or the
device's current network IPv4 endpoints in all-interfaces mode. Each endpoint has
a copy icon button beside its URL. When no network IPv4 address is available, the
card prompts you to connect to a network. Addresses refresh while the app is open.

## Screen-off operation

While a virtual display is active, mua holds a `PARTIAL_WAKE_LOCK` tagged
`mua:McpServer`. This keeps the CPU available without waking the physical screen.
The lock is acquired after successful display creation and released on display
stop, Shizuku disconnect, failed display creation, or service destruction. Android
also releases it if the process dies. A running MCP server with no display holds
no wake lock. Stop the display when finished: an active display keeps the CPU
awake even between requests. The foreground notification includes a Stop action.
The notification and main screen's MCP server card show the same display and
CPU wake-lock status. The screen refreshes while visible and when resumed.

For long unplugged sessions, open **Battery optimization settings** in mua,
show all apps, and exempt mua. The setup card disappears once mua is exempt.
[Doze normally ignores wake locks and restricts networking](https://developer.android.com/training/monitoring-device-state/doze-standby);
exemption permits partial wake locks and network access. A foreground service
alone does not prevent CPU suspend. OEM battery restrictions may also apply, and
an exemption for mua does not exempt apps controlled on the virtual display.

There is no general public Android GPU wake-lock API. The existing independent,
always-unlocked virtual display lets apps render while the main display sleeps;
the GPU runs on demand. A partial wake lock does not pin CPU/GPU clocks or prevent
thermal throttling. No busy render loop, global performance mode, or vendor clock
override is used.

## Tools

| Tool | Required arguments | Result |
| --- | --- | --- |
| `display_start` | `width`, `height`, `dpi` | Replaces the display; 64–4096 pixels per side, at most 8 megapixels, 72–640 DPI |
| `display_stop` | None | Releases display and capture buffers |
| `apps_list` | None | Launchable packages and labels |
| `app_launch` | `package` | Requests launch on the virtual display |
| `focused_app` | None | Focused package, if any |
| `screenshot` | None | PNG image content, at native display resolution |
| `tap` | `x`, `y` | Tap at native display coordinates |
| `swipe` | `x1`, `y1`, `x2`, `y2`, `durationMs` | Swipe lasting 1–5000 ms |
| `key_press` | `keyCode` | Android key event, e.g. BACK=4, HOME=3, ENTER=66 |

Coordinates start at `(0, 0)` and must be smaller than the display dimensions.
Screenshots own their pixels and do not expose a shared mutable ImageReader
buffer. Before the first frame arrives, screenshot returns a tool error; retry.
Launching reports a request, not confirmation that an app rendered successfully.
Some apps reject secondary displays; hidden Android APIs also vary by device.

## Protocol

Uses the official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
(`kotlin-sdk-server` 0.15.0) with Ktor CIO and the SDK's
`mcpStatelessStreamableHttp` transport. The SDK owns JSON-RPC parsing,
initialization/version negotiation, tool discovery/dispatch, notifications, and
HTTP transport behavior. mua registers tools using `Server.addTool` and returns
SDK `CallToolResult`, `TextContent`, and `ImageContent` values.

The endpoint returns JSON responses without SSE or session IDs. GET and DELETE
return 405. Use the protocol version negotiated at initialization in subsequent
`MCP-Protocol-Version` headers. The SDK validates Host/Origin against loopback
hosts and, in all-interfaces mode, the device's IPv4 interface addresses captured
at server startup. Restart after changing networks or IP addresses. Arbitrary
hostnames and browser origins are rejected. The SDK limits request bodies to its
default 4 MiB. mua checks the bearer token before requests reach the MCP endpoint. All display operations remain serialized
on the control executor, outside the HTTP and UI threads.

Every POST needs `Content-Type: application/json`,
`Accept: application/json, text/event-stream`, and the authorization header.
Invalid tool arguments and display failures return MCP tool errors.

Example initialization body:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"local-client","version":"1.0"}}}
```

After sending `notifications/initialized`, create a display:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"display_start","arguments":{"width":1080,"height":1920,"dpi":420}}}
```
