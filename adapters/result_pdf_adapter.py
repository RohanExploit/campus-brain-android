import sys
import duckdb
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))
from models.canonical import StudentRecord, SubjectRecord
from config import DATA_ROOT, validate_tenant_id


def load_student_record(tenant_id: str, roll_no: str) -> StudentRecord:
    # Validate tenant_id (blocks '../..' path escape) and build the path through
    # config, matching the repo-wide path-hardening standard. Previously this
    # adapter derived its own DATA_ROOT and used tenant_id unchecked.
    tenant_id = validate_tenant_id(tenant_id)
    db_path = DATA_ROOT / tenant_id / "tabular.duckdb"
    if not db_path.exists():
        raise FileNotFoundError(f"Database not found for tenant: {tenant_id}")

    con = duckdb.connect(str(db_path), read_only=True)
    try:
        student_row = con.execute(
            "SELECT sgpa FROM students WHERE roll_no = ?", [roll_no]
        ).fetchone()

        if not student_row:
            raise ValueError(f"Student {roll_no} not found")

        sgpa = student_row[0]

        subject_rows = con.execute(
            "SELECT subject_code, credit, grade_point FROM student_subjects "
            "WHERE roll_no = ? AND credit > 0 AND grade_point IS NOT NULL",
            [roll_no]
        ).fetchall()

        subjects = [
            SubjectRecord(code=r[0], credit=float(r[1]), grade_point=float(r[2]))
            for r in subject_rows
        ]

        return StudentRecord(
            roll_no=roll_no,
            sgpa=float(sgpa) if sgpa is not None else None,
            subjects=subjects
        )
    finally:
        con.close()
