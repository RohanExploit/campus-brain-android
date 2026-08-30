"""Hermetic tests for scripts/export_mobile_bundle.py.

No dependency on the real corpus under data/ (gitignored, absent in CI).
Builds a tiny synthetic tenant fixture in tmp_path and exercises build_bundle().
"""

import json
import sqlite3
import sys
from pathlib import Path

import numpy as np
import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT / "scripts"))

from export_mobile_bundle import build_bundle  # noqa: E402


@pytest.fixture()
def synthetic_corpus(tmp_path):
    chunks = [
        {
            "page_content": "The quick brown fox jumps over the lazy dog.",
            "metadata": {
                "source": "doc_a.md",
                "chunk_index": 0,
                "Header 2": "Intro",
                "Header 3": "Overview",
            },
        },
        {
            "page_content": "SQLite full text search uses FTS5 virtual tables.",
            "metadata": {"source": "doc_a.md", "chunk_index": 1},
        },
        {
            "page_content": "Mobile bundles ship as a single asset database.",
            "metadata": {
                "source": "doc_b.md",
                "chunk_index": 0,
                "Header 2": "Deployment",
            },
        },
    ]
    chunks_json_path = tmp_path / "embeddings_chunks.json"
    chunks_json_path.write_text(json.dumps(chunks), encoding="utf-8")

    rng = np.random.default_rng(42)
    embeddings = rng.random((3, 4), dtype=np.float32)
    embeddings_npy_path = tmp_path / "embeddings.npy"
    np.save(embeddings_npy_path, embeddings)

    # Tiny duckdb with students / student_subjects tables.
    duckdb = pytest.importorskip("duckdb")
    tabular_path = tmp_path / "tabular.duckdb"
    dcon = duckdb.connect(str(tabular_path))
    dcon.execute(
        "CREATE TABLE students (roll_no VARCHAR, name VARCHAR, sgpa DOUBLE, "
        "estimated_sgpa DOUBLE, total_marks INTEGER, result VARCHAR, "
        "is_supply BOOLEAN, seat_cancelled BOOLEAN)"
    )
    dcon.execute(
        "INSERT INTO students VALUES "
        "('R001', 'Alice', 8.5, 8.5, 450, 'PASS', False, False), "
        "('R002', 'Bob', NULL, 4.1, 300, 'FAIL', True, False)"
    )
    dcon.execute(
        "CREATE TABLE student_subjects (roll_no VARCHAR, subject_code VARCHAR, "
        "credit INTEGER, grade VARCHAR, grade_point DOUBLE, raw_grade_string VARCHAR)"
    )
    dcon.execute(
        "INSERT INTO student_subjects VALUES "
        "('R001', 'CS101', 4, 'A', 9.0, 'A'), "
        "('R002', 'CS101', 4, 'F', 0.0, 'F')"
    )
    dcon.close()

    # Tiny graphml file.
    nx = pytest.importorskip("networkx")
    graph = nx.Graph()
    graph.add_edge("Alice", "CS101", relation="ENROLLED_IN")
    graphml_path = tmp_path / "company_brain.graphml"
    nx.write_graphml(graph, str(graphml_path))

    return {
        "chunks": chunks,
        "embeddings": embeddings,
        "chunks_json_path": chunks_json_path,
        "embeddings_npy_path": embeddings_npy_path,
        "tabular_duckdb_path": tabular_path,
        "graphml_path": graphml_path,
    }


