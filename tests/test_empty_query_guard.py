"""An empty query must not return a student roster.

Found during demo rehearsal: POSTing {"query": ""} to /query classified TABULAR — no
keyword matched a FACT/LOCAL/GLOBAL pattern, and the aggregate branch is the fallthrough —
and answered with an unfiltered name table. Roughly seventy real student names came back
for pressing Enter on an empty box, with no query at all behind it.

The dashboard disables its submit button while the box is empty, so this was only
reachable by whitespace, by paste, or by calling the API directly. That is not much of a
defence: the roster is the most sensitive thing this system returns, and the input that
produced it was nothing.

Rejected at the API boundary now, before routing or retrieval runs.
"""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))


def _client(monkeypatch):
    """App with routing stubbed — this tests the guard, not retrieval."""
    import api.main as main

    class _FakeRouter:
        async def route_query(self, query, role=None, force_route=None):
            return "TABULAR", "| name |\n|---|\n| A REAL STUDENT |", {}

    monkeypatch.setattr(main, "get_router", lambda tid: _FakeRouter())
    return TestClient(main.app)


def test_empty_query_is_rejected(monkeypatch):
    r = _client(monkeypatch).post("/query", json={"query": "", "tenant_id": "tenant_1"})
    assert r.status_code == 400, f"empty query returned {r.status_code}: {r.text[:200]}"
    assert "STUDENT" not in r.text.upper()


def test_whitespace_only_query_is_rejected(monkeypatch):
    """The case the UI's disabled-button guard does not cover."""
    c = _client(monkeypatch)
    for blank in (" ", "   ", "\t", "\n", " \t\n "):
        r = c.post("/query", json={"query": blank, "tenant_id": "tenant_1"})
        assert r.status_code == 400, f"{blank!r} returned {r.status_code}"
        assert "STUDENT" not in r.text.upper(), f"{blank!r} leaked roster content"


def test_a_real_query_still_works(monkeypatch):
    """The guard must reject nothing else."""
    r = _client(monkeypatch).post(
        "/query", json={"query": "how many students failed?", "tenant_id": "tenant_1"})
    assert r.status_code == 200
    assert r.json()["query_type"] == "TABULAR"


def test_guard_runs_before_any_retrieval(monkeypatch):
    """Rejection must not touch the router — no tenant load, no index, no model."""
    import api.main as main
    called = []
    monkeypatch.setattr(main, "get_router",
                        lambda tid: called.append(tid) or (_ for _ in ()).throw(
                            AssertionError("router built for an empty query")))
    r = TestClient(main.app).post("/query", json={"query": "  ", "tenant_id": "tenant_1"})
    assert r.status_code == 400
    assert called == []
