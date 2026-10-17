# mua (Mobile-Using Agent)

An Android app exposing Shizuku virtual displays through MCP. It uses the shared
[display-control library](../display-control/README.md) without the assistant’s
agent loop or harness. Requires Android 12+ and a running Shizuku installation.

## Quick start

1. Build with `./gradlew :mua:assembleDebug` and install
   `mua/build/outputs/apk/debug/mua-debug.apk`.
2. Open **mua** and grant permission through the **Shizuku not granted** card.
3. Select the listening address and port, then tap **Start mua**. The default is
   `127.0.0.1:8765`; select `0.0.0.0` for network access. Ports must be 1024–65535.
4. Copy an endpoint URL and the **Authorization** value from **Client connection**
   into your MCP client. Use Streamable HTTP and send the copied `Bearer …` value
   in the `Authorization` header. Network access uses unencrypted HTTP.
5. Call `display_start` with no arguments. It returns a display ID and uses the
   main display’s size and DPI. Pass `displayId` to launch, capture, input, and
   stop tools. Use `displays_list` to find active IDs, or **Stop mua** to release all.

Settings save automatically when valid; stop the server before changing them.
All clients share the managed displays. The CPU is kept awake only while at least
one display is active. For long screen-off sessions, use the battery optimization setup card.

See [configuration and MCP reference](../docs/mua.md) for networking,
authorization reset, power behavior, tools, and protocol examples.
