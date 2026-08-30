"""
Golden-set evaluation runner (Phase 1 / CR3).

Runs each question in golden_set.json through the LIVE retrieval system exactly
as the API does (retrieval.router.QueryRouter + generation.answer), scores the
answer against a hand-verified gold, and reports per-route + overall accuracy.

Standalone (not a pytest test) so it can run against the real tenant_1 corpus
without the audit/ conftest import chain. Read-only: never writes tenant data.

Usage:
    python tests/eval/run_eval.py                      # full run, prints report
    python tests/eval/run_eval.py --out baseline.json  # also dump machine result
    python tests/eval/run_eval.py --limit 5            # first N questions (smoke)
    python tests/eval/run_eval.py --answers runs/x.jsonl [--resume]   # RUN/SCORE split

RUN/SCORE split (Phase-B): with --answers, every question's FULL answer, its length and
its wall-clock are appended to a JSONL file as they are produced. Scoring then becomes a
free CPU replay over that file (tests/eval/score_answers.py) — so a scorer variant costs
no GPU, and no scorer can be written *after* seeing the numbers it is supposed to judge:
the answers are frozen on disk first. --resume skips ids already present, so a run killed
mid-way (or a dead Ollama) does not cost the whole eval.

The JSONL holds raw answer text, which on tenant_1 contains student names — it is
gitignored (tests/eval/*.jsonl) for the same reason *_results.json is.

Scoring modes (per question `gold.mode`):
    contains      answer must contain EVERY string in gold.expect (case-insensitive)
    contains_any  answer must contain AT LEAST ONE string in gold.expect
    insufficient  answer must signal missing evidence ("don't have enough", etc.)
"""
import argparse
import asyncio
import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

import config                                      # noqa: E402
# NOTE: retrieval.router / generation.answer (faiss + sentence-transformers) are
# imported lazily inside run()/answer_for() so this module can be imported cheaply
# — e.g. by tests/test_eval_no_egress.py — without loading the heavy ML stack.


def enforce_no_egress() -> None:
    """Phase -1.2: the eval MUST measure the local model only. generation/answer.py
    silently falls back to a cloud 70B on any Ollama hiccup — that would swap the
    model under measurement AND ship document context (student PII) off-machine.
    Force egress off and fail loudly if anything (an env override) re-enabled it."""
    config.ALLOW_EXTERNAL_LLM = False
    if config.ALLOW_EXTERNAL_LLM:  # defensive: catch a re-enable after this point
        raise SystemExit(
            "FATAL: external LLM egress is ON during eval — refusing to run "
            "(a cloud model would invalidate the measurement and leak PII)."
        )

GOLDEN = Path(__file__).resolve().parent / "golden_set.json"
# Heuristic markers of an "I can't answer that" refusal. Substring match, so it
# can over-fire on a long answer that merely quotes one of these phrases — an
# accepted limitation of a keyword scorer; keep the phrases refusal-specific.
_INSUFFICIENT_MARKERS = (
    "don't have enough", "do not have enough", "insufficient",
    "not enough information", "no information", "cannot find", "not found",
    "no student", "no matching", "no record", "no such", "does not exist",
    "not exist", "could not extract", "no results",
)


def _strip_group_commas(text: str) -> str:
    """1,42,000 -> 142000. Applied to gold and answer alike (see derive_gold_v2)."""
    return re.sub(r"(?<=\d),(?=\d)", "", text)


def score_anchors(answer: str, gold: dict) -> bool:
    """The `anchors` schema written by tests/eval/derive_gold_v2.py.

    `required` is a list of GROUPS; a group holds the accepted surfaces for one anchor
    (e.g. ["CSE", "Computer Science and Engineering"]) and is satisfied by any of them.
    Every group must be satisfied. Anchors of <=3 chars match on word boundaries, so an
    'AI' anchor does not match the "ai" inside chair/maintain/said.

    `bonus` anchors — figures the documents never state, which an answer would have to
    compute — are deliberately NOT consulted here; they are reported as their own
    sub-metric by score_answers.py rather than conjoined with quotable retrieval.
    """
    a = _strip_group_commas((answer or "").lower())

    def hit(tok: str) -> bool:
        t = _strip_group_commas(str(tok).lower())
        if len(t) <= 3:
            return re.search(rf"\b{re.escape(t)}\b", a) is not None
        return t in a

    return all(any(hit(f) for f in group) for group in gold.get("required", []))


