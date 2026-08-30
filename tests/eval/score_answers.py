"""
SCORE half of the Phase-B RUN/SCORE split (see run_eval.py).

Scores a frozen answers JSONL produced by `run_eval.py --answers`. Costs no GPU, so a
scorer variant is a free replay rather than a fresh eval — and, more importantly, the
answers are written to disk *before* any scorer variant is authored, so a scorer cannot be
quietly shaped around the numbers it is meant to judge. Re-runnable by an auditor from the
same two files.

Scorers
    v1        the shipped run_eval.score() — THE DECISION VARIABLE. Never edited here.
    wb        v1 plus word-boundary matching for expect tokens <= 3 chars. Diagnostic only:
              gold 'AI' matches the 'ai' inside chair/maintain/available/said, so G003 and
              L010 pass on essentially any English answer. Reported, never decisive —
              11 of the 22 TABULAR invariant golds also have <=3-char expects (T19 'BC'
              must not become \bBC\b, which would stop matching "OBC"), so switching the
              invariant's own ruler mid-flight would make it incomparable to its baseline.
    rotated   v1, but each answer scored against the NEXT question's gold. This is the
              artifact floor: whatever it scores was bought by length and vocabulary, not
              by answering. A real gain must exceed its own rotated arm.

Usage
    python tests/eval/score_answers.py --answers runs/base_stress.jsonl \
        --golden tests/eval/golden_stress.json [--compare runs/after_stress.jsonl]
"""
import argparse
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.run_eval import score as score_v1  # noqa: E402  the frozen decision scorer
from tests.eval.run_eval import score_anchors  # noqa: E402
from tests.eval.derive_gold_v2 import strip_group_commas  # noqa: E402


def score_wb(answer: str, gold: dict) -> bool:
    """v1 semantics, but short expect tokens must match on word boundaries."""
    a = (answer or "").lower()
    mode = gold["mode"]
    expect = [str(x).lower() for x in gold.get("expect", [])]

    def hit(tok: str) -> bool:
        if len(tok) <= 3:
            return re.search(rf"\b{re.escape(tok)}\b", a) is not None
        return tok in a

    if mode == "contains":
        return all(hit(t) for t in expect)
    if mode == "contains_any":
        return any(hit(t) for t in expect)
    return score_v1(answer, gold)  # 'insufficient' is unchanged


def _hit(tok: str, answer_norm: str) -> bool:
    """Short anchors match on word boundaries: 'AI' must not match the 'ai' in 'chair'."""
    t = tok.lower()
    if len(t) <= 3:
        return re.search(rf"\b{re.escape(t)}\b", answer_norm) is not None
    return t in answer_norm


def score_v2(answer: str, gold: dict) -> bool:
    """Required anchors only — see tests/eval/derive_gold_v2.py for how they are derived.

    Delegates to run_eval.score_anchors so the live run and this replay cannot drift into
    scoring the same answer differently. Non-anchor golds fall through to v1 unchanged.
    """
    if gold.get("mode") != "anchors":
        return score_v1(answer, gold)          # 'insufficient' items are unchanged
    return score_anchors(answer, gold)


def score_v2_bonus(answer: str, gold: dict) -> bool | None:
    """Did the answer produce the DERIVED figures (sums, totals) too? None = not applicable."""
    bonus = gold.get("bonus") or []
    if not bonus:
        return None
    a = strip_group_commas((answer or "").lower())
    return all(_hit(tok, a) for tok in bonus)


SCORERS = {"v1": score_v1, "wb": score_wb, "v2": score_v2}


def load_answers(path: Path) -> dict:
    rows = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue  # torn final line from a killed run
            rows[r["id"]] = r
    return rows


def load_golds(path: Path) -> dict:
    spec = json.loads(path.read_text(encoding="utf-8"))
    return {q["id"]: q for q in spec["questions"]}


def evaluate(answers: dict, golds: dict, scorer, rotate: bool = False) -> dict:
    ids = [i for i in golds if i in answers]
    per_route, detail = {}, {}
    for n, qid in enumerate(ids):
        q = golds[qid]
        gold = golds[ids[(n + 1) % len(ids)]]["gold"] if rotate else q["gold"]
        ok = scorer(answers[qid]["answer"], gold)
        pr = per_route.setdefault(q["route"], [0, 0])
        pr[0] += int(ok)
        pr[1] += 1
        detail[qid] = ok
    return {
        "n": len(ids),
        "overall": sum(p[0] for p in per_route.values()),
        "per_route": {r: (c, t) for r, (c, t) in sorted(per_route.items())},
        "detail": detail,
    }


def mcnemar(before: dict, after: dict) -> tuple[int, int, list, list]:
    """Discordant pairs. b = fail->pass (gained), c = pass->fail (lost)."""
    gained = [i for i in before if not before[i] and after.get(i)]
    lost = [i for i in before if before[i] and not after.get(i, False)]
    return len(gained), len(lost), gained, lost


