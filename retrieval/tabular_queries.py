import asyncio
import duckdb
import sys
from pathlib import Path
import json
import re
import logging
import httpx
from rapidfuzz import process, fuzz, utils
sys.path.append(str(Path(__file__).resolve().parent.parent))

from config import tenant_dir
import config
from models.grades import FAIL_GRADES

logger = logging.getLogger(__name__)

def get_connection(tenant_id: str):
    """Read-only connection to the given tenant's tabular.duckdb.

    Raises FileNotFoundError with a clear message if the tenant has no
    tabular data yet, rather than silently falling back to another
    tenant's database.
    """
    db_path = tenant_dir(tenant_id) / "tabular.duckdb"
    if not db_path.exists():
        raise FileNotFoundError(
            f"No tabular/student data available for tenant '{tenant_id}'."
        )
    return duckdb.connect(str(db_path), read_only=True)


# Bounded result cache, mirroring retrieval/sql_templates.py's _SQL_CACHE.
# Keyed on tabular.duckdb's mtime so an ingestion rewrite transparently
# invalidates stale entries. Deliberately NOT a pooled/persistent connection:
# ingestion/parse_tabular.py opens a non-read-only connection to this same
# file out-of-band, so holding a long-lived read handle open risks lock
# contention/staleness. This caches query *results* only.
_TABULAR_CACHE = {}
_TABULAR_CACHE_MAX = 256


def clear_tabular_cache():
    _TABULAR_CACHE.clear()


def _cached_fetch(sql, params=(), tenant_id: str = None):
    """Executes sql against tabular.duckdb, caching results by (tenant, mtime, sql, params).

    Raises FileNotFoundError (via get_connection) if the tenant has no tabular data.
    """
    db_path = tenant_dir(tenant_id) / "tabular.duckdb"
    try:
        mtime = db_path.stat().st_mtime
    except OSError:
        mtime = None
    key = (tenant_id, mtime, sql, tuple(params))
    cached = _TABULAR_CACHE.get(key)
    if cached is not None:
        return cached

    conn = get_connection(tenant_id)
    try:
        cur = conn.execute(sql, params) if params else conn.execute(sql)
        rows = cur.fetchall()
    finally:
        conn.close()

    if len(_TABULAR_CACHE) >= _TABULAR_CACHE_MAX:
        _TABULAR_CACHE.pop(next(iter(_TABULAR_CACHE)))
    _TABULAR_CACHE[key] = rows
    return rows

def get_average_sgpa(subject_code=None, tenant_id: str = None):
    """
    Returns the average SGPA. If a subject_code is provided,
    returns the average SGPA of students who took that subject.
    """
    if subject_code:
        # Average SGPA of students taking this subject (they all might, but just in case)
        query = """
            SELECT AVG(s.sgpa) as avg_sgpa
            FROM students s
            JOIN student_subjects ss ON s.roll_no = ss.roll_no
            WHERE ss.subject_code = ? AND s.sgpa IS NOT NULL
        """
        try:
            rows = _cached_fetch(query, (subject_code,), tenant_id)
        except FileNotFoundError as e:
            return str(e)
    else:
        query = """
            SELECT AVG(sgpa) as avg_sgpa
            FROM students
            WHERE sgpa IS NOT NULL
        """
        try:
            rows = _cached_fetch(query, (), tenant_id)
        except FileNotFoundError as e:
            return str(e)

    res = rows[0] if rows else None
    if res and res[0] is not None:
        return f"The average SGPA is {res[0]:.2f}"
    return "Could not calculate average SGPA."

def count_failures(subject_code=None, tenant_id: str = None):
    """
    Counts the number of failed students overall or in a specific subject.
    """
    if subject_code:
        # Failing grades come from the single source of truth (models.grades).
        # Only 'FF' fails; 'AB' (8.5) is a pass and 'AU' is an audit subject.
        # FAIL_GRADES is code-controlled (no user input) — safe to inline.
        fail_sql = ", ".join(f"'{g}'" for g in FAIL_GRADES)
        query = f"""
            SELECT COUNT(*) FROM student_subjects
            WHERE subject_code = ? AND grade IN ({fail_sql})
        """
        try:
            rows = _cached_fetch(query, (subject_code,), tenant_id)
        except FileNotFoundError as e:
            return str(e)
        return f"There are {rows[0][0]} failures in subject {subject_code}."
    else:
        query = """
            SELECT COUNT(*) FROM students
            WHERE result = 'FAIL' OR result LIKE '%FAIL%'
        """
        try:
            rows = _cached_fetch(query, (), tenant_id)
        except FileNotFoundError as e:
            return str(e)
        return f"There are {rows[0][0]} total failed students."

