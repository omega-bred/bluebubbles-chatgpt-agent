import json
import sqlite3
import time
from contextlib import closing
from dataclasses import dataclass
from hashlib import sha256
from uuid import UUID


@dataclass(frozen=True)
class OutboundRequest:
    destination_hash: str
    content: str
    title: str = ""
    request_id: str | None = None

    def __post_init__(self):
        if not all(isinstance(value, str) for value in (self.destination_hash, self.content, self.title)):
            raise ValueError("destination_hash, content and title must be strings")
        object.__setattr__(self, "destination_hash", self.destination_hash.strip().lower())
        if not self.destination_hash or not self.content.strip():
            raise ValueError("destination_hash and content are required")
        if self.request_id is not None:
            object.__setattr__(self, "request_id", str(UUID(self.request_id)))

    def fingerprint(self):
        data = json.dumps([self.destination_hash, self.content, self.title], separators=(",", ":"))
        return sha256(data.encode()).hexdigest()


class DeliveryConflict(ValueError):
    pass


class OutboundDeliveries:
    def __init__(self, path):
        self.path = path
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("""
                CREATE TABLE IF NOT EXISTS outbound_requests (
                    request_id TEXT PRIMARY KEY, fingerprint TEXT NOT NULL,
                    message_id TEXT, created_at INTEGER NOT NULL
                )
                """)
            db.execute("DELETE FROM outbound_requests WHERE created_at < ?",
                       (int(time.time()) - 30 * 86400,))

    def lookup(self, request_id, fingerprint=None):
        with closing(sqlite3.connect(self.path)) as db:
            row = db.execute(
                "SELECT fingerprint, message_id FROM outbound_requests WHERE request_id = ?",
                (request_id,),
            ).fetchone()
        if row is None:
            return None
        if fingerprint is not None and row[0] != fingerprint:
            raise DeliveryConflict("request_id already belongs to a different message")
        return ({"status": "queued", "message_id": row[1]}
                if row[1] is not None else {"status": "pending"})

    def send(self, request, prepare, enqueue):
        fingerprint = request.fingerprint()
        receipt = self.lookup(request.request_id, fingerprint)
        if receipt is not None:
            return receipt
        # Path discovery can fail safely before reserving the send.
        message = prepare(request.destination_hash, request.content, request.title)
        with closing(sqlite3.connect(self.path)) as db, db:
            inserted = db.execute("""
                INSERT INTO outbound_requests (request_id, fingerprint, created_at)
                VALUES (?, ?, ?) ON CONFLICT DO NOTHING
                """, (request.request_id, fingerprint, int(time.time()))).rowcount
        if inserted == 0:
            return self.lookup(request.request_id, fingerprint)

        # A pending receipt survives interrupted delivery and prevents a second enqueue.
        outbound_id = enqueue(message)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute("UPDATE outbound_requests SET message_id = ? WHERE request_id = ?",
                       (outbound_id, request.request_id))
        return {"status": "queued", "message_id": outbound_id}
