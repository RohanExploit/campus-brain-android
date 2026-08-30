"""Regression guard for the DBATU grade scale.

Locks in the fact that 'AB' is a passing grade worth 8.5 and 'FF' is the only
academic failure worth 0 — so the earlier misclassification (AB treated as an
absence/failure across ingestion, retrieval, and audit) cannot come back.
"""
from models.grades import GRADE_POINTS, FAIL_GRADES, is_fail, is_audit


def test_ab_is_a_passing_grade_worth_8_5():
    assert GRADE_POINTS["AB"] == 8.5
    assert "AB" not in FAIL_GRADES
    assert is_fail("AB") is False


def test_ff_is_failing_worth_0():
    assert GRADE_POINTS["FF"] == 0.0
    assert "FF" in FAIL_GRADES
    assert is_fail("FF") is True


def test_only_ff_is_a_failing_grade():
    # Not 'AB' (a pass) and not 'XX' (a non-graded status).
    assert FAIL_GRADES == ("FF",)


def test_au_is_audit_not_fail():
    assert is_audit("AU") is True
    assert is_fail("AU") is False


def test_full_scale_matches_printed_legend():
    assert GRADE_POINTS == {
        "EX": 10.0, "AA": 9.0, "AB": 8.5, "BB": 8.0, "BC": 7.5,
        "CC": 7.0, "CD": 6.5, "DD": 6.0, "DE": 5.5, "EE": 5.0, "FF": 0.0,
    }