# One-sided exact-binomial thresholds at alpha=0.05: improvements needed for a given
# number of regressions. Pre-registered in docs/PHASE_B_COUNCIL_VERDICT.md; do not tune.
MCNEMAR_MIN_B = {0: 5, 1: 7, 2: 9, 3: 10, 4: 12}


def verdict(b: int, c: int) -> str:
    need = MCNEMAR_MIN_B.get(c)
    if need is None:
        return f"REJECT (c={c} regressions is past the pre-registered table)"
    if c >= b:
        return f"REJECT (b={b} <= c={c}: no net gain)"
    if b >= need:
        return f"ACCEPT (b={b} >= {need} required at c={c})"
    return f"INCONCLUSIVE (b={b}, need {need} at c={c}) — keep behind the flag, claim nothing"


def applicable_scorers(golds: dict) -> dict:
    """v1/wb read the {mode: contains, expect: [...]} schema; v2 reads {mode: anchors}.

    A gold FILE is written in one schema or the other, so pick the scorers that can read
    it rather than crashing on the first mismatched question. The v2 file is scored by v2
    alone, which also means the two schemas can never be silently averaged together.
    """
    if "anchors" in {q["gold"].get("mode") for q in golds.values()}:
        return {"v2": score_v2}
    return SCORERS


def report(label: str, answers: dict, golds: dict) -> dict:
    out = {}
    scorers = applicable_scorers(golds)
    primary = score_v2 if "v2" in scorers and "v1" not in scorers else score_v1
    print(f"\n=== {label} (n={len(answers)}) ===")
    for name, fn in scorers.items():
        r = evaluate(answers, golds, fn)
        out[name] = r
        routes = "  ".join(f"{k} {c}/{t}" for k, (c, t) in r["per_route"].items())
        print(f"  {name:8s} overall {r['overall']}/{r['n']}   {routes}")
    bonus_ids = [i for i, q in golds.items()
                 if i in answers and (q["gold"].get("bonus"))]
    if bonus_ids:
        hit = sum(1 for i in bonus_ids if score_v2_bonus(answers[i]["answer"], golds[i]["gold"]))
        print(f"  {'bonus':8s} derived-figure anchors {hit}/{len(bonus_ids)} "
              f"({', '.join(sorted(bonus_ids))})   <- arithmetic, reported not conjoined")

    rot = evaluate(answers, golds, primary, rotate=True)
    out["rotated"] = rot
    routes = "  ".join(f"{k} {c}/{t}" for k, (c, t) in rot["per_route"].items())
    print(f"  {'rotated':8s} overall {rot['overall']}/{rot['n']}   {routes}   <- artifact floor")
    lens = sorted(r["answer_len"] for r in answers.values() if "answer_len" in r)
    if lens:
        print(f"  answer length median {lens[len(lens) // 2]}  p90 {lens[int(len(lens) * 0.9)]}")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--answers", required=True)
    ap.add_argument("--golden", required=True)
    ap.add_argument("--compare", default=None, help="a second answers JSONL to diff against")
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    golds = load_golds(Path(args.golden))
    base = load_answers(Path(args.answers))
    res = {"baseline": report(Path(args.answers).name, base, golds)}

    if args.compare:
        cmp_rows = load_answers(Path(args.compare))
        res["compare"] = report(Path(args.compare).name, cmp_rows, golds)
        key = "v1" if "v1" in res["baseline"] else "v2"
        shown = ("v1 scorer, the pre-registered decision variable" if key == "v1"
                 else "v2 scorer, repaired derivation — REPORTED, never the decision variable")
        print(f"\n=== DECISION ({shown}) ===")
        for route in sorted({q["route"] for q in golds.values()}):
            ids = [i for i, q in golds.items() if q["route"] == route]
            bd = {i: res["baseline"][key]["detail"].get(i) for i in ids
                  if i in res["baseline"][key]["detail"]}
            ad = {i: res["compare"][key]["detail"].get(i) for i in ids}
            b, c, gained, lost = mcnemar(bd, ad)
            print(f"  {route:8s} b(gained)={b} c(lost)={c}  {verdict(b, c)}")
            if gained:
                print(f"           gained: {', '.join(sorted(gained))}")
            if lost:
                print(f"           LOST:   {', '.join(sorted(lost))}")
        rb = res["baseline"]["rotated"]["overall"]
        ra = res["compare"]["rotated"]["overall"]
        print(f"\n  artifact floor moved {rb} -> {ra}. A real gain must exceed this movement.")

    if args.out:
        Path(args.out).write_text(json.dumps(res, indent=2), encoding="utf-8")
        print(f"\nwrote {args.out}")


if __name__ == "__main__":
    main()
