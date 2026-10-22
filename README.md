# mua (Mobile-Using Agent)

![mua — Mobile-Using Agent](media/mua-banner.png)

mua lets an AI agent work for you in Android apps **while you use another app on
your phone, or while the screen is off**. The agent operates apps on separate
virtual displays, leaving the physical screen available for you.

Through MCP and Shizuku, agents can create displays, launch apps, capture
screenshots, and send touch and key input. Connect directly to the phone or use
the optional central server to access multiple devices through one MCP endpoint.

## Get started

You need Android 12 or newer, a running [Shizuku](https://shizuku.rikka.app/)
installation authorized through ADB or root, and an MCP client supporting
Streamable HTTP.

1. Build with `./gradlew :mua:assembleFossDebug` and install
   `mua/build/outputs/apk/foss/debug/mua-foss-debug.apk`.
2. Open **mua** and grant Shizuku permission. Select `0.0.0.0` as the listening
   address to connect from another device on your network, then tap **Start mua**.
3. Copy the endpoint URL and **Authorization** value from **MCP server**
   into your MCP client.

For screen-off use, follow the battery optimization setup in the app.
See [configuration and MCP tools](docs/mua.md) for more details.

## Central server

The optional central server lets agents operate your phone **while you’re on the
move**, over mobile data or Wi-Fi. Your phone connects to the server, so your
agent doesn’t need to be on the same network. You can connect multiple devices
through one MCP endpoint.

Follow the [building and self-hosting guide](docs/building-and-self-hosting.md)
for Android builds, server deployment, and optional FCM wake-up.

## Project modules

- [mua](mua/README.md): Android MCP app.
- [server](server/README.md): central MCP gateway for multiple devices.
- [display-control](display-control/README.md): shared Shizuku virtual display,
  screenshot, app launching, and input injection library.
- [Neko Assistant](docs/neko-assistant.md) (`app`): companion Android app with
  an LLM agent and chat harness, inspired by
  [Doubao Phone Assistant](https://o.doubao.com/).

## Development

See [build and validation](docs/development.md) for build commands, release
signing, GitHub Actions, and device checks.
