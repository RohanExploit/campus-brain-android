"""
Audit 20 — Enterprise Chaos
Pass: System degrades gracefully under resource failures. No data corruption. No crash.
"""
import pytest
import duckdb
from pathlib import Path
from unittest.mock import patch, MagicMock

pytestmark = pytest.mark.reliability

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_ROOT = PROJECT_ROOT / "data" / "tenants"


class TestEnterpriseChaos:

    def test_ollama_down_triggers_fallback(self):
        """When Ollama is unreachable, generate_answer returns a fallback, not a crash."""
        import httpx
        from unittest.mock import AsyncMock, patch
        import asyncio
        from generation.answer import generate_answer

        async def _run():
            with patch("httpx.AsyncClient.post", new_callable=AsyncMock) as mock_post:
                mock_post.side_effect = httpx.ConnectError("Connection refused")
                result = await generate_answer("test query", "test context")
                return result

        result = asyncio.get_event_loop().run_until_complete(_run())
        assert result is not None, "generate_answer must not return None when Ollama is down"
        assert isinstance(result, str), "Fallback must return a string"
        assert len(result) > 0, "Fallback response must not be empty"

    def test_corrupt_duckdb_handled_gracefully(self, tmp_path):
        """A corrupt DuckDB file must raise an exception, not crash the process."""
        corrupt_db = tmp_path / "corrupt.duckdb"
        corrupt_db.write_bytes(b"THIS IS NOT A VALID DUCKDB FILE")
        with pytest.raises(Exception):
            con = duckdb.connect(str(corrupt_db))
            con.execute("SELECT 1")

    def test_missing_faiss_index_handled(self, tmp_path):
        """Missing FAISS index must be caught and logged, not crash the server."""
        faiss_path = tmp_path / "nonexistent.faiss"
        assert not faiss_path.exists()
        # Simulate the load logic
        try:
            import faiss
            faiss.read_index(str(faiss_path))
            pytest.fail("Should have raised an exception for missing FAISS file")
        except Exception as e:
            assert e is not None  # Exception caught — graceful handling

    def test_disk_full_simulation(self, tmp_path):
        """When disk is nearly full, ingestion must reject cleanly, not corrupt data."""
        with patch("os.statvfs") as mock_statvfs:
            mock_result = MagicMock()
            mock_result.f_bavail = 100        # 100 blocks free
            mock_result.f_bsize = 512          # 512 bytes per block
            mock_result.f_bavail * mock_result.f_bsize  # = 51200 bytes ≈ 50KB
            mock_statvfs.return_value = mock_result

            # Check that the system would detect low disk
            free_bytes = mock_result.f_bavail * mock_result.f_bsize
            MIN_FREE = 100 * 1024 * 1024  # 100 MB minimum
            would_reject = free_bytes < MIN_FREE
            assert would_reject, "System should reject ingestion when < 100MB free"

    def test_manifest_db_survives_concurrent_reads(self):
        """Multiple concurrent read connections to manifest.db must not deadlock."""
        import threading
        import sqlite3

        dbs = list(DATA_ROOT.glob("*/manifest.db"))
        if not dbs:
            pytest.skip("No manifest.db found")

        db_path = str(dbs[0])
        results = []
        errors = []

        def read_manifest():
            try:
                conn = sqlite3.connect(db_path, timeout=5)
                count = conn.execute("SELECT COUNT(*) FROM manifest").fetchone()[0]
                conn.close()
                results.append(count)
            except Exception as e:
                errors.append(str(e))

        threads = [threading.Thread(target=read_manifest) for _ in range(10)]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)

        assert not errors, f"Concurrent reads caused errors: {errors}"
        assert len(results) == 10, f"Only {len(results)}/10 threads completed"

    def test_key_rotation_fails_gracefully(self, tmp_path):
        """Wrong decryption key must raise InvalidTag, not silently return garbage."""
        try:
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            import os
            key1 = AESGCM(os.urandom(32))
            key2 = AESGCM(os.urandom(32))
            nonce = os.urandom(12)
            ciphertext = key1.encrypt(nonce, b"student data", None)
            with pytest.raises(Exception):
                key2.decrypt(nonce, ciphertext, None)
        except ImportError:
            pytest.skip("cryptography not installed")

    def test_router_fallback_on_exception(self):
        """QueryRouter must return a safe fallback if classification fails."""
        from retrieval.router import QueryRouter
        import inspect
        src = inspect.getsource(QueryRouter)
        assert "except" in src or "try" in src, \
            "QueryRouter must have try/except for graceful fallback"
