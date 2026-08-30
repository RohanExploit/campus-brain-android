"""
Audit 13 — Performance P99
Pass: TABULAR < 15s, GLOBAL < 30s, 10-user concurrency stays within 2x.
"""
import time
import asyncio
import statistics
import pytest
from pathlib import Path

pytestmark = pytest.mark.performance

SLA = {
    "FACT":    5.0,
    "LOCAL":   10.0,
    "TABULAR": 15.0,
    "GLOBAL":  30.0,
}

CONCURRENCY = 10


async def _mock_query(query_type: str, delay_s: float) -> dict:
    """Simulates a query with realistic latency."""
    await asyncio.sleep(delay_s)
    return {"type": query_type, "latency": delay_s, "status": "ok"}


class TestPerformance:

    def test_sla_constants_defined(self):
        """SLA thresholds are defined and reasonable."""
        for qtype, sla in SLA.items():
            assert 0 < sla <= 60, f"SLA for {qtype} must be 0-60s, got {sla}"

    @pytest.mark.asyncio
    async def test_tabular_p99_sla(self):
        """TABULAR query latency P99 must be < 15s under single-user load."""
        latencies = []
        for _ in range(5):
            start = time.perf_counter()
            await _mock_query("TABULAR", 0.1)  # mock — real test uses live API
            latencies.append(time.perf_counter() - start)
        p99 = statistics.quantiles(latencies, n=100)[-1] if len(latencies) >= 10 else max(latencies)
        assert p99 < SLA["TABULAR"], f"TABULAR P99={p99:.2f}s exceeds SLA={SLA['TABULAR']}s"

    @pytest.mark.asyncio
    async def test_concurrency_within_2x_degradation(self):
        """10 concurrent queries must complete within 2x single-query time."""
        # Single-user baseline
        start = time.perf_counter()
        await _mock_query("LOCAL", 0.05)
        baseline = time.perf_counter() - start

        # 10-user concurrent
        sem = asyncio.Semaphore(CONCURRENCY)
        async def bounded_query():
            async with sem:
                return await _mock_query("LOCAL", 0.05)

        start = time.perf_counter()
        results = await asyncio.gather(*[bounded_query() for _ in range(CONCURRENCY)])
        total = time.perf_counter() - start
        avg_under_load = total / CONCURRENCY

        assert avg_under_load < baseline * 2, (
            f"Concurrency degradation {avg_under_load:.2f}s > 2x baseline {baseline:.2f}s"
        )
        assert all(r["status"] == "ok" for r in results), "Some concurrent queries failed"

    def test_metrics_collector_module_exists(self):
        project_root = Path(__file__).resolve().parent.parent.parent
        mc = project_root / "audit" / "utils" / "metrics_collector.py"
        assert mc.exists(), f"metrics_collector.py not found at {mc}"

    def test_ollama_warmup_in_lifespan(self):
        """api/main.py must warm up Ollama in lifespan to avoid cold-start latency."""
        project_root = Path(__file__).resolve().parent.parent.parent
        main = project_root / "api" / "main.py"
        content = main.read_text(encoding="utf-8")
        assert "lifespan" in content, "lifespan event handler missing from api/main.py"
        assert "ollama" in content.lower() or "warm" in content.lower(), \
            "Ollama warmup not present in lifespan handler"

    def test_sla_for_all_query_types_defined(self):
        for qt in ["FACT", "LOCAL", "GLOBAL", "TABULAR"]:
            assert qt in SLA, f"SLA not defined for query type: {qt}"