def score(answer: str, gold: dict) -> bool:
    a = (answer or "").lower()
    mode = gold["mode"]
    expect = [str(x).lower() for x in gold.get("expect", [])]
    if mode == "contains":
        return all(e in a for e in expect)
    if mode == "contains_any":
        return any(e in a for e in expect)
    if mode == "insufficient":
        return any(m in a for m in _INSUFFICIENT_MARKERS)
    # Added for the bench gold schema. The three modes above are untouched, so every
    # historical number measured with this scorer remains comparable to itself.
    if mode == "anchors":
        return score_anchors(answer, gold)
    raise ValueError(f"unknown gold.mode: {mode}")


async def answer_for(router, query: str, force_route: str | None = None) -> tuple[str, str]:
    """Mirror api/main.py: route -> (TABULAR short-circuit | LLM synthesis)."""
    from generation.answer import generate_answer  # lazy: heavy ML import
    kwargs = {"force_route": force_route} if force_route else {}
    qtype, context, _meta = await router.route_query(query, **kwargs)
    context_text = str(context).strip()
    if not context_text:
        return qtype, "I don't have enough information to answer that."
    if qtype == "TABULAR":
        return qtype, context_text
    return qtype, await generate_answer(query, context, qtype)


# A dead/thrashing Ollama does not raise — generate_answer catches everything and returns
# one of these strings, which match no _INSUFFICIENT_MARKERS. The eval would then complete
# and report ~0%, and a gated harness would "revert" a perfectly good change. Three in a row
# is not a model having a bad night; it is the engine being gone.
_ENGINE_DEAD_MARKERS = (
    "sorry, the local generation engine",
    "sorry, both local generation and the fallback",
    "<exception:",
)
_MAX_CONSECUTIVE_ENGINE_ERRORS = 3


def _is_engine_error(answer: str) -> bool:
    a = (answer or "").lower()
    return any(m in a for m in _ENGINE_DEAD_MARKERS)


def _completed_ids(answers_path: Path) -> set[str]:
    """Ids already written to the JSONL, so --resume can skip them."""
    if not answers_path.exists():
        return set()
    done = set()
    for line in answers_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            done.add(json.loads(line)["id"])
        except (json.JSONDecodeError, KeyError):
            continue  # a torn final line from a killed run: re-answer that question
    return done


