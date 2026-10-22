# Building and self-hosting Mua

This guide covers building the Android app and running your own central MCP
server. For direct access on your local network, build and install the app,
then follow [local MCP setup](mua.md); a central server is optional.

## Requirements

- An Android 12+ phone with [Shizuku](https://shizuku.rikka.app/) running and
  authorized through ADB or root.
- Android Studio with its bundled JDK, Android SDK Platform 37.1, and Platform
  Tools. Open the repository to install the SDK components requested by Gradle.
  For terminal builds, point `JAVA_HOME` at the Android Studio JDK and set
  `sdk.dir` in the root `local.properties` to your Android SDK directory.
- An MCP client supporting Streamable HTTP.
- For hosting: Python 3.12+ and [uv](https://docs.astral.sh/uv/), or Docker.
- For optional FCM wake-up: Google Play services on the phone and a Firebase
  project with the Android app registered as `moe.reimu.mua`.

Run commands from the repository root unless a command explicitly changes
working directory.

## Build and install the Android app

Choose a variant:

| Variant | Build command | Debug APK |
| --- | --- | --- |
| `foss` (Google-free) | `./gradlew :mua:assembleFossDebug` | `mua/build/outputs/apk/foss/debug/mua-foss-debug.apk` |
| `google` (FCM) | `./gradlew :mua:assembleGoogleDebug` | `mua/build/outputs/apk/google/debug/mua-google-debug.apk` |

The Google-free variant includes no Firebase or Google Play services SDKs.
Both variants share an application ID, so only one can be installed at a time.
Updates must use a compatible signing key.

Before building FCM, download **google-services.json** from your Firebase
project and put it at `mua/src/google/google-services.json`. FCM variants are skipped if
configuration is missing; Google-free builds do not need it. This file is ignored
by Git and is separate from the server's private key. Use `:mua:assembleDebug`
to build all available debug variants. For CI, set the `MUA_GOOGLE_SERVICES_JSON`
repository secret to the JSON contents; without it or a local file, CI builds
and releases only Google-free Mua.

With USB debugging enabled and the phone connected, install your chosen variant:

```sh
./gradlew :mua:installFossDebug
# Or, with Firebase configuration present:
./gradlew :mua:installGoogleDebug
```

Open Mua, grant Shizuku and notification permissions, and follow its battery
optimization setup for screen-off use. Shizuku must remain available for device
control, including after an FCM wake-up.

For signed APKs, configure [release signing](development.md#release-signing)
and build `:mua:assembleFossRelease` or `:mua:assembleGoogleRelease`.
Outputs are under `mua/build/outputs/apk/<flavor>/release/`. Keep the same signing
key for subsequent releases.

## Configure the central server

Create the server's environment file:

```sh
cp server/.env.example server/.env
```

Generate two independent random tokens, for example by running this twice:

```sh
uv run --directory server python -c 'import secrets; print(secrets.token_urlsafe(32))'
```

Set unquoted `KEY=value` entries in `server/.env`:

```dotenv
MUA_CLIENT_TOKEN=your-random-client-token
MUA_DEVICE_TOKEN=your-different-random-device-token
```

`MUA_CLIENT_TOKEN` lets MCP clients control all connected devices.
`MUA_DEVICE_TOKEN` is shared by the phones and authorizes device connections and
push registration. Give every phone a distinct device ID. Neither credential is
the local MCP token displayed by the Android app. Restart the server after
changing its environment.

### Run with Python

```sh
cd server
uv sync --locked
./start.sh --host 127.0.0.1 --port 8000
```

This listens on the host's loopback interface for a reverse proxy. For direct
LAN access, use `--host 0.0.0.0` and the host's LAN address on the phone.
HTTP does not encrypt credentials or screenshots; use HTTPS for internet access.

The source checkout stores SQLite data in `server/data/mua.sqlite3`, independent
of the working directory. The directory is ignored by `server/.gitignore`.
`start.sh` sets this path and loads `server/.env` relative to its own directory,
so it can also be run as `./server/start.sh` from the repository root.

`MUA_DATA_DIR` is required and must be nonempty. The launcher and Docker image
set it automatically; set it explicitly when running the Python module directly.

### Run with Docker

For Docker Compose, use [server/compose.example.yml](../server/compose.example.yml):

```sh
cd server
docker compose -f compose.example.yml pull
docker compose -f compose.example.yml up -d
```

Create `.env` as described above first. The example includes commented optional
FCM credential settings. See the [Compose instructions](../server/README.md#docker-compose)
for ports, credentials, and shutdown. To use plain Docker instead, follow the
commands below.

From the repository root:

```sh
docker build -t mua-server ./server
docker run -d --name mua-server --restart unless-stopped \
  --env-file server/.env \
  -v mua-data:/data \
  -p 127.0.0.1:8000:8000 \
  mua-server
```

The image stores SQLite at `/data/mua.sqlite3`; the named volume preserves it
when replacing the container. For a bind mount, the directory must be writable
by UID 10001. Keep `MUA_DATA_DIR` unset in the environment file to use the
image's default. The image excludes local credentials and runs as UID 10001.

Use `docker logs mua-server` to inspect startup and `docker stop mua-server`
to stop it. For direct LAN access, publish `-p 8000:8000` instead.

### Publish through HTTPS

Point a domain at your host and configure a TLS reverse proxy to forward to
`127.0.0.1:8000`. Forward these paths without stripping them:

- `/mcp` for Streamable HTTP clients.
- `/devices/{deviceId}/ws` with WebSocket upgrades for phone connections.
- `/devices/{deviceId}/push` for token registration.

Forwarding the entire domain to the server is also supported. Allow large
WebSocket messages for screenshot results and long-lived device connections.
The phone's URL is `https://mua.example.com`; Mua derives the WebSocket URL
internally. If you use a path prefix, preserve it consistently in the proxy and
server routing.

Run **one server process/worker and one replica**. Live device connections and
pending requests are held in memory; independent replicas cannot route calls to
each other's devices.

## Connect the phone and MCP client

1. Stop Mua before editing transport settings.
2. In **Central server**, enter `https://mua.example.com` (or
   `http://192.168.1.10:8000` for LAN testing). Do not append `/mcp` or use
   `ws://` or `wss://`.
3. Set a device name and unique device ID. Enter `MUA_DEVICE_TOKEN` in
   **Shared device token**.
4. Enable **Central server** and tap **Start mua**. Wait for **Connected**.
   The local **MCP server** switch is optional for central access.
5. Configure your MCP client with endpoint `https://mua.example.com/mcp` and
   header `Authorization: Bearer <MUA_CLIENT_TOKEN>`.
6. Call `devices_list`, then `display_start` with the phone's `deviceId`.
   Use the returned display ID for other tools and call `display_stop` when done.

The phone initiates the connection; it needs no public IP, port forwarding, or
inbound firewall rule. See the [central tool reference](mua-remote.md#central-mcp-tools)
for arguments and reconnect behavior.

## Enable automatic FCM wake-up

Skip this section for Google-free installations. Without FCM, start Mua manually
and keep its central connection running to receive calls.

1. Install the FCM APK built with your Firebase project's Android configuration.
2. Enable the Firebase Cloud Messaging API for that project and grant the server
   identity permission to send messages, such as the Firebase Cloud Messaging
   API Admin role.
3. Save the server service-account JSON at
   `server/firebase-service-account.json`. This path is ignored by Git. Keep
   the private key out of the Android APK and container image.
4. Restart `server/start.sh`. It automatically sets the credential path when
   `server/firebase-service-account.json` exists; no path entry in `.env` is needed.

5. On the phone, enable **Wake via FCM** with **Central server** configured and
   enabled. Mua registers its token automatically.

For Docker, set `GOOGLE_APPLICATION_CREDENTIALS` in `server/.env` to
`/run/secrets/firebase-service-account.json`, and add this mount to the
`docker run` command before the image name:

```sh
--mount "type=bind,src=$(pwd)/server/firebase-service-account.json,dst=/run/secrets/firebase-service-account.json,readonly"
```

Ensure UID 10001 can read the mounted key. The Android `google-services.json`
cannot authorize server sends. The service-account JSON supplies the project ID;
set `MUA_FIREBASE_PROJECT_ID` only to override it. To disable server wake-up, remove the credential file from the launcher
location and leave both `GOOGLE_APPLICATION_CREDENTIALS` and
`MUA_FIREBASE_PROJECT_ID` unset.

Check that `devices_list` reports `wakeEnabled` for the phone. A tool call to an
offline registered device triggers a push and waits up to 30 seconds for the
phone to reconnect. Listing devices alone does not send pushes.

Only sessions started by FCM stop after 10 minutes without calls. A manual start
keeps the service running. Token maintenance uses persistent background work
and does not require the UI or MCP service to stay open: it syncs on relevant
settings/token changes and every 30 days. Android can delay background work;
force-stopped apps must be opened again. The server retires tokens after 60 days
without synchronization. See [FCM behavior and validation](mua-fcm.md) for details.

## Maintenance and troubleshooting

Back up SQLite using an online SQLite backup or stop the server before copying
the database. Do not copy only the main file while it is being written: pending
changes can be in its WAL. The database contains device metadata and push tokens,
but no screenshot or task history. Keep the database and server credentials
private, and preserve the Docker volume when updating the image.

| Symptom | Check |
| --- | --- |
| FCM build task is unavailable | Put `google-services.json` in `mua/src/google/`, with an Android client matching `moe.reimu.mua`. |
| Phone cannot connect | Base URL, shared device token, server reachability, and proxy WebSocket support. |
| MCP client gets an authorization error | Use `MUA_CLIENT_TOKEN`, not the device or local MCP token. |
| Offline phone does not wake | FCM variant, opt-in, `wakeEnabled`, matching Firebase project, server credentials, Play services, and Android background restrictions. |
| Phone wakes but tools fail | Shizuku must be running with permission granted to Mua. |
| Registrations disappear after replacing the container | Mount the same persistent volume at `/data`. |

After a connection loss, check device state before retrying a mutating operation:
it may already have run. The server does not replay interrupted calls.