def list_students_below_sgpa(threshold: float, tenant_id: str = None):
    """
    Lists students with an SGPA strictly below the provided threshold.
    """
    query = """
        SELECT roll_no, name, sgpa
        FROM students
        WHERE sgpa < ? AND sgpa IS NOT NULL
        ORDER BY sgpa ASC
    """
    try:
        results = _cached_fetch(query, (threshold,), tenant_id)
    except FileNotFoundError as e:
        return str(e)

    if not results:
        return f"No students found with SGPA below {threshold}."

    lines = [f"Found {len(results)} students with SGPA below {threshold}:"]
    for row in results:
        lines.append(f"- {row[1]} (Roll: {row[0]}): SGPA {row[2]:.2f}")
    return "\n".join(lines)

def get_student_record(roll_no: str, tenant_id: str = None):
    """
    Retrieves the complete record of a student given their roll number.
    """
    q_student = "SELECT name, sgpa, result, estimated_sgpa, total_marks, is_supply, seat_cancelled FROM students WHERE roll_no = ?"
    try:
        student_rows = _cached_fetch(q_student, (roll_no,), tenant_id)
    except FileNotFoundError as e:
        return str(e)
    student = student_rows[0] if student_rows else None

    if not student:
        return f"Student with roll number {roll_no} not found."

    name, sgpa, result, est_sgpa, total_marks, is_supply, seat_cancelled = student

    q_subjects = "SELECT subject_code, grade, grade_point FROM student_subjects WHERE roll_no = ?"
    try:
        subjects = _cached_fetch(q_subjects, (roll_no,), tenant_id)
    except FileNotFoundError as e:
        return str(e)

    lines = [f"🎓 **Student Record for {name}**"]
    lines.append(f"🆔 Roll No: `{roll_no}`")

    res_emoji = "✅" if result == "PASS" else ("❌" if result == "FAIL" else "⚠️")
    lines.append(f"📊 Result: {res_emoji} {result}")

    sgpa_str = f"{sgpa:.2f}" if sgpa is not None else 'N/A'
    lines.append(f"📈 SGPA: **{sgpa_str}**")

    if is_supply:
        lines.append("🔖 Type: Supplementary Exam")
    if seat_cancelled:
        lines.append("🚫 Seat Cancelled")

    if total_marks is not None:
        lines.append(f"💯 Total Marks: {total_marks}")

    lines.append("\n📚 **Subjects:**")
    for sub in subjects:
        lines.append(f"  • `{sub[0]}`: Grade **{sub[1]}** (Point: {sub[2]})")

    return "\n".join(lines)

# Words that are never part of a student NAME — lookup verbs, record nouns,
# filler and question words. Whatever survives stripping these from a query is
# treated as the name. This deterministic pass is the backstop that stops name
# resolution from depending on the LLM parsing every phrasing correctly.
_NAME_STOPWORDS = frozenset({
    "result", "results", "record", "records", "marksheet", "marksheets",
    "marks", "mark", "grade", "grades", "grading", "score", "scores",
    "sgpa", "cgpa", "gpa", "detail", "details", "report", "card",
    "of", "for", "the", "a", "an", "and", "to", "in", "on", "with",
    "show", "get", "give", "gimme", "me", "my", "tell", "about",
    "what", "whats", "is", "are", "was", "were", "did", "do", "does",
    "has", "have", "please", "student", "students", "roll", "rollno",
    "number", "no", "find", "search", "fetch", "display", "view", "print",
    "see", "want", "need", "pass", "passed", "fail", "failed", "how", "did",
})


def _roll_from_query(raw: str):
    """A standalone 10-15 digit run is a roll number. Deterministic; no LLM."""
    m = re.search(r'\b(\d{10,15})\b', raw or "")
    return m.group(1) if m else None


def _name_tokens_from_query(raw: str) -> list[str]:
    """Name tokens = alphabetic words (len>=2) left after removing lookup/filler
    words. Order-independent, so "gaikwad rohan result" and "result of rohan
    gaikwad" yield the same token set {gaikwad, rohan}."""
    words = re.findall(r"[A-Za-z]+", (raw or "").lower())
    return [w for w in words if len(w) >= 2 and w not in _NAME_STOPWORDS]


