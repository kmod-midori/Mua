# Neko Assistant

Neko Assistant is the Android app in the `app` module. It includes an LLM agent
and chat harness for operating phone apps in the background, using the same
Shizuku display-control library as mua.

It requires Android 12+, a running and authorized Shizuku installation, and an
OpenAI-compatible LLM provider and model configured in the app.

Build with `./gradlew :app:assembleDebug` and install
`app/build/outputs/apk/debug/app-debug.apk`.

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
