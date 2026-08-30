"""Authoritative DBATU grade scale + classification — single source of truth.

Base grade points are taken from the grade legend printed on the source result
PDFs, and independently cross-checked against ingested data: in
`student_subjects.grade_point` the value is stored as ``base_point * credit``,
so dividing by the credit recovers exactly the scale below (e.g. AB rows carry
17.0 at 2 credits and 34.0 at 4 credits -> 8.5).

Import from here; do NOT re-hardcode grade lists elsewhere. A duplicated,
wrong copy of this mapping is what previously misclassified 'AB' (a pass, 8.5)
as a failure across ingestion, retrieval and audit.
"""

# Base grade points on the 0-10 scale.
GRADE_POINTS = {
    "EX": 10.0,  # excellent
    "AA": 9.0,
    "AB": 8.5,   # 80.01-85.00 — a PASS, not an absence
    "BB": 8.0,
    "BC": 7.5,
    "CC": 7.0,
    "CD": 6.5,
    "DD": 6.0,
    "DE": 5.5,
    "EE": 5.0,
    "FF": 0.0,   # fail, 0.00-39.99
}

# The only academic failing grade is FF. 'AB' is a pass; 'AU' is an audit
# subject; 'XX' is a non-graded status code (no point value in the legend).
FAIL_GRADES = ("FF",)

# Audit subjects: 0 points and excluded from the SGPA credit denominator.
AUDIT_GRADES = ("AU",)


def is_fail(grade: str) -> bool:
    """True only for academic failing grades (FF)."""
    return grade in FAIL_GRADES


def is_audit(grade: str) -> bool:
    """True for audit subjects, which are excluded from SGPA credits."""
    return grade in AUDIT_GRADES
