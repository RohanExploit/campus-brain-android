"""Manifest ledger: the one definition of the per-tenant manifest.db schema.

Lives apart from ingestion/parse.py so that tooling which only needs to read or
write the ledger -- scripts/bootstrap_manifests.py, the corpus catalog -- does
not have to import Docling and the whole parsing stack to do it.

parse.py re-imports these names, so every existing
`from ingestion.parse import _sha256` style reference keeps working.
"""
import hashlib
import json
import sqlite3
from datetime import datetime, timezone
from pathlib import Path

# ─── Manifest helpers ──────────────────────────────────────────────────────────

MANIFEST_SCHEMA = """
CREATE TABLE IF NOT EXISTS manifest (
    doc_id          TEXT PRIMARY KEY,
    file_hash       TEXT NOT NULL,
    parse_status    TEXT DEFAULT 'PENDING',
    last_indexed_at TEXT,
    error_message   TEXT,
    page_count      INTEGER,
    file_size_bytes INTEGER,
    flags           TEXT
);
"""

def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

def _get_manifest_conn(tenant_dir: Path) -> sqlite3.Connection:
    manifest_db = tenant_dir / "manifest.db"
    conn = sqlite3.connect(manifest_db)
    conn.execute(MANIFEST_SCHEMA)
    # CREATE TABLE IF NOT EXISTS is a no-op on manifest.db files created
    # before the `flags` column was added — migrate those in place.
    cols = {row[1] for row in conn.execute("PRAGMA table_info(manifest)")}
    if "flags" not in cols:
        conn.execute("ALTER TABLE manifest ADD COLUMN flags TEXT")
    conn.commit()
    return conn

def _manifest_update(conn: sqlite3.Connection, doc_id: str, file_hash: str,
                      parse_status: str, page_count=None, error_message=None,
                      flags: list = None, file_size_bytes: int = None):
    conn.execute(
        """INSERT OR REPLACE INTO manifest
           (doc_id, file_hash, parse_status, last_indexed_at, error_message, page_count, file_size_bytes, flags)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
        (
            doc_id,
            file_hash,
            parse_status,
            datetime.now(timezone.utc).isoformat(),
            error_message,
            page_count,
            file_size_bytes,
            json.dumps(flags or []),
        )
    )
    conn.commit()

def _manifest_get(conn: sqlite3.Connection, doc_id: str) -> dict | None:
    row = conn.execute(
        "SELECT doc_id, file_hash, parse_status FROM manifest WHERE doc_id = ?", (doc_id,)
    ).fetchone()
    if not row:
        return None
    return {"doc_id": row[0], "file_hash": row[1], "parse_status": row[2]}