async def extract_student_identifier(raw_query: str) -> dict:
    """
    Uses the local LLM to extract student name or roll number from a natural language query.
    Returns: {"name": str|None, "roll_no": str|None}
    """
    prompt = f"""Extract the student name or roll number being asked about in this query.
Respond with ONLY valid JSON, no other text.

Query: "{raw_query}"

Format: {{"name": "extracted name or null", "roll_no": "extracted roll number or null"}}"""

    payload = {
        "model": config.OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0.0, "num_ctx": config.OLLAMA_NUM_CTX}
    }
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            res = await client.post(f"{config.OLLAMA_BASE_URL}/api/generate", json=payload)
            res.raise_for_status()
            text = res.json()["response"].strip()
            # Try to strip markdown code blocks if any
            if text.startswith("```json"):
                text = text[7:]
            if text.startswith("```"):
                text = text[3:]
            if text.endswith("```"):
                text = text[:-3]
            data = json.loads(text.strip())
            return {
                "name": data.get("name") if data.get("name") else None,
                "roll_no": data.get("roll_no") if data.get("roll_no") else None
            }
    except Exception as e:
        print(f"[DEBUG] Extraction failed: {e}")
        return {"name": None, "roll_no": None}

def fuzzy_find_student(extracted_name: str, all_db_names: list[tuple[str, str]], threshold: int = 75):
    """
    all_db_names is a list of tuples: (roll_no, name)
    Returns list of (roll_no, name, score) sorted by score descending.
    """
    names_only = [x[1] for x in all_db_names]
    matches = process.extract(extracted_name, names_only, scorer=fuzz.WRatio, processor=utils.default_process, limit=3)

    results = []
    for match_name, score, idx in matches:
        if score >= threshold:
            results.append((all_db_names[idx][0], match_name, score))
    return results

async def get_student_by_name(name_query: str, tenant_id: str = None):
    """
    Searches for a student by name using LLM extraction and rapidfuzz.
    """
    extracted = await extract_student_identifier(name_query)

    # Roll number: trust a deterministic 10-15 digit match over the LLM.
    roll_no_ext = _roll_from_query(name_query) or extracted.get("roll_no")
    if roll_no_ext:
        return get_student_record(str(roll_no_ext), tenant_id)

    # Name: deterministic token strip is PRIMARY (it keeps every name word
    # regardless of phrasing/order); the LLM's name is a fallback only when the
    # strip yields nothing. This fixes cases where the LLM dropped a token
    # (e.g. returned only "rohan" for "gaikwad rohan result").
    name_ext = extracted.get("name")
    det_tokens = _name_tokens_from_query(name_query)
    search_tokens = det_tokens or [t for t in (name_ext or "").lower().split() if len(t) >= 2]
    if not search_tokens:
        return "Could not extract a valid student name or roll number from the query."
    # Name used for display and the fuzzy fallback below.
    name_ext = name_ext or " ".join(search_tokens)

    try:
        conn = get_connection(tenant_id)
    except FileNotFoundError as e:
        return str(e)

    # All DB access is wrapped so the connection is always closed, even if a
    # conn.execute() raises mid-query (otherwise the handle leaks on error).
    try:
        # 1. Try robust tokenized search over the deterministic name tokens
        tokens = [t for t in search_tokens if len(t) > 2]
        if tokens:
            query_parts = []
            params = []
            for token in tokens:
                query_parts.append("LOWER(name) LIKE ?")
                params.append(f"%{token}%")
            where_clause = " AND ".join(query_parts)
            sql = f"SELECT roll_no, name FROM students WHERE {where_clause} LIMIT 10"
            exact_matches = conn.execute(sql, params).fetchall()

            if len(exact_matches) == 1:
                return get_student_record(exact_matches[0][0], tenant_id)
            elif len(exact_matches) > 1 and len(exact_matches) <= 3:
                # Disambiguate exact matches
                lines = [f"Found {len(exact_matches)} students matching '{name_ext}':"]
                for idx, match in enumerate(exact_matches):
                    lines.append(f"{idx+1}. {match[1]} (Roll: {match[0]})")
                lines.append("Reply with the roll number to confirm.")
                return "\n".join(lines)

        # Fallback to rapidfuzz. Bounded with a generous cap so an ambiguous
        # name search can't pull an unbounded, ever-growing full-table read.
        _FUZZY_FALLBACK_LIMIT = 5000
        all_students = conn.execute(
            "SELECT roll_no, name FROM students LIMIT ?", (_FUZZY_FALLBACK_LIMIT,)
        ).fetchall()
    finally:
        conn.close()
    if len(all_students) >= _FUZZY_FALLBACK_LIMIT:
        logger.warning(
            "get_student_by_name: fuzzy fallback hit the %d-row cap for tenant '%s'; "
            "results may be truncated as the roster grows.",
            _FUZZY_FALLBACK_LIMIT, tenant_id,
        )

    matches = fuzzy_find_student(name_ext, all_students, threshold=75)

    if not matches:
        return f"Student matching '{name_ext}' not found (highest match score was below threshold). Extracted as: {extracted}"

    # Check if we have a clear winner
    # A clear winner is either the only match above 75, or the top match is >90 and beats the runner-up by > 5 points
    is_clear_winner = False
    if len(matches) == 1 and matches[0][2] >= 75:
        is_clear_winner = True
    elif len(matches) > 1 and matches[0][2] >= 90 and (matches[0][2] - matches[1][2] > 5):
        is_clear_winner = True

    if is_clear_winner:
        return get_student_record(matches[0][0], tenant_id)

    # Disambiguation
    lines = [f"Did you mean one of these? (Extracted: '{name_ext}')"]
    for idx, match in enumerate(matches):
        lines.append(f"{idx+1}. {match[1]} (Roll: {match[0]}) - Match Score: {match[2]:.1f}")
    lines.append("Reply with the roll number to confirm.")
    return "\n".join(lines)