def test_build_bundle(tmp_path, synthetic_corpus):
    out_path = tmp_path / "out" / "brain.db"
    stats = build_bundle(
        tenant_id="tenant_test",
        chunks_json_path=synthetic_corpus["chunks_json_path"],
        embeddings_npy_path=synthetic_corpus["embeddings_npy_path"],
        tabular_duckdb_path=synthetic_corpus["tabular_duckdb_path"],
        graphml_path=synthetic_corpus["graphml_path"],
        out_path=out_path,
        source_commit="deadbee",
    )

    assert out_path.exists()
    assert stats["chunk_count"] == 3
    assert stats["embedding_dim"] == 4
    assert stats["student_count"] == 2
    assert stats["subject_row_count"] == 2
    assert stats["edge_count"] == 1

    conn = sqlite3.connect(str(out_path))
    try:
        cur = conn.cursor()

        # user_version == 2 since the `documents` table was added. Readers must
        # treat that table as optional (the Kotlin app probes sqlite_master for
        # it) so a version-1 bundle still opens.
        cur.execute("PRAGMA user_version")
        assert cur.fetchone()[0] == 2

        # All tables exist.
        cur.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
        )
        table_names = {row[0] for row in cur.fetchall()}
        for expected in [
            "meta",
            "chunks",
            "chunks_fts",
            "embeddings",
            "graph_edges",
            "students",
            "student_subjects",
            "documents",
        ]:
            assert expected in table_names

        # documents is derived from chunks, so it cannot disagree with what is
        # actually retrievable: a document exists there only if it has chunks.
        cur.execute("SELECT COUNT(*) FROM documents")
        n_docs = cur.fetchone()[0]
        cur.execute("SELECT COUNT(DISTINCT doc_id) FROM chunks")
        assert n_docs == cur.fetchone()[0]

        cur.execute("SELECT value FROM meta WHERE key = 'document_count'")
        assert int(cur.fetchone()[0]) == n_docs

        # Every document carries a title, and the numeric ordering prefix the
        # renderers emit ("24_", "svc_02_") is a sort key, not part of the name.
        cur.execute("SELECT doc_id, title, chunk_count FROM documents")
        for doc_id, title, chunk_count in cur.fetchall():
            assert title, doc_id
            assert not title[0].isdigit(), title
            assert chunk_count > 0, doc_id

        # chunks.id lines up with input order / embedding row order.
        cur.execute("SELECT id, doc_id, section, content FROM chunks ORDER BY id")
        rows = cur.fetchall()
        assert [r[0] for r in rows] == [0, 1, 2]
        assert rows[0][1] == "doc_a.md"
        assert rows[0][2] == "Intro > Overview"
        assert rows[1][2] is None
        assert rows[2][2] == "Deployment"
        assert rows[0][3] == synthetic_corpus["chunks"][0]["page_content"]

        # Vector round-trip: exact match to source embeddings row.
        for i in range(3):
            cur.execute("SELECT vec FROM embeddings WHERE chunk_id = ?", (i,))
            blob = cur.fetchone()[0]
            assert len(blob) == 4 * 4  # 4 floats * 4 bytes, no normalisation
            arr = np.frombuffer(blob, dtype="<f4")
            np.testing.assert_array_equal(arr, synthetic_corpus["embeddings"][i])

        # FTS5 query returns the expected chunk.
        cur.execute(
            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'quick'"
        )
        fts_rows = cur.fetchall()
        assert fts_rows == [(0,)]

        cur.execute(
            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'mobile'"
        )
        fts_rows = cur.fetchall()
        assert fts_rows == [(2,)]

        # graph_edges populated with fallback-safe relation.
        cur.execute("SELECT src, rel, dst FROM graph_edges")
        edges = cur.fetchall()
        assert len(edges) == 1
        assert edges[0][1] == "ENROLLED_IN"

        # students / student_subjects rows, booleans as 0/1.
        cur.execute(
            "SELECT roll_no, name, sgpa, is_supply, seat_cancelled FROM students "
            "ORDER BY roll_no"
        )
        student_rows = cur.fetchall()
        assert student_rows[0] == ("R001", "Alice", 8.5, 0, 0)
        assert student_rows[1] == ("R002", "Bob", None, 1, 0)

        cur.execute("SELECT COUNT(*) FROM student_subjects")
        assert cur.fetchone()[0] == 2

        # meta has all seven required keys.
        cur.execute("SELECT key, value FROM meta")
        meta = dict(cur.fetchall())
        expected_keys = {
            "tenant_id",
            "built_at_utc",
            "chunk_count",
            "embedding_dim",
            "student_count",
            "subject_row_count",
            "source_commit",
        }
        assert expected_keys <= meta.keys()
        assert meta["tenant_id"] == "tenant_test"
        assert meta["chunk_count"] == "3"
        assert meta["embedding_dim"] == "4"
        assert meta["student_count"] == "2"
        assert meta["subject_row_count"] == "2"
        assert meta["source_commit"] == "deadbee"
    finally:
        conn.close()


def test_build_bundle_mismatched_rows_raises(tmp_path, synthetic_corpus):
    bad_embeddings_path = tmp_path / "bad_embeddings.npy"
    np.save(bad_embeddings_path, np.zeros((2, 4), dtype=np.float32))

    with pytest.raises(ValueError):
        build_bundle(
            tenant_id="tenant_test",
            chunks_json_path=synthetic_corpus["chunks_json_path"],
            embeddings_npy_path=bad_embeddings_path,
            tabular_duckdb_path=synthetic_corpus["tabular_duckdb_path"],
            graphml_path=synthetic_corpus["graphml_path"],
            out_path=tmp_path / "out2" / "brain.db",
        )
