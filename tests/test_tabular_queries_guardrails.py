"""
Pytest coverage for retrieval/tabular_queries.py's SQL guardrail logic
(_check_table_allowlist, _sanitize_sql, generate_and_run_sql's rejection path)
and its student name fuzzy-matching/disambiguation logic
(fuzzy_find_student, get_student_by_name).

No live services required: the LLM (Ollama) and DuckDB connection are
monkeypatched/mocked throughout, following the pattern already established
in tests/test_sql_route.py's `router` fixture. Uses a small synthetic
in-memory `students` table — never touches real tenant/PII data.
"""
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import duckdb
import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))

from retrieval import tabular_queries as tq


# ---------------------------------------------------------------------------
# _check_table_allowlist
# ---------------------------------------------------------------------------

def test_allowlist_accepts_students():
    assert tq._check_table_allowlist("SELECT * FROM students") is None


def test_allowlist_accepts_student_subjects_join():
    sql = (
        "SELECT s.roll_no FROM students s "
        "JOIN student_subjects ss ON s.roll_no = ss.roll_no"
    )
    assert tq._check_table_allowlist(sql) is None


def test_allowlist_rejects_other_bareword_table():
    reason = tq._check_table_allowlist("SELECT * FROM secret_table")
    assert reason is not None
    assert "secret_table" in reason


def test_allowlist_rejects_bare_quoted_path_single_quote():
    # The real bug: FROM '<path>' is a DuckDB file-scan literal, invisible to
    # a bareword-identifier regex. Must be rejected.
    reason = tq._check_table_allowlist("SELECT * FROM 'C:/Windows/win.ini'")
    assert reason is not None
    assert "file/path literal" in reason


def test_allowlist_rejects_double_quoted_identifier():
    # Documented trade-off: FROM "students" is valid standard-SQL quoted-
    # identifier syntax, but the LLM prompt never asks for it, so it is
    # rejected along with the file-literal form rather than allowlisted.
    reason = tq._check_table_allowlist('SELECT * FROM "students"')
    assert reason is not None


def test_allowlist_rejects_read_csv_function_form():
    reason = tq._check_table_allowlist("SELECT * FROM read_csv('x.csv')")
    assert reason is not None
    assert "read_csv" in reason


def test_allowlist_rejects_read_csv_auto_function_form():
    reason = tq._check_table_allowlist("SELECT * FROM read_csv_auto('x.csv')")
    assert reason is not None
    assert "read_csv_auto" in reason


def test_allowlist_rejects_union_file_read_bypass():
    sql = "SELECT * FROM students UNION SELECT * FROM 'C:/secret.csv'"
    reason = tq._check_table_allowlist(sql)
    assert reason is not None


def test_allowlist_rejects_subquery_file_read_bypass():
    sql = (
        "SELECT * FROM students WHERE roll_no IN "
        "(SELECT roll_no FROM 'C:/secret.csv')"
    )
    reason = tq._check_table_allowlist(sql)
    assert reason is not None


# ---------------------------------------------------------------------------
# _sanitize_sql
# ---------------------------------------------------------------------------

def test_sanitize_strips_markdown_fences():
    sql, rejection = tq._sanitize_sql("```sql\nSELECT * FROM students\n```")
    assert rejection is None
    assert sql.startswith("SELECT")
    assert "```" not in sql


def test_sanitize_rejects_non_select():
    sql, rejection = tq._sanitize_sql("DROP TABLE students")
    assert rejection is not None
    assert "does not start with SELECT" in rejection


def test_sanitize_rejects_multi_statement():
    # e.g. the ATTACH DATABASE probe pattern — a second statement after ';'
    sql, rejection = tq._sanitize_sql(
        "SELECT * FROM students; ATTACH DATABASE 'x.db' AS x"
    )
    assert rejection is not None
    assert "multi-statement" in rejection


def test_sanitize_adds_limit_when_missing():
    sql, rejection = tq._sanitize_sql("SELECT * FROM students")
    assert rejection is None
    assert "LIMIT" in sql.upper()


def test_sanitize_leaves_existing_limit_alone():
    sql, rejection = tq._sanitize_sql("SELECT * FROM students LIMIT 5")
    assert rejection is None
    assert sql.count("LIMIT") == 1 or sql.upper().count("LIMIT") == 1


