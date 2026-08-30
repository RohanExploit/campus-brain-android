"""Build the TABULAR half of the bench: a synthetic result database + its golds.

TABULAR is ~48% of real demand (22 of 46 questions in the real tenant's golden set) and
until now the only thing testing it was those 22 questions on the production database. That
made it both the largest slice of the product and the least covered by the instrument, and
it meant no TABULAR change could be evaluated without touching real data.

Everything here is generated arithmetically from a fixed table — no RNG, no seeds — so the
database is byte-reproducible and every gold is computed from the same rows the system will
query. A gold cannot drift from the data because both come from this file.

Written to its OWN tenant (tenant_bench_sql). Adding a tabular.duckdb to tenant_bench would
change that tenant's routing behaviour — `router.route_query` takes the TABULAR->FACT
fallback precisely when no tabular.duckdb exists — and would silently invalidate every
document-side number already measured there.

Usage:  python tests/eval/bench/build_tabular.py
"""
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import duckdb  # noqa: E402

from tests.eval.bench.world import DEPARTMENTS  # noqa: E402

TEN = PROJECT_ROOT / "data" / "tenants" / "tenant_bench_sql"
KIT = PROJECT_ROOT / "Dataset" / "bench_v1" / "golden"
OUT_GOLD = PROJECT_ROOT / "tests" / "eval" / "golden_bench_sql.json"

# Fictional surnames/given names combined arithmetically — no real person is represented,
# and the combination is a pure function of the index so the roster is reproducible.
GIVEN = ["Aarav", "Isha", "Rohit", "Sneha", "Kunal", "Priya", "Omkar", "Neha",
         "Sagar", "Trupti", "Nikhil", "Asmita"]
SURNAME = ["Pardeshi", "Wagholikar", "Bhandari", "Ghatge", "Sutar", "Marathe",
           "Zambre", "Kadolkar", "Phadtare", "Ingale"]

SUBJECTS = [
    ("BT301", "Engineering Mathematics III", 4),
    ("BT302", "Data Structures", 4),
    ("BT303", "Computer Networks", 3),
    ("BT304", "Operating Systems", 3),
    ("BT305", "Database Systems", 4),
    ("BT306", "Software Engineering", 3),
    ("BT307", "Professional Elective I", 3),
]

# grade -> (points, is_fail). FF is the only failing grade, matching models/grades.py.
GRADES = [("AA", 10.0), ("AB", 9.0), ("BB", 8.0), ("BC", 7.0),
          ("CC", 6.0), ("CD", 5.0), ("DD", 4.0), ("FF", 0.0)]
N_STUDENTS = 120


def roster():
    """One deterministic student per index: roll, name, department."""
    out = []
    for i in range(N_STUDENTS):
        roll = f"2306318124{3000 + i}"
        name = f"{GIVEN[i % len(GIVEN)]} {SURNAME[(i // len(GIVEN)) % len(SURNAME)]}"
        dept = DEPARTMENTS[i % len(DEPARTMENTS)][0]
        out.append((roll, name.upper(), dept))
    return out


def grade_for(i: int, j: int) -> tuple:
    """Grade of student i in subject j — a fixed arithmetic pattern, not randomness.

    The spread is deliberately uneven: a handful of students fail several subjects (so
    "failed at least N" has a non-trivial answer), one band sits exactly on a boundary
    (so threshold queries are testable), and BT307 is failed far more often than the rest
    (so per-subject aggregates differ from each other).
    """
    k = (i * 7 + j * 3) % 17
    if j == 6 and i % 5 == 0:          # elective is the weak subject
        return GRADES[7]
    if i % 23 == 0 and j % 2 == 0:     # a few multi-subject failures
        return GRADES[7]
    return GRADES[k % 7]


def build_rows():
    rows = []
    for i, (roll, name, dept) in enumerate(roster()):
        pts, creds, failed = 0.0, 0, 0
        subject_rows = []
        for j, (code, _title, credit) in enumerate(SUBJECTS):
            grade, gp = grade_for(i, j)
            is_fail = grade == "FF"
            failed += int(is_fail)
            pts += gp * credit
            creds += credit
            subject_rows.append((code, credit, grade, gp * credit, is_fail))
        sgpa = round(pts / creds, 2)
        result = "FAIL" if failed else "PASS"
        for code, credit, grade, gpw, is_fail in subject_rows:
            rows.append((roll, name, code, credit, grade, gpw, sgpa, result,
                         False, False, 3, is_fail, dept, "bench_generated", "synthetic"))
    return rows


