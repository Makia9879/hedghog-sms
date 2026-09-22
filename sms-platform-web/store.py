"""把短信正文、平台和卡槽存到本机 SQLite。"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import threading
import time
from pathlib import Path

from classifier import (
    HUMAN,
    FeatureExtractor,
    LocalModel,
    classify_text,
    display_name,
    stable_platform_key,
    stable_platform_label_id,
)
from parser import ParsedMessage


class Store:
    def __init__(self, data_dir: Path):
        self.data_dir = data_dir
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.db_path = self.data_dir / "platform.sqlite"
        self.key_path = self.data_dir / "hmac.key"
        self.lock = threading.RLock()
        self._init_db()
        self.extractor = FeatureExtractor(self._hmac_key())

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path)
        connection.row_factory = sqlite3.Row
        return connection

    def import_messages(self, messages: list[ParsedMessage]) -> dict[str, int]:
        inserted = 0
        skipped = 0
        now = int(time.time() * 1000)
        with self.lock, self.connect() as connection:
            for message in messages:
                digest = hashlib.sha256(
                    "\n".join((message.slot, message.received_at, message.sender, message.body)).encode("utf-8")
                ).hexdigest()
                cursor = connection.execute(
                    """
                    INSERT OR IGNORE INTO messages
                      (content_hash, slot, sender, received_at, direction, body, status, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'UNCLASSIFIED', ?)
                    """,
                    (digest, message.slot, message.sender, message.received_at, message.direction, message.body, now),
                )
                if cursor.rowcount:
                    inserted += 1
                else:
                    skipped += 1
        return {"inserted": inserted, "skipped": skipped}

    def classify_all(self) -> dict[str, int]:
        now = int(time.time() * 1000)
        counts = {"LABELED": 0, "PENDING_LABEL": 0, "NON_OTP": 0}
        with self.lock, self.connect() as connection:
            model, names = self._load_model(connection)
            rows = connection.execute(
                "SELECT id, sender, body FROM messages WHERE source IS NULL OR source != ?",
                (HUMAN,),
            ).fetchall()
            for row in rows:
                result = classify_text(row["body"], row["sender"], model, self.extractor, names)
                connection.execute(
                    """
                    UPDATE messages
                    SET is_otp = ?, platform_key = ?, platform_name = ?, status = ?, source = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (
                        1 if result.is_otp else 0,
                        result.platform_key,
                        result.platform_name,
                        result.status,
                        result.source,
                        now,
                        row["id"],
                    ),
                )
                if result.status == "LABELED" and result.platform_key and result.platform_name and result.label_id:
                    self._remember_platform(connection, result.platform_key, result.platform_name, result.label_id)
                counts[result.status] = counts.get(result.status, 0) + 1
        return counts

    def label_messages(self, message_ids: list[int], platform_name: str) -> int:
        name = display_name(platform_name)
        platform_key = stable_platform_key(name)
        label_id = stable_platform_label_id(name)
        now = int(time.time() * 1000)
        updated = 0
        with self.lock, self.connect() as connection:
            model, names = self._load_model(connection)
            self._remember_platform(connection, platform_key, name, label_id)
            names[label_id] = (platform_key, name)
            for message_id in message_ids:
                row = connection.execute("SELECT id, sender, body FROM messages WHERE id = ?", (message_id,)).fetchone()
                if row is None:
                    continue
                features = self.extractor.extract(row["body"], row["sender"])
                trained = connection.execute(
                    "SELECT label_id FROM training_samples WHERE message_id = ?",
                    (message_id,),
                ).fetchone()
                if trained is None:
                    model.learn(message_id, label_id, features)
                    connection.execute(
                        "INSERT INTO training_samples (message_id, label_id, features_json) VALUES (?, ?, ?)",
                        (message_id, label_id, _dump_features(features)),
                    )
                elif trained["label_id"] != label_id:
                    model.correct(message_id, trained["label_id"], label_id)
                    connection.execute(
                        "UPDATE training_samples SET label_id = ?, features_json = ? WHERE message_id = ?",
                        (label_id, _dump_features(features), message_id),
                    )
                connection.execute(
                    """
                    UPDATE messages
                    SET is_otp = 1, platform_key = ?, platform_name = ?, status = 'LABELED',
                        source = ?, updated_at = ?
                    WHERE id = ?
                    """,
                    (platform_key, name, HUMAN, now, message_id),
                )
                updated += 1
        if updated:
            self.classify_all()
        return updated

    def summary(self) -> dict:
        with self.lock, self.connect() as connection:
            totals = connection.execute(
                """
                SELECT
                  COUNT(*) AS messages,
                  SUM(status = 'LABELED') AS labeled,
                  SUM(status = 'PENDING_LABEL') AS pending,
                  SUM(status = 'NON_OTP') AS non_otp,
                  SUM(status = 'UNCLASSIFIED') AS unclassified
                FROM messages
                """
            ).fetchone()
            slots = connection.execute(
                """
                SELECT slot,
                       COUNT(*) AS messages,
                       SUM(status = 'LABELED') AS labeled,
                       SUM(status = 'PENDING_LABEL') AS pending
                FROM messages
                GROUP BY slot
                ORDER BY slot
                """
            ).fetchall()
            platforms = connection.execute(
                """
                SELECT slot, platform_key, platform_name, COUNT(*) AS count, MAX(received_at) AS latest
                FROM messages
                WHERE status = 'LABELED'
                GROUP BY slot, platform_key, platform_name
                ORDER BY slot, count DESC, platform_name
                """
            ).fetchall()
        return {
            "totals": {key: totals[key] or 0 for key in totals.keys()},
            "slots": [dict(row) for row in slots],
            "platforms": [dict(row) for row in platforms],
        }

    def messages(self, slot: str | None, platform_key: str | None, status: str | None, limit: int = 80) -> list[dict]:
        clauses: list[str] = []
        args: list[object] = []
        if slot:
            clauses.append("slot = ?")
            args.append(slot)
        if platform_key:
            clauses.append("platform_key = ?")
            args.append(platform_key)
        if status:
            clauses.append("status = ?")
            args.append(status)
        where = ("WHERE " + " AND ".join(clauses)) if clauses else ""
        args.append(max(1, min(limit, 200)))
        with self.lock, self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT id, slot, sender, received_at, direction, body, platform_name, platform_key, status, source
                FROM messages
                {where}
                ORDER BY received_at DESC, id DESC
                LIMIT ?
                """,
                args,
            ).fetchall()
        return [dict(row) for row in rows]

    def _init_db(self) -> None:
        with self.connect() as connection:
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS messages (
                  id INTEGER PRIMARY KEY,
                  content_hash TEXT NOT NULL UNIQUE,
                  slot TEXT NOT NULL,
                  sender TEXT,
                  received_at TEXT,
                  direction TEXT,
                  body TEXT NOT NULL,
                  is_otp INTEGER NOT NULL DEFAULT 0,
                  platform_key TEXT,
                  platform_name TEXT,
                  status TEXT NOT NULL DEFAULT 'UNCLASSIFIED',
                  source TEXT,
                  updated_at INTEGER NOT NULL
                );
                CREATE TABLE IF NOT EXISTS platforms (
                  label_id INTEGER PRIMARY KEY,
                  platform_key TEXT NOT NULL,
                  display_name TEXT NOT NULL
                );
                CREATE TABLE IF NOT EXISTS training_samples (
                  message_id INTEGER PRIMARY KEY,
                  label_id INTEGER NOT NULL,
                  features_json TEXT NOT NULL
                );
                """
            )

    def _hmac_key(self) -> bytes:
        if self.key_path.exists():
            return self.key_path.read_bytes()
        key = hashlib.sha256(str(time.time_ns()).encode("utf-8")).digest()
        self.key_path.write_bytes(key)
        return key

    def _remember_platform(self, connection: sqlite3.Connection, platform_key: str, name: str, label_id: int) -> None:
        connection.execute(
            """
            INSERT INTO platforms (label_id, platform_key, display_name)
            VALUES (?, ?, ?)
            ON CONFLICT(label_id) DO UPDATE SET platform_key = excluded.platform_key, display_name = excluded.display_name
            """,
            (label_id, platform_key, name),
        )

    def _load_model(self, connection: sqlite3.Connection) -> tuple[LocalModel, dict[int, tuple[str, str]]]:
        model = LocalModel()
        for row in connection.execute("SELECT message_id, label_id, features_json FROM training_samples ORDER BY message_id"):
            model.learn(row["message_id"], row["label_id"], _load_features(row["features_json"]))
        names = {
            row["label_id"]: (row["platform_key"], row["display_name"])
            for row in connection.execute("SELECT label_id, platform_key, display_name FROM platforms")
        }
        return model, names


def _dump_features(features: dict[int, int]) -> str:
    return json.dumps({str(bucket): count for bucket, count in features.items()}, separators=(",", ":"))


def _load_features(payload: str) -> dict[int, int]:
    return {int(bucket): count for bucket, count in json.loads(payload).items()}
