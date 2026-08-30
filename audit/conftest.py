"""
Shared pytest fixtures for the Enterprise Audit Suite.
All tests import from here via conftest auto-discovery.
"""
import sys
import uuid
import shutil
import sqlite3
import logging
import tempfile
from pathlib import Path
from datetime import datetime
from typing import Generator, AsyncGenerator

import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

# Ensure project root is on path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from api.main import app
from auth.allowlist import AllowlistManager
from ingestion.manifest import _get_manifest_conn

DATA_ROOT = PROJECT_ROOT / "data" / "tenants"
AUDIT_LOG_PATH = PROJECT_ROOT / "data" / "audit.jsonl"

logger = logging.getLogger("audit.conftest")

# ─── Audit Logger Fixture ──────────────────────────────────────────────────────

class AuditLogCapture:
    """Captures structured audit log events emitted during a test."""
    def __init__(self):
        self.events: list[dict] = []

    def emit(self, event: dict):
        event.setdefault("timestamp", datetime.utcnow().isoformat() + "Z")
        self.events.append(event)

    def find(self, **kwargs) -> list[dict]:
        return [e for e in self.events if all(e.get(k) == v for k, v in kwargs.items())]

    def assert_field(self, field: str):
        missing = [i for i, e in enumerate(self.events) if field not in e or not e[field]]
        assert not missing, f"Audit events {missing} are missing required field '{field}'"


@pytest.fixture
def audit_capture() -> AuditLogCapture:
    return AuditLogCapture()


# ─── API Client Fixture ────────────────────────────────────────────────────────

