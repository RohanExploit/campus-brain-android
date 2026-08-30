"""
Hermetic unit tests for ingestion/parse_tabular.py — the core PDF result-sheet
parser that feeds roll no / SGPA / grade / credit data into the SQL analytics
layer (retrieval/sql_templates.py).

Previously this module had zero real pytest coverage: tests/test_production_import.py
imports it but only prints a diagnostic summary (no asserts, no test_* functions),
and every other diagnostic script (test_all_pdfs.py, test_full_pass.py, etc.)
re-implements extract_rows/parse_header/parse_single_block locally instead of
importing the production module, so those scripts could never catch a regression
in the real parser.

All fixtures here are hand-authored synthetic word-dicts (fake roll numbers,
fake names) — no real PDFs, no student PII. Expected values are hand-computed
in comments next to each fixture so a wrong-but-passing assertion is easy to spot.
"""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ingestion.parse_tabular import extract_rows, parse_header, parse_single_block


def word(text, top, x0, x1=None):
    """Build a pdfplumber-style word dict."""
    if x1 is None:
        x1 = x0 + max(len(text), 1)
    return {"text": text, "top": top, "x0": x0, "x1": x1}


def row_of(tokens_with_x0, top):
    """tokens_with_x0: list of (text, x0) -> a single clustered row (unsorted input ok)."""
    return [word(t, top, x0) for t, x0 in tokens_with_x0]


# ---------------------------------------------------------------------------
# extract_rows: row-clustering by y-coordinate, independent of any real PDF.
# ---------------------------------------------------------------------------

class FakePage:
    """Stub replacing a pdfplumber Page — only extract_words() is used."""
    def __init__(self, words):
        self._words = words

    def extract_words(self):
        return self._words


def test_extract_rows_clusters_by_y_and_sorts_by_x():
    # Two logical rows at top=100 and top=150 (well beyond the 3.0 tolerance),
    # words within a row given out of x-order to prove the x0 re-sort happens.
    words = [
        word("BBB", top=100, x0=50),
        word("AAA", top=100.5, x0=10),   # within 3.0 of 100 -> same row as BBB
        word("ZZZ", top=150, x0=5),
        word("YYY", top=151, x0=40),     # within 3.0 of 150 -> same row as ZZZ
    ]
    page = FakePage(words)
    rows = extract_rows(page)

    assert len(rows) == 2
    assert [w["text"] for w in rows[0]] == ["AAA", "BBB"]   # sorted by x0 within row
    assert [w["text"] for w in rows[1]] == ["ZZZ", "YYY"]


def test_extract_rows_new_row_when_y_gap_exceeds_tolerance():
    # top values 3.0 apart or less merge; 3.1+ apart split into a new row.
    words = [
        word("A", top=10, x0=0),
        word("B", top=13.0, x0=10),   # abs diff 3.0 -> same row (<=3.0)
        word("C", top=16.1, x0=20),   # abs diff from cur_y(10) = 6.1 -> new row
    ]
    page = FakePage(words)
    rows = extract_rows(page)

    assert len(rows) == 2
    assert [w["text"] for w in rows[0]] == ["A", "B"]
    assert [w["text"] for w in rows[1]] == ["C"]


# ---------------------------------------------------------------------------
# parse_header: subject/credit-column extraction + expected_total_max /
# printed_max_sum computation.
# ---------------------------------------------------------------------------

def test_parse_header_extracts_subjects_credits_and_totals():
    # Row0 (subject row): "Total Marks(" literal must appear once tokens are
    # joined with spaces -> "Total" + " " + "Marks(200)" == "Total Marks(200)".
    # Subject x0s are far apart (10 vs 100) so nearest-credit matching in the
    # CREDIT row is unambiguous.
    row0 = row_of([("BTCOC501", 10), ("BTCOC502", 100), ("Total", 150), ("Marks(200)", 170)], top=10)
    # Row1: CREDIT row. "4" (x0=12) is closest to BTCOC501 (x0=10, dist 2 vs 88).
    # "3" (x0=98) is closest to BTCOC502 (x0=100, dist 2 vs 88).
    row1 = row_of([("CREDIT", 0), ("4", 12), ("3", 98)], top=20)
    # Row2: printed totals row — must startswith "TOTAL" and contain "100/40".
    row2 = row_of([("TOTAL", 0), ("100/40", 20)], top=30)

    rows = [row0, row1, row2]
    subjects, expected_total_max, printed_max_sum = parse_header(rows)

    assert subjects is not None
    assert [s["code"] for s in subjects] == ["BTCOC501", "BTCOC502"]
    assert expected_total_max == 200  # from "Total Marks(200)"
    subj_by_code = {s["code"]: s for s in subjects}
    assert subj_by_code["BTCOC501"]["credit"] == 4
    assert subj_by_code["BTCOC502"]["credit"] == 3
    assert printed_max_sum == 100  # "100/40" token -> +100 (before the "/")


