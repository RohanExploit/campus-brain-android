"""
Hermetic API tests — no Ollama / FAISS / model loads.

The query path is exercised by monkeypatching get_router + generate_answer with
fakes, so we test the endpoint's real logic (validation, routing, formatting)
without external services. Assertions are concrete, not "200 or 500".
"""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parent.parent))
import api.main as main

client = TestClient(main.app)


class _FakeRouter:
    def __init__(self, qtype="VECTOR", context="Paris is the capital of France.", metadata=None):
        self._qtype, self._context = qtype, context
        self._metadata = metadata or {"debug_sql": None}

    async def route_query(self, query, role=None):
        return self._qtype, self._context, self._metadata


def test_health():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_query_invalid_tenant_id_is_400():
    # traversal / malformed tenant_id must be rejected before any data load
    r = client.post("/query", json={"query": "hi", "tenant_id": "../../tenant_2"})
    assert r.status_code == 400
    assert "tenant" in r.json()["detail"].lower()


def test_query_happy_path(monkeypatch):
    monkeypatch.setattr(main, "get_router", lambda tid: _FakeRouter())

    async def _fake_answer(query, context, qtype):
        return "The capital is Paris."

    monkeypatch.setattr(main, "generate_answer", _fake_answer)

    r = client.post("/query", json={"query": "capital of France?", "tenant_id": "tenant_1"})
    assert r.status_code == 200
    body = r.json()
    assert body["query_type"] == "VECTOR"
    assert body["answer"] == "The capital is Paris."
    assert "Paris" in body["context_used"]


def test_query_empty_context_returns_no_info_message(monkeypatch):
    monkeypatch.setattr(main, "get_router", lambda tid: _FakeRouter(context=""))

    async def _fake_answer(query, context, qtype):  # should NOT be called on empty context
        raise AssertionError("generate_answer must not run when context is empty")

    monkeypatch.setattr(main, "generate_answer", _fake_answer)

    r = client.post("/query", json={"query": "unknown?", "tenant_id": "tenant_1"})
    assert r.status_code == 200
    assert r.json()["answer"] == "I don't have enough information to answer that."


def test_query_student_record_short_circuits_generation(monkeypatch):
    record = "🎓 **Student Record for Jane Doe**\nSGPA: 9.1"
    monkeypatch.setattr(main, "get_router", lambda tid: _FakeRouter(qtype="TABULAR", context=record))

    async def _fake_answer(query, context, qtype):
        raise AssertionError("generate_answer must not run for pre-formatted student records")

    monkeypatch.setattr(main, "generate_answer", _fake_answer)

    r = client.post("/query", json={"query": "record of Jane", "tenant_id": "tenant_1"})
    assert r.status_code == 200
    assert r.json()["answer"] == record


def test_query_tabular_answer_short_circuits_generation(monkeypatch):
    # Any TABULAR context (SQL template output, markdown result tables, etc.) is
    # already a finished deterministic answer and must never be re-paraphrased
    # by the LLM, which could corrupt exact figures.
    tabular_answer = "Pass percentage: 82.5% (100 of 121 students passed)."
    monkeypatch.setattr(main, "get_router", lambda tid: _FakeRouter(qtype="TABULAR", context=tabular_answer))

    async def _fake_answer(query, context, qtype):
        raise AssertionError("generate_answer must not run for TABULAR answers")

    monkeypatch.setattr(main, "generate_answer", _fake_answer)

    r = client.post("/query", json={"query": "what is the pass percentage?", "tenant_id": "tenant_1"})
    assert r.status_code == 200
    body = r.json()
    assert body["answer"] == tabular_answer
    assert body["context_used"] == tabular_answer
