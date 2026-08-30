"""
Script to auto-generate regression_benchmark.json from live DuckDB data.
Run once: python scripts/generate_benchmark.py
Produces: audit/fixtures/regression_benchmark.json
"""
import json
import duckdb
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
DB_PATH = PROJECT_ROOT / "data" / "tenants" / "tenant_1" / "tabular.duckdb"
OUTPUT = PROJECT_ROOT / "audit" / "fixtures" / "regression_benchmark.json"
TENANT_ID = "tenant_1"


def generate():
    con = duckdb.connect(str(DB_PATH), read_only=True)
    benchmark = []

    # ── Q1–Q8: Individual SGPA queries ────────────────────────────────────────
    students = con.execute(
        "SELECT roll_no, name, sgpa FROM students WHERE sgpa IS NOT NULL ORDER BY RANDOM() LIMIT 8"
    ).fetchall()
    for roll_no, name, sgpa in students:
        benchmark.append({
            "query": f"What is the SGPA of student with roll number {roll_no}?",
            "expected_answer": str(round(sgpa, 2)),
            "query_type": "TABULAR",
            "tenant_id": TENANT_ID,
            "roll_no": roll_no,
            "field": "sgpa",
        })

    # ── Q9–Q12: Result status queries ─────────────────────────────────────────
    results_sample = con.execute(
        "SELECT roll_no, name, result FROM students WHERE result IS NOT NULL ORDER BY RANDOM() LIMIT 4"
    ).fetchall()
    for roll_no, name, result in results_sample:
        benchmark.append({
            "query": f"Did student {roll_no} pass their semester examination?",
            "expected_answer": "PASS" if result == "PASS" else "FAIL",
            "query_type": "TABULAR",
            "tenant_id": TENANT_ID,
            "roll_no": roll_no,
            "field": "result",
        })

    # ── Q13–Q16: Subject-level grade queries ───────────────────────────────────
    subject_sample = con.execute("""
        SELECT s.roll_no, s.subject_code, s.grade, s.credit
        FROM student_subjects s
        WHERE s.grade IS NOT NULL AND s.credit > 0
        ORDER BY RANDOM() LIMIT 4
    """).fetchall()
    for roll_no, subject_code, grade, credit in subject_sample:
        benchmark.append({
            "query": f"What grade did student {roll_no} get in subject {subject_code}?",
            "expected_answer": grade,
            "query_type": "TABULAR",
            "tenant_id": TENANT_ID,
            "roll_no": roll_no,
            "subject_code": subject_code,
            "field": "grade",
        })

    # ── Q17–Q18: Hallucination guards ─────────────────────────────────────────
    benchmark.append({
        "query": "What is the SGPA of student 9999999999?",
        "expected_answer": "INSUFFICIENT_EVIDENCE",
        "query_type": "TABULAR",
        "tenant_id": TENANT_ID,
        "field": "hallucination_guard",
    })
    benchmark.append({
        "query": "List all students in the Quantum Computing department.",
        "expected_answer": "INSUFFICIENT_EVIDENCE",
        "query_type": "GLOBAL",
        "tenant_id": TENANT_ID,
        "field": "hallucination_guard",
    })

    # ── Q19–Q20: Needs-review questions ───────────────────────────────────────
    review_count = con.execute("SELECT COUNT(*) FROM needs_review").fetchone()[0]
    benchmark.append({
        "query": "How many student records are currently flagged for review?",
        "expected_answer": str(review_count),
        "query_type": "TABULAR",
        "tenant_id": TENANT_ID,
        "field": "review_count",
    })
    benchmark.append({
        "query": "What is the total number of students in the database?",
        "expected_answer": str(con.execute("SELECT COUNT(*) FROM students").fetchone()[0]),
        "query_type": "TABULAR",
        "tenant_id": TENANT_ID,
        "field": "student_count",
    })

    con.close()

    # Trim to exactly 20
    benchmark = benchmark[:20]

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(benchmark, f, indent=2, ensure_ascii=False)

    print(f"Generated {len(benchmark)} benchmark pairs -> {OUTPUT}")
    for i, item in enumerate(benchmark):
        print(f"  {i+1:02d}. [{item['query_type']}] {item['query'][:60]}...")
        print(f"      Expected: {item['expected_answer'][:40]}")


if __name__ == "__main__":
    generate()
