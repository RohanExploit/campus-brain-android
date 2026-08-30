"""Run the bench through both LOCAL context arms and score the comparison.

One command, because the comparison is only valid if both arms see the same machine
conditions: this box drops into Ollama reload thrash when free RAM falls below ~2 GB, and
a run measured during thrash is not comparable to one measured outside it. Two arms run by
hand hours apart can differ for that reason alone.

Both arms write frozen answer files, so every later scorer question is a CPU replay.
--resume is passed through, so a run killed by thrash costs minutes rather than the run.

Arms:
  graph   LOCAL_GRAPH_CONTEXT=1 — today's default: 2-hop graph edges as context.
  vector  LOCAL_GRAPH_CONTEXT=0 — the flagged alternative: retrieved chunk text.

Usage:  python tests/eval/bench/run_arms.py [--out DIR] [--limit N] [--resume]
"""
import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

PY = PROJECT_ROOT / ".venv312" / "Scripts" / "python.exe"
GOLD = PROJECT_ROOT / "tests" / "eval" / "golden_bench.json"
TEN = PROJECT_ROOT / "data" / "tenants" / "tenant_bench"
MIN_FREE_MB = 2500


def free_mb() -> int:
    try:
        import ctypes

        class S(ctypes.Structure):
            _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                        ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                        ("ullTotalPageFile", ctypes.c_ulonglong),
                        ("ullAvailPageFile", ctypes.c_ulonglong),
                        ("ullTotalVirtual", ctypes.c_ulonglong),
                        ("ullAvailVirtual", ctypes.c_ulonglong),
                        ("ullAvailExtendedVirtual", ctypes.c_ulonglong)]

        s = S()
        s.dwLength = ctypes.sizeof(S)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(s))
        return int(s.ullAvailPhys / (1024 * 1024))
    except Exception:
        return -1


def preflight() -> None:
    problems = []
    if not (TEN / "embeddings" / "faiss.index").exists():
        problems.append("tenant_bench has no faiss.index — run ingest_bench.py fact")
    if not (TEN / "graph" / "community_summaries.json").exists():
        problems.append("tenant_bench has no community summaries — run ingest_bench.py graph "
                        "(the GLOBAL route answers from them, and the graph arm needs the graph)")
    if not GOLD.exists():
        problems.append("golden_bench.json missing — run derive_gold_v2.py")
    if shutil.which("curl"):
        r = subprocess.run(["curl", "-s", "-m", "5", "http://127.0.0.1:11434/api/tags"],
                           capture_output=True, text=True)
        if "qwen" not in r.stdout:
            problems.append("Ollama is not serving the model")
    mb = free_mb()
    if 0 <= mb < MIN_FREE_MB:
        problems.append(
            f"only {mb} MB RAM free (<{MIN_FREE_MB}). Below roughly this line Ollama unloads "
            "and reloads the model between calls — measured 90-170 s per call, past every "
            "timeout in the codebase — and the numbers stop being comparable.")
    if problems:
        for p in problems:
            print(f"  BLOCKED: {p}")
        raise SystemExit(1)
    print(f"preflight ok ({mb} MB RAM free)")


def run_arm(name: str, graph_on: bool, out_dir: Path, limit, resume: bool) -> Path:
    answers = out_dir / f"bench_{name}.jsonl"
    env = {**os.environ, "LOCAL_GRAPH_CONTEXT": "1" if graph_on else "0"}
    cmd = [str(PY), str(PROJECT_ROOT / "tests" / "eval" / "run_eval.py"),
           "--golden", str(GOLD), "--answers", str(answers)]
    if limit:
        cmd += ["--limit", str(limit)]
    if resume:
        cmd += ["--resume"]
    print(f"\n=== arm: {name} (LOCAL_GRAPH_CONTEXT={env['LOCAL_GRAPH_CONTEXT']}) ===")
    r = subprocess.run(cmd, env=env, cwd=PROJECT_ROOT, text=True,
                       capture_output=True)
    for line in r.stdout.splitlines():
        if any(k in line for k in ("overall answer", "route classification", "answer length",
                                   "FACT ", "GLOBAL ", "LOCAL ", "TABULAR ")):
            print("  " + line.strip())
    if r.returncode != 0:
        print(f"  arm {name} exited {r.returncode}: {r.stdout[-400:]}")
        print("  (partial answers are preserved; rerun with --resume)")
        raise SystemExit(r.returncode)
    return answers


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=None)
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--resume", action="store_true")
    a = ap.parse_args()

    out_dir = Path(a.out) if a.out else PROJECT_ROOT / "tests" / "eval" / "bench_runs"
    out_dir.mkdir(parents=True, exist_ok=True)

    preflight()
    graph_answers = run_arm("graph", True, out_dir, a.limit, a.resume)
    vector_answers = run_arm("vector", False, out_dir, a.limit, a.resume)

    print("\n=== comparison (frozen answers, CPU replay) ===")
    subprocess.run([str(PY), str(PROJECT_ROOT / "tests" / "eval" / "score_answers.py"),
                    "--answers", str(graph_answers), "--compare", str(vector_answers),
                    "--golden", str(GOLD)], cwd=PROJECT_ROOT)


if __name__ == "__main__":
    main()
