"""
Parameterized SQL templates for the top analytical queries (P3.12).

These run BEFORE any LLM SQL generation: deterministic, fast, and available
even when Ollama is down. All queries are parameterized SELECTs against the
read-only analytics.duckdb `exam_results` table (built by
ingestion/build_exam_results.py). No string interpolation of user input.

match_template(query) -> (fn, kwargs) | None  lets the router try a template
first and fall back to LLM text-to-SQL only for unmatched patterns.
"""
import re
import sys
from pathlib import Path

import duckdb

sys.path.append(str(Path(__file__).resolve().parent.parent))
from config import tenant_dir, DEFAULT_TENANT_ID


def _analytics_path(tenant_id: str) -> Path:
    return tenant_dir(tenant_id) / "analytics.duckdb"


def get_analytics_connection(tenant_id: str = None):
    """Read-only connection to analytics.duckdb; builds it once if missing."""
    tenant_id = tenant_id or DEFAULT_TENANT_ID
    path = _analytics_path(tenant_id)
    if not path.exists():
        from ingestion.build_exam_results import build_exam_results
        build_exam_results(tenant_id)
    return duckdb.connect(str(path), read_only=True)


# Bounded result cache (P4.14). Key includes analytics.duckdb mtime so a rebuild
# transparently invalidates stale entries. Data is static between ingests.
_SQL_CACHE = {}
_SQL_CACHE_MAX = 256


def clear_sql_cache():
    _SQL_CACHE.clear()


def _rows(tenant_id, sql, params=()):
    tid = tenant_id or DEFAULT_TENANT_ID
    path = _analytics_path(tid)
    try:
        mtime = path.stat().st_mtime
    except OSError:
        mtime = None
    key = (tid, mtime, sql, tuple(params))
    cached = _SQL_CACHE.get(key)
    if cached is not None:
        return cached

    con = get_analytics_connection(tid)
    try:
        cur = con.execute(sql, params)
        cols = [d[0] for d in con.description]
        result = (cur.fetchall(), cols)
    finally:
        con.close()

    if len(_SQL_CACHE) >= _SQL_CACHE_MAX:
        _SQL_CACHE.pop(next(iter(_SQL_CACHE)))
    _SQL_CACHE[key] = result
    return result



# --------------------------------------------------------------------------
# Student-identity rendering  (Phase-B T2.3)
# --------------------------------------------------------------------------

def _student_label(name, roll, redact: bool) -> str:
    """How one student is named in a multi-row answer.

    These roster templates are the system's largest PII surface: they emit every
    matching student's full name and roll number, and api/main.py returns TABULAR
    context verbatim, so whatever is formatted here is what the user reads.

    `redact=False` reproduces the original f-string byte-for-byte — that is the shipped
    default and tests/test_pii_role_gate.py asserts it, because the TABULAR invariant
    (21/22 on tenant_1) is measured against these exact strings. Redaction is applied
    HERE, at the point of rendering, rather than as a regex over a finished answer:
    a post-hoc regex over TABULAR output would be guessing at which digits are roll
    numbers and which are marks, and that is how an invariant gets broken quietly.
    """
    if not redact:
        return f"{name or 'Unknown'} (Roll: {roll})"
    # Keep the row (the count is the answer) but drop the identity. The roll number is
    # replaced rather than truncated: a partial roll is still an identifier.
    return "[student identity withheld]"


# --------------------------------------------------------------------------
# Templates  (each returns {"answer", "debug_sql", "template"})
# --------------------------------------------------------------------------

def students_failed_at_least(n: int, tenant_id: str = None, redact: bool = False) -> dict:
    sql = (
        "SELECT roll_no, name, COUNT(DISTINCT subject_code) AS failed_subjects "
        "FROM exam_results WHERE is_fail "
        "GROUP BY roll_no, name "
        "HAVING COUNT(DISTINCT subject_code) >= ? "
        "ORDER BY failed_subjects DESC, roll_no"
    )
    rows, _ = _rows(tenant_id, sql, (n,))
    if not rows:
        return {"answer": f"No students failed at least {n} subjects.",
                "debug_sql": sql, "template": "students_failed_at_least"}
    lines = [f"Found {len(rows)} students who failed at least {n} subjects:"]
    for roll, name, fails in rows:
        lines.append(f"- {_student_label(name, roll, redact)}: {fails} subjects")
    return {"answer": "\n".join(lines), "debug_sql": sql, "template": "students_failed_at_least"}


