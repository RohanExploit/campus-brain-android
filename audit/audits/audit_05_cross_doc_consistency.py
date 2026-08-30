"""
Audit 05 — Cross-Document Consistency
Goal: Detect conflicting identities, duplicate students, branch changes,
      inconsistent DOBs.
Pass criterion: All conflicts surfaced, none silently merged.
"""
import pytest
import duckdb
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))

pytestmark = pytest.mark.integrity


# ─── Consistency Checker ──────────────────────────────────────────────────────

class ConsistencyChecker:
    def __init__(self, db_path: str):
        self.db_path = db_path

    def find_duplicate_roll_numbers(self) -> list[dict]:
        con = duckdb.connect(self.db_path, read_only=True)
        rows = con.execute(
            "SELECT roll_no, COUNT(*) as cnt FROM students GROUP BY roll_no HAVING cnt > 1"
        ).fetchall()
        con.close()
        return [{"roll_no": r[0], "count": r[1]} for r in rows]

    def find_conflicting_dob(self, conflict_table: list[dict]) -> list[dict]:
        """
        Given a list of records from multiple sources for the same roll_no,
        detect DOB conflicts.
        conflict_table: [{roll_no, dob, source}, ...]
        """
        from collections import defaultdict
        by_roll = defaultdict(list)
        for rec in conflict_table:
            by_roll[rec["roll_no"]].append(rec)

        conflicts = []
        for roll_no, records in by_roll.items():
            dobs = set(r["dob"] for r in records)
            if len(dobs) > 1:
                conflicts.append({
                    "roll_no": roll_no,
                    "conflicting_dobs": list(dobs),
                    "sources": [r["source"] for r in records],
                })
        return conflicts

    def find_conflicting_branches(self, conflict_table: list[dict]) -> list[dict]:
        """Detect branch changes for the same student across documents."""
        from collections import defaultdict
        by_roll = defaultdict(list)
        for rec in conflict_table:
            by_roll[rec["roll_no"]].append(rec)

        conflicts = []
        for roll_no, records in by_roll.items():
            branches = set(r.get("branch", "") for r in records)
            if len(branches) > 1:
                conflicts.append({
                    "roll_no": roll_no,
                    "conflicting_branches": list(branches),
                    "sources": [r["source"] for r in records],
                })
        return conflicts

    def find_sgpa_conflicts(self, conflict_table: list[dict]) -> list[dict]:
        """Detect SGPA conflicts for the same student across documents."""
        from collections import defaultdict
        by_roll = defaultdict(list)
        for rec in conflict_table:
            by_roll[rec["roll_no"]].append(rec)

        conflicts = []
        for roll_no, records in by_roll.items():
            sgpas = set(r.get("sgpa") for r in records)
            if len(sgpas) > 1:
                max_delta = max(sgpas) - min(sgpas)
                if max_delta > 0.01:
                    conflicts.append({
                        "roll_no": roll_no,
                        "conflicting_sgpas": list(sgpas),
                        "delta": max_delta,
                        "sources": [r["source"] for r in records],
                    })
        return conflicts


# ─── Simulated multi-document conflict data ───────────────────────────────────

CONFLICT_RECORDS = [
    # Student 2021001001: conflicting DOB between two documents
    {"roll_no": "2021001001", "dob": "2002-05-10", "branch": "CS", "sgpa": 8.5, "source": "doc_A.pdf"},
    {"roll_no": "2021001001", "dob": "2002-06-15", "branch": "CS", "sgpa": 8.5, "source": "doc_B.pdf"},
    # Student 2021001002: conflicting branch between two documents
    {"roll_no": "2021001002", "dob": "2002-08-22", "branch": "CS", "sgpa": 7.2, "source": "doc_A.pdf"},
    {"roll_no": "2021001002", "dob": "2002-08-22", "branch": "IT", "sgpa": 7.2, "source": "doc_C.pdf"},
    # Student 2021001003: SGPA conflict
    {"roll_no": "2021001003", "dob": "2001-11-30", "branch": "IT", "sgpa": 5.8, "source": "doc_A.pdf"},
    {"roll_no": "2021001003", "dob": "2001-11-30", "branch": "IT", "sgpa": 6.5, "source": "doc_D.pdf"},
    # Student 2021001004: no conflict (clean)
    {"roll_no": "2021001004", "dob": "2002-03-15", "branch": "CS", "sgpa": 9.1, "source": "doc_A.pdf"},
    {"roll_no": "2021001004", "dob": "2002-03-15", "branch": "CS", "sgpa": 9.1, "source": "doc_B.pdf"},
]