# ---------------------------------------------------------------------------
# SQL guardrail helpers
# ---------------------------------------------------------------------------

_STRIP_MD = re.compile(r'^```(?:sql)?\s*|\s*```$', re.IGNORECASE)
_MULTI_STMT = re.compile(r';\s*\S')  # semicolon followed by non-whitespace → 2nd statement
_LIMIT_MISSING = re.compile(r'\bLIMIT\b', re.IGNORECASE)
_UNBOUNDED_LIMIT = 200  # cap applied when the model omits LIMIT entirely
_QUERY_TIMEOUT_SECONDS = 10  # kill runaway scans on the 4GB-VRAM / shared-CPU box

# Only these tables/columns may appear in generated SQL — enforced deterministically,
# never by asking the LLM nicely (see researchdoneby web based claude.md, section D).
_ALLOWED_TABLES = {"students", "student_subjects"}
_TABLE_REF = re.compile(r'\b(?:FROM|JOIN)\s+([a-zA-Z_][a-zA-Z0-9_]*)', re.IGNORECASE)

# DuckDB also supports FROM/JOIN directly against a quoted string literal
# (e.g. FROM 'C:/some/file.csv' or FROM "some_ident") for its file-scanning
# table functions. That's a quoted literal, not a bareword identifier, so it
# is invisible to _TABLE_REF above — reject it outright rather than trying to
# allowlist it, since no allowlisted table ever needs to be referenced by a
# quoted literal.
_FROM_NONIDENT = re.compile(r'\b(?:FROM|JOIN)\s+[\'"]', re.IGNORECASE)


def _check_table_allowlist(sql: str) -> str | None:
    """Returns a rejection reason if any FROM/JOIN targets a non-allowed table."""
    if _FROM_NONIDENT.search(sql):
        return "Guardrail: query references a file/path literal instead of an allowlisted table."
    referenced = {m.group(1).lower() for m in _TABLE_REF.finditer(sql)}
    disallowed = referenced - _ALLOWED_TABLES
    if disallowed:
        return f"Guardrail: query references non-allowlisted table(s): {sorted(disallowed)}"
    return None


def _sanitize_sql(raw: str) -> tuple[str, str | None]:
    """
    Strip markdown fences, enforce a single SELECT statement over allowlisted tables.
    Returns (cleaned_sql, rejection_reason).  rejection_reason is None on success.
    """
    sql = _STRIP_MD.sub('', raw.strip()).strip()

    # Must start with SELECT
    if not sql.upper().lstrip().startswith('SELECT'):
        return sql, f"Guardrail: generated SQL does not start with SELECT (got: {sql[:80]!r})"

    # Must be a single statement (no second statement after a semicolon)
    stripped = sql.rstrip(';')
    if _MULTI_STMT.search(stripped):
        return sql, "Guardrail: multi-statement SQL rejected (only single SELECT allowed)"

    table_rejection = _check_table_allowlist(stripped)
    if table_rejection:
        return sql, table_rejection

    # Add a LIMIT cap if the model forgot one (prevents accidental full-table dumps)
    if not _LIMIT_MISSING.search(stripped):
        sql = stripped + f" LIMIT {_UNBOUNDED_LIMIT}"
        logger.info("SQL guardrail: no LIMIT found, capped at %d", _UNBOUNDED_LIMIT)

    return sql, None


