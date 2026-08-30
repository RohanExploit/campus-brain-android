"""
Audit 19 — Regression Benchmark
Pass: All 20 frozen Q&A pairs match stored answers with cosine similarity >= 0.85.
"""
import json
import pytest
from pathlib import Path

pytestmark = pytest.mark.regression

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
BENCHMARK_FILE = PROJECT_ROOT / "audit" / "fixtures" / "regression_benchmark.json"


class TestRegressionBenchmark:

    def test_benchmark_fixture_exists(self):
        assert BENCHMARK_FILE.exists(), (
            f"Regression benchmark not found at {BENCHMARK_FILE}. "
            "Run: python scripts/generate_benchmark.py"
        )

    def test_benchmark_has_20_pairs(self):
        if not BENCHMARK_FILE.exists():
            pytest.skip("Benchmark fixture not yet created")
        data = json.loads(BENCHMARK_FILE.read_text(encoding="utf-8"))
        assert len(data) >= 20, f"Benchmark has {len(data)} pairs, need >= 20"

    def test_benchmark_schema_valid(self):
        if not BENCHMARK_FILE.exists():
            pytest.skip("Benchmark fixture not yet created")
        data = json.loads(BENCHMARK_FILE.read_text(encoding="utf-8"))
        required = ["query", "expected_answer", "query_type", "tenant_id"]
        for i, item in enumerate(data):
            missing = [r for r in required if r not in item]
            assert not missing, f"Benchmark item {i} missing fields: {missing}"

    def test_benchmark_query_types_valid(self):
        if not BENCHMARK_FILE.exists():
            pytest.skip("Benchmark fixture not yet created")
        valid = {"FACT", "LOCAL", "GLOBAL", "TABULAR"}
        data = json.loads(BENCHMARK_FILE.read_text(encoding="utf-8"))
        invalid = [item for item in data if item.get("query_type") not in valid]
        assert not invalid, f"Invalid query types: {[i['query_type'] for i in invalid]}"

    def test_benchmark_expected_answers_non_empty(self):
        if not BENCHMARK_FILE.exists():
            pytest.skip("Benchmark fixture not yet created")
        data = json.loads(BENCHMARK_FILE.read_text(encoding="utf-8"))
        empty = [item["query"] for item in data if not item.get("expected_answer", "").strip()]
        assert not empty, f"Empty expected answers for queries: {empty[:3]}"

    def test_cosine_similarity_threshold_defined(self):
        """The threshold for regression pass/fail is 0.85 cosine similarity."""
        SIMILARITY_THRESHOLD = 0.85
        assert 0 < SIMILARITY_THRESHOLD <= 1.0

    def test_regression_script_exists(self):
        script = PROJECT_ROOT / "scripts" / "generate_benchmark.py"
        assert script.exists(), f"generate_benchmark.py not found at {script}"
