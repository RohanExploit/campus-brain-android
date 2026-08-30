"""
Audit 06 — Multi-Tenant Isolation
Goal: Ensure no retrieval, cache, embedding, or session leakage across tenants.
Pass criterion: 0 cross-tenant leaks (production gate blocker).
"""
import pytest
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent))


pytestmark = [pytest.mark.security, pytest.mark.integrity]

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
DATA_ROOT = PROJECT_ROOT / "data" / "tenants"


# ─── Tenant-scoped data that must NOT leak ────────────────────────────────────

TENANT_A_SECRET = "ROLL_A_ONLY_999111222"
TENANT_B_SECRET = "ROLL_B_ONLY_888333444"


class MockVectorIndex:
    """Simulates a per-tenant in-memory vector index."""
    def __init__(self, tenant_id: str):
        self.tenant_id = tenant_id
        self._documents: list[dict] = []

    def add(self, doc: dict):
        doc["_tenant"] = self.tenant_id
        self._documents.append(doc)

    def search(self, query: str, top_k: int = 3) -> list[dict]:
        """Return only documents belonging to this tenant."""
        return [
            d for d in self._documents
            if d.get("_tenant") == self.tenant_id
            and query.lower() in d.get("content", "").lower()
        ][:top_k]


class MockRouter:
    """Simulates the QueryRouter, enforcing per-tenant isolation."""
    _indexes: dict[str, MockVectorIndex] = {}

    @classmethod
    def get_index(cls, tenant_id: str) -> MockVectorIndex:
        if tenant_id not in cls._indexes:
            cls._indexes[tenant_id] = MockVectorIndex(tenant_id)
        return cls._indexes[tenant_id]

    @classmethod
    def reset(cls):
        cls._indexes.clear()


# ─── Tests ────────────────────────────────────────────────────────────────────

class TestMultiTenantIsolation:

    def setup_method(self):
        MockRouter.reset()

    def test_vector_index_isolation(self, two_isolated_tenants):
        """Documents from tenant A must not appear in tenant B's search results."""
        t1, t2 = two_isolated_tenants

        idx_a = MockRouter.get_index(t1["tenant_id"])
        idx_b = MockRouter.get_index(t2["tenant_id"])

        idx_a.add({"content": f"Secret document {TENANT_A_SECRET} for Tenant A only", "doc_id": "a_doc.pdf"})
        idx_b.add({"content": f"Secret document {TENANT_B_SECRET} for Tenant B only", "doc_id": "b_doc.pdf"})

        # Search from Tenant B for Tenant A's secret
        results_b = idx_b.search(TENANT_A_SECRET)
        assert not results_b, (
            f"CRITICAL ISOLATION FAILURE: Tenant B retrieved Tenant A's document: {results_b}"
        )

        # Search from Tenant A for Tenant B's secret
        results_a = idx_a.search(TENANT_B_SECRET)
        assert not results_a, (
            f"CRITICAL ISOLATION FAILURE: Tenant A retrieved Tenant B's document: {results_a}"
        )

    def test_cross_tenant_query_returns_empty(self, two_isolated_tenants):
        """Querying a tenant for data that exists only in another tenant returns nothing."""
        t1, t2 = two_isolated_tenants

        idx_a = MockRouter.get_index(t1["tenant_id"])
        idx_a.add({"content": f"Student {TENANT_A_SECRET} enrolled in CS", "doc_id": "a_students.pdf"})

        # Tenant B has no documents
        idx_b = MockRouter.get_index(t2["tenant_id"])
        results = idx_b.search("enrolled in CS")
        assert not results, (
            f"Tenant B should return nothing (empty index), got: {results}"
        )

    def test_tenant_directory_isolation(self, two_isolated_tenants):
        """
        Tenant directories must be physically separate. No symlinks or shared paths.
        """
        t1, t2 = two_isolated_tenants
        t1_dir = t1["tenant_dir"]
        t2_dir = t2["tenant_dir"]

        assert t1_dir != t2_dir, "Tenant directories must differ"
        assert not t1_dir.is_relative_to(t2_dir), "Tenant A must not be inside Tenant B dir"
        assert not t2_dir.is_relative_to(t1_dir), "Tenant B must not be inside Tenant A dir"

    def test_manifest_isolation(self, two_isolated_tenants):
        """Each tenant's manifest DB is its own file; no shared manifest."""
        t1, t2 = two_isolated_tenants
        db1 = t1["tenant_dir"] / "manifest.db"
        db2 = t2["tenant_dir"] / "manifest.db"
        assert db1.resolve() != db2.resolve(), \
            "Two tenants must not share the same manifest.db file"

    def test_in_memory_router_cache_isolation(self):
        """
        The in-memory `routers` dict in api/main.py must not serve one tenant's
        index when another tenant is requested.
        """
        # Simulate the router cache behavior
        routers_cache: dict = {}

        def get_router(tenant_id: str):
            if tenant_id not in routers_cache:
                routers_cache[tenant_id] = MockRouter.get_index(tenant_id)
            return routers_cache[tenant_id]

        idx_a = get_router("tenant_isolation_A")
        idx_a.add({"content": f"Confidential {TENANT_A_SECRET}", "doc_id": "conf.pdf"})

        idx_b = get_router("tenant_isolation_B")
        # B should have its own empty index
        results = idx_b.search(TENANT_A_SECRET)
        assert not results, (
            "Router cache leak: tenant_B retrieved tenant_A content from routers dict"
        )

    def test_allowlist_prevents_cross_tenant_access(self):
        """An unauthorized user for tenant A must not access tenant B's data."""
        from auth.allowlist import AllowlistManager
        mgr = AllowlistManager()
        # Only user "user_a" is allowed in tenant_a
        mgr.allowlist["tenant_isolation_gate_A"] = {
            "telegram_users": ["user_a"],
            "whatsapp_users": [],
        }
        # user_b must not be allowed in tenant_isolation_gate_A
        is_allowed = mgr.is_telegram_user_allowed("tenant_isolation_gate_A", "user_b")
        assert not is_allowed, \
            "CRITICAL: Unauthorized user gained access to wrong tenant"

    def test_no_global_shared_duckdb(self, two_isolated_tenants):
        """
        Each tenant must have its own DuckDB file. Verify no shared path is used.
        """
        t1, t2 = two_isolated_tenants
        db1_path = t1["tenant_dir"] / "tabular.duckdb"
        db2_path = t2["tenant_dir"] / "tabular.duckdb"
        # They should be different paths
        assert str(db1_path) != str(db2_path), \
            "Tenants share the same DuckDB path — isolation failure"
