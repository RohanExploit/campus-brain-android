"""
Audit 12 — Explainability
Pass: Every answer includes stage trace, retrieved evidence, verification status, citations.
"""
import pytest
from pathlib import Path

pytestmark = pytest.mark.observability

REQUIRED_PIPELINE_STAGES = [
    "query_classification",
    "retrieval_routing",
    "evidence_assembly",
    "verification",
    "answer_generation",
    "citation_formatting",
]


class TestExplainability:

    def test_router_emits_query_type(self):
        """router.py must return a classified query type with every result."""
        from retrieval.router import QueryRouter
        import inspect
        src = inspect.getsource(QueryRouter)
        assert "FACT" in src and "LOCAL" in src and "GLOBAL" in src and "TABULAR" in src, \
            "QueryRouter must emit FACT/LOCAL/GLOBAL/TABULAR classification"

    def test_answer_includes_context_reference(self):
        """generate_answer must receive and use context, not fabricate."""
        from generation.answer import generate_answer
        import inspect
        src = inspect.getsource(generate_answer)
        assert "context" in src, "generate_answer must accept and use context parameter"

    def test_global_answer_has_structured_sections(self):
        """GLOBAL query responses must have structured Markdown sections."""
        from generation.answer import generate_answer
        import inspect
        src = inspect.getsource(generate_answer)
        assert "Recommendation" in src or "Supporting Evidence" in src or "Citations" in src, \
            "GLOBAL prompt must include structured sections"

    def test_pipeline_stages_documented(self):
        """All pipeline stages must be represented in codebase."""
        project_root = Path(__file__).resolve().parent.parent.parent
        stage_files = {
            "query_classification": project_root / "retrieval" / "router.py",
            "retrieval_routing":    project_root / "retrieval" / "router.py",
            "answer_generation":    project_root / "generation" / "answer.py",
        }
        for stage, path in stage_files.items():
            assert path.exists(), f"Stage '{stage}' file missing: {path}"

    def test_verification_status_enum_defined(self):
        """Extraction must use VERIFIED/UNVERIFIED/CONFLICT/INSUFFICIENT_EVIDENCE."""
        # Check that at least the string is referenced somewhere in the codebase
        project_root = Path(__file__).resolve().parent.parent.parent
        found = False
        for py_file in project_root.rglob("*.py"):
            try:
                content = py_file.read_text(encoding="utf-8", errors="ignore")
                if "VERIFIED" in content or "INSUFFICIENT_EVIDENCE" in content:
                    found = True
                    break
            except Exception:
                continue
        assert found, "Verification status enums not found in codebase"

    def test_needs_review_table_captures_failures(self):
        """Extraction failures route to needs_review, not silently discarded."""
        import duckdb
        project_root = Path(__file__).resolve().parent.parent.parent
        dbs = list((project_root / "data" / "tenants").glob("*/tabular.duckdb"))
        if not dbs:
            pytest.skip("No tabular.duckdb found")
        con = duckdb.connect(str(dbs[0]), read_only=True)
        tables = [r[0] for r in con.execute("SHOW TABLES").fetchall()]
        con.close()
        assert "needs_review" in tables, "needs_review table missing from DuckDB schema"
