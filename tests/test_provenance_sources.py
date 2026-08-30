"""Provenance: the FACT path must report which documents it answered from (Phase-B T2.1).

VectorSearch has always returned per-hit metadata; _fact_context was the one place it
got dropped, so no surface could cite anything while the GLOBAL prompt simultaneously
ordered the model to write a citations section it had no material for.

Two properties are load-bearing here and are asserted below:
  * sources land in metadata["sources"], NOT in the context string. 15 of 120 stress
    golds pass on source-label text alone, so labels in context would inflate every
    substring score measured afterwards.
  * _fact_context keeps its single-argument signature — three fallback tests monkeypatch
    it with a one-arg lambda, and shipping citations must not break the TABULAR fallback.
"""
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config
import retrieval.router as router_mod


class _FakeVS:
    """Two hits from two documents, plus a duplicate of the first source."""

    def search(self, query, top_k=5):
        return [
            {"content": "B.Tech requires 168 credits.",
             "metadata": {"source": "01_academic_regulations.md", "Header 2": "Credits"}},
            {"content": "Hostel fee is Rs 45,000.",
             "metadata": {"source": "02_fee_structure.md", "Header 2": "Hostel"}},
            {"content": "A minimum CGPA of 6.0 applies.",
             "metadata": {"source": "01_academic_regulations.md", "Header 2": "Credits"}},
        ]


@pytest.fixture
def router(monkeypatch):
    monkeypatch.setattr(router_mod, "VectorSearch", lambda tid: _FakeVS())
    monkeypatch.setattr(router_mod, "GraphSearch", lambda tid: object())
    monkeypatch.setattr(router_mod, "CommunitySearch", lambda tid: object())
    return router_mod.QueryRouter("t")


@pytest.mark.asyncio
async def test_fact_route_reports_sources(router, monkeypatch):
    monkeypatch.setattr(router, "classify_query", AsyncMock(return_value=("FACT", None)))

    qtype, context, metadata = await router.route_query("How many credits?")

    assert qtype == "FACT"
    sources = metadata["sources"]
    assert [s["source"] for s in sources] == [
        "01_academic_regulations.md", "02_fee_structure.md"
    ], "duplicate sources must collapse, order follows retrieval rank"
    assert sources[0]["section"] == "Credits"


@pytest.mark.asyncio
async def test_source_labels_never_enter_the_context_string(router, monkeypatch):
    """The scorer is substring-based; labels in context would be free gold tokens."""
    monkeypatch.setattr(router, "classify_query", AsyncMock(return_value=("FACT", None)))

    _, context, _ = await router.route_query("How many credits?")

    assert "01_academic_regulations.md" not in context
    assert "Credits" not in context
    assert context == (
        "B.Tech requires 168 credits.\n"
        "Hostel fee is Rs 45,000.\n"
        "A minimum CGPA of 6.0 applies."
    )


def test_fact_context_stays_single_argument(router):
    """Three fallback tests monkeypatch this with `lambda q: ...` — keep it callable so."""
    assert router._fact_context("How many credits?").startswith("B.Tech requires")


@pytest.mark.asyncio
async def test_summary_path_reports_no_sources(router, monkeypatch):
    """Community summaries are generated from bare entity names and carry no source at all.
    The absence of a sources key is the honest signal, and is why the GLOBAL prompt no
    longer asks the model to write a citations section over them."""
    monkeypatch.setattr(config, "GLOBAL_CHUNK_FANOUT", False)
    monkeypatch.setattr(router, "classify_query", AsyncMock(return_value=("GLOBAL", None)))
    router.cs = SimpleNamespace(get_all_summaries=lambda: "Community 0:\nsomething")

    _, _, metadata = await router.route_query("Summarise the themes.")

    assert "sources" not in metadata


@pytest.mark.asyncio
async def test_global_chunk_fanout_carries_real_provenance(router, monkeypatch):
    """With the fan-out on (the default since it measured 82.5% against 36.8%), GLOBAL
    answers are built from real chunks — so unlike the summary path they can actually be
    cited, and the citations section deleted from the prompt could be earned back."""
    monkeypatch.setattr(config, "GLOBAL_CHUNK_FANOUT", True)
    monkeypatch.setattr(router, "classify_query", AsyncMock(return_value=("GLOBAL", None)))

    _, context, metadata = await router.route_query("Summarise the themes.")

    assert [s["source"] for s in metadata["sources"]] == [
        "01_academic_regulations.md", "02_fee_structure.md"
    ]
    assert "01_academic_regulations.md" not in context, "labels must stay out of context"