async def run(golden_path: Path, limit: int | None, answers_path: Path | None = None,
              resume: bool = False, force_route: bool = False):
    enforce_no_egress()  # Phase -1.2: never let a cloud model answer during eval
    from retrieval.router import QueryRouter  # lazy: heavy ML import
    spec = json.loads(golden_path.read_text(encoding="utf-8"))
    questions = spec["questions"][: limit or None]
    tenant_id = spec.get("tenant_id", "tenant_1")
    router = QueryRouter(tenant_id)

    done_ids = _completed_ids(answers_path) if (answers_path and resume) else set()
    if done_ids:
        print(f"resuming: {len(done_ids)} answers already on disk, skipping those")
    prior = {}
    if done_ids:
        for line in answers_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                try:
                    r = json.loads(line)
                    prior[r["id"]] = r
                except (json.JSONDecodeError, KeyError):
                    continue
    if answers_path:
        answers_path.parent.mkdir(parents=True, exist_ok=True)

    per_route = {}   # route -> [correct, total]
    route_hits = 0   # classifier routed to the expected route
    rows = []
    consecutive_engine_errors = 0
    for q in questions:
        exp_route = q["route"]
        if q["id"] in done_ids:
            rec = prior[q["id"]]
            got_route, answer, elapsed = rec["routed_as"], rec["answer"], rec.get("elapsed_s", 0.0)
        else:
            t0 = time.perf_counter()
            try:
                got_route, answer = await answer_for(
                    router, q["query"], q["route"] if force_route else None)
            except Exception as e:
                got_route, answer = "ERROR", f"<exception: {e}>"
            elapsed = time.perf_counter() - t0

            if _is_engine_error(answer) or got_route == "ERROR":
                consecutive_engine_errors += 1
                if consecutive_engine_errors >= _MAX_CONSECUTIVE_ENGINE_ERRORS:
                    if answers_path:
                        print(f"partial answers preserved at {answers_path} — rerun with --resume")
                    raise SystemExit(
                        f"FATAL: {consecutive_engine_errors} consecutive generation failures "
                        f"(last: {answer[:120]!r}). The generation engine is down — aborting "
                        "rather than reporting a near-zero score that would look like a regression."
                    )
            else:
                consecutive_engine_errors = 0

            if answers_path:
                with answers_path.open("a", encoding="utf-8") as fh:
                    fh.write(json.dumps({
                        "id": q["id"], "route": exp_route, "routed_as": got_route,
                        "query": q["query"], "answer": answer,
                        "answer_len": len(answer or ""), "elapsed_s": round(elapsed, 2),
                        "tenant_id": tenant_id,
                    }, ensure_ascii=False) + "\n")
                    fh.flush()

        ok = score(answer, q["gold"]) if got_route != "ERROR" else False
        route_ok = got_route == exp_route
        route_hits += int(route_ok)
        pr = per_route.setdefault(exp_route, [0, 0])
        pr[0] += int(ok)
        pr[1] += 1
        rows.append({
            "id": q["id"], "route": exp_route, "routed_as": got_route,
            "route_ok": route_ok, "answer_ok": ok,
            "query": q["query"], "answer": answer[:300],
            "answer_len": len(answer or ""), "elapsed_s": round(elapsed, 2),
        })
        print(f"  [{'PASS' if ok else 'FAIL'}] {q['id']} {exp_route}"
              f"{'' if route_ok else f'(routed {got_route})'}: {q['query'][:60]}")

    total_ok = sum(p[0] for p in per_route.values())
    total = sum(p[1] for p in per_route.values())
    lengths = sorted(r["answer_len"] for r in rows)
    times = sorted(r["elapsed_s"] for r in rows)

    def _pct(xs, p):
        return xs[min(len(xs) - 1, int(len(xs) * p))] if xs else 0

    summary = {
        "generated": datetime.now(timezone.utc).isoformat(),
        "golden_version": spec.get("version"),
        "tenant_id": tenant_id,
        # Answer length is a confound, not decoration: the scorer is substring-based, so a
        # change that only makes answers longer scores better without answering better.
        # Latency is the shippability gate (API_TIMEOUT=60 kills a generation call).
        "answer_len_median": _pct(lengths, 0.5),
        "answer_len_p90": _pct(lengths, 0.9),
        "elapsed_s_median": _pct(times, 0.5),
        "elapsed_s_max": times[-1] if times else 0,
        "overall_accuracy": round(total_ok / total, 4) if total else 0.0,
        "route_classification_accuracy": round(route_hits / total, 4) if total else 0.0,
        "n": total,
        "per_route": {r: {"correct": c, "total": t, "accuracy": round(c / t, 4) if t else 0.0}
                      for r, (c, t) in sorted(per_route.items())},
    }
    print("\n=== EVAL SUMMARY ===")
    print(f"overall answer accuracy: {summary['overall_accuracy']:.1%}  ({total_ok}/{total})")
    print(f"route classification acc: {summary['route_classification_accuracy']:.1%}")
    print(f"answer length median/p90: {summary['answer_len_median']}/{summary['answer_len_p90']} chars"
          f"   latency median/max: {summary['elapsed_s_median']}s/{summary['elapsed_s_max']}s")
    for r, s in summary["per_route"].items():
        print(f"  {r:8s} {s['accuracy']:.1%}  ({s['correct']}/{s['total']})")
    return summary, rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--golden", default=str(GOLDEN))
    ap.add_argument("--out", default=None, help="write summary+rows JSON here")
    ap.add_argument("--limit", type=int, default=None)
    ap.add_argument("--answers", default=None,
                    help="append every full answer to this JSONL (RUN half of the RUN/SCORE split)")
    ap.add_argument("--resume", action="store_true",
                    help="skip ids already present in --answers (recover a killed run)")
    ap.add_argument("--force-route", action="store_true",
                    help="serve each question on the route its gold declares, bypassing the "
                         "classifier. Measures route QUALITY with routing accuracy held at "
                         "100%%, which is the only way to tell a bad route from a bad router.")
    args = ap.parse_args()
    answers_path = Path(args.answers) if args.answers else None
    summary, rows = asyncio.run(run(Path(args.golden), args.limit, answers_path, args.resume,
                                    args.force_route))
    if args.out:
        # --out gets the PII-safe summary only. The full rows (answer text can
        # contain student names) always go to a sibling *_results.json, which is
        # gitignored — so a documented `--out baseline.json` can never leak PII.
        out = Path(args.out)
        out.write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding="utf-8")
        rows_path = out.with_name(out.stem + "_results.json")
        rows_path.write_text(
            json.dumps({"summary": summary, "rows": rows}, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )
        print(f"\nwrote {out} (summary) and {rows_path.name} (rows — gitignored)")


if __name__ == "__main__":
    main()
