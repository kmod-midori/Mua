#!/usr/bin/env python3
"""Export the shared Android tool catalog for the standalone Python package."""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
source = root / "display-control/src/main/resources/device_tools.json"
target = root / "server/src/mua_server/catalog/device_tools.json"
if "--check" in sys.argv:
    if source.read_bytes() != target.read_bytes():
        sys.exit("Server tool catalog is stale; run python scripts/sync-tool-catalog.py")
else:
    target.write_bytes(source.read_bytes())