def test_parse_header_no_total_marks_header_returns_none():
    rows = [row_of([("NOT", 0), ("A", 20), ("HEADER", 40)], top=10)]
    subjects, expected_total_max, printed_max_sum = parse_header(rows)
    assert subjects is None
    assert expected_total_max == 0
    assert printed_max_sum == 0


def test_parse_header_fix2_skips_subject_code_suffix_row_before_credit():
    # Fix 2: long subject codes (CSE_2 layout) wrap onto a suffix row that does
    # NOT contain "CREDIT". parse_header must skip that row and find CREDIT
    # two rows below the subject row, not one.
    row0 = row_of([("BTCOC501", 10), ("BTCOC502", 100), ("Total", 150), ("Marks(200)", 170)], top=10)
    suffix_row = row_of([("1", 5), ("2C", 50)], top=20)          # no "CREDIT" -> must be skipped
    credit_row = row_of([("CREDIT", 0), ("4", 12), ("3", 98)], top=30)

    rows = [row0, suffix_row, credit_row]
    subjects, expected_total_max, printed_max_sum = parse_header(rows)

    assert subjects is not None
    subj_by_code = {s["code"]: s for s in subjects}
    assert subj_by_code["BTCOC501"]["credit"] == 4
    assert subj_by_code["BTCOC502"]["credit"] == 3


# ---------------------------------------------------------------------------
# parse_single_block: roll_no/name/result parsing, Fix 1 (SGPA scan), grade
# grace-mark stripping (merge fix), marks/sgpa matching, flags.
# ---------------------------------------------------------------------------

# Shared subjects for the block tests: two subjects, well-separated x0s
# (x0 doesn't matter for parse_single_block, only for parse_header, but kept
# consistent for readability). Credits: 4 and 3 -> registered_credits = 7.
SUBJECTS = [
    {"code": "BTCOC501", "x0": 10, "x1": 20, "credit": 4},
    {"code": "BTCOC502", "x0": 100, "x1": 110, "credit": 3},
]


def _pass_block():
    # 7 distinct rows so no index in parse_single_block accidentally aliases
    # another (block[4]=r4, block[-2]=totals_row, block[-1]=grades_row are all
    # different physical rows here since len(block)==7, grade_row_idx=-1).
    r0 = row_of([("1234567890123", 0), ("TEST", 30), ("STUDENT", 60), ("PASS", 120)], top=0)
    # r1: SGPA row with trailing Percentage/ExtTotal columns after SGPA (Fix 1).
    # First float token in [0,10] is "2.00" -> sgpa=2.00. "95.5" and "850" are
    # not picked (95.5 > 10, and 850 has no decimal point so doesn't match the regex).
    r1 = row_of([("2.00", 0), ("95.5", 20), ("850", 40)], top=10)
    r2 = row_of([("unused", 0)], top=20)   # padding, never indexed
    r3 = row_of([("unused", 0)], top=30)   # padding, never indexed
    # r4 = block[4]: total_marks read positionally from first token.
    r4 = row_of([("172", 0), ("x", 20)], top=40)
    # r5 = block[-2] = totals_row: per-subject marks summing to 172, matching r4.
    r5 = row_of([("86", 0), ("86", 50)], top=50)
    # r6 = block[-1] = grades_row: subject0 grade token + a *separate* grace-mark
    # token that must be merged onto the previous token (not pre-joined), to
    # actually exercise the merge logic (not just the strip regex).
    r6 = row_of([("72/A/8", 0), ("(G-5)", 30), ("85/B/6", 60)], top=60)
    return [r0, r1, r2, r3, r4, r5, r6]


def test_parse_single_block_pass_all_flags_clean():
    block = _pass_block()
    res = parse_single_block(block, SUBJECTS, expected_total_max=200, printed_max_sum=100)

    assert res["roll_no"] == "1234567890123"
    assert res["name"] == "TEST STUDENT"
    assert res["result"] == "PASS"
    assert res["is_supply"] is False
    assert res["sgpa"] == 2.00
    assert res["total_marks"] == 172

    # Grace-mark merge + strip: grades_tokens = ["72/A/8(G-5)", "85/B/6"] before
    # strip; grade_str after regex strip drops "(G-5)" leaving "72/A/8".
    subj0, subj1 = res["subjects"]
    assert subj0["code"] == "BTCOC501"
    assert subj0["grade"] == "A"
    assert subj0["grade_point"] == 8.0
    # NOTE: 'raw' re-reads the *unstripped* merged token, not the stripped
    # grade_str used for grade/points -- so the grace suffix is still present.
    assert subj0["raw"] == "72/A/8(G-5)"

    assert subj1["code"] == "BTCOC502"
    assert subj1["grade"] == "B"
    assert subj1["grade_point"] == 6.0
    assert subj1["raw"] == "85/B/6"

    # calc_points = 8.0 + 6.0 = 14.0; registered_credits = 4 + 3 = 7 (no AU);
    # calc_sgpa = round(14.0 / 7, 2) = 2.0 == sgpa (2.00) within 0.05 -> matches.
    assert res["estimated_sgpa_partial_credits"] == 2.0

    # calc_total_marks = 86 + 86 = 172 == total_marks (172) -> marks_match True.
    # gap = 172 - 172 = 0.
    assert res["gap"] == 0
    assert res["flags"] == []
    assert res["passed_all"] is True