def students_failed_most(limit: int = 10, tenant_id: str = None, redact: bool = False) -> dict:
    sql = (
        "SELECT roll_no, name, COUNT(DISTINCT subject_code) AS failed_subjects "
        "FROM exam_results WHERE is_fail "
        "GROUP BY roll_no, name "
        "ORDER BY failed_subjects DESC, roll_no "
        "LIMIT ?"
    )
    rows, _ = _rows(tenant_id, sql, (limit,))
    if not rows:
        return {"answer": "No failing students found.", "debug_sql": sql,
                "template": "students_failed_most"}
    top = rows[0][2]
    lines = [f"Students who failed the most subjects (max = {top}):"]
    for roll, name, fails in rows:
        lines.append(f"- {_student_label(name, roll, redact)}: {fails} subjects")
    return {"answer": "\n".join(lines), "debug_sql": sql, "template": "students_failed_most"}


def pass_percentage(tenant_id: str = None) -> dict:
    sql = (
        "SELECT "
        "  100.0 * COUNT(*) FILTER (WHERE result = 'PASS') / NULLIF(COUNT(*), 0) AS pass_pct, "
        "  COUNT(*) FILTER (WHERE result = 'PASS') AS passed, "
        "  COUNT(*) AS total "
        "FROM (SELECT DISTINCT roll_no, result FROM exam_results)"
    )
    rows, _ = _rows(tenant_id, sql)
    pct, passed, total = rows[0]
    if pct is None:
        return {"answer": "No result data available.", "debug_sql": sql, "template": "pass_percentage"}
    return {"answer": f"Pass percentage: {pct:.1f}% ({passed} of {total} students passed).",
            "debug_sql": sql, "template": "pass_percentage"}


def fail_percentage(tenant_id: str = None) -> dict:
    """Share of students whose overall result is FAIL.

    Deliberately a template and not LLM text-to-SQL. Left to the generator this
    question produced `... COUNT(*) FROM students WHERE result = 'FAIL'`, which
    filters to the failing rows BEFORE aggregating, so the denominator equals the
    numerator and the answer is always exactly 100%. The DISTINCT subquery below
    counts each student once (exam_results holds one row per subject), so the
    denominator is every student, not every exam record.
    """
    sql = (
        "SELECT "
        "  100.0 * COUNT(*) FILTER (WHERE result = 'FAIL') / NULLIF(COUNT(*), 0) AS fail_pct, "
        "  COUNT(*) FILTER (WHERE result = 'FAIL') AS failed, "
        "  COUNT(*) AS total "
        "FROM (SELECT DISTINCT roll_no, result FROM exam_results)"
    )
    rows, _ = _rows(tenant_id, sql)
    pct, failed, total = rows[0]
    if pct is None:
        return {"answer": "No result data available.", "debug_sql": sql, "template": "fail_percentage"}
    return {"answer": f"Fail percentage: {pct:.1f}% ({failed} of {total} students failed).",
            "debug_sql": sql, "template": "fail_percentage"}


def toppers_by_sgpa(limit: int = 10, tenant_id: str = None, redact: bool = False) -> dict:
    sql = (
        "SELECT DISTINCT roll_no, name, sgpa FROM exam_results "
        "WHERE sgpa IS NOT NULL ORDER BY sgpa DESC, roll_no LIMIT ?"
    )
    rows, _ = _rows(tenant_id, sql, (limit,))
    if not rows:
        return {"answer": "No SGPA data available.", "debug_sql": sql, "template": "toppers_by_sgpa"}
    lines = [f"Top {len(rows)} students by SGPA:"]
    for i, (roll, name, sgpa) in enumerate(rows, 1):
        lines.append(f"{i}. {_student_label(name, roll, redact)}: SGPA {sgpa:.2f}")
    return {"answer": "\n".join(lines), "debug_sql": sql, "template": "toppers_by_sgpa"}


def subject_failure_counts(limit: int = 50, tenant_id: str = None) -> dict:
    sql = (
        "SELECT subject_code, COUNT(*) FILTER (WHERE is_fail) AS fails "
        "FROM exam_results GROUP BY subject_code HAVING fails > 0 "
        "ORDER BY fails DESC, subject_code LIMIT ?"
    )
    rows, _ = _rows(tenant_id, sql, (limit,))
    if not rows:
        return {"answer": "No subject failures found.", "debug_sql": sql,
                "template": "subject_failure_counts"}
    lines = ["Failures per subject:"]
    for code, fails in rows:
        lines.append(f"- {code}: {fails} failures")
    return {"answer": "\n".join(lines), "debug_sql": sql, "template": "subject_failure_counts"}


