"""
Paraphrase-robustness runner (RUN phase).

A benchmark asks each question once, in the wording its author chose, and reports
whether the system can answer it. That is not the question a pilot fails on. Real
users ask the same thing five different ways, and the QA sweep on tenant_1 found
one question — "how many students failed two or more subjects" — returning three
different outcomes across three phrasings: a wrong number, a routing error, and a
timeout. Accuracy did not move; trust did.

This harness asks every question in EVERY phrasing a user plausibly writes, and
freezes the answers so scoring is a free CPU replay (same RUN/SCORE discipline as
run_eval.py: no scorer can be written after seeing the numbers it judges).

Usage:
    python tests/eval/run_paraphrase.py                            # run + freeze
    python tests/eval/run_paraphrase.py --answers runs/p.jsonl     # explicit path
    python tests/eval/run_paraphrase.py --resume                   # skip done ids
    python tests/eval/run_paraphrase.py --group fail_pct           # one group only

Then score with tests/eval/score_paraphrase.py.

The JSONL holds raw answer text, which on tenant_1 contains student names — it
lands under tests/eval/*.jsonl, which is gitignored for that reason.
"""
import argparse
import asyncio
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import config                                                     # noqa: E402
from tests.eval.run_eval import answer_for, enforce_no_egress     # noqa: E402

DEFAULT_SET = Path(__file__).parent / "paraphrase_set.json"
DEFAULT_OUT = Path(__file__).parent / "runs" / "paraphrase.jsonl"


def load_groups(path: Path, only: str | None) -> list[dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    groups = data["groups"]
    if only:
        groups = [g for g in groups if g["id"] == only]
        if not groups:
            raise SystemExit(f"no group with id {only!r} in {path}")
    return groups


def _completed(answers_path: Path) -> set[tuple[str, int]]:
    """(group_id, phrasing_index) pairs already frozen, so --resume is cheap."""
    if not answers_path.exists():
        return set()
    done = set()
    for line in answers_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            rec = json.loads(line)
            done.add((rec["group_id"], rec["phrasing_index"]))
        except (json.JSONDecodeError, KeyError):
            continue          # a torn last line from a killed run; just re-ask it
    return done


async def run(set_path: Path, answers_path: Path, only: str | None, resume: bool) -> None:
    enforce_no_egress()
    from retrieval.router import QueryRouter          # lazy: heavy ML import

    groups = load_groups(set_path, only)
    done = _completed(answers_path) if resume else set()
    answers_path.parent.mkdir(parents=True, exist_ok=True)

    total = sum(len(g["phrasings"]) for g in groups)
    asked = 0
    started = time.time()
    print(f"paraphrase run: {len(groups)} groups, {total} phrasings "
          f"({len(done)} already frozen)" if resume else
          f"paraphrase run: {len(groups)} groups, {total} phrasings")

    routers: dict[str, object] = {}
    with answers_path.open("a", encoding="utf-8") as fh:
        for g in groups:
            tenant = g.get("tenant", config.DEFAULT_TENANT_ID)
            if tenant not in routers:
                routers[tenant] = QueryRouter(tenant_id=tenant)
            router = routers[tenant]

            for i, phrasing in enumerate(g["phrasings"]):
                asked += 1
                if (g["id"], i) in done:
                    continue
                t0 = time.time()
                try:
                    qtype, answer = await answer_for(router, phrasing)
                except Exception as e:                 # a crash IS the finding here
                    qtype, answer = "ERROR", f"<exception: {type(e).__name__}: {e}>"
                elapsed = time.time() - t0

                fh.write(json.dumps({
                    "group_id": g["id"],
                    "phrasing_index": i,
                    "phrasing": phrasing,
                    "tenant": tenant,
                    "route": qtype,
                    "answer": answer,
                    "answer_chars": len(answer or ""),
                    "seconds": round(elapsed, 2),
                    "asked_at": datetime.now(timezone.utc).isoformat(),
                }, ensure_ascii=False) + "\n")
                fh.flush()
                print(f"  [{asked}/{total}] {g['id']}#{i} {qtype:8s} "
                      f"{elapsed:5.1f}s  {phrasing[:58]}")

    print(f"\nfrozen -> {answers_path}  ({time.time() - started:.0f}s)")
    print("score it:  python tests/eval/score_paraphrase.py "
          f"--answers {answers_path}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--set", type=Path, default=DEFAULT_SET)
    ap.add_argument("--answers", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--group", default=None, help="run a single group id")
    ap.add_argument("--resume", action="store_true",
                    help="skip (group, phrasing) pairs already in the answers file")
    a = ap.parse_args()
    asyncio.run(run(a.set, a.answers, a.group, a.resume))


if __name__ == "__main__":
    main()
