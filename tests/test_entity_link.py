"""Tests for LOCAL-route question-side entity linking (retrieval/entity_link).

Pins the three things that were silently broken in the old LOCAL path:
  1. the linked entity comes from the QUESTION text, not the top hit's filename;
  2. a known entity matches its graph node;
  3. a matched node returns a non-empty neighborhood.
Hermetic tests use an explicit node list; the last checks the real graph.
"""
import sys
from pathlib import Path

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config
from retrieval.entity_link import link_entities

NODES = [
    "Rohan Gaikwad", "YSPM's Yashoda Technical Campus", "RAG-MicroSim", "RAG",
    "Satara", "trust", "college", "High-frequency trading", "Amit Lokhande",
]


def test_links_entity_from_question_not_filename():
    matched, _ = link_entities(
        "Who are the authors of the RAG-MicroSim paper?",
        NODES + ["Final  Resarch paper.md"],
    )
    assert "RAG-MicroSim" in matched
    assert "Final  Resarch paper.md" not in matched  # never link the source filename


def test_prefer_longer_dedup_drops_bare_substring():
    matched, _ = link_entities("Tell me about the RAG-MicroSim framework", NODES)
    assert "RAG-MicroSim" in matched
    assert "RAG" not in matched


def test_multiword_entity_subset_links():
    # question names a subset of the full node name -> still links
    matched, _ = link_entities("In which city is Yashoda Technical Campus located?", NODES)
    assert "YSPM's Yashoda Technical Campus" in matched


def test_lowercase_common_noun_does_not_match_junk_node():
    # 'trust'/'college' are lowercase common nouns here and generic graph junk
    matched, _ = link_entities("Which trust runs the college?", NODES)
    assert "trust" not in matched and "college" not in matched


def test_known_multiword_entity_matches():
    matched, _ = link_entities("What institution is Rohan Gaikwad affiliated with?", NODES)
    assert "Rohan Gaikwad" in matched


def test_below_confidence_returns_empty_not_nearest_node():
    # No node in this list genuinely matches the question. The linker must
    # return EMPTY rather than the nearest (wrong) node — a confident wrong
    # match fetches a wrong neighborhood silently.
    nodes = ["Flash Crash", "Market Microstructure", "Anomaly Detection"]
    matched, scores = link_entities("Who are the authors of the paper?", nodes)
    assert matched == []
    assert scores == {}


def test_confident_match_still_links_above_threshold():
    # A genuine exact match must survive the confidence gate.
    matched, _ = link_entities("What institution is Rohan Gaikwad affiliated with?", NODES)
    assert "Rohan Gaikwad" in matched


# ── Real graph (skip if absent) ───────────────────────────────────────────────

_GRAPH = config.tenant_dir("tenant_1") / "graph" / "company_brain.graphml"
_real = pytest.mark.skipif(not _GRAPH.exists(), reason="tenant_1 graph not present")


@_real
def test_real_entity_matches_and_neighborhood_nonempty():
    from retrieval.graph_traverse import GraphSearch
    gs = GraphSearch("tenant_1")
    nodes = list(gs.G.nodes())
    matched, _ = link_entities("What institution is Rohan Gaikwad affiliated with?", nodes)
    assert "Rohan Gaikwad" in matched
    assert gs.get_neighborhood("Rohan Gaikwad", hops=2)  # non-empty neighborhood