def test_sanitize_rejects_file_read_bypass_end_to_end():
    sql, rejection = tq._sanitize_sql("SELECT * FROM 'C:/Windows/win.ini'")
    assert rejection is not None
    assert "file/path literal" in rejection


def test_sanitize_rejects_union_bypass_end_to_end():
    sql, rejection = tq._sanitize_sql(
        "SELECT * FROM students UNION SELECT * FROM 'C:/secret.csv'"
    )
    assert rejection is not None


# ---------------------------------------------------------------------------
# fuzzy_find_student (pure, synchronous)
# ---------------------------------------------------------------------------

_MOCK_DB = [
    ("R001", "GAIKWAD ROHAN VIJAY"),
    ("R002", "GAIKWAD ROHAN VINOD"),
    ("R003", "SMITH JOHN"),
]


def test_fuzzy_find_exact_match():
    matches = tq.fuzzy_find_student("SMITH JOHN", _MOCK_DB, threshold=75)
    assert matches
    assert matches[0][0] == "R003"
    assert matches[0][2] == pytest.approx(100, abs=0.5)


def test_fuzzy_find_close_match_above_threshold():
    matches = tq.fuzzy_find_student("SMTH JON", _MOCK_DB, threshold=75)
    assert matches
    assert matches[0][0] == "R003"


def test_fuzzy_find_no_match_below_threshold():
    matches = tq.fuzzy_find_student("ZZZZZZZZZZQQQQQ", _MOCK_DB, threshold=75)
    assert matches == []


def test_fuzzy_find_ambiguous_name_multiple_candidates():
    matches = tq.fuzzy_find_student("Gaikwad Rohan", _MOCK_DB, threshold=75)
    rolls = {m[0] for m in matches}
    assert "R001" in rolls
    assert "R002" in rolls
    assert len(matches) >= 2


# ---------------------------------------------------------------------------
# get_student_by_name disambiguation branching
# ---------------------------------------------------------------------------

def _make_mock_conn():
    """In-memory DuckDB connection seeded with a small synthetic students
    table. Never touches real tenant/PII data."""
    conn = duckdb.connect(":memory:")
    conn.execute(
        "CREATE TABLE students (roll_no VARCHAR, name VARCHAR, sgpa DOUBLE, "
        "result VARCHAR, estimated_sgpa DOUBLE, total_marks INTEGER, "
        "is_supply BOOLEAN, seat_cancelled BOOLEAN)"
    )
    conn.execute(
        "CREATE TABLE student_subjects (roll_no VARCHAR, subject_code VARCHAR, "
        "grade VARCHAR, grade_point DOUBLE)"
    )
    conn.executemany(
        "INSERT INTO students VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        [
            ("R001", "GAIKWAD ROHAN VIJAY", 8.5, "PASS", 8.5, 850, False, False),
            ("R002", "GAIKWAD ROHAN VINOD", 7.9, "PASS", 7.9, 790, False, False),
            ("R003", "SMITH JOHN", 6.2, "PASS", 6.2, 620, False, False),
        ],
    )
    return conn


class _UnclosableConnProxy:
    """Wraps a DuckDB connection so calls to .close() are no-ops.

    get_student_by_name closes its connection after the exact-match branch,
    then get_student_record (via _cached_fetch) needs a fresh one — in
    production get_connection() reopens the file each time, but our
    in-memory :memory: mock has no file to reopen from, so the same handle
    must survive repeated "close" calls within a single test.
    """

    def __init__(self, real_conn):
        self._real = real_conn

    def execute(self, *args, **kwargs):
        return self._real.execute(*args, **kwargs)

    def executemany(self, *args, **kwargs):
        return self._real.executemany(*args, **kwargs)

    def close(self):
        pass  # kept open for reuse across the mocked get_connection() calls


