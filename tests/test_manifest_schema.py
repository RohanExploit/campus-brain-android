"""One manifest schema, one definition.

`pipeline.check_for_changes` used to CREATE its own `manifest` table shaped
(filepath, hash, last_indexed_at) in the same manifest.db that
`ingestion.manifest` creates as (doc_id, file_hash, parse_status, ...). Both used
CREATE TABLE IF NOT EXISTS, so whichever ran first defined the table and the
other silently mismatched -- in practice parse.py won on every real tenant and
run_pipeline died on `no such column: hash` before doing any work.

`scripts/bootstrap_manifests.py` carried a third copy that was missing the
`flags` column.

These tests fail if any of those copies come back.
"""
import re
import sqlite3
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from ingestion.manifest import MANIFEST_SCHEMA, _get_manifest_conn

EXPECTED_COLUMNS = {
    "doc_id",
    "file_hash",
    "parse_status",
    "last_indexed_at",
    "error_message",
    "page_count",
    "file_size_bytes",
    "flags",
}


def _columns_of(schema_sql: str) -> set[str]:
    body = schema_sql[schema_sql.index("(") + 1 : schema_sql.rindex(")")]
    cols = set()
    for line in body.splitlines():
        line = line.strip().rstrip(",")
        if not line:
            continue
        cols.add(line.split()[0])
    return cols


def test_parse_schema_has_expected_columns():
    assert _columns_of(MANIFEST_SCHEMA) == EXPECTED_COLUMNS


def test_created_manifest_matches_schema(tmp_path):
    conn = _get_manifest_conn(tmp_path)
    try:
        cols = {row[1] for row in conn.execute("PRAGMA table_info(manifest)")}
    finally:
        conn.close()
    assert cols == EXPECTED_COLUMNS


def test_legacy_manifest_is_migrated_in_place(tmp_path):
    """A manifest.db predating the `flags` column gains it rather than breaking."""
    legacy = tmp_path / "manifest.db"
    conn = sqlite3.connect(legacy)
    conn.execute(
        """CREATE TABLE manifest (
               doc_id TEXT PRIMARY KEY, file_hash TEXT NOT NULL,
               parse_status TEXT DEFAULT 'PENDING', last_indexed_at TEXT,
               error_message TEXT, page_count INTEGER, file_size_bytes INTEGER)"""
    )
    conn.execute(
        "INSERT INTO manifest (doc_id, file_hash) VALUES ('a.pdf', 'deadbeef')"
    )
    conn.commit()
    conn.close()

    conn = _get_manifest_conn(tmp_path)
    try:
        cols = {row[1] for row in conn.execute("PRAGMA table_info(manifest)")}
        rows = conn.execute("SELECT doc_id, file_hash FROM manifest").fetchall()
    finally:
        conn.close()
    assert cols == EXPECTED_COLUMNS
    assert rows == [("a.pdf", "deadbeef")], "migration must not drop existing rows"


def test_no_second_manifest_schema_definition_in_repo():
    """Only ingestion/parse.py may declare the manifest table."""
    offenders = []
    skip_dirs = {
        ".git", "node_modules", "__pycache__", ".venv312", "dashboard",
        "graphify-out", "data", "Dataset", "Results Dataset", "debug_outputs",
    }
    for path in PROJECT_ROOT.rglob("*.py"):
        if any(part in skip_dirs for part in path.parts):
            continue
        if path == PROJECT_ROOT / "ingestion" / "manifest.py":
            continue
        if path == Path(__file__):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        # Only flag a CREATE TABLE whose target is the manifest table itself,
        # not an unrelated table mentioning the word later in the file.
        for match in re.finditer(
            r"CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?manifest\b",
            text,
            re.IGNORECASE,
        ):
            offenders.append(f"{path.relative_to(PROJECT_ROOT)}:{text[:match.start()].count(chr(10)) + 1}")
    assert not offenders, (
        "manifest schema redeclared outside ingestion/manifest.py: " + ", ".join(offenders)
    )


@pytest.mark.parametrize(
    "manifest_path",
    sorted((PROJECT_ROOT / "data" / "tenants").glob("*/manifest.db"))
    if (PROJECT_ROOT / "data" / "tenants").exists()
    else [],
    ids=lambda p: p.parent.name,
)
def test_live_tenant_manifests_match_schema(manifest_path):
    """Every real manifest that has a manifest table has the right columns.

    Tenants ingested via tests/eval/ingest_*.py skip parse.py entirely and have
    a manifest.db with no manifest table at all -- that is a different gap, not
    a schema mismatch, so it is skipped rather than failed.
    """
    conn = sqlite3.connect(f"file:{manifest_path}?mode=ro", uri=True)
    try:
        has_table = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='manifest'"
        ).fetchone()
        if not has_table:
            pytest.skip(f"{manifest_path.parent.name} has no manifest table")
        cols = {row[1] for row in conn.execute("PRAGMA table_info(manifest)")}
    finally:
        conn.close()
    assert cols == EXPECTED_COLUMNS
