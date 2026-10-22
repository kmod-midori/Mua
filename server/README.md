# mua central server

Connect multiple Android devices to your MCP client through a single endpoint.
Phones connect to the server from the mua app.

## Quick start

Requires Python 3.12+ and [uv](https://docs.astral.sh/uv/).

```sh
cd server
uv sync --locked
cp .env.example .env
```

Set `MUA_CLIENT_TOKEN` and `MUA_DEVICE_TOKEN` in `.env` to independently generated
random tokens, then start the server:

```sh
./start.sh --host 0.0.0.0 --port 8000
```

Follow the [building and self-hosting guide](../docs/building-and-self-hosting.md)
for deployment, connecting phones, and configuring your MCP client.
See the [gateway reference](../docs/mua-remote.md) for protocol and tool behavior.

`start.sh` resolves paths relative to `server/`, so it also works as
`./server/start.sh` from the repository root. It loads `server/.env`, sets `MUA_DATA_DIR` to `server/data` (containing
`mua.sqlite3`), and automatically uses
`server/firebase-service-account.json` for FCM if present. The credential file
is optional and ignored by Git. Arguments are forwarded to the server; the
default listener is `127.0.0.1:8000`.

`MUA_DATA_DIR` is required and must be nonempty. The launcher and Docker image
set it automatically; set it explicitly when running the Python module directly.

Docker deployments use the image entrypoint and should mount a volume at `/data`.
See [FCM registration](../docs/mua-fcm.md).

## Docker Compose

From `server/`, create `.env` from `.env.example` and set the two authentication
tokens, then run:

```sh
docker compose -f compose.example.yml pull
docker compose -f compose.example.yml up -d
```

The [Compose example](compose.example.yml) uses the published GHCR image, persists data
in a named volume, and binds to `127.0.0.1:8000` for a host reverse proxy. Change
the port mapping to `8000:8000` for direct LAN access. Use HTTPS for internet access.

For FCM, save `firebase-service-account.json` in `server/` and uncomment both the
credential environment entry and bind mount. The project ID is read from the key;
`MUA_FIREBASE_PROJECT_ID` in `.env` can override it. Without FCM, leave both Firebase
environment variables unset in `.env`.

View logs with `docker compose -f compose.example.yml logs -f` and stop with
`docker compose -f compose.example.yml down`. Do not add `--volumes` unless you
intend to delete the stored registrations.
