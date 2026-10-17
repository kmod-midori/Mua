# Neko Assistant

![Neko Assistant banner](media/banner.png)

Neko Assistant is an open-source replica of [Doubao Phone Assistant](https://o.doubao.com/).

It uses an LLM agent to operate phone apps in the background. Shizuku lets the assistant view and interact with apps.

## Modules

- `app`: Neko Assistant, including the agent and chat harness.
- [display-control](display-control/README.md): shared Shizuku virtual display,
  frame capture, app launching, and input injection library.
- [mua (Mobile-Using Agent)](mua/README.md): standalone Android app exposing the
  library through a configurable MCP server, without the agent or harness.
- [server](server/README.md): optional FastAPI central MCP gateway for devices
  connected through outbound WebSockets, managed with uv.

## Requirements

- Android 12 or newer
- [Shizuku](https://shizuku.rikka.app/) installed, running, and authorized through ADB or root
- An OpenAI-compatible LLM provider and model configured in the app

## Optional Termux shell

Install and open [Termux](https://github.com/termux/termux-app) to initialize its
environment. Set `allow-external-apps=true` in `~/.termux/termux.properties` and
reload its settings with `termux-reload-settings`. Install any command-line
packages you want the assistant to use in Termux.

The assistant asks you to approve each command and its working directory before
running it in the background. You can reject a command and optionally tell the
assistant what to do instead. On first approval, Android asks for
NekoAssistant's **Run commands in Termux environment** permission. If needed,
enable it in Android Settings → Apps → NekoAssistant → Permissions.

You can review command output in the conversation. Stopping the assistant or
reaching the waiting limit may leave the command running in Termux. Check
Termux before retrying a command whose result is unknown.

## Development

See [build and validation](docs/development.md) for build commands and device checks.
