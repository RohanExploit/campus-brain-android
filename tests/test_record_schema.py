"""
Hermetic unit tests for ingestion/record_schema.py -- the output schema
validator that guards parse_tabular.py's parsed records before they are
trusted as clean output.
"""
from ingestion.record_schema import validate_parsed_record


def _valid_record():
    """Minimal fully-valid record with all keys populated sensibly."""
    return {
        "roll_no": "1234567890123",
        "name": "TEST STUDENT",
        "result": "PASS",
        "sgpa": 8.5,
        "estimated_sgpa_partial_credits": 8.5,
        "total_marks": 172,
        "is_supply": False,
        "seat_cancelled": False,
        "subjects": [
            {"code": "BTCOC501", "credit": 4, "grade": "A", "grade_point": 8.0, "raw": "72/A/8"},
            {"code": "BTCOC502", "credit": 3, "grade": "B", "grade_point": 6.0, "raw": "85/B/6"},
        ],
        "passed_all": True,
        "flags": [],
        "gap": 0,
        "derived_max": 100,
        "raw_block": "raw text here",
    }


def test_fully_valid_record_has_no_violations():
    rec = _valid_record()
    assert validate_parsed_record(rec) == []


def test_missing_roll_no_flagged():
    rec = _valid_record()
    rec["roll_no"] = None
    assert "roll_no_missing" in validate_parsed_record(rec)


def test_empty_roll_no_flagged():
    rec = _valid_record()
    rec["roll_no"] = ""
    assert "roll_no_missing" in validate_parsed_record(rec)


def test_sgpa_out_of_range_high():
    rec = _valid_record()
    rec["sgpa"] = 11.0
    assert "sgpa_out_of_range" in validate_parsed_record(rec)


def test_sgpa_none_not_flagged():
    rec = _valid_record()
    rec["sgpa"] = None
    assert "sgpa_out_of_range" not in validate_parsed_record(rec)


def test_sgpa_bool_flagged():
    # bool is technically a subclass of int in Python, but a bool SGPA is
    # nonsensical and must be rejected, not silently accepted as 1.0/0.0.
    rec = _valid_record()
    rec["sgpa"] = True
    assert "sgpa_out_of_range" in validate_parsed_record(rec)


def test_subjects_not_list_flagged():
    rec = _valid_record()
    rec["subjects"] = "x"
    assert "subjects_not_list" in validate_parsed_record(rec)


def test_subjects_empty_flagged():
    rec = _valid_record()
    rec["subjects"] = []
    assert "subjects_empty" in validate_parsed_record(rec)


def test_subject_missing_code_flagged():
    rec = _valid_record()
    rec["subjects"] = [
        {"code": "", "credit": 4, "grade": "A", "grade_point": 8.0, "raw": "72/A/8"},
    ]
    assert "subject_code_missing" in validate_parsed_record(rec)


def test_subject_grade_point_bad_flagged():
    rec = _valid_record()
    rec["subjects"] = [
        {"code": "BTCOC501", "credit": 4, "grade": "A", "grade_point": "8", "raw": "72/A/8"},
    ]
    assert "subject_grade_point_bad" in validate_parsed_record(rec)


def test_total_marks_negative_flagged():
    rec = _valid_record()
    rec["total_marks"] = -5
    assert "total_marks_negative" in validate_parsed_record(rec)
