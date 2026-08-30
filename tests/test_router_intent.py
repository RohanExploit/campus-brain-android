"""Hermetic unit tests for the pure tabular intent classifier extracted from
retrieval/router.py. No DB, no LLM, no other project imports — just the
classifier itself."""
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from retrieval.intent import classify_tabular_intent, TabularIntent


def test_list_all_failed_at_least_routes_dynamic_sql():
    intent = classify_tabular_intent("list all students who failed at least 4 subjects")
    assert intent.kind == "dynamic_sql"


def test_search_for_name_routes_name_search():
    intent = classify_tabular_intent("search for Rohan Gaikwad")
    assert intent.kind == "name_search"


def test_search_for_with_fail_routes_dynamic_sql():
    intent = classify_tabular_intent("search for students who fail subject BTCOC501")
    assert intent.kind == "dynamic_sql"


def test_average_sgpa_extracts_subject():
    intent = classify_tabular_intent("average sgpa in subject BTCOC501")
    assert intent.kind == "average_sgpa"
    assert intent.params["subject"] == "BTCOC501"


def test_count_failures_extracts_subject():
    intent = classify_tabular_intent("how many students failed in subject BTCOC502")
    assert intent.kind == "count_failures"
    assert intent.params["subject"] == "BTCOC502"


def test_average_sgpa_no_subject_match_is_none():
    intent = classify_tabular_intent("average sgpa overall")
    assert intent.kind == "average_sgpa"
    assert intent.params["subject"] is None


def test_count_failures_no_subject_match_is_none():
    intent = classify_tabular_intent("how many students failed")
    assert intent.kind == "count_failures"
    assert intent.params["subject"] is None


def test_fail_without_count_keywords_routes_dynamic_sql():
    intent = classify_tabular_intent("students who fail subject BTCOC501")
    assert intent.kind == "dynamic_sql"


def test_below_sgpa_threshold():
    intent = classify_tabular_intent("students below 6.0 sgpa")
    assert intent.kind == "below_sgpa"
    assert intent.params["threshold"] == 6.0


def test_below_sgpa_threshold_not_confused_with_semester_number():
    intent = classify_tabular_intent("semester 3 students below 6 sgpa")
    assert intent.kind == "below_sgpa"
    assert intent.params["threshold"] == 6.0


def test_record_by_roll_extracts_roll_number():
    intent = classify_tabular_intent("record for roll 2267571242025")
    assert intent.kind == "record_by_roll"
    assert intent.params["roll"] == "2267571242025"


def test_student_named_without_roll_digits_routes_name_search():
    intent = classify_tabular_intent("student named Jane")
    assert intent.kind == "name_search"


def test_result_of_name_routes_name_search_not_exact_sql():
    # Regression: "result of <name>" used to fall through to dynamic_sql, whose
    # exact case-sensitive `name = '...'` match missed reordered/differently-
    # cased names ("Rohan Vijay gaikwad" != stored "GAIKWAD ROHAN VIJAY").
    # It must route to the order-independent fuzzy name_search path.
    for q in [
        "result of Rohan Vijay gaikwad",
        "result for rohan gaikwad",
        "marks of gaikwad rohan vijay",
        "marksheet of Jane Doe",
        "grades for John Smith",
        # name-first / suffix order, no "of|for" — the second reported break
        "rohan gaikwad result",
        "gaikwad rohan result",
        "rohan gaikwad marksheet",
        "show rohan gaikwad result",
    ]:
        assert classify_tabular_intent(q).kind == "name_search", q


def test_result_of_roll_number_routes_record_by_roll():
    intent = classify_tabular_intent("result of 23067571242048")
    assert intent.kind == "record_by_roll"
    assert intent.params["roll"] == "23067571242048"


def test_aggregate_result_query_not_captured_by_name_lookup():
    # "failed their semester result" has no "of/for" after result -> stays on the
    # fail/count path, not name_search.
    intent = classify_tabular_intent("how many students failed their semester result")
    assert intent.kind == "count_failures"


def test_unmatched_query_routes_dynamic_sql():
    intent = classify_tabular_intent("who is the topper")
    assert intent.kind == "dynamic_sql"


def test_tabular_intent_is_frozen_dataclass_with_default_params():
    intent = TabularIntent("dynamic_sql")
    assert intent.params == {}
