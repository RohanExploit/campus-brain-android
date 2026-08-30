"""
Security regression: per-tenant API-key scoping (RBAC).

Two principal roles: admin (full access, sees all tenants — what the
dashboard uses; the env API_KEY resolves to this) and tenant (a key bound to
one tenant_id via the optional auth/api_keys.json store; may only touch that
tenant). See auth/api_keys.py and the `authenticate` dependency in
api/main.py.

Uses TestClient WITHOUT its context manager so the app lifespan (Ollama/model
warmup) never runs — mirrors tests/test_api_auth.py.
"""
import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

sys.path.append(str(Path(__file__).resolve().parent.parent))

import api.main as main
from auth import api_keys as api_keys_module

client = TestClient(main.app)

ADMIN_KEY = "admin-s3cret"
TENANT1_KEY = "tenant1-s3cret"


@pytest.fixture
def rbac_env(tmp_path, monkeypatch):
    """REQUIRE_API_KEY on, env API_KEY as admin key, temp api_keys.json with a
    tenant_1-scoped key. Monkeypatches the module _keys_path so the loader
    reads our temp file instead of the real auth/api_keys.json.

    Also monkeypatches main.DATA_ROOT to a tmp_path tree containing an empty
    tenant_1/ directory, so /tenants has something to discover hermetically —
    real data/tenants/ is gitignored and absent in CI. /tenants degrades
    gracefully (doc/student counts default to 0) when raw/manifest.db/
    tabular.duckdb are missing, so an empty dir is sufficient here."""
    monkeypatch.setenv("REQUIRE_API_KEY", "1")
    monkeypatch.setenv("API_KEY", ADMIN_KEY)

    keys_file = tmp_path / "api_keys.json"
    keys_file.write_text(json.dumps([
        {"key": TENANT1_KEY, "role": "tenant", "tenant_id": "tenant_1"},
    ]))
    monkeypatch.setattr(api_keys_module, "_keys_path", lambda: keys_file)

    fake_data_root = tmp_path / "data" / "tenants"
    (fake_data_root / "tenant_1").mkdir(parents=True)
    monkeypatch.setattr(main, "DATA_ROOT", fake_data_root)

    return keys_file


def test_admin_key_sees_all_tenants(rbac_env):
    r = client.get("/tenants", headers={"X-API-Key": ADMIN_KEY})
    assert r.status_code == 200
    tenants = r.json()["tenants"]
    assert len(tenants) >= 1


def test_admin_key_admin_status_ok(rbac_env):
    r = client.get("/admin/status", headers={"X-API-Key": ADMIN_KEY})
    assert r.status_code == 200


def test_tenant_key_sees_only_own_tenant(rbac_env):
    r = client.get("/tenants", headers={"X-API-Key": TENANT1_KEY})
    assert r.status_code == 200
    tenants = r.json()["tenants"]
    # Every returned entry (if any) must be tenant_1 — no other tenant id leaks.
    for t in tenants:
        assert t["id"] == "tenant_1"


def test_tenant_key_query_own_tenant_not_forbidden(rbac_env):
    r = client.post(
        "/query",
        json={"query": "hello", "tenant_id": "tenant_1"},
        headers={"X-API-Key": TENANT1_KEY},
    )
    # May be 200 or 500 depending on whether tenant data/models are available
    # in this environment — the point is it must NOT be blocked by RBAC (403).
    assert r.status_code != 403


def test_tenant_key_query_other_tenant_forbidden(rbac_env):
    r = client.post(
        "/query",
        json={"query": "hello", "tenant_id": "tenant_2"},
        headers={"X-API-Key": TENANT1_KEY},
    )
    assert r.status_code == 403


def test_tenant_key_admin_status_forbidden(rbac_env):
    r = client.get("/admin/status", headers={"X-API-Key": TENANT1_KEY})
    assert r.status_code == 403


def test_tenant_key_documents_other_tenant_forbidden(rbac_env):
    r = client.get("/documents", params={"tenant_id": "tenant_2"}, headers={"X-API-Key": TENANT1_KEY})
    assert r.status_code == 403


def test_unknown_key_is_rejected(rbac_env):
    r = client.get("/tenants", headers={"X-API-Key": "not-a-real-key"})
    assert r.status_code == 401


def test_tenant_key_audit_forbidden(rbac_env):
    # The audit suite runs cross-tenant integrity checks, so it is admin-only.
    r = client.get("/audit/status", headers={"X-API-Key": TENANT1_KEY})
    assert r.status_code == 403


def test_admin_key_audit_not_forbidden(rbac_env):
    # Admin may reach audit (200/500 depending on data — just not RBAC-blocked).
    r = client.get("/audit/status", headers={"X-API-Key": ADMIN_KEY})
    assert r.status_code != 403
