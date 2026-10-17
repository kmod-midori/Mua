# mua central server

A FastAPI gateway exposing a Streamable HTTP MCP endpoint for Android devices
connected through outbound WebSockets. Uses the official Python MCP SDK and uv.
The existing on-device MCP endpoint stays available.

Requires Python 3.12+ and [uv](https://docs.astral.sh/uv/).

```sh
cd server
uv sync --locked
cp .env.example .env
# Edit .env with independently generated client/device tokens.
uv run --env-file .env python -m mua_server --host 0.0.0.0 --port 8000
```

Configure `.env` with `MUA_CLIENT_TOKEN` and `MUA_DEVICE_TOKEN` first.
See [setup and protocol](../docs/mua-remote.md) for credentials, mobile settings,
MCP examples, and deployment details.

## Docker

From the repository root, after configuring `server/.env`:

```sh
docker build -t mua-server ./server
docker run --rm --name mua-server --env-file server/.env -p 8000:8000 mua-server
```

The image runs as a non-root user with one server worker. Credentials are supplied
at runtime. See [container deployment](../docs/mua-remote.md#docker) for details.
