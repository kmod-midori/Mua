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
uv run --env-file .env python -m mua_server --host 0.0.0.0 --port 8000
```

Follow the [setup guide](../docs/mua-remote.md) to connect phones and configure
your MCP client. [Docker deployment](../docs/mua-remote.md#docker) is also available.
