"""Structural invariants of the world-model benchmark (tests/eval/bench/).

A benchmark degrades quietly. Someone enriches the corpus, and a document that now
mentions both halves of a two-hop question turns that question into a lookup — the score
goes UP and the slice silently stops measuring what it claims to. That is not hypothetical:
adding department profiles collapsed eleven LOCAL hops the first time, and only the
validator caught it.

So the properties the benchmark depends on are asserted here, and run with the suite.
"""
import json
import sys
from pathlib import Path

import pytest

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.bench import render_corpus, validate_bench  # noqa: E402
from tests.eval.bench.world import (  # noqa: E402
    COMMITTEES, DEPARTMENTS, HOSTELS, LABS, PROJECTS, rupees,
)

CORPUS = PROJECT_ROOT / "Dataset" / "bench_v1" / "corpus"
GOLD = PROJECT_ROOT / "tests" / "eval" / "golden_bench.json"

# The rendered corpus lives under Dataset/, which is gitignored because it is a
# generated artifact rather than source. The two tests below read the rendered
# .md files, so on a clean checkout they have nothing to read and previously
# failed with an empty-list assertion that looked like a real regression. They
# skip instead. Regenerate the corpus locally with tests/eval/bench/render_corpus.py.
requires_rendered_corpus = pytest.mark.skipif(
    not CORPUS.exists(),
    reason="Dataset/bench_v1/corpus is gitignored; render it with tests/eval/bench/render_corpus.py",
)


# ── the world model ──────────────────────────────────────────────────────────

def test_no_head_of_department_is_also_a_lab_custodian():
    """A custodian who is an HOD would put the answer to "who heads the department that
    runs lab X" inside the infrastructure register, for an unrelated reason — the hop
    could then be skipped by luck rather than answered."""
    hods = {d[3] for d in DEPARTMENTS}
    custodians = {lab[6] for lab in LABS}
    assert not (hods & custodians), f"custodians who are HODs: {hods & custodians}"


def test_every_lab_belongs_to_a_real_department():
    codes = {d[1] for d in DEPARTMENTS}
    assert all(lab[1] in codes for lab in LABS)


def test_committee_chairs_and_project_leads_are_known_people():
    people = {d[3] for d in DEPARTMENTS} | {lab[6] for lab in LABS} | {h[3] for h in HOSTELS}
    people |= {"Dr. Nandini Apte", "Shri Prakash Deshmukh"}
    for name, chair, *_ in COMMITTEES:
        assert chair in people, f"{name} chaired by unknown person {chair}"
    for title, pi, *_ in PROJECTS:
        assert pi in people, f"{title} led by unknown person {pi}"


def test_department_codes_and_names_are_unique():
    assert len({d[1] for d in DEPARTMENTS}) == len(DEPARTMENTS)
    assert len({d[0] for d in DEPARTMENTS}) == len(DEPARTMENTS)


def test_indian_digit_grouping():
    assert rupees(142000) == "1,42,000"
    assert rupees(29635000) == "2,96,35,000"
    assert rupees(750) == "750"


# ── the rendered corpus ──────────────────────────────────────────────────────

def test_department_profiles_do_not_name_their_laboratories():
    """The single line that would collapse every lab-based hop into a one-document lookup."""
    for code in [d[1] for d in DEPARTMENTS]:
        text = render_corpus.doc_department_profile(code)
        for lab in [x for x in LABS if x[1] == code]:
            assert lab[0] not in text, (
                f"{code} profile names {lab[0]!r}; the infrastructure register must stay "
                "the only place a laboratory maps to a department"
            )


@requires_rendered_corpus
def test_lab_to_department_mapping_lives_in_exactly_one_document():
    docs = {p.name: p.read_text(encoding="utf-8") for p in CORPUS.glob("*.md")}
    for lab, code, *_ in LABS:
        dept = next(d[0] for d in DEPARTMENTS if d[1] == code)
        both = [n for n, t in docs.items() if lab in t and dept in t]
        assert both == ["04_infrastructure_register.md"], (
            f"{lab} + {dept} co-occur in {both}"
        )


@requires_rendered_corpus
def test_corpus_is_large_enough_that_top_k_is_not_the_whole_corpus():
    """At 27 chunks a k=10 retrieval returned a third of everything and precision was
    untestable. Keep the corpus above the size where retrieval has to choose."""
    total = sum(len(p.read_text(encoding="utf-8")) for p in CORPUS.glob("*.md"))
    assert len(list(CORPUS.glob("*.md"))) >= 25
    assert total >= 20000, f"corpus is only {total} chars"


# ── the generated question set ───────────────────────────────────────────────

def test_gold_file_matches_the_kit_and_has_the_expected_slices():
    spec = json.loads(GOLD.read_text(encoding="utf-8"))
    counts = {}
    for q in spec["questions"]:
        counts[q["route"]] = counts.get(q["route"], 0) + 1
    assert spec["tenant_id"] == "tenant_bench"
    # large enough that the pre-registered McNemar table can resolve a real effect
    assert counts["GLOBAL"] >= 50
    assert counts["LOCAL"] >= 50
    assert counts["FACT"] >= 90


def test_no_duplicate_ids_or_questions():
    qs = json.loads(GOLD.read_text(encoding="utf-8"))["questions"]
    assert len({q["id"] for q in qs}) == len(qs)
    assert len({q["query"] for q in qs}) == len(qs)


def test_derived_figures_are_bonus_never_required():
    """A figure the documents never state must not be conjoined with quotable anchors —
    otherwise an answer that retrieved every line correctly fails for not doing arithmetic."""
    qs = json.loads(GOLD.read_text(encoding="utf-8"))["questions"]
    with_bonus = [q for q in qs if q["gold"].get("bonus")]
    assert with_bonus, "the arithmetic sub-metric has disappeared"
    for q in with_bonus:
        flat = [f for grp in q["gold"]["required"] for f in grp]
        for b in q["gold"]["bonus"]:
            assert b not in flat


@pytest.mark.skipif(not GOLD.exists(), reason="bench not generated")
def test_full_validator_passes():
    """The same gate that runs before a measurement, run with the suite."""
    assert validate_bench.main() == 0
