# Central mua gateway

The optional central path runs alongside the existing local MCP server:

```text
MCP client ── HTTP(S) /mcp ── FastAPI gateway
                                  │
                         outbound WebSocket
                                  │
                            mua on Android
                                  │
                      shared control executor
                                  │
                         Shizuku display service
```

Each device initiates its WebSocket connection; no inbound phone port, ADB
forwarding, or public device address is required. Local MCP and central requests
use the same display resources, tool validation, foreground-app guard, and
serialized execution queue. Android remains the source of task/display state.

## Server setup

From `server/`, run `uv sync --locked`. Create a `.env` file (ignored by Git):

```dotenv
MUA_CLIENT_TOKEN=replace-with-a-random-client-token
MUA_DEVICE_TOKEN=replace-with-a-random-shared-device-token
```

Generate each token independently, for example with
`uv run python -c 'import secrets; print(secrets.token_urlsafe(32))'`.
`MUA_CLIENT_TOKEN` authorizes MCP clients to control all connected devices.
`MUA_DEVICE_TOKEN` is shared by all devices; device IDs do not need to be
registered on the server. Anyone holding this token can connect under a device ID.
These credentials are separate from the local MCP token shown in mua.
Environment changes take effect when the server restarts.

Start the server:

```sh
uv run --env-file .env python -m mua_server --host 0.0.0.0 --port 8000
```

The default bind address is `127.0.0.1`; specify `0.0.0.0` to accept remote
connections. Run **one process/worker**: the live connection registry and pending
requests are held in memory. Multiple independent workers or replicas require
shared routing infrastructure and are not supported by this implementation.
There is no database or stored screenshot/task history.

For internet use, terminate TLS at a reverse proxy and forward both `/mcp` and
`/devices/{deviceId}/ws` to this process, with WebSocket upgrades enabled.
Use HTTPS for MCP and WSS on the phone. `ws://` and HTTP are available for local
network development and do not encrypt credentials or content. The launcher
allows full WebSocket messages without Uvicorn's default message-size limit so
PNG results fit; reverse-proxy limits must also accommodate images. MCP uses the
SDK's default request-body limit. Host and Origin headers are not allowlisted;
bearer authentication is required.

## Docker

Build using `server/` as the Docker context:

```sh
docker build -t mua-server ./server
docker run --rm --name mua-server --env-file server/.env -p 8000:8000 mua-server
```

For Docker's `--env-file`, use unquoted `KEY=value` lines for `MUA_CLIENT_TOKEN`
and `MUA_DEVICE_TOKEN`. The image excludes local credentials, virtual environments,
and tests. A separate build stage installs production dependencies from `uv.lock`
and packages the server, including its shared tool catalog. The runtime uses
Python 3.14, runs as user `mua` (UID 10001), and needs no uv or dependency downloads.

The entrypoint runs one worker on `0.0.0.0:8000`. To override its port, append
`--host 0.0.0.0 --port 9000` and publish `-p 9000:9000`. When a reverse proxy runs
on the host, publish `-p 127.0.0.1:8000:8000` and use the TLS configuration above.
`docker stop mua-server` sends the shutdown signal directly to the Python process.
No persistent volume is required; devices reconnect after the container restarts.

## Mobile setup

1. Stop mua before editing its configuration.
2. Enable **Connect to central server** in the **Central server** card.
3. Enter the base URL, such as `wss://mua.example.com` or
   `ws://192.168.1.10:8000`. A path prefix is supported when the proxy preserves
   the corresponding routing. Do not include `/mcp` or the device WebSocket path.
4. Enter a **Device name** to identify this phone in `devices_list` (initially
   its model name). Keep the generated **Device ID**, or choose a unique one.
   Enter the server's `MUA_DEVICE_TOKEN` in **Shared device token**, using the same
   token on every device.
5. Start mua. The card reports Connecting, Connected, or a reconnect countdown.

