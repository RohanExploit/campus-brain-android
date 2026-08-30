"""
Build the consolidated `exam_results` analytics table (P3.9).

One row per (student, subject). Denormalized from the already-parsed
students + student_subjects tables. Derives `semester` from the subject code and
an `is_fail` flag, and tags provenance columns.

RETENTION-SAFE: reads the PII store (tabular.duckdb) with ATTACH ... (READ_ONLY)
and writes ONLY a separate analytics.duckdb. The raw PII DuckDB is never opened
for writing, never modified. CREATE OR REPLACE only affects analytics.duckdb.
"""
import sys as _sys
from pathlib import Path as _Path
for _p in (_Path(__file__).resolve().parent, _Path(__file__).resolve().parent.parent):
    if str(_p) not in _sys.path:
        _sys.path.append(str(_p))

import logging
from pathlib import Path

import duckdb

from config import tenant_dir, DEFAULT_TENANT_ID
from models.grades import FAIL_GRADES
from utils.logging_config import setup_logging

setup_logging()

# Failing grades come from the single source of truth (models.grades). Per the
# printed DBATU legend the only academic fail is 'FF'; 'AB' (8.5) is a pass and
# 'AU' is an audit subject — neither is a failure.


def source_db(tenant_id: str) -> Path:
    return tenant_dir(tenant_id) / "tabular.duckdb"


def analytics_db(tenant_id: str) -> Path:
    return tenant_dir(tenant_id) / "analytics.duckdb"


def build_exam_results(tenant_id: str = None) -> dict:
    tenant_id = tenant_id or DEFAULT_TENANT_ID
    src = source_db(tenant_id)
    dst = analytics_db(tenant_id)
    if not src.exists():
        raise FileNotFoundError(f"source PII store not found: {src}")

    fail_list = ", ".join(f"'{g}'" for g in FAIL_GRADES)   # for the IN (...) clause
    provenance_text = (
        "derived: students JOIN student_subjects; semester from subject_code; "
        f"is_fail = grade IN ({fail_list})"
    )  # mirrors the executed predicate exactly (quoted grades)
    # provenance_text carries the quoted grade list ("IN ('FF')") and is then
    # embedded in the SELECT as a single-quoted SQL literal, so its own quotes
    # have to be doubled. Without this every build_exam_results call dies on
    # `Parser Error: syntax error at or near "')'"` -- i.e. no tenant could build
    # an analytics.duckdb at all, which is what the TABULAR templates read.
    provenance_sql = provenance_text.replace("'", "''")
    con = duckdb.connect(str(dst))  # writes ONLY analytics.duckdb
    try:
        con.execute(f"ATTACH '{src.as_posix()}' AS src (READ_ONLY)")
        con.execute(f"""
            CREATE OR REPLACE TABLE exam_results AS
            SELECT
                ss.roll_no                                   AS roll_no,
                s.name                                       AS name,
                ss.subject_code                              AS subject_code,
                ss.credit                                    AS credit,
                ss.grade                                     AS grade,
                ss.grade_point                               AS grade_point,
                s.sgpa                                       AS sgpa,
                s.result                                     AS result,
                s.is_supply                                  AS is_supply,
                s.seat_cancelled                             AS seat_cancelled,
                TRY_CAST(regexp_extract(ss.subject_code, '[A-Za-z]+([0-9])', 1) AS INTEGER) AS semester,
                (ss.grade IN ({fail_list}))                  AS is_fail,
                CAST(NULL AS VARCHAR)                         AS department,
                'tabular.duckdb'                             AS source_file,
                '{provenance_sql}'                           AS provenance
            FROM src.student_subjects ss
            LEFT JOIN src.students s ON s.roll_no = ss.roll_no
        """)
        n = con.execute("SELECT COUNT(*) FROM exam_results").fetchone()[0]
        nstud = con.execute("SELECT COUNT(DISTINCT roll_no) FROM exam_results").fetchone()[0]
        nfail = con.execute("SELECT COUNT(*) FROM exam_results WHERE is_fail").fetchone()[0]
        con.execute("DETACH src")
    finally:
        con.close()

    logging.info("exam_results built: %d rows, %d students, %d fail-rows -> %s", n, nstud, nfail, dst)
    return {"rows": n, "students": nstud, "fail_rows": nfail, "path": str(dst)}


if __name__ == "__main__":
    tid = _sys.argv[1] if len(_sys.argv) > 1 else DEFAULT_TENANT_ID
    print(build_exam_results(tid))