@pytest.fixture
def mock_conn(monkeypatch):
    real_conn = _make_mock_conn()
    proxy = _UnclosableConnProxy(real_conn)
    monkeypatch.setattr(tq, "get_connection", lambda tenant_id: proxy)
    # get_student_record uses the cached-fetch path keyed on file mtime;
    # bypass it and hit our in-memory conn directly for the record lookup too.
    def fake_cached_fetch(sql, params=(), tenant_id=None):
        cur = real_conn.execute(sql, params) if params else real_conn.execute(sql)
        return cur.fetchall()
    monkeypatch.setattr(tq, "_cached_fetch", fake_cached_fetch)
    yield real_conn


@pytest.mark.asyncio
async def test_get_student_by_name_clear_winner(monkeypatch, mock_conn):
    monkeypatch.setattr(
        tq, "extract_student_identifier",
        AsyncMock(return_value={"name": "SMITH JOHN", "roll_no": None}),
    )
    result = await tq.get_student_by_name("who is smith john", "tenant_test")
    assert "SMITH JOHN" in result
    assert "R003" in result


@pytest.mark.asyncio
async def test_get_student_by_name_disambiguation_multiple_matches(monkeypatch, mock_conn):
    monkeypatch.setattr(
        tq, "extract_student_identifier",
        AsyncMock(return_value={"name": "Gaikwad Rohan", "roll_no": None}),
    )
    result = await tq.get_student_by_name("lookup gaikwad rohan", "tenant_test")
    assert "R001" in result
    assert "R002" in result
    assert "Reply with the roll number" in result


@pytest.mark.asyncio
async def test_get_student_by_name_no_match(monkeypatch, mock_conn):
    monkeypatch.setattr(
        tq, "extract_student_identifier",
        AsyncMock(return_value={"name": "ZZZZZZZZZZ QQQQQQQQQQ", "roll_no": None}),
    )
    result = await tq.get_student_by_name("lookup nobody", "tenant_test")
    assert "not found" in result.lower()


@pytest.mark.asyncio
async def test_get_student_by_name_extraction_failure(monkeypatch, mock_conn):
    monkeypatch.setattr(
        tq, "extract_student_identifier",
        AsyncMock(return_value={"name": None, "roll_no": None}),
    )
    result = await tq.get_student_by_name("???", "tenant_test")
    assert "Could not extract" in result


# ---------------------------------------------------------------------------
# generate_and_run_sql guardrail-rejection path (no DuckDB execution, no Ollama)
# ---------------------------------------------------------------------------

@pytest.mark.asyncio
async def test_generate_and_run_sql_rejects_file_read_bypass(monkeypatch):
    monkeypatch.setattr(
        tq, "_ask_llm_for_sql",
        AsyncMock(return_value="SELECT * FROM 'C:/Windows/win.ini'"),
    )
    out = await tq.generate_and_run_sql("read me a file", tenant_id="tenant_test")
    assert "rejected by guardrail" in out["answer"]
    assert out["debug_sql"] == "SELECT * FROM 'C:/Windows/win.ini'"


@pytest.mark.asyncio
async def test_generate_and_run_sql_rejects_drop_table(monkeypatch):
    monkeypatch.setattr(
        tq, "_ask_llm_for_sql",
        AsyncMock(return_value="DROP TABLE students"),
    )
    out = await tq.generate_and_run_sql("delete everything", tenant_id="tenant_test")
    assert "rejected by guardrail" in out["answer"]
    assert out["debug_sql"] == "DROP TABLE students"


@pytest.mark.asyncio
async def test_generate_and_run_sql_rejects_multi_statement(monkeypatch):
    adversarial = "SELECT * FROM students; ATTACH DATABASE 'x.db' AS x"
    monkeypatch.setattr(
        tq, "_ask_llm_for_sql",
        AsyncMock(return_value=adversarial),
    )
    out = await tq.generate_and_run_sql("multi statement attempt", tenant_id="tenant_test")
    assert "rejected by guardrail" in out["answer"]
    assert out["debug_sql"] == adversarial


@pytest.mark.asyncio
async def test_generate_and_run_sql_llm_unreachable(monkeypatch):
    monkeypatch.setattr(tq, "_ask_llm_for_sql", AsyncMock(return_value=None))
    out = await tq.generate_and_run_sql("anything", tenant_id="tenant_test")
    assert "Failed to reach Ollama" in out["answer"]
    assert out["debug_sql"] is None