@pytest_asyncio.fixture
async def api_client() -> AsyncGenerator[AsyncClient, None]:
    """Async HTTP client wired directly to the FastAPI app (no network needed)."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://testserver") as client:
        yield client


# ─── Isolated Tenant Fixtures ──────────────────────────────────────────────────

def _create_tenant_skeleton(tenant_id: str, base: Path) -> Path:
    tenant_dir = base / tenant_id
    for sub in ["raw", "parsed", "embeddings"]:
        (tenant_dir / sub).mkdir(parents=True, exist_ok=True)
    # Manifest DB with the production schema. This fixture used to declare its
    # own four-column table, so audits ran against a manifest shape that no real
    # tenant has.
    _get_manifest_conn(tenant_dir).close()
    return tenant_dir


@pytest.fixture
def test_tenant() -> Generator[dict, None, None]:
    """
    Creates a fully isolated ephemeral test tenant, yields its info dict,
    then tears it down completely.
    """
    tenant_id = f"audit_{uuid.uuid4().hex[:8]}"
    tenant_dir = _create_tenant_skeleton(tenant_id, DATA_ROOT)
    # Register in allowlist manager (in-memory, does not persist)
    mgr = AllowlistManager()
    mgr.allowlist[tenant_id] = {
        "telegram_users": ["audit_user"],
        "whatsapp_users": [],
        "description": f"Ephemeral audit tenant {tenant_id}",
        "roles": {
            "admin": ["audit_admin"],
            "registrar": ["audit_registrar"],
            "faculty": ["audit_faculty"],
            "student": ["audit_student"],
        }
    }
    yield {
        "tenant_id": tenant_id,
        "tenant_dir": tenant_dir,
        "manifest_db": tenant_dir / "manifest.db",
    }
    # Teardown
    shutil.rmtree(tenant_dir, ignore_errors=True)
    mgr.allowlist.pop(tenant_id, None)


@pytest.fixture
def two_isolated_tenants() -> Generator[tuple[dict, dict], None, None]:
    """Creates two fully isolated tenants for cross-tenant isolation tests."""
    t1_id = f"audit_{uuid.uuid4().hex[:8]}"
    t2_id = f"audit_{uuid.uuid4().hex[:8]}"
    t1_dir = _create_tenant_skeleton(t1_id, DATA_ROOT)
    t2_dir = _create_tenant_skeleton(t2_id, DATA_ROOT)
    mgr = AllowlistManager()
    for tid in [t1_id, t2_id]:
        mgr.allowlist[tid] = {"telegram_users": ["audit_user"], "whatsapp_users": []}
    yield (
        {"tenant_id": t1_id, "tenant_dir": t1_dir},
        {"tenant_id": t2_id, "tenant_dir": t2_dir},
    )
    shutil.rmtree(t1_dir, ignore_errors=True)
    shutil.rmtree(t2_dir, ignore_errors=True)
    mgr.allowlist.pop(t1_id, None)
    mgr.allowlist.pop(t2_id, None)


# ─── Minimal DuckDB Fixture ────────────────────────────────────────────────────

@pytest.fixture
def duckdb_tenant(test_tenant):
    """
    Adds a seeded DuckDB tabular.duckdb to the test tenant.
    Returns (tenant_info, duckdb_path).
    """
    import duckdb
    db_path = test_tenant["tenant_dir"] / "tabular.duckdb"
    con = duckdb.connect(str(db_path))
    con.execute("""
        CREATE TABLE students (
            roll_no VARCHAR PRIMARY KEY,
            name    VARCHAR,
            branch  VARCHAR,
            dob     DATE,
            sgpa    FLOAT,
            cgpa    FLOAT,
            attendance_pct FLOAT,
            has_backlog BOOLEAN,
            graduated BOOLEAN
        )
    """)
    con.execute("""
        CREATE TABLE student_subjects (
            roll_no      VARCHAR,
            subject_code VARCHAR,
            credits      INTEGER,
            marks        FLOAT,
            grade        VARCHAR
        )
    """)
    # Seed realistic test data
    # sgpa (5th field) equals compute_sgpa() over each student's subjects on the
    # correct DBATU scale (AA=9, AB=8.5, BB=8, BC=7.5, FF=0; AU excluded), so the
    # extraction-verification audit checks stored == recomputed for real.
    students = [
        ("2021001001", "Rahul Sharma",  "CS", "2002-05-10", 8.79, 8.2, 92.0, False, True),   # (4*9+3*8.5)/7
        ("2021001002", "Priya Patel",   "CS", "2002-08-22", 7.79, 7.0, 78.5, True,  True),    # (4*8+3*7.5)/7
        ("2021001003", "Amit Verma",    "IT", "2001-11-30", 0.0,  5.5, 61.0, True,  False),   # only FF -> 0
        ("2021001004", "Sneha Nair",    "CS", "2002-03-15", 9.0,  8.9, 95.0, False, True),    # (4*9+3*9)/7
        ("2021001005", "विक्रम सिंह",  "ME", "2002-07-04", 8.0,  6.3, 74.0, False, True),     # 4*8/4
    ]
    con.executemany("INSERT INTO students VALUES (?,?,?,?,?,?,?,?,?)", students)

    subjects = [
        ("2021001001", "BT301", 4, 85.0, "AA"),
        ("2021001001", "BT302", 3, 82.0, "AB"),
        ("2021001002", "BT301", 4, 62.0, "BB"),
        ("2021001002", "BT302", 3, 55.0, "BC"),
        ("2021001003", "BT301", 4, 39.0, "FF"),
        ("2021001004", "BT301", 4, 92.0, "AA"),
        ("2021001004", "BT302", 3, 88.0, "AA"),
        ("2021001005", "BT301", 4, 65.0, "BB"),
    ]
    con.executemany("INSERT INTO student_subjects VALUES (?,?,?,?,?)", subjects)
    con.close()
    return test_tenant, db_path


# ─── Temp Directory Fixture ────────────────────────────────────────────────────

@pytest.fixture
def tmp_dir() -> Generator[Path, None, None]:
    d = Path(tempfile.mkdtemp(prefix="audit_tmp_"))
    yield d
    shutil.rmtree(d, ignore_errors=True)
