"""The repaired stresskit gold derivation (tests/eval/derive_gold_v2.py).

These tests exist because the derivation decides what "correct" means. A scorer bug is
not a small bug: the original derivation marked a factually perfect GLOBAL answer FAIL
because it wrote "Computer Science and Engineering" where the human gold wrote "CSE", and
it turned "Rs. 2,96,35,000" into the four anchors 2/96/35/000 — two of which are free in
any numeric answer while the actual figure was required nowhere.

The rules under test are deliberately mechanical, so that an auditor can regenerate the
gold file from the kit plus the corpus and get byte-identical output.
"""
import json
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.derive_gold_v2 import (  # noqa: E402
    build_alias_map, corpus_forms, derive_gold_v2, strip_group_commas,
)
from tests.eval.score_answers import score_v2, score_v2_bonus  # noqa: E402

CORPUS_DIR = PROJECT_ROOT / "data" / "tenants" / "tenant_stress" / "parsed"


# ── R1: digit-group normalisation ────────────────────────────────────────────

def test_indian_digit_grouping_normalises_to_one_token():
    assert strip_group_commas("Rs. 1,42,000") == "Rs. 142000"
    assert strip_group_commas("2,96,35,000") == "29635000"


def test_normalisation_leaves_non_digit_commas_alone():
    assert strip_group_commas("Rane, Vasant") == "Rane, Vasant"
    assert strip_group_commas("A, 1, B") == "A, 1, B"


# ── R2: aliases are derived from the corpus, never typed ─────────────────────

def test_alias_map_derives_acronyms_from_phrase_initials():
    aliases = build_alias_map(
        "The Department of Computer Science and Engineering is large.\n"
        "Processed under the Digital Personal Data Protection Act, 2023.\n"
    )
    assert "Computer Science and Engineering" in aliases["CSE"]
    # prefix match: the phrase abbreviates to DPDPA, and DPDP is what the human wrote
    assert any("Digital Personal Data Protection" in p for p in aliases["DPDP"])


def test_alias_phrases_never_span_a_paragraph_break():
    """With \\s instead of [^\\S\\n] this invents "Credit Structure\\n\\nEvery" -> CSE."""
    aliases = build_alias_map("Credit Structure\n\nEvery student must register.\n")
    assert not any("\n" in phrase for phrases in aliases.values() for phrase in phrases)
    assert "CSE" not in aliases


def test_both_surfaces_are_accepted_for_an_aliased_anchor():
    corpus = "Department of Computer Science and Engineering, headed by Dr. Meera Joshi."
    provable, forms = corpus_forms("CSE", corpus, corpus, build_alias_map(corpus))
    assert provable
    assert forms[0] == "CSE", "an answer that writes the acronym must still count"
    assert "Computer Science and Engineering" in forms


# ── R3: required vs bonus ────────────────────────────────────────────────────

def _derive(ans, corpus):
    return derive_gold_v2(ans, corpus, strip_group_commas(corpus), build_alias_map(corpus))


def test_quotable_figures_are_required():
    gold = _derive("CSE is best (94.1% pass).",
                   "| Computer Science and Engineering | 118 | 94.1 | 44.0 |")
    flat = [f for group in gold["required"] for f in group]
    assert "94.1" in flat
    assert gold["bonus"] == []


def test_a_figure_the_human_computed_becomes_bonus_not_a_required_anchor():
    """2,96,35,000 is a SUM. Requiring it conjoined with quotable anchors fails the whole
    question for an answer that retrieved every line correctly but did not add them up."""
    corpus = "AMC 18,00,000 per year. HPC 26,00,000 per year. Housekeeping 41,00,000."
    gold = _derive("Total 2,96,35,000 across 18,00,000 and 26,00,000 contracts.", corpus)
    assert "29635000" in gold["bonus"]
    flat = [f for group in gold["required"] for f in group]
    assert "1800000" in flat and "2600000" in flat


def test_bonus_anchors_are_still_scored_just_separately():
    gold = {"mode": "anchors", "required": [], "bonus": ["29635000"]}
    assert score_v2_bonus("The total is 2,96,35,000.", gold) is True
    assert score_v2_bonus("I could not compute a total.", gold) is False
    assert score_v2_bonus("anything", {"mode": "anchors", "required": [], "bonus": []}) is None


# ── the scorer ───────────────────────────────────────────────────────────────

def test_answer_passes_whichever_surface_it_uses():
    gold = {"mode": "anchors",
            "required": [["CSE", "Computer Science and Engineering"]], "bonus": []}
    assert score_v2("CSE performs best.", gold)
    assert score_v2("Computer Science and Engineering performs best.", gold)
    assert not score_v2("Civil performs best.", gold)


def test_scorer_is_blind_to_digit_grouping_in_the_answer():
    gold = {"mode": "anchors", "required": [["142000"]], "bonus": []}
    assert score_v2("The first-year total is Rs. 1,42,000.", gold)
    assert score_v2("The first-year total is Rs. 142000.", gold)


def test_short_anchors_match_on_word_boundaries():
    """'AI' must stop matching the 'ai' inside chair/maintain/available/said."""
    gold = {"mode": "anchors", "required": [["AI"]], "bonus": []}
    assert not score_v2("The chair remains available, he said.", gold)
    assert score_v2("The AI department was created in 2021.", gold)


def test_insufficient_items_are_untouched_by_v2():
    gold = {"mode": "insufficient", "expect": []}
    assert score_v2("I don't have enough information to answer that.", gold)
    assert not score_v2("The PhD tuition fee is Rs 50,000.", gold)


# ── the real files ───────────────────────────────────────────────────────────

@pytest.mark.skipif(not CORPUS_DIR.exists(), reason="stress corpus not ingested")
def test_the_acronyms_that_broke_v1_are_genuinely_absent_from_the_corpus():
    corpus = "\n".join(p.read_text(encoding="utf-8", errors="replace")
                       for p in sorted(CORPUS_DIR.glob("*.md")))
    for acronym in ("CSE", "DPDP"):
        assert acronym not in corpus, (
            f"{acronym} now appears in the corpus; the alias rule needs rechecking"
        )
    assert "Computer Science and Engineering" in corpus
    assert "Digital Personal Data Protection Act" in corpus


def test_v1_gold_file_is_untouched_and_remains_the_decision_variable():
    v1 = json.loads((PROJECT_ROOT / "tests" / "eval" / "golden_stress.json")
                    .read_text(encoding="utf-8"))
    modes = {q["gold"]["mode"] for q in v1["questions"]}
    assert modes <= {"contains", "contains_any", "insufficient"}
    assert "anchors" not in modes, "v2 must be a separate file, never an edit of v1"
    assert v1["version"] == "stress-1"
