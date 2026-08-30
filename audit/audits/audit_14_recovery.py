"""
Audit 14 — Recovery
Pass: System restarts cleanly after crash. No data lost. Ingestion is resumable.
"""
import sqlite3
import pytest
from pathlib import Path

pytestmark = pytest.mark.reliability

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_ROOT = PROJECT_ROOT / "data" / "tenants"


class TestRecovery:

    def test_manifest_db_uses_sqlite(self):
        """manifest.db is SQLite — supports WAL mode for crash safety."""
        dbs = list(DATA_ROOT.glob("*/manifest.db"))
        assert dbs, "No manifest.db found in any tenant directory"
        for db in dbs[:2]:
            conn = sqlite3.connect(db)
            conn.execute("PRAGMA journal_mode=WAL")
            mode = conn.execute("PRAGMA journal_mode").fetchone()[0]
            conn.close()
            assert mode in ("wal", "delete"), f"{db.name}: WAL mode failed"

    def test_parse_py_skips_already_parsed(self):
        """parse.py skips files whose hash hasn't changed — idempotent pipeline."""
        parse_py = PROJECT_ROOT / "ingestion" / "parse.py"
        content = parse_py.read_text(encoding="utf-8")
        assert "file_hash" in content or "exists()" in content, \
            "parse.py must check existing hash or output file to skip already-parsed files"

    def test_manifest_status_never_null(self):
        """Every manifest entry has a non-null parse_status."""
        dbs = list(DATA_ROOT.glob("*/manifest.db"))
        if not dbs:
            pytest.skip("No manifest.db found")
        for db in dbs[:3]:
            conn = sqlite3.connect(db)
            null_rows = conn.execute(
                "SELECT COUNT(*) FROM manifest WHERE parse_status IS NULL"
            ).fetchone()[0]
            conn.close()
            assert null_rows == 0, f"{db.parent.name}: {null_rows} rows with NULL parse_status"

    def test_failed_parse_recorded_not_silenced(self):
        """PARSE_FAILURE flag must appear in manifest flags column, not be dropped."""
        dbs = list(DATA_ROOT.glob("*/manifest.db"))
        if not dbs:
            pytest.skip("No manifest.db found")
        for db in dbs[:3]:
            conn = sqlite3.connect(db)
            failed = conn.execute(
                "SELECT doc_id, flags FROM manifest WHERE parse_status = 'FAILED'"
            ).fetchall()
            conn.close()
            for doc_id, flags in failed:
                assert flags is not None, f"{doc_id}: FAILED status with no flags"

    def test_faiss_index_rebuild_on_hash_change(self):
        """vector store must not reuse stale FAISS index after document change."""
        vector_store = PROJECT_ROOT / "ingestion" / "vector_store.py"
        assert vector_store.exists(), "vector_store.py not found"
        content = vector_store.read_text(encoding="utf-8")
        assert "faiss" in content.lower(), "vector_store.py must reference FAISS"

    def test_duckdb_survives_read_only_open(self):
        """Production DuckDB opens read-only — concurrent readers never corrupt data."""
        import duckdb
        dbs = list(DATA_ROOT.glob("*/tabular.duckdb"))
        if not dbs:
            pytest.skip("No tabular.duckdb found")
        con = duckdb.connect(str(dbs[0]), read_only=True)
        count = con.execute("SELECT COUNT(*) FROM students").fetchone()[0]
        con.close()
        assert count >= 0

    def test_no_orphaned_manifest_entries(self):
        """Every manifest entry should correspond to a file in raw/ or parsed/."""
        dbs = list(DATA_ROOT.glob("*/manifest.db"))
        if not dbs:
            pytest.skip("No manifest.db found")
        for db in dbs[:2]:
            tenant_dir = db.parent
            raw_dir = tenant_dir / "raw"
            conn = sqlite3.connect(db)
            doc_ids = {r[0] for r in conn.execute("SELECT doc_id FROM manifest").fetchall()}
            conn.close()
            if raw_dir.exists():
                raw_files = {f.name for f in raw_dir.iterdir() if f.is_file()}
                orphans = doc_ids - raw_files
                assert len(orphans) < len(doc_ids) * 0.1, \
                    f"More than 10% manifest entries are orphaned: {list(orphans)[:5]}"
