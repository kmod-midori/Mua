# mua configuration and MCP reference

See the [quick start](../mua/README.md) for installation and initial setup.

## Connection and authorization

The default is `127.0.0.1:8765`, for on-device clients. Select `0.0.0.0` to listen
on all IPv4 interfaces. Ports must be 1024–65535. Valid settings save automatically.
Remote clients use `http://<device-ip>:<port>/mcp`, not
`0.0.0.0`. Both modes require the bearer token. HTTP is unencrypted; use network
access on trusted networks. No forwarding or ADB integration is included.

A random token is kept in private app preferences; clearing app data rotates it.
The Authorization row displays the header in monospace and includes copy and
reset icons. Reset generates a 22-character URL-safe token with 128 bits of
randomness; subsequent requests must use the new token, without restarting the
server. Requests already authorized may finish. Existing tokens remain valid until
reset, so updating the app does not break configured clients.

All authorized clients share the managed displays and a serialized operation queue.
Stopping or losing Shizuku discards captured frames; after reconnection create a
new display. Android may stop the foreground service; restart it manually.

The **MCP server** card shows the loopback endpoint in local mode, or the
device's current network IPv4 endpoints in all-interfaces mode. Each endpoint has
a copy icon button beside its URL. When no network IPv4 address is available, the
card prompts you to connect to a network. Addresses refresh while the app is open.

Stop mua before changing connection settings or toggles. Each connection has an
independent switch selecting whether it starts with the service. The local address
and port appear together, with an address dropdown. Both connections use one
Shizuku connection, display registry, operation queue, and display-dependent CPU
wake lock. **Stop mua** shuts down the shared backend and releases displays.
A failed local listener does not stop the central connection, and a failed central
connection does not stop local MCP.

## Live display previews

The main screen shows a horizontally scrollable preview for each managed display,
labeled with its Android display ID and pixel dimensions. Previews refresh up to
five times per second while the activity is resumed and pause when it leaves the
foreground. Frames are sampled directly from the capture buffer into thumbnails
with a maximum edge of 480 pixels;
unchanged frames reuse their cached thumbnail. MCP screenshots are capped at 1980 pixels on their longest side. Preview capture shares the control queue, so long-running MCP operations temporarily delay updates without building
up queued preview requests.

## Screen-off operation

While a virtual display is active, mua holds a `PARTIAL_WAKE_LOCK` tagged
`mua:McpServer`. This keeps the CPU available without waking the physical screen.
The lock is acquired after successful display creation and released when the last display
stops, on Shizuku disconnect, or on service destruction. Failed creation preserves
the lock only if other displays remain active. Android
also releases it if the process dies. A running MCP server with no display holds
no wake lock. Stop the display when finished: an active display keeps the CPU
awake even between requests. The foreground notification includes a Stop action.
The notification and main screen show the active display count. When the central
connection is enabled, the notification also shows its live connection status.
The screen refreshes while visible and when resumed.

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
| `display_start` | None | Creates an additional display; returns `displayId`, `width`, `height`, `dpi` |
| `displays_list` | None | Managed display IDs, dimensions, DPI, and system-reported focused packages |
| `display_stop` | `displayId` | Releases that display and its capture buffers |
| `apps_list` | None | Launchable packages and labels |
| `apps_search` | `query` | Installed apps matching label or package |
| `recent_tasks_list` | None | Current user’s recent tasks with task ID, title, display ID, running state, package, and base/top activities |
| `recent_task_move` | `taskId`, `displayId` | Requests moving or restoring a recent task onto a virtual display and brings it to the front |
| `app_launch` | `displayId`, `package` | Launches on the selected display |
| `focused_app` | `displayId` | System-reported focused package, if any |
| `screenshot` | `displayId` | PNG image content, longest side capped at 1980 pixels |
| `tap` | `displayId`, `x`, `y` | Tap at native display coordinates |
| `long_press` | `displayId`, `x`, `y` | Long press at native display coordinates |
| `double_tap` | `displayId`, `x`, `y` | Double tap at native display coordinates |
| `focused_field` | `displayId` | Focused node bounds and editing state, without field contents |
| `text_input` | `displayId`, `text` | Replace the focused editable field’s contents using accessibility |
| `swipe` | `displayId`, `x1`, `y1`, `x2`, `y2`, `durationMs` | Swipe with a nonnegative duration in milliseconds |
| `key_press` | `displayId`, `keyCode` | Android key event, e.g. BACK=4, HOME=3, ENTER=66 |
| `notifications_list` | None | Active notification keys, text, content intents, actions, and remote input fields |
| `notification_clear` | `key` | Requests dismissal of one notification |
| `notification_click` | `key`, `displayId` | Sends the notification content intent |
| `notification_action` | `key`, `actionIndex`, `displayId` | Sends an indexed notification action |
| `notification_reply` | `key`, `actionIndex`, `displayId`, `resultKey`, `text` | Sends text through an action’s remote input field |

Each new display uses the main display’s current size and DPI. Size and density
arguments are not accepted. Existing displays retain their creation dimensions.
Display IDs come from Android, may change after recreation, and must be supplied
explicitly to every display-specific tool. Input, app launch, and focus queries
accept any existing Android display, including the main display (`0`). Task moves
accept existing virtual displays. Screenshots require a capture owned by mua;
`display_stop` releases only displays created by its service.