def test_parse_single_block_fail_with_no_sgpa_token_fix1():
    # Fix 1: FAIL students print no SGPA token at all on the r1 row. The scan
    # must return None (not misread a percentage/total column as SGPA), and a
    # FAIL row with sgpa=None must NOT be flagged sgpa_mismatch (only the PASS
    # branch requires a present, matching SGPA).
    block = _pass_block()
    # Replace r0's result status with FAIL and r1 with no float in [0.0, 10.0]:
    # "85.5" fails the range check (>10), "850" has no decimal point at all.
    block[0] = row_of([("1234567890123", 0), ("TEST", 30), ("STUDENT", 60), ("FAIL", 120)], top=0)
    block[1] = row_of([("85.5", 0), ("850", 20)], top=10)

    res = parse_single_block(block, SUBJECTS, expected_total_max=200, printed_max_sum=100)

    assert res["result"] == "FAIL"
    assert res["sgpa"] is None
    assert "sgpa_mismatch" not in res["flags"]
    # Marks/grades are otherwise identical to the PASS fixture -> still clean.
    assert res["passed_all"] is True


def test_parse_single_block_sgpa_mismatch_flagged_for_pass():
    # Same block as the clean PASS case but SGPA printed on the sheet (9.00)
    # disagrees with the calculated SGPA (2.0) by more than 0.05 -> must flag.
    block = _pass_block()
    block[1] = row_of([("9.00", 0), ("95.5", 20), ("850", 40)], top=10)

    res = parse_single_block(block, SUBJECTS, expected_total_max=200, printed_max_sum=100)

    assert res["result"] == "PASS"
    assert res["sgpa"] == 9.00
    assert res["estimated_sgpa_partial_credits"] == 2.0
    assert "sgpa_mismatch" in res["flags"]
    assert res["passed_all"] is False


def test_parse_single_block_supply_shifts_grade_row_index():
    # is_supply: block's LAST row contains "Winter -"/"Summer -" -> grade_row_idx
    # becomes -2 (grades are second-to-last row), and totals_row shifts to
    # block[-3] accordingly. Build an 8-row block: the first 7 rows are the
    # same as _pass_block(), with one extra trailing "Winter - 2024" row.
    block = _pass_block()
    trailing = row_of([("Winter", 0), ("-", 30), ("2024", 50)], top=70)
    block.append(trailing)

    res = parse_single_block(block, SUBJECTS, expected_total_max=200, printed_max_sum=100)

    assert res["is_supply"] is True
    # Grades/marks rows are unchanged relative to _pass_block() (just shifted
    # by the trailing row), so the parsed values should match the base PASS case.
    assert res["subjects"][0]["grade"] == "A"
    assert res["subjects"][0]["grade_point"] == 8.0
    assert res["subjects"][1]["grade"] == "B"
    assert res["subjects"][1]["grade_point"] == 6.0
    assert res["total_marks"] == 172
    assert res["gap"] == 0
    assert res["passed_all"] is True


def test_parse_single_block_token_count_mismatch_flagged():
    # Fewer grade tokens than subjects -> token_count_mismatch flag, and the
    # missing subject defaults to grade "FF" / 0 points via the "0/FF/0" fallback.
    block = _pass_block()
    # Only one grade token instead of two.
    block[-1] = row_of([("72/A/8", 0)], top=60)

    res = parse_single_block(block, SUBJECTS, expected_total_max=200, printed_max_sum=100)

    assert "token_count_mismatch" in res["flags"]
    assert res["subjects"][1]["grade"] == "FF"
    assert res["subjects"][1]["grade_point"] == 0.0


def test_parse_single_block_single_row_block_raises_named_valueerror():
    # Regression: a student header row with no following R1..R4 detail rows
    # (a 1-row block) previously hit `block[1]` -> anonymous IndexError, which
    # the caller in parse_tabular_pdf swallows -> the student silently vanished.
    # It must now raise a ValueError naming the roll so the drop is traceable.
    single_row_block = [[word("1234567890123", 0, 0), word("TEST", 0, 20), word("PASS", 0, 40)]]
    with pytest.raises(ValueError, match="1234567890123"):
        parse_single_block(single_row_block, SUBJECTS, expected_total_max=200, printed_max_sum=100)