def student_count(tenant_id: str = None) -> dict:
    # Counts students present in exam_results (student_subjects LEFT JOIN
    # students). Assumes every student has >=1 subject row — true for the
    # current corpus; a student with zero subjects would be undercounted.
    sql = "SELECT COUNT(DISTINCT roll_no) FROM exam_results"
    rows, _ = _rows(tenant_id, sql)
    n = rows[0][0]
    return {"answer": f"There are {n} students in the database.",
            "debug_sql": sql, "template": "student_count"}


def result_count(status: str = "PASS", tenant_id: str = None) -> dict:
    # DISTINCT (roll_no, result) so each student counts once regardless of how
    # many subject rows they have. status is code-controlled (PASS/FAIL).
    status = "FAIL" if str(status).upper().startswith("FAIL") else "PASS"
    sql = ("SELECT COUNT(*) FROM (SELECT DISTINCT roll_no, result FROM exam_results) "
           "WHERE result = ?")
    rows, _ = _rows(tenant_id, sql, (status,))
    n = rows[0][0]
    verb = "failed" if status == "FAIL" else "passed"
    return {"answer": f"{n} students {verb} their semester examination.",
            "debug_sql": sql, "template": "result_count"}


def bottom_by_sgpa(limit: int = 10, tenant_id: str = None, redact: bool = False) -> dict:
    sql = ("SELECT DISTINCT roll_no, name, sgpa FROM exam_results "
           "WHERE sgpa IS NOT NULL ORDER BY sgpa ASC, roll_no LIMIT ?")
    rows, _ = _rows(tenant_id, sql, (limit,))
    if not rows:
        return {"answer": "No SGPA data available.", "debug_sql": sql, "template": "bottom_by_sgpa"}
    lines = [f"Lowest {len(rows)} students by SGPA:"]
    for i, (roll, name, sgpa) in enumerate(rows, 1):
        lines.append(f"{i}. {_student_label(name, roll, redact)}: SGPA {sgpa:.2f}")
    return {"answer": "\n".join(lines), "debug_sql": sql, "template": "bottom_by_sgpa"}


def count_sgpa_at_least(threshold: float, tenant_id: str = None) -> dict:
    sql = ("SELECT COUNT(*) FROM (SELECT DISTINCT roll_no, sgpa FROM exam_results "
           "WHERE sgpa IS NOT NULL) WHERE sgpa >= ?")
    rows, _ = _rows(tenant_id, sql, (threshold,))
    n = rows[0][0]
    if n == 0:
        return {"answer": f"No students have an SGPA of {threshold:g} or above.",
                "debug_sql": sql, "template": "count_sgpa_at_least"}
    return {"answer": f"{n} students have an SGPA of {threshold:g} or above.",
            "debug_sql": sql, "template": "count_sgpa_at_least"}


def supplementary_count(tenant_id: str = None) -> dict:
    sql = "SELECT COUNT(DISTINCT roll_no) FROM exam_results WHERE is_supply"
    rows, _ = _rows(tenant_id, sql)
    n = rows[0][0]
    if n == 0:
        return {"answer": "No students appeared for a supplementary examination.",
                "debug_sql": sql, "template": "supplementary_count"}
    return {"answer": f"{n} students appeared for a supplementary examination.",
            "debug_sql": sql, "template": "supplementary_count"}


# --------------------------------------------------------------------------
# Matcher
# --------------------------------------------------------------------------

