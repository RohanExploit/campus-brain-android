"""
Metrics Collector — latency histograms, P50/P95/P99, throughput, memory/CPU.
"""
import time
import asyncio
import statistics
from dataclasses import dataclass, field
from typing import Callable, Awaitable, Any

try:
    import numpy as np
    _HAS_NUMPY = True
except ImportError:
    _HAS_NUMPY = False

try:
    import psutil
    _HAS_PSUTIL = True
except ImportError:
    _HAS_PSUTIL = False


@dataclass
class LatencyResult:
    samples: list[float] = field(default_factory=list)  # seconds

    @property
    def count(self) -> int:
        return len(self.samples)

    @property
    def p50(self) -> float:
        return self._percentile(50)

    @property
    def p95(self) -> float:
        return self._percentile(95)

    @property
    def p99(self) -> float:
        return self._percentile(99)

    @property
    def mean(self) -> float:
        return statistics.mean(self.samples) if self.samples else 0.0

    @property
    def max(self) -> float:
        return max(self.samples) if self.samples else 0.0

    def _percentile(self, pct: float) -> float:
        if not self.samples:
            return 0.0
        if _HAS_NUMPY:
            return float(np.percentile(self.samples, pct))
        sorted_s = sorted(self.samples)
        idx = int(len(sorted_s) * pct / 100)
        return sorted_s[min(idx, len(sorted_s) - 1)]

    def assert_p99_below(self, threshold_s: float):
        assert self.p99 <= threshold_s, (
            f"P99 latency {self.p99:.2f}s exceeds threshold {threshold_s:.2f}s "
            f"(n={self.count}, P50={self.p50:.2f}s, P95={self.p95:.2f}s)"
        )

    def report(self) -> dict:
        return {
            "count": self.count,
            "p50_s": round(self.p50, 3),
            "p95_s": round(self.p95, 3),
            "p99_s": round(self.p99, 3),
            "mean_s": round(self.mean, 3),
            "max_s": round(self.max, 3),
        }


class MetricsCollector:
    def __init__(self):
        self.latencies: dict[str, LatencyResult] = {}

    def _get_bucket(self, label: str) -> LatencyResult:
        if label not in self.latencies:
            self.latencies[label] = LatencyResult()
        return self.latencies[label]

    async def measure_async(
        self,
        coro_fn: Callable[[], Awaitable[Any]],
        label: str = "default",
    ) -> Any:
        """Time a single async call and record to the named bucket."""
        start = time.perf_counter()
        result = await coro_fn()
        elapsed = time.perf_counter() - start
        self._get_bucket(label).samples.append(elapsed)
        return result

    async def run_concurrent(
        self,
        coro_fn: Callable[[], Awaitable[Any]],
        n: int,
        concurrency: int = 10,
        label: str = "default",
    ) -> list[Any]:
        """
        Run `n` calls with `concurrency` concurrent workers.
        Returns all results.
        """
        semaphore = asyncio.Semaphore(concurrency)

        async def _wrapped():
            async with semaphore:
                return await self.measure_async(coro_fn, label)

        return await asyncio.gather(*[_wrapped() for _ in range(n)])

    def system_snapshot(self) -> dict:
        snap = {}
        if _HAS_PSUTIL:
            proc = psutil.Process()
            snap["rss_mb"] = round(proc.memory_info().rss / 1024 / 1024, 1)
            snap["cpu_pct"] = proc.cpu_percent(interval=0.1)
        else:
            snap["rss_mb"] = None
            snap["cpu_pct"] = None

        # Try nvidia-smi
        try:
            import subprocess
            result = subprocess.run(
                ["nvidia-smi", "--query-gpu=memory.used,memory.total",
                 "--format=csv,noheader,nounits"],
                capture_output=True, text=True, timeout=5
            )
            if result.returncode == 0:
                parts = result.stdout.strip().split(", ")
                if len(parts) == 2:
                    snap["vram_used_gb"] = round(int(parts[0]) / 1024, 1)
                    snap["vram_total_gb"] = round(int(parts[1]) / 1024, 1)
        except Exception:
            snap["vram_used_gb"] = None
            snap["vram_total_gb"] = None

        return snap

    def throughput(self, label: str = "default") -> float:
        """Requests per second for a given label bucket."""
        r = self._get_bucket(label)
        if r.count == 0 or r.mean == 0:
            return 0.0
        return round(1.0 / r.mean, 2)

    def full_report(self) -> dict:
        return {
            label: {**result.report(), "throughput_rps": self.throughput(label)}
            for label, result in self.latencies.items()
        }
