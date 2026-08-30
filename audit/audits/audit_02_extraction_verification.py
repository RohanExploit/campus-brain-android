"""
Audit 02 — Extraction Verification
Goal: Verify extracted values (SGPA, totals) using independent business logic.
Pass criterion: No invalid verified records (deviation > 0.01 in SGPA).
"""
import pytest
import duckdb

from models.grades import GRADE_POINTS, is_audit

pytestmark = pytest.mark.integrity


# ─── Business Logic (ground-truth SGPA formula) ───────────────────────────────

def compute_sgpa(subjects: list[dict]) -> float:
    """
    SGPA = sum(credits * grade_points) / sum(registered_credits)

    Grade points come from the single source of truth (models.grades), which
    matches the printed DBATU legend. Audit subjects (AU) score 0 and are
    excluded from the credit denominator.
    """
    total_credits = 0
    weighted_sum = 0.0
    for s in subjects:
        grade = s.get("grade", "FF")
        if is_audit(grade):            # AU: not counted toward SGPA credits
            continue
        credits = s.get("credits", 0)
        gp = GRADE_POINTS.get(grade, 0)
        weighted_sum += credits * gp
        total_credits += credits
    if total_credits == 0:
        return 0.0
    return round(weighted_sum / total_credits, 2)


def compute_total_marks(subjects: list[dict]) -> float:
    return sum(s.get("marks", 0.0) for s in subjects)


# ─── Tests ────────────────────────────────────────────────────────────────────

class TestExtractionVerification:

    def test_sgpa_recomputation_matches_stored(self, duckdb_tenant):
        """
        For every student, independently recompute SGPA from subjects table
        and compare to stored sgpa column. Flag deviations > 0.01.
        """
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)

        students = con.execute("SELECT roll_no, sgpa FROM students WHERE sgpa IS NOT NULL").fetchall()
        violations = []

        for roll_no, stored_sgpa in students:
            subjects = con.execute(
                "SELECT credits, grade, marks FROM student_subjects WHERE roll_no = ?",
                [roll_no]
            ).fetchall()
            subject_dicts = [
                {"credits": c, "grade": g, "marks": m}
                for c, g, m in subjects
            ]
            if not subject_dicts:
                continue
            # A student with only audit (AU) subjects has no gradeable credits;
            # compute_sgpa returns 0.0 there, which need not equal a stored sgpa
            # carried from prior credits — skip rather than flag a false violation.
            if all(is_audit(s.get("grade")) for s in subject_dicts):
                continue
            computed = compute_sgpa(subject_dicts)
            if stored_sgpa is not None and abs(computed - stored_sgpa) > 0.01:
                violations.append({
                    "roll_no": roll_no,
                    "stored_sgpa": stored_sgpa,
                    "computed_sgpa": computed,
                    "delta": abs(computed - stored_sgpa),
                })

        con.close()
        assert not violations, (
            f"SGPA verification failed for {len(violations)} students: {violations}"
        )

    def test_no_negative_marks(self, duckdb_tenant):
        """All marks must be non-negative."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        bad = con.execute(
            "SELECT roll_no, subject_code, marks FROM student_subjects WHERE marks < 0"
        ).fetchall()
        con.close()
        assert not bad, f"Negative marks found: {bad}"

    def test_no_marks_exceeding_maximum(self, duckdb_tenant):
        """No subject marks should exceed 100."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        bad = con.execute(
            "SELECT roll_no, subject_code, marks FROM student_subjects WHERE marks > 100"
        ).fetchall()
        con.close()
        assert not bad, f"Marks > 100 found: {bad}"

    def test_sgpa_in_valid_range(self, duckdb_tenant):
        """All stored SGPA values must be in [0, 10]."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        bad = con.execute(
            "SELECT roll_no, sgpa FROM students WHERE sgpa < 0 OR sgpa > 10"
        ).fetchall()
        con.close()
        assert not bad, f"SGPA out of [0,10] range: {bad}"

    def test_grade_ff_implies_low_marks(self, duckdb_tenant):
        """Students with grade FF should not have marks >= 40 (passing threshold)."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        bad = con.execute(
            "SELECT roll_no, subject_code, marks, grade FROM student_subjects "
            "WHERE grade = 'FF' AND marks >= 40"
        ).fetchall()
        con.close()
        assert not bad, (
            f"Inconsistency: FF grade with passing marks >= 40: {bad}"
        )

    def test_credits_are_positive(self, duckdb_tenant):
        """All credit values must be > 0."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        bad = con.execute(
            "SELECT roll_no, subject_code, credits FROM student_subjects WHERE credits <= 0"
        ).fetchall()
        con.close()
        assert not bad, f"Non-positive credits found: {bad}"

    def test_duplicate_roll_numbers_absent(self, duckdb_tenant):
        """The students table must have no duplicate roll numbers."""
        tenant_info, db_path = duckdb_tenant
        con = duckdb.connect(str(db_path), read_only=True)
        dups = con.execute(
            "SELECT roll_no, COUNT(*) as cnt FROM students GROUP BY roll_no HAVING cnt > 1"
        ).fetchall()
        con.close()
        assert not dups, f"Duplicate roll numbers detected: {dups}"

    def test_sgpa_formula_correctness(self):
        """Unit test the recomputation formula itself against known values."""
        # AA=9, credits=4 → SGPA = (4*9)/(4) = 9.0
        assert compute_sgpa([{"credits": 4, "grade": "AA", "marks": 90}]) == 9.0
        # AB=8.5 is a pass, credits=3 → (3*8.5)/3 = 8.5
        assert compute_sgpa([{"credits": 3, "grade": "AB", "marks": 82}]) == 8.5
        # BB=8 (4cr) + FF=0 (3cr) → (32+0)/7 ≈ 4.57
        mixed = [
            {"credits": 4, "grade": "BB", "marks": 70},
            {"credits": 3, "grade": "FF", "marks": 20},
        ]
        result = compute_sgpa(mixed)
        assert abs(result - round(32 / 7, 2)) < 0.01, f"Formula error: got {result}"
