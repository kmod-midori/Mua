# Display-control integration

Android library shared by Neko Assistant (`:app`) and mua (`:mua`), minimum API 31.
Contains the privileged virtual-display/input service, its hidden-framework
wrappers, frame capture, notification access, shared device-tool execution, and a
reusable Shizuku connection helper.

The `moe.reimu.nekoassistant.UserService` and `IUserService` names are part of the
Shizuku construction and Binder descriptor contract. Keep these names and the
explicit AIDL transaction numbers stable. `UserService`
extends `IUserService.Stub()` directly and runs in Shizuku's separate process.
It is not an Android Service and must not be registered as one.

Add `implementation(project(":display-control"))` to a host app. The host must
include the Shizuku provider dependency and manifest provider with an authority
based on its own application ID (see [mua’s manifest](../mua/src/main/AndroidManifest.xml)). `ShizukuDisplayConnection` builds the
service ComponentName from the host package; start/close it on the main thread.
It observes Shizuku availability and permission changes. The host owns permission
prompts, foreground-service lifetime, and its policy for service disconnects.
The original assistant retains its agent-specific binding/status lifecycle.

`DeviceToolExecutor` owns the shared tool implementation and display captures.
Connect it to the current `IUserService`, then execute typed `DeviceCommand<R>` values off the UI thread.
A dispatcher table binds each command type to its wire name, serializer, handler,
and response encoder. Neko uses the typed path directly. Mua passes MCP arguments
to the wire entry point, which deserializes the same command and invokes the same
handler. Fixed request and response structures use Kotlin serialization; JSON
objects remain only at the dynamic MCP protocol boundary.
Operations and capture cleanup are serialized inside the executor. Wire calls
return transport-independent text or image bytes for Mua to convert to MCP content. Agent-only
approval, waiting, and conversation behavior stay in `:app`. Both apps use native
display coordinates and explicit display IDs.

`DeviceToolCatalog` owns all device definitions, typed codecs, annotations and
argument validation. Both app registries adapt these definitions to their SDK;
Neko keeps only takeover, waiting, completion and the Termux approval flow local.
The catalog resource lives in `display-control/src/main/resources/device_tools.json`.
After editing it, run `python scripts/sync-tool-catalog.py` to refresh the standalone
server's package export; server tests check that export against the shared source.

`display_start` uses the main display's size and DPI and returns its Android ID.
The executor owns its frame Handler thread and closes captures on disconnect.
`bitmap(displayId)` returns independently owned pixels for the agent screenshot
pipeline; callers recycle them when no longer needed. `previews()` returns shared,
immutable thumbnails that must not be recycled or modified. Frame callbacks run
outside capture locks and should schedule work rather than block.

Call `connected(null)` on disconnect and `close()` when the host service stops.
Closing releases managed displays and capture resources. Close the Shizuku
connection as well to remove the remote process and local binding callbacks.
The privileged service serializes display/input operations at the Binder
boundary. It queries Android tasks for app placement; only capture resources are
tracked locally.

Consumers receive keep rules for the reflectively constructed Shizuku service.
No agent, UI, MCP, database, or LLM dependency is included in this library.

Use `getDisplayIds()` and the display-ID input, launch, and focus methods for
multiple displays; release each with `releaseDisplay(id)`. The host tracks capture
resources, while the privileged service queries Android tasks for app placement.
Transactions 1–9 provide the default-display API for Binder compatibility. Both
apps use explicit display IDs through the shared executor. Display-ID methods
use separate transaction codes; do not renumber either set.

`getRecentTasksJson()` queries Android’s current-user recent tasks without storing
a local task registry. `moveRecentTaskToDisplay(taskId, displayId)` uses
`startActivityFromRecents` with launch-display options. It does not poll for
launch/focus confirmation. The duplicate-app guard only checks focused tasks’
top activities on other displays, excluding the source task during a move.
Background tasks do not block launches or moves.
