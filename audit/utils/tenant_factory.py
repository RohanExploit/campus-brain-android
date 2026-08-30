"""
Tenant Factory — create/tear-down ephemeral test tenants for audit isolation.
"""
import sys
import uuid
import sqlite3
import shutil
from pathlib import Path
from contextlib import contextmanager

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_ROOT = PROJECT_ROOT / "data" / "tenants"

sys.path.insert(0, str(PROJECT_ROOT))
from ingestion.manifest import _get_manifest_conn


def create_tenant(tenant_id: str | None = None) -> dict:
    """
    Create an isolated test tenant directory with a blank manifest DB.
    Returns a dict with tenant_id, tenant_dir, manifest_db path.
    """
    if tenant_id is None:
        tenant_id = f"audit_{uuid.uuid4().hex[:8]}"

    tenant_dir = DATA_ROOT / tenant_id
    for sub in ["raw", "parsed", "embeddings"]:
        (tenant_dir / sub).mkdir(parents=True, exist_ok=True)

    # Production schema, not a local five-column approximation of it.
    manifest_db = tenant_dir / "manifest.db"
    _get_manifest_conn(tenant_dir).close()

    return {
        "tenant_id": tenant_id,
        "tenant_dir": tenant_dir,
        "manifest_db": manifest_db,
        "raw_dir": tenant_dir / "raw",
        "parsed_dir": tenant_dir / "parsed",
        "embeddings_dir": tenant_dir / "embeddings",
    }


def destroy_tenant(tenant_info: dict):
    """Remove the ephemeral tenant directory completely."""
    shutil.rmtree(tenant_info["tenant_dir"], ignore_errors=True)


@contextmanager
def ephemeral_tenant(tenant_id: str | None = None):
    """Context manager: create a tenant, yield its info, then destroy it."""
    info = create_tenant(tenant_id)
    try:
        yield info
    finally:
        destroy_tenant(info)


def seed_manifest(tenant_info: dict, records: list[dict]):
    """
    Insert records into the manifest DB.
    Each record: {doc_id, file_hash, parse_status, last_indexed_at?}
    """
    conn = sqlite3.connect(tenant_info["manifest_db"])
    try:
        for r in records:
            conn.execute(
                """INSERT OR REPLACE INTO manifest
                   (doc_id, file_hash, parse_status, last_indexed_at, error_message)
                   VALUES (?, ?, ?, ?, ?)""",
                (
                    r["doc_id"],
                    r["file_hash"],
                    r.get("parse_status", "SUCCESS"),
                    r.get("last_indexed_at", None),
                    r.get("error_message", None),
                ),
            )
        conn.commit()
    finally:
        conn.close()


def read_manifest(tenant_info: dict) -> list[dict]:
    """Read all manifest records for a tenant."""
    conn = sqlite3.connect(tenant_info["manifest_db"])
    try:
        rows = conn.execute(
            "SELECT doc_id, file_hash, parse_status, last_indexed_at, error_message FROM manifest"
        ).fetchall()
    finally:
        conn.close()
    return [
        dict(zip(["doc_id", "file_hash", "parse_status", "last_indexed_at", "error_message"], r))
        for r in rows
    ]
