"""Export the tenant corpus into a single SQLite file for the Android app.

Packs chunks + embeddings + graph edges + tabular student data from a tenant's
data directory into one SQLite database (mobile/assets/brain.db by default) so
the phone can answer questions with no laptop and no network.

This module is purely additive: it does not modify retrieval/, api/,
generation/, ingestion/, dashboard/, tests/, or config.py.

Usage:
    python scripts/export_mobile_bundle.py --tenant tenant_1 --out mobile/assets/brain.db
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import sqlite3
import subprocess
from pathlib import Path
from typing import Optional

import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent

SCHEMA_SQL = """
PRAGMA user_version = 2;

CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE chunks (
  id       INTEGER PRIMARY KEY,
  doc_id   TEXT NOT NULL,
  section  TEXT,
  content  TEXT NOT NULL
);

CREATE VIRTUAL TABLE chunks_fts USING fts5(
  content, doc_id, content='chunks', content_rowid='id', tokenize='porter unicode61'
);

CREATE TABLE embeddings (
  chunk_id INTEGER PRIMARY KEY REFERENCES chunks(id),
  vec      BLOB NOT NULL
);

CREATE TABLE graph_edges (
  id  INTEGER PRIMARY KEY,
  src TEXT NOT NULL,
  rel TEXT NOT NULL,
  dst TEXT NOT NULL
);
CREATE INDEX idx_edges_src ON graph_edges(src);
CREATE INDEX idx_edges_dst ON graph_edges(dst);

CREATE TABLE students (
  roll_no TEXT PRIMARY KEY,
  name TEXT,
  sgpa REAL,
  estimated_sgpa REAL,
  total_marks INTEGER,
  -- NOT NULL is load-bearing, not decoration. The phone computes pass% and
  -- fail% as two COUNT(*) FILTER clauses over one shared COUNT(*) denominator.
  -- Under SQL three-valued logic a NULL result is excluded from BOTH filters
  -- while still counting in the denominator, so the two percentages would
  -- silently stop summing to 100 -- the same shape as the bug where filtering
  -- before aggregating made fail% always exactly 100. Fail at export instead.
  result TEXT NOT NULL,
  is_supply INTEGER,
  seat_cancelled INTEGER
);
CREATE INDEX idx_students_name ON students(name);

