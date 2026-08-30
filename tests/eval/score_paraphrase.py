"""
Paraphrase-robustness scorer (SCORE phase).

Replays the frozen answers from run_paraphrase.py. Free, CPU-only, and repeatable,
so a scorer change costs no GPU and cannot be tuned against numbers it has already
seen.

The headline is NOT accuracy. Accuracy already has a benchmark. The number that
predicts whether a pilot survives real users is STABILITY:

    stability   share of questions where every phrasing scores the same way.
                A question that is right 4 times and wrong once is not 80% right —
                it is unreliable, and a user who hits the wrong phrasing first
                concludes the product is broken.

    all-correct share of questions answered correctly in every phrasing. This is
                the number to quote to an institution.

    fragile     the questions that answer differently depending on wording. This
                is the work queue; everything else is commentary.

Usage:
    python tests/eval/score_paraphrase.py --answers tests/eval/runs/paraphrase.jsonl
    python tests/eval/score_paraphrase.py --answers ... --json out.json
"""
import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.run_eval import score            # noqa: E402  (same scorer as the bench)

DEFAULT_SET = Path(__file__).parent / "paraphrase_set.json"

_NUM_RE = re.compile(r"-?\d+(?:\.\d+)?")


def _numbers(text: str) -> list[str]:
    """Numeric fingerprint of an answer, for spotting two phrasings that both
    'work' but return different figures."""
    return _NUM_RE.findall((text or "").replace(",", ""))


def _hit_anti_gold(answer: str, anti: list[str]) -> str | None:
    low = (answer or "").lower()
    for bad in anti:
        if bad.lower() in low:
            return bad
    return None


def load(answers_path: Path) -> dict[str, list[dict]]:
    by_group: dict[str, list[dict]] = defaultdict(list)
    for line in answers_path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            rec = json.loads(line)
            by_group[rec["group_id"]].append(rec)
    for recs in by_group.values():
        recs.sort(key=lambda r: r["phrasing_index"])
    return by_group


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--answers", type=Path, required=True)
    ap.add_argument("--set", type=Path, default=DEFAULT_SET)
    ap.add_argument("--json", type=Path, default=None, help="also dump machine result")
    a = ap.parse_args()

    spec = {g["id"]: g for g in json.loads(a.set.read_text(encoding="utf-8"))["groups"]}
    by_group = load(a.answers)

    rows, fragile, anti_hits = [], [], []
    n_stable = n_all_correct = 0

    for gid, recs in by_group.items():
        g = spec.get(gid)
        if g is None:
            print(f"  ! answers contain unknown group {gid!r}; skipping")
            continue
        gold = g["gold"]
        anti = g.get("anti_gold", [])

        verdicts, routes, fingerprints = [], set(), set()
        for r in recs:
            ok = score(r["answer"], gold)
            bad = _hit_anti_gold(r["answer"], anti)
            if bad:
                ok = False                       # a known-wrong answer is never a pass
                anti_hits.append((gid, r["phrasing"], bad))
            verdicts.append(ok)
            routes.add(r["route"])
            fingerprints.add(tuple(_numbers(r["answer"])[:4]))

        stable = len(set(verdicts)) == 1
        all_correct = all(verdicts)
        n_stable += stable
        n_all_correct += all_correct

        rows.append({
            "group": gid,
            "n": len(recs),
            "correct": sum(verdicts),
            "stable": stable,
            "all_correct": all_correct,
            "routes": sorted(routes),
            "distinct_number_answers": len(fingerprints),
        })
        if not stable:
            fragile.append((gid, g["intent"], recs, verdicts))

    total = len(rows)
    if not total:
        raise SystemExit("no scorable groups found in the answers file")

    print(f"\n{'group':<20} {'ok/n':>7}  {'stable':<7} {'routes':<22} distinct-number-answers")
    print("-" * 82)
    for r in sorted(rows, key=lambda r: (r["stable"], r["correct"] / r["n"])):
        print(f"{r['group']:<20} {r['correct']:>3}/{r['n']:<3}  "
              f"{'yes' if r['stable'] else 'NO':<7} "
              f"{','.join(r['routes']):<22} {r['distinct_number_answers']}")

    n_phrasings = sum(r["n"] for r in rows)
    n_correct = sum(r["correct"] for r in rows)
    print("\n" + "=" * 82)
    print(f"stability     {n_stable}/{total} ({100*n_stable/total:.1f}%)  "
          f"— questions whose every phrasing scores the same")
    print(f"all-correct   {n_all_correct}/{total} ({100*n_all_correct/total:.1f}%)  "
          f"— questions correct in EVERY phrasing")
    print(f"per-phrasing  {n_correct}/{n_phrasings} ({100*n_correct/n_phrasings:.1f}%)  "
          f"— the number a normal benchmark would report")

    if anti_hits:
        print(f"\nknown-wrong answers returned ({len(anti_hits)}):")
        for gid, phrasing, bad in anti_hits:
            print(f"  {gid:<18} {bad!r:<18} <- {phrasing[:50]}")

    if fragile:
        print(f"\nFRAGILE — same question, different answer ({len(fragile)}):")
        for gid, intent, recs, verdicts in fragile:
            print(f"\n  {gid}  ({intent})")
            for r, ok in zip(recs, verdicts):
                mark = "ok  " if ok else "WRONG"
                one_line = " ".join((r["answer"] or "").split())[:88]
                print(f"    {mark} [{r['route']:<8}] {r['phrasing'][:52]}")
                print(f"          -> {one_line}")
    else:
        print("\nno fragile questions: every question answered consistently across phrasings.")

    if a.json:
        a.json.write_text(json.dumps({
            "stability": {"pass": n_stable, "total": total},
            "all_correct": {"pass": n_all_correct, "total": total},
            "per_phrasing": {"pass": n_correct, "total": n_phrasings},
            "groups": rows,
            "anti_gold_hits": [{"group": g, "phrasing": p, "matched": b}
                               for g, p, b in anti_hits],
        }, indent=2), encoding="utf-8")
        print(f"\nwrote {a.json}")


if __name__ == "__main__":
    main()