def write_db(rows):
    TEN.mkdir(parents=True, exist_ok=True)
    apath = TEN / "analytics.duckdb"
    if apath.exists():
        apath.unlink()
    con = duckdb.connect(str(apath))
    con.execute("""
        CREATE TABLE exam_results (
            roll_no VARCHAR, name VARCHAR, subject_code VARCHAR, credit INTEGER,
            grade VARCHAR, grade_point DOUBLE, sgpa DOUBLE, result VARCHAR,
            is_supply BOOLEAN, seat_cancelled BOOLEAN, semester INTEGER,
            is_fail BOOLEAN, department VARCHAR, source_file VARCHAR, provenance VARCHAR)
    """)
    con.executemany("INSERT INTO exam_results VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", rows)
    con.close()

    # The router decides whether the TABULAR route is live by testing for tabular.duckdb,
    # so this tenant needs one or every TABULAR question would take the FACT fallback.
    tpath = TEN / "tabular.duckdb"
    if tpath.exists():
        tpath.unlink()
    con = duckdb.connect(str(tpath))
    con.execute("CREATE TABLE students (roll_no VARCHAR, name VARCHAR, sgpa DOUBLE, "
                "estimated_sgpa DOUBLE, total_marks INTEGER, result VARCHAR, "
                "is_supply BOOLEAN, seat_cancelled BOOLEAN)")
    seen, srows = set(), []
    for r in rows:
        if r[0] not in seen:
            seen.add(r[0])
            srows.append((r[0], r[1], r[6], r[6], 0, r[7], False, False))
    con.executemany("INSERT INTO students VALUES (?,?,?,?,?,?,?,?)", srows)
    con.execute("CREATE TABLE student_subjects (roll_no VARCHAR, subject_code VARCHAR, "
                "credit INTEGER, grade VARCHAR, grade_point DOUBLE, raw_grade_string VARCHAR)")
    con.executemany("INSERT INTO student_subjects VALUES (?,?,?,?,?,?)",
                    [(r[0], r[2], r[3], r[4], r[5], r[4]) for r in rows])
    con.close()
    return apath, len(srows)


def build_golds(apath: Path):
    """Every answer is computed by SQL over the rows just written."""
    con = duckdb.connect(str(apath), read_only=True)
    q = lambda s: con.execute(s).fetchall()  # noqa: E731

    total = q("SELECT COUNT(DISTINCT roll_no) FROM exam_results")[0][0]
    passed = q("SELECT COUNT(*) FROM (SELECT DISTINCT roll_no, result FROM exam_results) "
               "WHERE result='PASS'")[0][0]
    failed = total - passed
    pass_pct = round(100.0 * passed / total, 1)
    top = q("SELECT DISTINCT roll_no, name, sgpa FROM exam_results ORDER BY sgpa DESC, "
            "roll_no LIMIT 3")
    bottom = q("SELECT DISTINCT roll_no, name, sgpa FROM exam_results ORDER BY sgpa ASC, "
               "roll_no LIMIT 3")
    fail2 = q("SELECT COUNT(*) FROM (SELECT roll_no FROM exam_results WHERE is_fail "
              "GROUP BY roll_no HAVING COUNT(DISTINCT subject_code) >= 2)")[0][0]
    fail3 = q("SELECT COUNT(*) FROM (SELECT roll_no FROM exam_results WHERE is_fail "
              "GROUP BY roll_no HAVING COUNT(DISTINCT subject_code) >= 3)")[0][0]
    below6 = q("SELECT COUNT(DISTINCT roll_no) FROM exam_results WHERE sgpa < 6.0")[0][0]
    subj_fail = q("SELECT subject_code, COUNT(*) FROM exam_results WHERE is_fail "
                  "GROUP BY subject_code ORDER BY 2 DESC")
    avg_ds = q("SELECT ROUND(AVG(sgpa),2) FROM (SELECT DISTINCT roll_no, sgpa FROM "
               "exam_results)")[0][0]
    con.close()

    def gold(*anchors):
        return {"mode": "anchors", "required": [[str(a)] for a in anchors], "bonus": []}

    items = [
        ("BT001", "What is the total number of students in the database?", gold(total)),
        ("BT002", "How many students passed the semester examination?", gold(passed)),
        ("BT003", "How many students failed the semester examination?", gold(failed)),
        ("BT004", "What is the pass percentage?", gold(pass_pct)),
        ("BT005", "How many students failed at least 2 subjects?", gold(fail2)),
        ("BT006", "How many students failed at least 3 subjects?", gold(fail3)),
        ("BT007", "List the top 3 students by SGPA.", gold(top[0][1].split()[0], top[0][2])),
        ("BT008", "Who has the highest SGPA?", gold(top[0][1].split()[0])),
        ("BT009", "Which students have the lowest SGPA?",
         gold(bottom[0][1].split()[0], bottom[0][2])),
        ("BT010", "How many students have an SGPA below 6.0?", gold(below6)),
        ("BT011", "Which subject has the most failures?", gold(subj_fail[0][0])),
        ("BT012", "How many students failed the Professional Elective I paper?",
         gold(dict(subj_fail).get("BT307", 0))),
        ("BT013", "What is the average SGPA across all students?", gold(avg_ds)),
        ("BT014", f"What is the result of student {top[0][0]}?", gold("PASS")),
        ("BT015", f"What is the SGPA of roll number {top[0][0]}?", gold(top[0][2])),
    ]
    return [{"id": i, "route": "TABULAR", "query": qq, "gold": g} for i, qq, g in items]


def main():
    rows = build_rows()
    apath, n_students = write_db(rows)
    golds = build_golds(apath)
    spec = {"version": "bench-sql-1", "tenant_id": "tenant_bench_sql",
            "description": ("Synthetic result database and TABULAR golds, both generated "
                            "from tests/eval/bench/build_tabular.py so the answers cannot "
                            "disagree with the rows the system queries."),
            "questions": golds}
    OUT_GOLD.write_text(json.dumps(spec, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"wrote {apath.name}: {len(rows)} subject rows, {n_students} students")
    print(f"wrote {OUT_GOLD.name}: {len(golds)} TABULAR questions")
    for g in golds[:5]:
        print(f"  {g['id']} {g['query'][:58]:60s} -> "
              f"{[grp[0] for grp in g['gold']['required']]}")


if __name__ == "__main__":
    main()