def _execute_with_timeout(sql: str, tenant_id: str = None) -> tuple[list | None, list | None, str | None]:
    """Runs sql against a fresh read-only connection, interrupting it after
    _QUERY_TIMEOUT_SECONDS. Returns (rows, columns, error_message)."""
    try:
        conn = get_connection(tenant_id)
    except FileNotFoundError as e:
        return None, None, str(e)
    result_holder: dict = {}

    def _run():
        try:
            result_holder["rows"] = conn.execute(sql).fetchall()
            result_holder["columns"] = [d[0] for d in conn.description]
        except Exception as e:
            result_holder["error"] = str(e)

    import threading
    t = threading.Thread(target=_run, daemon=True)
    t.start()
    t.join(_QUERY_TIMEOUT_SECONDS)

    if t.is_alive():
        try:
            conn.interrupt()
        except Exception:
            pass
        t.join(2)
        conn.close()
        return None, None, f"Query timed out after {_QUERY_TIMEOUT_SECONDS}s"

    conn.close()
    if "error" in result_holder:
        return None, None, result_holder["error"]
    return result_holder["rows"], result_holder["columns"], None


_SQL_SCHEMA = '''
Table: students
- roll_no (VARCHAR, Primary Key)
- name (VARCHAR)
- sgpa (DOUBLE)
- estimated_sgpa (DOUBLE)
- total_marks (INTEGER)
- result (VARCHAR): e.g. "PASS", "FAIL"
- is_supply (BOOLEAN)
- seat_cancelled (BOOLEAN)

Table: student_subjects
- roll_no (VARCHAR, Foreign Key to students)
- subject_code (VARCHAR): e.g. 'BTCOC501', 'BTCOC502'.
  The first digit immediately after the letter-prefix run indicates semester (e.g. 'BTCOC501' -> letters 'BTCOC' then '5' = sem 5).
  IMPORTANT: match the semester digit ONLY at that position, not anywhere in the code (a course-number digit like '504' contains '4' but is semester 5, not 4).
  To match all sem-N subjects use: regexp_matches(subject_code, '^BT[A-Z]+N') (DuckDB regex), e.g. sem 5: regexp_matches(subject_code, '^BT[A-Z]+5')
- credit (INTEGER)
- grade (VARCHAR): 'AA','AB','BB','BC','CC','CD','DD','EE','DE','FF','XX','EX','AU'.
  Base grade points (0-10 scale): EX=10, AA=9, AB=8.5, BB=8, BC=7.5, CC=7, CD=6.5, DD=6, DE=5.5, EE=5, FF=0.
  'EX' = excellent. 'AU' = audit subject (0 points, excluded from SGPA credits, NOT a fail).
  Failing grade: 'FF' only (marks 0.00-39.99). 'AB' is a PASS (8.5), never a failure.
  CAUTION: the `grade_point` column stores base_point * credit (weighted), NOT the 0-10 base
  above (e.g. AB at 4 credits = 34.0). To filter/compare grades on the 0-10 scale, use `grade`,
  never a numeric threshold on `grade_point`.
- grade_point (DOUBLE)
- raw_grade_string (VARCHAR)
'''

_SQL_GEN_RULES = (
    "You are a DuckDB SQL expert. Write ONE SQL SELECT query to answer the question below.\n"
    "Rules:\n"
    "- Output ONLY the raw SQL, no markdown, no explanation.\n"
    "- Must be a single SELECT statement.\n"
    "- Only use the tables `students` and `student_subjects` — no other tables exist.\n"
    "- Do NOT include LIMIT unless the question asks for a specific number.\n"
)


