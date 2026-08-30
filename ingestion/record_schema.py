"""Output schema validation for parsed result-sheet records.

parse_tabular.py's parser (parse_single_block) reads student result rows out
of pdfplumber word-position clusters using layout heuristics (column x0
distances, row offsets, regex token scans). Those heuristics are tuned
against known PDF layouts; a new or slightly different layout can cause the
parser to mis-extract a record without raising an exception — e.g. picking
up the wrong token as a roll number, silently producing an empty subjects
list, or reading a non-numeric string into a field expected to be numeric.

Such a record would otherwise look structurally fine (it's a well-formed
dict with the right keys) and could be trusted as "clean" output and land in
the analytics tables. validate_parsed_record is the last line of defense
before that happens: it inspects a single parsed record dict for internal
consistency/shape problems and reports them as violation strings so the
caller can quarantine the record into the needs_review queue instead of
silently trusting it.
"""

from typing import Any


def _is_number_not_bool(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def validate_parsed_record(rec: dict) -> list[str]:
    """Validate a parsed record dict, returning a list of violation strings.

    An empty list means the record passed all checks. This function is pure
    (no I/O, no DB) and only inspects the fields of the dict passed in.
    """
    violations: list[str] = []

    roll_no = rec.get("roll_no")
    if not isinstance(roll_no, str) or not roll_no:
        violations.append("roll_no_missing")

    result = rec.get("result")
    if not isinstance(result, str) or not result:
        violations.append("result_missing")

    sgpa = rec.get("sgpa")
    if sgpa is not None:
        if not (_is_number_not_bool(sgpa) and 0.0 <= sgpa <= 10.0):
            violations.append("sgpa_out_of_range")

    subjects = rec.get("subjects")
    if not isinstance(subjects, list):
        violations.append("subjects_not_list")
    elif len(subjects) == 0:
        violations.append("subjects_empty")
    else:
        bad_type = False
        code_missing = False
        grade_point_bad = False
        for subject in subjects:
            if not isinstance(subject, dict):
                bad_type = True
                continue
            code = subject.get("code")
            if not isinstance(code, str) or not code:
                code_missing = True
            if not _is_number_not_bool(subject.get("grade_point")):
                grade_point_bad = True
        if bad_type:
            violations.append("subject_bad_type")
        if code_missing:
            violations.append("subject_code_missing")
        if grade_point_bad:
            violations.append("subject_grade_point_bad")

    total_marks = rec.get("total_marks")
    if _is_number_not_bool(total_marks) and total_marks < 0:
        violations.append("total_marks_negative")

    flags = rec.get("flags")
    if not isinstance(flags, list):
        violations.append("flags_not_list")

    return violations
