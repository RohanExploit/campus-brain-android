"""Phase-0 instrumentation: convert the stresskit golden files into run_eval schema.

run_eval expects {version, tenant_id, questions:[{id, route, query, gold:{mode, expect}}]}.
The stresskit ships prose gold answers instead, so we derive a DETERMINISTIC, AUDITABLE
scorer per question. See docs/PHASE_0_BASELINE.md "Scoring methodology" for the rules and
known limitations (yes/no polarity false-pass, soft-prose over-credit, multi-number
over-match). The scorer is fixed, so paired McNemar deltas across phases stay valid.

Anchors extracted from each gold answer:
  numbers/percentages (168, 75, 94.1) · short all-caps codes (FF, XX, AB, CSE) ·
  proper-noun phrases (Dr. Vasant Rane).
mode:  hard anchor -> contains (ALL) ; else proper nouns -> contains_any ;
       else soft prose -> contains_any on 2 longest content words (flagged NEEDS_MANUAL).
unanswerable -> insufficient (abstain = correct).

Writes tests/eval/golden_stress.json (committed) + an audit table next to it.

Usage:  python tests/eval/adapt_golden.py
"""
import json
import re
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
KIT = PROJECT_ROOT / "Dataset" / "Untested stresskit as of 4pm 18-08-2026" / "golden"
OUT = PROJECT_ROOT / "tests" / "eval" / "golden_stress.json"
AUDIT = PROJECT_ROOT / "tests" / "eval" / "golden_stress_scorer_audit.md"

NUM_RE = re.compile(r"\b\d+(?:\.\d+)?\b")
CODE_RE = re.compile(r"\b[A-Z]{2,4}\b")
PROPER_RE = re.compile(r"\b(?:Dr\.?\s+)?[A-Z][a-z]+(?:\s+[A-Z][a-z]+)+\b")
STOP = {"the", "and", "for", "are", "not", "only", "per", "cent", "with", "that", "this",
        "from", "his", "her", "two", "one", "who", "what", "which", "department", "heads", "head"}


def derive_gold(ans: str):
    nums = NUM_RE.findall(ans)
    codes = CODE_RE.findall(ans)
    propers = list(dict.fromkeys(PROPER_RE.findall(ans)))
    hard = list(dict.fromkeys(nums + codes))
    if hard:
        return {"mode": "contains", "expect": hard}, False
    if propers:
        return {"mode": "contains_any", "expect": propers}, False
    words = [w for w in re.findall(r"[A-Za-z]{4,}", ans) if w.lower() not in STOP]
    words = sorted(set(words), key=len, reverse=True)[:2]
    return {"mode": "contains_any", "expect": words or [ans[:20]]}, True


def load(name):
    return json.loads((KIT / name).read_text(encoding="utf-8"))["questions"]


def main():
    questions, audit = [], []
    for route, fname in [("FACT", "golden_fact.json"),
                         ("GLOBAL", "golden_global.json"),
                         ("LOCAL", "golden_local.json")]:
        for q in load(fname):
            gold, manual = derive_gold(q["expected_answer"])
            item = {"id": q["id"], "route": route, "query": q["question"], "gold": gold}
            if "phrasing" in q:
                item["phrasing"] = q["phrasing"]
            questions.append(item)
            audit.append((q["id"], route, gold["mode"], gold["expect"],
                          q["expected_answer"], "MANUAL?" if manual else ""))
    for q in load("golden_unanswerable.json"):
        questions.append({"id": q["id"], "route": "FACT", "query": q["question"],
                          "gold": {"mode": "insufficient", "expect": []},
                          "gap_type": q.get("gap_type"), "unanswerable": True})
        audit.append((q["id"], "UNANS", "insufficient", [], q["expected_behaviour"], ""))

    spec = {"version": "stress-1", "tenant_id": "tenant_stress",
            "description": "Stresskit adapted to run_eval schema (Phase-0 baseline)",
            "questions": questions}
    OUT.write_text(json.dumps(spec, indent=2, ensure_ascii=False), encoding="utf-8")

    lines = ["# Stresskit scorer audit\n",
             f"{len(questions)} questions. `contains` = ALL expect strings required; "
             "`contains_any` = at least one; `insufficient` = abstention.\n",
             "| id | route | mode | expect | expected_answer | flag |",
             "|---|---|---|---|---|---|"]
    for id_, route, mode, expect, ans, flag in audit:
        lines.append(f"| {id_} | {route} | {mode} | {expect} | {ans.replace('|', chr(92)+'|')[:80]} | {flag} |")
    AUDIT.write_text("\n".join(lines), encoding="utf-8")

    n_manual = sum(1 for a in audit if a[5])
    print(f"wrote {OUT} ({len(questions)} questions); {n_manual} NEEDS_MANUAL; audit -> {AUDIT.name}")


if __name__ == "__main__":
    main()