CREATE TABLE student_subjects (
  roll_no TEXT NOT NULL,
  subject_code TEXT NOT NULL,
  credit INTEGER,
  grade TEXT,
  grade_point REAL,
  raw_grade_string TEXT
);
CREATE INDEX idx_ss_roll ON student_subjects(roll_no);
CREATE INDEX idx_ss_subject ON student_subjects(subject_code);
-- One row per source document. Without it the app can only offer
-- SELECT DISTINCT doc_id FROM chunks, i.e. a list of raw filenames like
-- "svc_02_scholarship_procedure_post-matric_scholarship_for_sc_st_students.pdf"
-- with no title, category, ordering or preview. Readers of older bundles must
-- still cope with this table being absent (user_version 1).
CREATE TABLE documents (
  doc_id         TEXT PRIMARY KEY,
  title          TEXT NOT NULL,
  category       TEXT,
  chunk_count    INTEGER NOT NULL,
  first_chunk_id INTEGER,
  preview        TEXT
);
CREATE INDEX idx_documents_category ON documents(category);
"""


def _section_for(metadata: dict) -> Optional[str]:
    """Join heading keys ("Header 1", "Header 2", ...) in numeric order."""
    header_keys = [k for k in metadata if k.startswith("Header")]

    def _header_sort_key(k: str):
        parts = k.split()
        if len(parts) == 2 and parts[1].isdigit():
            return int(parts[1])
        return k

    header_keys.sort(key=_header_sort_key)
    values = [str(metadata[k]) for k in header_keys if metadata.get(k)]
    if not values:
        return None
    return " > ".join(values)


_DOC_CATEGORIES = (
    ("timetable", "Timetable"),
    ("exam", "Exams"),
    ("calendar", "Academic Calendar"),
    ("holiday", "Academic Calendar"),
    ("attendance", "Attendance"),
    ("scholarship", "Scholarships"),
    ("placement", "Placements"),
    ("training", "Placements"),
    ("library", "Library"),
    ("grievance", "Grievance"),
    ("ragging", "Grievance"),
    ("spoc", "Directory"),
    ("handbook", "Handbook"),
    ("notice", "Notices"),
    ("fee", "Notices"),
    ("hostel", "Notices"),
    ("bus", "Notices"),
    ("convocation", "Events"),
    ("fest", "Events"),
    ("event", "Events"),
    ("sports", "Events"),
    ("alumni", "Events"),
    ("lecture", "Events"),
    ("visit", "Events"),
    ("nss", "Events"),
    ("incubation", "Services"),
)


def _title_for(doc_id: str) -> str:
    """Human-readable title from a corpus filename.

    Strips the numeric ordering prefix the renderers emit ("24_", "svc_02_")
    because it is a sort key, not part of the document's name.
    """
    stem = Path(doc_id).stem
    stem = re.sub(r"^(svc_)?\d+[_\-]", "", stem)
    stem = stem.replace("_", " ").replace("-", " ").strip()
    return stem.title() if stem else Path(doc_id).stem


def _category_for(doc_id: str) -> str:
    name = Path(doc_id).name.lower()
    for key, cat in _DOC_CATEGORIES:
        if key in name:
            return cat
    return "Other"


def _get_source_commit() -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            check=True,
        )
        return out.stdout.strip()
    except Exception:
        return "unknown"


def _bool_to_int(v) -> Optional[int]:
    if v is None:
        return None
    return 1 if bool(v) else 0


def build_bundle(
    tenant_id: str,
    chunks_json_path: Path,
    embeddings_npy_path: Path,
    tabular_duckdb_path: Optional[Path],
    graphml_path: Optional[Path],
    out_path: Path,
    source_commit: Optional[str] = None,
) -> dict:
    """Build the mobile SQLite bundle. Returns a dict of row counts / stats.

    All paths are explicit so this function is testable against synthetic
    fixtures with no dependency on the real corpus.
    """
    out_path = Path(out_path)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        out_path.unlink()

    with open(chunks_json_path, "r", encoding="utf-8") as f:
        chunks = json.load(f)

    embeddings = np.load(embeddings_npy_path)
    if embeddings.shape[0] != len(chunks):
        raise ValueError(
            f"embeddings rows ({embeddings.shape[0]}) != chunk count ({len(chunks)})"
        )
    embedding_dim = int(embeddings.shape[1])

    conn = sqlite3.connect(str(out_path))
    try:
        conn.executescript(SCHEMA_SQL)

        # --- chunks + embeddings ---
        chunk_rows = []
        for idx, chunk in enumerate(chunks):
            metadata = chunk.get("metadata", {}) or {}
            doc_id = metadata.get("source", "")
            section = _section_for(metadata)
            content = chunk.get("page_content", "")
            chunk_rows.append((idx, doc_id, section, content))
        conn.executemany(
            "INSERT INTO chunks (id, doc_id, section, content) VALUES (?, ?, ?, ?)",
            chunk_rows,
        )

        embed_rows = []
        for idx in range(embeddings.shape[0]):
            vec_bytes = embeddings[idx].astype("<f4").tobytes()
            embed_rows.append((idx, vec_bytes))
        conn.executemany(
            "INSERT INTO embeddings (chunk_id, vec) VALUES (?, ?)", embed_rows
        )

        # Populate FTS index from the base table.
        conn.execute("INSERT INTO chunks_fts(chunks_fts) VALUES ('rebuild')")

        # --- documents ---
        # Derived from chunks, so it cannot disagree with what is actually
        # retrievable: a document exists here only if it has chunks.
        doc_stats = conn.execute(
            "SELECT doc_id, COUNT(*), MIN(id) FROM chunks GROUP BY doc_id ORDER BY doc_id"
        ).fetchall()

        # Every rendered document opens with the same institute letterhead, so
        # a naive "first 240 characters" preview is the identical boilerplate on
        # all 58 rows -- a list where every subtitle is the same tells the reader
        # nothing. Rather than hard-code the letterhead, find the lines that
        # recur across most documents and drop them: that keeps working if the
        # corpus is re-rendered with different header text.
        first_bodies = {}
        for doc_id, _n, first_id in doc_stats:
            first_bodies[doc_id] = conn.execute(
                "SELECT content FROM chunks WHERE id = ?", (first_id,)
            ).fetchone()[0] or ""

        line_doc_count = {}
        for body in first_bodies.values():
            for line in {ln.strip() for ln in body.splitlines() if len(ln.strip()) > 15}:
                line_doc_count[line] = line_doc_count.get(line, 0) + 1
        threshold = max(2, int(0.30 * len(first_bodies)))
        boilerplate = {ln for ln, c in line_doc_count.items() if c >= threshold}

        doc_rows = []
        for doc_id, n_chunks, first_id in doc_stats:
            kept = [
                ln.strip() for ln in first_bodies[doc_id].splitlines()
                if ln.strip() and ln.strip() not in boilerplate
            ]
            preview = " ".join(kept)[:240] or first_bodies[doc_id][:240]
            doc_rows.append(
                (doc_id, _title_for(doc_id), _category_for(doc_id), n_chunks, first_id, preview)
            )
        conn.executemany(
            "INSERT INTO documents (doc_id, title, category, chunk_count, first_chunk_id, preview) "
            "VALUES (?, ?, ?, ?, ?, ?)",
            doc_rows,
        )

        # --- graph edges ---
        edge_count = 0
        if graphml_path is not None and Path(graphml_path).exists():
            import networkx as nx

            graph = nx.read_graphml(str(graphml_path))
            edge_rows = []
            for src, dst, data in graph.edges(data=True):
                rel = data.get("relation") or data.get("relationship") or data.get(
                    "label"
                ) or "RELATED_TO"
                edge_rows.append((str(src), str(rel), str(dst)))
            conn.executemany(
                "INSERT INTO graph_edges (src, rel, dst) VALUES (?, ?, ?)", edge_rows
            )
            edge_count = len(edge_rows)

        # --- tabular: students + student_subjects ---
        student_count = 0
        subject_row_count = 0
        if tabular_duckdb_path is not None and Path(tabular_duckdb_path).exists():
            import duckdb

            dcon = duckdb.connect(str(tabular_duckdb_path), read_only=True)
            try:
                students = dcon.execute(
                    "SELECT roll_no, name, sgpa, estimated_sgpa, total_marks, "
                    "result, is_supply, seat_cancelled FROM students"
                ).fetchall()
                student_rows = [
                    (
                        r[0],
                        r[1],
                        r[2],
                        r[3],
                        r[4],
                        r[5],
                        _bool_to_int(r[6]),
                        _bool_to_int(r[7]),
                    )
                    for r in students
                ]
                conn.executemany(
                    "INSERT INTO students (roll_no, name, sgpa, estimated_sgpa, "
                    "total_marks, result, is_supply, seat_cancelled) "
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    student_rows,
                )
                student_count = len(student_rows)

                subjects = dcon.execute(
                    "SELECT roll_no, subject_code, credit, grade, grade_point, "
                    "raw_grade_string FROM student_subjects"
                ).fetchall()
                conn.executemany(
                    "INSERT INTO student_subjects (roll_no, subject_code, credit, "
                    "grade, grade_point, raw_grade_string) VALUES (?, ?, ?, ?, ?, ?)",
                    subjects,
                )
                subject_row_count = len(subjects)
            finally:
                dcon.close()

        # --- meta ---
        built_at_utc = datetime.datetime.now(datetime.timezone.utc).isoformat()
        commit = source_commit if source_commit is not None else _get_source_commit()
        meta_rows = [
            ("tenant_id", tenant_id),
            ("built_at_utc", built_at_utc),
            ("chunk_count", str(len(chunks))),
            ("embedding_dim", str(embedding_dim)),
            ("student_count", str(student_count)),
            ("subject_row_count", str(subject_row_count)),
            ("document_count", str(len(doc_rows))),
            ("source_commit", commit),
        ]
        conn.executemany("INSERT INTO meta (key, value) VALUES (?, ?)", meta_rows)

        conn.commit()
        conn.execute("VACUUM")
        conn.commit()
    finally:
        conn.close()

    return {
        "chunk_count": len(chunks),
        "embedding_dim": embedding_dim,
        "edge_count": edge_count,
        "student_count": student_count,
        "subject_row_count": subject_row_count,
        "out_path": str(out_path),
        "file_size_bytes": out_path.stat().st_size,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tenant", default="tenant_1")
    parser.add_argument("--out", default=str(PROJECT_ROOT / "mobile" / "assets" / "brain.db"))
    args = parser.parse_args()

    tenant_dir = PROJECT_ROOT / "data" / "tenants" / args.tenant
    chunks_json_path = tenant_dir / "embeddings" / "embeddings_chunks.json"
    embeddings_npy_path = tenant_dir / "embeddings" / "embeddings.npy"
    tabular_duckdb_path = tenant_dir / "tabular.duckdb"
    graphml_path = tenant_dir / "graph" / "company_brain.graphml"

    out_path = Path(args.out)
    if not out_path.is_absolute():
        out_path = PROJECT_ROOT / out_path

    stats = build_bundle(
        tenant_id=args.tenant,
        chunks_json_path=chunks_json_path,
        embeddings_npy_path=embeddings_npy_path,
        tabular_duckdb_path=tabular_duckdb_path,
        graphml_path=graphml_path,
        out_path=out_path,
    )

    size_mb = stats["file_size_bytes"] / (1024 * 1024)
    print("Mobile bundle export complete:")
    print(f"  tenant:            {args.tenant}")
    print(f"  out:               {stats['out_path']}")
    print(f"  chunks:            {stats['chunk_count']}")
    print(f"  embedding_dim:     {stats['embedding_dim']}")
    print(f"  graph_edges:       {stats['edge_count']}")
    print(f"  students:          {stats['student_count']}")
    print(f"  student_subjects:  {stats['subject_row_count']}")
    print(f"  file size:         {stats['file_size_bytes']} bytes ({size_mb:.2f} MB)")


if __name__ == "__main__":
    main()
