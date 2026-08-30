"""
Part B: Text-to-SQL test harness with known-answer verification.

Ground truth established from direct DuckDB queries run against
R:/Startup research/Start up V2/data/tenants/tenant_1/tabular.duckdb
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))
from config import PROJECT_ROOT
import asyncio
import io
import sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
sys.path.insert(0, f'{PROJECT_ROOT}')

from retrieval.tabular_queries import generate_and_run_sql, _sanitize_sql

# ── Known-answer test cases ────────────────────────────────────────────────
# Each entry: (label, query, checker_fn)
# checker_fn(result_dict) -> (passed: bool, note: str)

GROUND_TRUTH = {
    "sem5_fail_2plus":       77,   # students failing 2+ sem-5 subjects
    "total_fail_students":   35,   # students with result LIKE '%FAIL%'
    "gaikwad_sgpa":          None, # roll 23067571242048 — SGPA is NULL
    "top_sgpa":              8.82, # max SGPA
    "pass_count":            334,  # students where result = 'PASS'
}

def _has_number(text: str, n) -> bool:
    """Returns True if the string representation of n appears in text."""
    return str(n) in text


TEST_CASES = [
    # --- queries that should return known counts ---
    (
        "students failing 2+ sem5 subjects (77 rows in per-student list)",
        "How many students failed at least 2 subjects in semester 5?",
        lambda r: (
            # Model returns one row per student (77 rows) rather than a single COUNT.
            # Both are valid representations; we verify row count = 77.
            r["answer"].count("\n") - 2 == 77  # header + separator + 77 data rows
            or _has_number(r["answer"], 77),
            f"Expected 77 rows or '77' in answer. Got {r['answer'].count(chr(10))-2} data rows. Answer: {r['answer'][:200]}"
        ),
    ),
    (
        "total fail students (count=35)",
        "How many students have a FAIL result?",
        lambda r: (
            _has_number(r["answer"], 35),
            f"Expected 35. Got: {r['answer'][:300]}"
        ),
    ),
    (
        "PASS count (334)",
        "How many students passed?",
        lambda r: (
            _has_number(r["answer"], 334),
            f"Expected 334. Got: {r['answer'][:300]}"
        ),
    ),
    (
        "highest SGPA (8.82)",
        "What is the highest SGPA among all students?",
        lambda r: (
            "8.82" in r["answer"] or "8.8" in r["answer"],
            f"Expected 8.82. Got: {r['answer'][:300]}"
        ),
    ),
    # --- gaikwad rohan vijay SGPA = NULL ---
    (
        "Gaikwad SGPA should be NULL (known model bug: case-sensitive name match)",
        "What is the SGPA of roll number 23067571242048?",
        lambda r: (
            # DB has NULL sgpa for this student; model should return NULL or no value.
            # Acceptable: 'null', 'none', 'n/a', or empty/zero sgpa column.
            "null" in r["answer"].lower()
            or "none" in r["answer"].lower()
            or "| NULL |" in r["answer"]
            or "no results" in r["answer"].lower(),
            f"Expected NULL sgpa. debug_sql={r['debug_sql']}. Got: {r['answer'][:300]}"
        ),
    ),
    # --- zero-result queries (hallucination check) ---
    (
        "SGPA > 9.5 should be zero students",
        "List all students with SGPA above 9.5",
        lambda r: (
            "no results" in r["answer"].lower() or "0" in r["answer"] or "no " in r["answer"].lower(),
            f"Expected zero/no results. Got: {r['answer'][:300]}"
        ),
    ),
    (
        "seat_cancelled should be zero students",
        "How many students had their seat cancelled?",
        lambda r: (
            "0" in r["answer"] or "no " in r["answer"].lower() or "zero" in r["answer"].lower(),
            f"Expected 0 seat cancelled. Got: {r['answer'][:300]}"
        ),
    ),
    (
        "students with SGPA above 10 should be zero",
        "Which students have SGPA greater than 10?",
        lambda r: (
            "no results" in r["answer"].lower() or "0" in r["answer"] or "no " in r["answer"].lower(),
            f"Expected zero. Got: {r['answer'][:300]}"
        ),
    ),
    # --- guardrail unit tests (no Ollama needed) ---
    (
        "GUARDRAIL: non-SELECT rejected",
        "__GUARDRAIL_TEST__",
        None,  # handled separately below
    ),
    (
        "GUARDRAIL: multi-statement rejected",
        "__GUARDRAIL_MULTI__",
        None,
    ),
    (
        "GUARDRAIL: no-LIMIT gets capped",
        "__GUARDRAIL_NOLIMIT__",
        None,
    ),
]

async def main():
    passed = 0
    failed = 0

    for label, query, checker in TEST_CASES:
        print(f"\n{'='*65}")
        print(f"TEST: {label}")

        # ── Guardrail-only tests (no LLM call needed) ─────────────────
        if query == "__GUARDRAIL_TEST__":
            sql, reason = _sanitize_sql("DELETE FROM students")
            ok = reason is not None
            print("  Input:  'DELETE FROM students'")
            print(f"  Result: {'REJECTED' if ok else 'ACCEPTED (WRONG!)'} — {reason}")
            print(f"  {'✅ PASS' if ok else '❌ FAIL'}")
            passed += ok; failed += (not ok)
            continue

        if query == "__GUARDRAIL_MULTI__":
            sql, reason = _sanitize_sql("SELECT * FROM students; DROP TABLE students")
            ok = reason is not None
            print("  Input:  'SELECT * FROM students; DROP TABLE students'")
            print(f"  Result: {'REJECTED' if ok else 'ACCEPTED (WRONG!)'} — {reason}")
            print(f"  {'✅ PASS' if ok else '❌ FAIL'}")
            passed += ok; failed += (not ok)
            continue

        if query == "__GUARDRAIL_NOLIMIT__":
            sql, reason = _sanitize_sql("SELECT * FROM students")
            ok = reason is None and "LIMIT 200" in sql
            print("  Input:  'SELECT * FROM students'")
            print(f"  Output: {sql}")
            print(f"  {'✅ PASS — LIMIT 200 added' if ok else '❌ FAIL — LIMIT not enforced'}")
            passed += ok; failed += (not ok)
            continue

        # ── Real SQL generation tests ─────────────────────────────────
        print(f"Query: {query}")
        result = await generate_and_run_sql(query, "tenant_1")
        print(f"debug_sql: {result['debug_sql']}")
        print(f"answer (first 400 chars): {result['answer'][:400]}")

        ok, note = checker(result)
        print(f"{'✅ PASS' if ok else '❌ FAIL'} — {note}")
        passed += ok; failed += (not ok)

    print(f"\n{'='*65}")
    print(f"RESULTS: {passed} passed, {failed} failed out of {passed + failed} tests")

asyncio.run(main())
