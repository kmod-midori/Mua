"""Durable device metadata. Live sockets and pending calls remain process-local."""

import sqlite3
from contextlib import closing
from datetime import UTC, datetime, timedelta
from pathlib import Path


class DeviceStore:
    STALE_AFTER = timedelta(days=60)

    def __init__(self, path: str):
        self.path = path

    def initialize(self):
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(self.connect()) as db, db:
            if db.execute("PRAGMA user_version").fetchone()[0] not in (0, 1):
                raise ValueError("Unsupported database schema version")
            db.execute("PRAGMA journal_mode=WAL")
            db.execute("""CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                fcm_token TEXT,
                push_updated_at TEXT,
                last_seen_at TEXT
            )""")
            db.execute("PRAGMA user_version=1")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def register_push(self, device_id: str, name: str, token: str | None):
        with closing(self.connect()) as db, db:
            # A rotated/reassigned installation token must not wake two device identities.
            if token is not None:
                db.execute(
                    "UPDATE devices SET fcm_token=NULL WHERE fcm_token=? AND device_id<>?",
                    (token, device_id),
                )
            db.execute(
                """INSERT INTO devices(device_id, name, fcm_token, push_updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET name=excluded.name,
                fcm_token=excluded.fcm_token, push_updated_at=excluded.push_updated_at""",
                (device_id, name, token, datetime.now(UTC).isoformat()),
            )

    def seen(self, device_id: str, name: str):
        with closing(self.connect()) as db, db:
            db.execute(
                """INSERT INTO devices(device_id, name, last_seen_at) VALUES (?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET name=excluded.name,
                last_seen_at=excluded.last_seen_at""",
                (device_id, name, datetime.now(UTC).isoformat()),
            )

    def _prune_stale(self, db):
        # Two monthly refresh opportunities before retiring a registration. Keep
        # device metadata; a later client upload can register the device again.
        cutoff = (datetime.now(UTC) - self.STALE_AFTER).isoformat()
        db.execute(
            """UPDATE devices SET fcm_token=NULL WHERE fcm_token IS NOT NULL
            AND (push_updated_at IS NULL OR push_updated_at < ?)""",
            (cutoff,),
        )

    def list_devices(self):
        with closing(self.connect()) as db, db:
            self._prune_stale(db)
            return [dict(row) for row in db.execute("SELECT * FROM devices ORDER BY device_id")]

    def push_token(self, device_id: str):
        with closing(self.connect()) as db, db:
            self._prune_stale(db)
            row = db.execute(
                "SELECT fcm_token FROM devices WHERE device_id=?", (device_id,)
            ).fetchone()
            return row[0] if row else None

    def clear_token(self, device_id: str, token: str):
        with closing(self.connect()) as db, db:
            db.execute(
                "UPDATE devices SET fcm_token=NULL WHERE device_id=? AND fcm_token=?",
                (device_id, token),
            )
