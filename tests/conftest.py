"""
Shared pytest fixtures + a service-availability probe so live-service tests can
skip cleanly (honest skip, not a false failure) when Ollama / the API are down.
"""
import socket
import sys
from pathlib import Path
from types import SimpleNamespace

import duckdb
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import config as _config


def _port_open(host: str, port: int, timeout: float = 0.3) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def service_up(host: str, port: int) -> bool:
    return _port_open(host, port)


# Convenience skip markers importable via `from conftest import requires_api`
requires_api = pytest.mark.skipif(
    not _port_open("127.0.0.1", 8000), reason="API server not running on :8000"
)
requires_ollama = pytest.mark.skipif(
    not _port_open("127.0.0.1", 11434), reason="Ollama not running on :11434"
)


@pytest.fixture
def project_root() -> Path:
    return _config.PROJECT_ROOT


@pytest.fixture
def data_root() -> Path:
    return _config.DATA_ROOT


@pytest.fixture
def seeded_tenant(tmp_path):
    """
    Builds a throwaway tenant ("ttest") under tmp_path with a tabular.duckdb
    containing the students / student_subjects / needs_review tables and a
    handful of SYNTHETIC rows (fake roll numbers, fake names). Lets tests that
    would otherwise depend on real tenant data (data/tenants/, gitignored and
    absent in CI) run hermetically instead.

    Column names/types mirror the real schema exactly, as read from
    adapters/result_pdf_adapter.py and retrieval/tabular_queries.py:
      students:         roll_no, name, sgpa, estimated_sgpa, total_marks,
                         result, is_supply, seat_cancelled
      student_subjects: roll_no, subject_code, credit, grade, grade_point,
                         raw_grade_string
      needs_review:     roll_no, name, flags, gap, derived_max, raw_block

    Returns a SimpleNamespace with:
      tenant_id  -> "ttest"
      data_root  -> tmp_path / "tenants"   (parent dir holding <tenant_id>/)
      tenant_dir -> tmp_path / "tenants" / "ttest"
      db_path    -> .../ttest/tabular.duckdb
      roll_nos   -> list of seeded roll numbers (all PASS, with subjects)
    """
    tenant_id = "ttest"
    data_root = tmp_path / "tenants"
    tenant_dir = data_root / tenant_id
    tenant_dir.mkdir(parents=True, exist_ok=True)
    db_path = tenant_dir / "tabular.duckdb"

    roll_nos = ["9990000000001", "9990000000002", "9990000000003"]

    con = duckdb.connect(str(db_path))
    try:
        con.execute("""
            CREATE TABLE students (
                roll_no VARCHAR PRIMARY KEY,
                name VARCHAR,
                sgpa DOUBLE,
                estimated_sgpa DOUBLE,
                total_marks INTEGER,
                result VARCHAR,
                is_supply BOOLEAN,
                seat_cancelled BOOLEAN
            )
        """)
        con.execute("""
            CREATE TABLE student_subjects (
                roll_no VARCHAR,
                subject_code VARCHAR,
                credit INTEGER,
                grade VARCHAR,
                grade_point DOUBLE,
                raw_grade_string VARCHAR,
                PRIMARY KEY (roll_no, subject_code)
            )
        """)
        con.execute("""
            CREATE TABLE needs_review (
                roll_no VARCHAR PRIMARY KEY,
                name VARCHAR,
                flags VARCHAR,
                gap INTEGER,
                derived_max INTEGER,
                raw_block VARCHAR
            )
        """)

        con.executemany(
            "INSERT INTO students VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            [
                (roll_nos[0], "SYNTH ALPHA TEST", 8.5, 8.5, 850, "PASS", False, False),
                (roll_nos[1], "SYNTH BETA TEST", 6.2, 6.2, 620, "PASS", False, False),
                (roll_nos[2], "SYNTH GAMMA TEST", 4.1, 4.1, 410, "FAIL", False, False),
            ],
        )
        con.executemany(
            "INSERT INTO student_subjects VALUES (?, ?, ?, ?, ?, ?)",
            [
                (roll_nos[0], "SYN101", 4, "O", 10.0, "O"),
                (roll_nos[0], "SYN102", 3, "A", 9.0, "A"),
                (roll_nos[1], "SYN101", 4, "B", 7.0, "B"),
                (roll_nos[1], "SYN102", 3, "C", 6.0, "C"),
                (roll_nos[2], "SYN101", 4, "F", 0.0, "F"),
            ],
        )
        con.executemany(
            "INSERT INTO needs_review VALUES (?, ?, ?, ?, ?, ?)",
            [
                (roll_nos[2], "SYNTH GAMMA TEST", "low_sgpa", 1, 10, "raw"),
            ],
        )
    finally:
        con.close()

    return SimpleNamespace(
        tenant_id=tenant_id,
        data_root=data_root,
        tenant_dir=tenant_dir,
        db_path=db_path,
        roll_nos=roll_nos,
    )
