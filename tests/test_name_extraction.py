"""Unit tests for the deterministic name/roll extraction that makes student
lookups phrasing-independent (no LLM). These back the fix for 'gaikwad rohan
result' resolving to 'GAIKWAD ROHAN VIJAY' regardless of word order."""
import sys
from pathlib import Path

sys.path.append(str(Path(__file__).resolve().parent.parent))

from retrieval.tabular_queries import _name_tokens_from_query, _roll_from_query


def test_name_tokens_strip_lookup_words_any_order():
    # order-independent: same token SET regardless of where "result" sits
    assert set(_name_tokens_from_query("gaikwad rohan result")) == {"gaikwad", "rohan"}
    assert set(_name_tokens_from_query("result of rohan gaikwad")) == {"rohan", "gaikwad"}
    assert set(_name_tokens_from_query("show me gaikwad rohan marksheet")) == {"gaikwad", "rohan"}
    assert set(_name_tokens_from_query("what is the result of ROHAN GAIKWAD")) == {"rohan", "gaikwad"}


def test_name_tokens_drop_filler_and_short_tokens():
    assert _name_tokens_from_query("please show the record of A B") == []
    assert set(_name_tokens_from_query("marks for John Smith")) == {"john", "smith"}


def test_roll_from_query_detects_long_digit_run():
    assert _roll_from_query("result of 23067571242048") == "23067571242048"
    assert _roll_from_query("show 2267571263022 marksheet") == "2267571263022"


def test_roll_from_query_ignores_short_numbers():
    # a 4-digit year is not a roll number
    assert _roll_from_query("result of 2024 batch topper") is None
    assert _roll_from_query("gaikwad rohan result") is None