The device sends a protocol-v1 hello containing its configured name and tool schemas.
The server acknowledges registration before accepting calls for it. A replacement
connection with the same ID retires the previous connection, so give each physical
device a unique ID. Credentials and settings persist in mua's private preferences;
Android backup remains disabled.

Stopping mua closes the WebSocket and releases its displays. Losing only the
central connection leaves displays and local MCP available. The bridge retries
with a 1–30-second exponential backoff and sends WebSocket pings every 20 seconds.
A reconnect re-registers the current tool catalog, without replaying calls.

The bridge does not acquire a CPU wake lock. The existing lock is held only while
mua has active virtual displays. Android power management may suspend networking
while idle; use the existing battery optimization exemption for screen-off use.
A persistent connection does not guarantee instant delivery during device sleep.

## Central MCP tools

Point a Streamable HTTP client at `https://mua.example.com/mcp` and send
`Authorization: Bearer <MUA_CLIENT_TOKEN>`. MCP negotiation and errors are handled
by the official Python SDK; HTTP transport is stateless with JSON responses.

| Tool | Arguments |
| --- | --- |
| `devices_list` | None |
| `display_start` | `deviceId` |
| `displays_list` | `deviceId` |
| `recent_tasks_list` | `deviceId` |
| `recent_task_move` | `deviceId`, `taskId`, `displayId` |
| `display_stop` | `deviceId`, `displayId` |
| `screenshot` | `deviceId`, `displayId` |
| `apps_list` | `deviceId` |
| `app_launch` | `deviceId`, `displayId`, `package` |
| `focused_app` | `deviceId`, `displayId` |
| `tap` | `deviceId`, `displayId`, `x`, `y` |
| `swipe` | `deviceId`, `displayId`, `x1`, `y1`, `x2`, `y2`, `durationMs` |
| `key_press` | `deviceId`, `displayId`, `keyCode` |

Standard MCP `tools/list` returns each operation's full argument schema. Call
`display_start` directly with `{"deviceId":"phone-a"}`. For example, a subsequent
screenshot request is:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "screenshot",
    "arguments": {"deviceId":"phone-a","displayId":7}
  }
}
```

The tool catalog stays stable even when no devices are connected. `devices_list`
returns current device IDs, configured names, and connection times. An offline
device or an operation absent from that device's advertised capabilities returns
a tool error. The gateway removes only `deviceId` before forwarding arguments and
preserves the original result, including image content and tool errors.

Android and the gateway share `server/src/mua_server/catalog/device_tools.json`.
Gradle packages this file as an Android Java resource; the Python package loads
it as package data and adds the required `deviceId` parameter. Update this shared
catalog when changing device tools. Device registration still advertises its
supported tools so older app versions can report unsupported operations clearly.

Display IDs are local to each device. All existing local tool behavior applies,
including the 1980-pixel screenshot cap and native-pixel input coordinates.
See [device tools](mua.md#tools) for details.

A request gets a unique correlation ID on its device connection. Responses from
other devices cannot resolve it. Disconnecting or replacing a connection fails
its pending requests with an unknown-outcome error. **Do not blindly retry a
mutating call** after disconnect: it may already have run. Query system state
first. Cancelling a waiting client request removes its pending result; an Android
operation already dispatched may still finish. The gateway does not impose a
new tool-execution deadline or replay interrupted requests.

## Validation

```sh
uv run --directory server pytest
uv run --directory server ruff check src tests
./gradlew :mua:testDebugUnitTest :mua:assembleDebug :mua:lintDebug
```

Gateway tests exercise authenticated MCP requests, shared-device authentication,
concurrent routing, image and error results, reconnects, connection replacement,
and pending-call cleanup. For device validation, use a temporary blank virtual
display, capture it via central MCP, restart the gateway, check that the same
display remains available locally and remotely, then release it.

Implementation references: [MCP Python SDK](https://github.com/modelcontextprotocol/python-sdk),
[Ktor client WebSockets](https://ktor.io/docs/client-websockets.html).
