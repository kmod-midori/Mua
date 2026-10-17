# display-control

An Android library for Shizuku virtual displays, frame capture, app launching,
and input injection. Shared by Neko Assistant and [mua](../mua/README.md).
Requires Android 12+ and Shizuku.

Add `implementation(project(":display-control"))` to your Android app and include
the Shizuku provider dependency and manifest entry. The host app owns permission
prompts, connection lifecycle, and display cleanup.

See the [integration guide](../docs/display-control.md) for service binding,
frame ownership, threading, and Binder compatibility.