async def _ask_llm_for_sql(prompt: str) -> str | None:
    payload = {
        "model": config.OLLAMA_MODEL,
        "prompt": prompt,
        "stream": False,
        "keep_alive": "10m",
        "options": {"temperature": 0.0, "num_ctx": config.OLLAMA_NUM_CTX},
    }
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            res = await client.post(f"{config.OLLAMA_BASE_URL}/api/generate", json=payload)
            res.raise_for_status()
            return res.json()["response"].strip()
    except Exception as e:
        logger.error("generate_and_run_sql: LLM call failed: %s", e)
        return None


async def generate_and_run_sql(raw_query: str, tenant_id: str = None) -> dict:
    """
    Uses LLM to generate DuckDB SQL for complex queries and executes it.
    On execution error, feeds the error back to the model once for a self-correction
    retry (execution-based self-correction — see researchdoneby web based claude.md, C.4).

    Returns a dict with:
      - answer (str): human-readable result or error
      - debug_sql (str | None): the exact SQL that was generated and run
        (or None / rejected SQL for auditability when guardrail fires)
    """
    prompt = (
        _SQL_GEN_RULES +
        "\nSchema:\n" + _SQL_SCHEMA +
        "\nQuestion: " + raw_query
    )

    raw_sql = await _ask_llm_for_sql(prompt)
    if raw_sql is None:
        return {"answer": "Failed to reach Ollama.", "debug_sql": None}

    sql, rejection = _sanitize_sql(raw_sql)
    if rejection:
        logger.warning("generate_and_run_sql REJECTED: %s | raw=%r", rejection, raw_sql)
        return {
            "answer": f"Query rejected by guardrail: {rejection}",
            "debug_sql": raw_sql,  # expose what the model generated for auditability
        }

    logger.info("generate_and_run_sql executing: %s", sql)
    results, columns, error = await asyncio.to_thread(_execute_with_timeout, sql, tenant_id)

    if error:
        logger.warning("generate_and_run_sql DB error, retrying once: %s | sql=%r", error, sql)
        retry_prompt = (
            _SQL_GEN_RULES +
            "\nSchema:\n" + _SQL_SCHEMA +
            "\nQuestion: " + raw_query +
            f"\n\nYour previous query failed with this error:\n{sql}\n-- error: {error}\n"
            "Fix the query and output ONLY the corrected raw SQL."
        )
        retry_raw_sql = await _ask_llm_for_sql(retry_prompt)
        if retry_raw_sql is not None:
            retry_sql, retry_rejection = _sanitize_sql(retry_raw_sql)
            if retry_rejection:
                return {
                    "answer": f"Query rejected by guardrail on retry: {retry_rejection}",
                    "debug_sql": retry_raw_sql,
                }
            logger.info("generate_and_run_sql retry executing: %s", retry_sql)
            results, columns, error = await asyncio.to_thread(_execute_with_timeout, retry_sql, tenant_id)
            sql = retry_sql

        if error:
            return {"answer": f"Error executing SQL: {error}", "debug_sql": sql}

    if not results:
        return {"answer": "Query returned no results.", "debug_sql": sql}

    # A question the schema cannot answer ("What NIRF rank did the college get?")
    # makes the model emit a literal SELECT — `SELECT NULL AS NIRF_RANK` — which
    # DuckDB runs happily and returns as ONE row of all-NULL. That is truthy, so it
    # sailed past the check above and rendered as `| NIRF_RANK | NULL |`: an invented
    # column name presented to the user in the shape of a record. Abstention is the
    # headline safety property here, and this was the hole in it.
    #
    # Genuinely-empty results (0 rows) are a real answer and keep their message
    # above; only a result whose every cell is NULL is treated as no answer at all.
    # Returning "" hands the question to the TABULAR->FACT fallback in
    # retrieval/router.py, which either finds real evidence or says it cannot.
    if all(cell is None for row in results for cell in row):
        logger.info(
            "generate_and_run_sql: all-NULL result from %r — treating as no answer "
            "(schema cannot answer this question)", sql)
        return {"answer": "", "debug_sql": sql}

    # Format as markdown table (capped at 200 rows — already enforced by LIMIT above)
    header = "| " + " | ".join(columns) + " |"
    separator = "|" + "|".join(["---"] * len(columns)) + "|"
    rows = [
        "| " + " | ".join(str(x) if x is not None else "NULL" for x in row) + " |"
        for row in results
    ]
    table = header + "\n" + separator + "\n" + "\n".join(rows)
    if len(results) >= _UNBOUNDED_LIMIT:
        table += f"\n\n*(Capped at {_UNBOUNDED_LIMIT} rows)*"

    return {"answer": table, "debug_sql": sql}
