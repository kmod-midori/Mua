# mua (Mobile-Using Agent)

Let an AI agent work in Android apps while you use another app or keep the screen
off. Requires Android 12+, [Shizuku](https://shizuku.rikka.app/), and an MCP client.

## Quick start

1. Build with `./gradlew :mua:assembleDebug` and install
   `mua/build/outputs/apk/debug/mua-debug.apk`.
2. Open **mua** and grant Shizuku permission.
3. Select `0.0.0.0` for access from another device, then tap **Start mua**.
4. Copy the endpoint URL and **Authorization** value from **MCP server** into
   your MCP client. Select Streamable HTTP as the transport.

For screen-off use, follow the battery optimization setup in the app.

- [Configuration and MCP tools](../docs/mua.md)
- [Central server setup](../docs/mua-remote.md)
- [Build and development](../docs/development.md)