# Counts in these questions are written as words at least as often as digits
# ("failed two or more subjects"). Left as digits-only, every worded phrasing
# missed every template below and fell through to LLM text-to-SQL, which
# answered "how many failed two or more subjects" with the count of ALL failing
# students (35 instead of 16) — a wrong answer to a question the DB can answer
# exactly. _num() maps either spelling to an int.
_WORD_NUM = {"one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
             "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10}
_NUM_RE = r"(\d+|" + "|".join(_WORD_NUM) + r")"


def _num(tok: str) -> int:
    tok = tok.strip().lower()
    return int(tok) if tok.isdigit() else _WORD_NUM[tok]


_AT_LEAST_N = re.compile(
    r"(?:at\s*least|atleast|>=|minimum(?:\s+of)?|min)\s*" + _NUM_RE, re.I)
_N_OR_MORE = re.compile(_NUM_RE + r"\s*(?:or\s+more|\+)\s*subject", re.I)
# "more than one subject" / "more than 1 backlog" means >= 2, not >= 1.
_MORE_THAN_N = re.compile(r"(?:more\s+than|greater\s+than|over|>)\s*" + _NUM_RE, re.I)
_TOP_N = re.compile(r"top\s+(\d+)", re.I)
_BOTTOM_N = re.compile(r"(?:bottom|lowest|worst)\s+(\d+)", re.I)
# Anchor an SGPA threshold to its keyword so a semester/year number elsewhere in
# the query isn't mistaken for it (mirrors intent.py's below_sgpa handling).
_SGPA_THRESHOLD = re.compile(
    r"(?:above|greater\s+than|greater|at\s*least|or\s+more|>=|sgpa)\D{0,12}(\d+(?:\.\d+)?)", re.I)
_SUBJECT_CODE = re.compile(r"\bBT[A-Z]{2,5}\d{3}[A-Z]?\b", re.I)


def match_template(query: str):
    """Return (callable, kwargs) for a matched analytical template, else None."""
    q = query.lower()

    # queries naming specific subject codes (e.g. "compare BTCOE504A and
    # BTCOL506") are targeted comparisons, not requests for the full
    # per-subject ranking — let these fall through to LLM text-to-SQL
    # instead of being swallowed by subject_failure_counts below.
    named_subjects = _SUBJECT_CODE.findall(query)

    # if "subject" is mentioned before "fail", the question is scoped to
    # per-subject breakdown, not per-student — don't let the failed-most-
    # subjects branch below shadow subject_failure_counts.
    subject_asks_first = "subject" in q and "fail" in q and q.find("subject") < q.find("fail")

    # failed the most subjects
    if not subject_asks_first and ("fail" in q or "backlog" in q) and ("most" in q or "highest number" in q or "maximum" in q):
        return students_failed_most, {}

    # failed at least N subjects  (also "N or more subjects", "more than N")
    if "fail" in q or "backlog" in q:
        m = _AT_LEAST_N.search(q) or _N_OR_MORE.search(q)
        if m:
            return students_failed_at_least, {"n": _num(m.group(1))}
        m = _MORE_THAN_N.search(q)
        if m:
            # strictly-greater-than N is at-least N+1
            return students_failed_at_least, {"n": _num(m.group(1)) + 1}
        if "multiple" in q:
            return students_failed_at_least, {"n": 2}

    # pass / fail percentage or rate. Checked before the generic fail-count
    # branch below, which would otherwise swallow "what is the fail percentage".
    _pct = ("percent" in q or "percentage" in q or "%" in q or "rate" in q)
    if "pass" in q and _pct:
        return pass_percentage, {}
    if ("fail" in q or "failure" in q) and _pct and not named_subjects:
        return fail_percentage, {}

    # toppers / top N by sgpa
    if "topper" in q or ("top" in q and "sgpa" in q) or ("highest" in q and "sgpa" in q):
        m = _TOP_N.search(q)
        return toppers_by_sgpa, ({"limit": int(m.group(1))} if m else {})

    # per-subject failure counts
    if (not named_subjects and "subject" in q and "fail" in q
            and ("per" in q or "each" in q or "which" in q or "wise" in q or "count" in q)):
        return subject_failure_counts, {}

    # lowest / bottom students by SGPA (highest/top handled by toppers above)
    if ("lowest" in q or "bottom" in q or "worst" in q) and "sgpa" in q:
        m = _BOTTOM_N.search(q)
        return bottom_by_sgpa, ({"limit": int(m.group(1))} if m else {})

    # count of students at/above an SGPA threshold (mirrors below_sgpa)
    if ("sgpa" in q and not named_subjects and "subject" not in q and "fail" not in q
            and ("above" in q or "greater" in q or "or more" in q or "at least" in q or ">=" in q)
            and ("how many" in q or "number of" in q or "count" in q)):
        m = _SGPA_THRESHOLD.search(q)
        return count_sgpa_at_least, {"threshold": float(m.group(1)) if m else 9.0}

    # supplementary-exam student count
    if "supplement" in q and ("how many" in q or "number of" in q or "count" in q):
        return supplementary_count, {}

    # overall failed-student count (student-level result=FAIL), distinct from
    # per-subject failure counts and "failed at least N subjects" above.
    if (("how many" in q or "number of" in q or "count of" in q) and "fail" in q
            and not named_subjects and "subject" not in q
            and "at least" not in q and "atleast" not in q
            and "most" not in q and "backlog" not in q):
        return result_count, {"status": "FAIL"}

    # passed-student count — subject-scoped questions fall through to dynamic SQL
    if ("pass" in q and ("how many" in q or "number of" in q or "count of" in q)
            and not named_subjects and "subject" not in q
            and "percent" not in q and "rate" not in q and "%" not in q):
        return result_count, {"status": "PASS"}

    # total student count
    if (("how many" in q or "number of" in q or "total" in q) and "student" in q
            and not any(w in q for w in ("fail", "subject", "sgpa", "pass", "below",
                                         "above", "supplement", "review", "backlog",
                                         "topper", "most", "least"))):
        return student_count, {}

    return None