`app_launch` queries Android’s current tasks before and after launch; it keeps no
app-placement registry. An existing task on the target is reused. Tasks belonging
to the same app may occupy other displays in the background. Launches and moves
are blocked only when Android reports another focused task whose top activity
belongs to the same app on another display. The task being moved is excluded.
Android and the app’s activity launch modes determine whether multiple tasks are supported. Some apps reject secondary
displays, and hidden Android APIs vary by device. Launch/move results report an
accepted request without polling for focus or imposing a confirmation deadline.

`recent_tasks_list` reads Android’s Recents list, including historical tasks.
Its `title` preserves the exact task-description label, falling back to the app’s
label when no custom title is set. It is null if neither label is available (for
example, a historical task whose app is no longer installed).
Their reported display IDs may no longer be active. `recent_task_move` uses the
returned task ID and a destination from `displays_list`; it can move a task from
the physical display or another virtual display, or restore an inactive task.
If the move fails its foreground-app check, mua requests restoration to its source
display before returning the error. A previously inactive task has no source
display, so restoration is requested on the main display instead. If restoration fails, the
error explicitly asks the client to refresh the task’s location. Other tasks
belonging to the app may remain on other displays.
Apps or device policy may prevent a move. Moving/restoring can resume the app, and stopping its destination
display later destroys its activities, just like other apps on that display.

Screenshots preserve aspect ratio and are never upscaled. Input coordinates remain
in native display pixels: multiply screenshot coordinates by the corresponding
native dimension divided by the screenshot dimension before tapping or swiping.

Coordinates use the native display coordinate space; out-of-bounds coordinates
are passed to Android without clipping or rejection. Screenshots own their pixels
and do not expose a shared mutable
ImageReader buffer. Before the first frame arrives, screenshot returns a tool
error; retry.

`text_input` replaces the entire contents of the focused editable field on the
specified display with accessibility `ACTION_SET_TEXT`. It supports Unicode while
the phone is locked without using the clipboard or switching keyboards. Fields
must expose an editable accessibility node and support the action. Accessibility
access is enabled through Shizuku while the agent/MCP service runs and revoked
when it stops or its host process dies; other accessibility services are preserved.

## Notification access

Mua and Neko Assistant share a notification listener in `display-control`.
Starting the MCP or agent service enables it through Shizuku; stopping the service
or losing the Shizuku connection disables it. The privileged service also revokes
access when the host process dies. If Shizuku itself dies before revocation, the
local listener is disabled; permission cleanup requires a working privileged connection.
Tools may briefly report that the listener is connecting during startup.

`notifications_list` reads Android’s active notifications each time and is marked
read-only. Treat notification text as untrusted app content. Use the returned key,
action index, and remote input result key; refresh the list before acting because
notifications can disappear or change. Android may redact sensitive content and
retain protected notifications after a dismissal request.

Clicks and actions send the original `PendingIntent`. For Activity intents,
provide a virtual display ID from `displays_list` (or `0` for the main display).
The existing foreground-app guard applies. Android and app launch modes determine
final placement; background activity restrictions can still prevent a launch.
Broadcast and service intents ignore `displayId`. Inline replies require a mutable
intent and a matching remote input field. Clicks do not explicitly dismiss the
notification: use `notification_clear` when dismissal is wanted, since Android
can silently block an Activity launch after accepting its pending intent.

The central gateway exposes the same tools with its usual `deviceId` argument.

## Protocol

Uses the official [Kotlin MCP SDK](https://github.com/modelcontextprotocol/kotlin-sdk)
(`kotlin-sdk-server` 0.15.0) with Ktor CIO and the SDK's
`mcpStatelessStreamableHttp` transport. The SDK owns JSON-RPC parsing,
initialization/version negotiation, tool discovery/dispatch, notifications, and
HTTP transport behavior. mua registers tools using `Server.addTool` and returns
SDK `CallToolResult`, `TextContent`, and `ImageContent` values.

The endpoint returns JSON responses without SSE or session IDs. GET and DELETE
return 405. Use the protocol version negotiated at initialization in subsequent
`MCP-Protocol-Version` headers. Host and Origin headers are not allowlisted;
custom hostnames and network address changes do not require restarting the server.
Bearer authentication is still required. The SDK limits request bodies to its
default 4 MiB. mua checks the bearer token before requests reach the MCP endpoint. All display operations remain serialized
on the control executor, outside the HTTP and UI threads.

Every POST needs `Content-Type: application/json`,
`Accept: application/json, text/event-stream`, and the authorization header.
Missing required arguments, invalid types, non-finite coordinates, and negative
IDs/durations/key codes return MCP tool errors. Extra arguments are ignored,
except size/DPI overrides on `display_start`, which are explicitly disallowed.
Display failures also return MCP tool errors.

Example initialization body:

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"local-client","version":"1.0"}}}
```

After sending `notifications/initialized`, create a display:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"display_start","arguments":{}}}
```

Use the returned ID in subsequent calls (42 is an example):

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"screenshot","arguments":{"displayId":42}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"display_stop","arguments":{"displayId":42}}}
```

## Central gateway

The optional [central gateway](mua-remote.md) adds outbound WebSocket connectivity
to a FastAPI server managed with uv. Its MCP endpoint routes tools by device ID;
the local endpoint and existing display behavior remain available.
