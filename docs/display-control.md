# Display-control integration

Android library shared by Neko Assistant (`:app`) and mua (`:mua`), minimum API 31.
Contains the privileged virtual-display/input service, its hidden-framework
wrappers, frame capture, and a reusable Shizuku connection helper.

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

Read `IUserService.getMainDisplayMetrics()` for width, height, and DPI, then use
`DisplayCapture(width, height, handler)` to create a capture surface. Pass the
surface to `createDisplay(surface)`, which uses the main display’s current metrics
and returns an Android display ID. If the main metrics change during creation,
release the display and retry with a matching capture. Call `bitmap()` for
independently owned pixels. The caller recycles returned bitmaps when no longer used. Capture
keeps at most one acquired frame. Its callback runs on the supplied Handler and
outside its internal image lock. The host owns the Handler thread.

Stop the remote display before closing capture; on Binder death, close capture
immediately. Close the connection to remove both the remote process and local
callbacks. Serialize host operations with shutdown, keep Binder calls off the UI
thread, and close a new capture if display creation fails. The privileged service
serializes display/input operations to prevent release during a gesture.

Consumers receive keep rules for the reflectively constructed Shizuku service.
No agent, UI, MCP, database, or LLM dependency is included in this library.

Use `getDisplayIds()` and the display-ID input, launch, and focus methods for
multiple displays; release each with `releaseDisplay(id)`. The host tracks capture
resources, while the privileged service queries Android tasks for app placement.
The original transactions 1–9 retain the legacy default-display API used by the
assistant. New methods use separate transaction codes; do not renumber either set.
