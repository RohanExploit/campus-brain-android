"""
P3.13 — end-to-end SQL-route tests for the analytical queries, especially the
previously-failing "students who failed at least 4 subjects".

Runs against the real tenant_1 analytics data (built read-only from tabular.duckdb).
Skips cleanly if that data is absent. Proves the answer is correct AND that it is
produced deterministically WITHOUT any Ollama call.
"""
import sys
from pathlib import Path
from unittest.mock import AsyncMock

import pytest

sys.path.append(str(Path(__file__).resolve().parent.parent))
import config
from retrieval import sql_templates

_SRC = config.tenant_dir("tenant_1") / "tabular.duckdb"
pytestmark = pytest.mark.skipif(not _SRC.exists(), reason="tenant_1 tabular.duckdb not present")


# ---- template-level (direct) ----

# NOTE: counts reflect the corrected grade classification — only 'FF' is a
# failure. 'AB' (8.5) is a pass. Before that fix these read 10 and 77, inflated
# by ~243 AB rows wrongly counted as fails.
def test_template_failed_at_least_4():
    out = sql_templates.students_failed_at_least(4, tenant_id="tenant_1")
    assert "Found 7 students" in out["answer"]
    assert "JAGTAP ANANT TANAJI" in out["answer"]
    assert "SHELKE MANISH SURESH" in out["answer"]
    assert out["template"] == "students_failed_at_least"


def test_template_failed_at_least_2():
    out = sql_templates.students_failed_at_least(2, tenant_id="tenant_1")
    assert "Found 16 students" in out["answer"]


def test_template_failed_most():
    out = sql_templates.students_failed_most(tenant_id="tenant_1")
    assert "max = 5" in out["answer"]
    assert "JAGTAP ANANT TANAJI" in out["answer"]


# ---- router end-to-end (deterministic, no Ollama) ----

@pytest.fixture
def router(monkeypatch):
    import retrieval.router as router_mod
    monkeypatch.setattr(router_mod, "VectorSearch", lambda tid: object())
    monkeypatch.setattr(router_mod, "GraphSearch", lambda tid: object())
    monkeypatch.setattr(router_mod, "CommunitySearch", lambda tid: object())
    # if any Ollama call happens, fail loudly — the SQL route must be LLM-free
    monkeypatch.setattr(
        router_mod._http_client, "post",
        AsyncMock(side_effect=AssertionError("SQL analytical route must not call Ollama")),
    )
    return router_mod.QueryRouter("tenant_1")


@pytest.mark.asyncio
async def test_router_failed_at_least_4_end_to_end(router):
    qtype, context, metadata = await router.route_query(
        "give me a list of students who failed at least 4 subjects"
    )
    assert qtype == "TABULAR"
    assert metadata.get("template") == "students_failed_at_least"
    assert "Found 7 students" in context
    assert "JAGTAP ANANT TANAJI" in context and "SHELKE MANISH SURESH" in context


@pytest.mark.asyncio
async def test_router_failed_at_least_2_end_to_end(router):
    qtype, context, metadata = await router.route_query("students who failed at least 2 subjects")
    assert qtype == "TABULAR"
    assert "Found 16 students" in context


@pytest.mark.asyncio
async def test_router_failed_most_end_to_end(router):
    qtype, context, metadata = await router.route_query("which student failed the most subjects?")
    assert qtype == "TABULAR"
    assert metadata.get("template") == "students_failed_most"
    assert "max = 5" in context