# ─── Tests ────────────────────────────────────────────────────────────────────

class TestCrossDocumentConsistency:

    def test_no_duplicate_roll_numbers_in_db(self, duckdb_tenant):
        """DuckDB must have no duplicate roll numbers in students table."""
        tenant_info, db_path = duckdb_tenant
        checker = ConsistencyChecker(str(db_path))
        dups = checker.find_duplicate_roll_numbers()
        assert not dups, f"Duplicate roll numbers in database: {dups}"

    def test_dob_conflict_detected(self):
        """
        When two documents provide conflicting DOBs for the same student,
        the conflict detector must surface it.
        """
        checker = ConsistencyChecker(":memory:")  # not used for this test
        conflicts = checker.find_conflicting_dob(CONFLICT_RECORDS)
        # We seeded a DOB conflict for 2021001001
        conflict_rolls = [c["roll_no"] for c in conflicts]
        assert "2021001001" in conflict_rolls, (
            f"DOB conflict for 2021001001 was NOT detected. Detected: {conflict_rolls}"
        )

    def test_branch_conflict_detected(self):
        """
        When two documents show different branches for the same student,
        the conflict detector must surface it.
        """
        checker = ConsistencyChecker(":memory:")
        conflicts = checker.find_conflicting_branches(CONFLICT_RECORDS)
        conflict_rolls = [c["roll_no"] for c in conflicts]
        assert "2021001002" in conflict_rolls, (
            f"Branch conflict for 2021001002 was NOT detected. Detected: {conflict_rolls}"
        )

    def test_sgpa_conflict_detected(self):
        """
        When two documents show different SGPA (delta > 0.01) for the same student,
        the conflict must be flagged.
        """
        checker = ConsistencyChecker(":memory:")
        conflicts = checker.find_sgpa_conflicts(CONFLICT_RECORDS)
        conflict_rolls = [c["roll_no"] for c in conflicts]
        assert "2021001003" in conflict_rolls, (
            f"SGPA conflict for 2021001003 was NOT detected. Detected: {conflict_rolls}"
        )

    def test_clean_student_no_false_positive(self):
        """
        Student 2021001004 has no conflicts. Conflict detector must not flag them.
        """
        checker = ConsistencyChecker(":memory:")
        dob_conflicts = checker.find_conflicting_dob(CONFLICT_RECORDS)
        branch_conflicts = checker.find_conflicting_branches(CONFLICT_RECORDS)
        sgpa_conflicts = checker.find_sgpa_conflicts(CONFLICT_RECORDS)
        all_conflict_rolls = set(
            c["roll_no"] for c in dob_conflicts + branch_conflicts + sgpa_conflicts
        )
        assert "2021001004" not in all_conflict_rolls, (
            "False positive: clean student 2021001004 was incorrectly flagged"
        )

    def test_conflict_includes_source_attribution(self):
        """Every detected conflict must name the conflicting source documents."""
        checker = ConsistencyChecker(":memory:")
        conflicts = checker.find_conflicting_dob(CONFLICT_RECORDS)
        for c in conflicts:
            assert "sources" in c and len(c["sources"]) >= 2, (
                f"Conflict missing source attribution: {c}"
            )

    def test_all_conflicts_surfaced_not_merged(self):
        """
        When conflicts exist, the total count must equal all seeded conflicts.
        None may be silently merged/lost.
        """
        checker = ConsistencyChecker(":memory:")
        all_conflicts = (
            checker.find_conflicting_dob(CONFLICT_RECORDS) +
            checker.find_conflicting_branches(CONFLICT_RECORDS) +
            checker.find_sgpa_conflicts(CONFLICT_RECORDS)
        )
        # We seeded 3 distinct conflict types for 3 distinct students
        conflict_rolls = set(c["roll_no"] for c in all_conflicts)
        expected_conflicts = {"2021001001", "2021001002", "2021001003"}
        assert conflict_rolls == expected_conflicts, (
            f"Not all conflicts detected. Expected: {expected_conflicts}, Got: {conflict_rolls}"
        )
