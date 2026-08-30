"""
Security regression: the optional X-API-Key gate.

The whole admin API is unauthenticated by default (frictionless localhost dev).
When REQUIRE_API_KEY is set, every endpoint except /health must present a
matching X-API-Key header. start.py's docstring already promises this gate;
these tests pin the behavior.

Uses TestClient WITHOUT its context manager so the app lifespan (Ollama/model
warmup) never runs — the gate is pure request-path logic.
"""
import sys
from pathlib import Path

from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parent.parent))

import api.main as main

client = TestClient(main.app)


def test_gate_off_by_default(monkeypatch):
    monkeypatch.delenv("REQUIRE_API_KEY", raising=False)
    # No key needed → normal 200.
    assert client.get("/tenants").status_code == 200


def test_gate_on_rejects_missing_key(monkeypatch):
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", "s3cret")
    r = client.get("/tenants")
    assert r.status_code == 401
    assert "X-API-Key" in r.json()["detail"]


def test_gate_on_rejects_wrong_key(monkeypatch):
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", "s3cret")
    r = client.get("/tenants", headers={"X-API-Key": "wrong"})
    assert r.status_code == 401


def test_gate_on_accepts_correct_key(monkeypatch):
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", "s3cret")
    r = client.get("/tenants", headers={"X-API-Key": "s3cret"})
    assert r.status_code == 200


def test_health_is_always_open(monkeypatch):
    """Liveness probe must not require a key even when the gate is on."""
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", "s3cret")
    assert client.get("/health").status_code == 200


def test_gate_on_but_no_key_configured_fails_closed(monkeypatch):
    """REQUIRE_API_KEY set with an empty API_KEY is a misconfig → 500, never open."""
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.delenv("API_KEY", raising=False)
    r = client.get("/tenants", headers={"X-API-Key": "anything"})
    assert r.status_code == 500


def test_audit_router_is_also_gated(monkeypatch):
    """App-level dependency must cover included routers, not just main.py routes."""
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", "s3cret")
    # Any audit endpoint; unauth must 401 before hitting handler logic.
    r = client.get("/audit/status")
    assert r.status_code == 401
