"""Run one gateway process; its WebSocket registry is intentionally process-local."""

import argparse

import uvicorn


def main():
    parser = argparse.ArgumentParser(description="mua central MCP server")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    uvicorn.run(
        "mua_server.app:create_app", factory=True, host=args.host, port=args.port, ws_max_size=None
    )


if __name__ == "__main__":
    main()
