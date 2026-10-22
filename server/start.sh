#!/bin/sh
set -eu

SERVER_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SERVER_DIR"

export MUA_DATA_DIR="$SERVER_DIR/data"
if [ -f "$SERVER_DIR/firebase-service-account.json" ]; then
    export GOOGLE_APPLICATION_CREDENTIALS="$SERVER_DIR/firebase-service-account.json"
fi

exec uv run --locked --env-file "$SERVER_DIR/.env" python -m mua_server "$@"
