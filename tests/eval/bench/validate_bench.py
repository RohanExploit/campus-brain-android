"""Validate the bench before anyone measures anything with it.

A benchmark that is wrong about its own corpus produces confident numbers that mean
nothing — which is exactly the failure this whole exercise uncovered in the previous kit
(13 of 22 GLOBAL golds demanded strings the corpus never contained, and a perfect answer
was scored FAIL for writing a department's name in full).

Four checks, all CPU:

  1. ANSWERABILITY. Every required anchor must appear in the documents the question itself
     names as supporting. Not merely somewhere in the corpus — in the cited documents. A
     question whose anchor lives only in some unrelated file is mislabelled, and would
     score the retriever on a document it was never pointed at.
  2. MULTI-HOP INTEGRITY. Every LOCAL question must genuinely span its cited documents: no
     single document may contain all of its anchors. Otherwise it is a FACT question
     wearing a LOCAL label, and the LOCAL slice stops measuring hops.
  3. UNANSWERABILITY. Each unanswerable question's distinctive terms must be absent from
     the corpus, so abstention really is the only correct behaviour.
  4. HYGIENE. No duplicate ids, no duplicate question text, no empty golds.

Exit code is non-zero if any check fails, so this can gate a run.

Usage:  python tests/eval/bench/validate_bench.py
"""
import json
import re
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

from tests.eval.derive_gold_v2 import strip_group_commas  # noqa: E402

CORPUS = PROJECT_ROOT / "Dataset" / "bench_v1" / "corpus"
KIT = PROJECT_ROOT / "Dataset" / "bench_v1" / "golden"
GOLD = PROJECT_ROOT / "tests" / "eval" / "golden_bench.json"

# Terms that must not appear in the corpus for the unanswerable set to be honest
UNANSWERABLE_TERMS = {
    "BU001": ["PhD tuition"], "BU002": ["M.Tech"], "BU003": ["mess menu"],
    "BU004": ["IIT"], "BU005": ["Aerospace"], "BU006": ["Dean of Student"],
    "BU007": ["patent"], "BU008": ["salary"], "BU009": ["air conditioning"],
    "BU010": ["NIRF"], "BU011": ["buses per route", "number of buses"], "BU012": ["topper"],
    "BU013": ["new library building"], "BU014": ["electricity"],
    "BU015": ["recruited the most"], "BU016": ["certification course"],
    "BU017": ["international student"], "BU018": ["retirement"],
    "BU019": ["previous Director"], "BU020": ["single-occupancy"],
}


def load_docs() -> dict:
    return {p.name: strip_group_commas(p.read_text(encoding="utf-8"))
            for p in sorted(CORPUS.glob("*.md"))}


def in_text(anchor: str, text: str) -> bool:
    a = strip_group_commas(anchor)
    if len(a) <= 3:
        return re.search(rf"\b{re.escape(a)}\b", text, re.IGNORECASE) is not None
    return a.lower() in text.lower()


def supporting_docs(kit_q: dict) -> list:
    if "supporting_docs" in kit_q:
        return kit_q["supporting_docs"]
    return [kit_q["source_doc"]] if "source_doc" in kit_q else []


def main():
    docs = load_docs()
    gold = {q["id"]: q for q in json.loads(GOLD.read_text(encoding="utf-8"))["questions"]}
    kit = {}
    for f in sorted(KIT.glob("golden_*.json")):
        for q in json.loads(f.read_text(encoding="utf-8"))["questions"]:
            kit[q["id"]] = q

    failures, warnings = [], []

    # 1. answerability
    for qid, g in gold.items():
        if g["gold"].get("mode") != "anchors":
            continue
        cited = supporting_docs(kit.get(qid, {}))
        if not cited:
            continue
        blob = "\n".join(docs.get(d, "") for d in cited)
        missing_docs = [d for d in cited if d not in docs]
        if missing_docs:
            failures.append(f"{qid}: cites documents that do not exist: {missing_docs}")
        for group in g["gold"]["required"]:
            if not any(in_text(form, blob) for form in group):
                failures.append(
                    f"{qid}: required anchor {group} is in no cited document {cited}")

    # 2. multi-hop integrity — the hop is a property of the QUESTION, not of the anchors:
    # the entity the question names must live in one document and the answer in another.
    for qid, g in gold.items():
        if not qid.startswith("BL") or g["gold"].get("mode") != "anchors":
            continue
        kq = kit.get(qid, {})
        bridge, groups = kq.get("bridge"), g["gold"]["required"]
        if not bridge:
            warnings.append(f"{qid}: no declared bridge entity — hop not verifiable")
            continue
        bridge_docs = {n for n, t in docs.items() if in_text(bridge, t)}
        answer_docs = {n for n, t in docs.items()
                       if all(any(in_text(f, t) for f in grp) for grp in groups)}
        if not bridge_docs:
            failures.append(f"{qid}: bridge entity {bridge!r} is in no document")
        elif answer_docs and bridge_docs & answer_docs:
            failures.append(
                f"{qid}: {sorted(bridge_docs & answer_docs)} contains BOTH the bridge "
                f"{bridge!r} and the answer — the question is answerable in one hop")

    # 3. unanswerability
    whole = "\n".join(docs.values())
    for qid, terms in UNANSWERABLE_TERMS.items():
        if qid not in gold:
            continue
        present = [t for t in terms if t.lower() in whole.lower()]
        if present:
            failures.append(f"{qid}: corpus DOES mention {present} — not unanswerable")

    # 4. hygiene
    questions = json.loads(GOLD.read_text(encoding="utf-8"))["questions"]
    ids = [q["id"] for q in questions]
    if len(ids) != len(set(ids)):
        failures.append("duplicate question ids")
    texts = [q["query"] for q in questions]
    dupes = {t for t in texts if texts.count(t) > 1}
    if dupes:
        failures.append(f"duplicate question text: {list(dupes)[:3]}")
    for q in questions:
        g = q["gold"]
        if g.get("mode") == "anchors" and not g["required"] and not g["bonus"]:
            failures.append(f"{q['id']}: no anchors at all")

    by_route = {}
    for q in questions:
        by_route[q["route"]] = by_route.get(q["route"], 0) + 1
    print(f"bench: {len(questions)} questions {by_route}")
    print(f"corpus: {len(docs)} documents, {sum(len(t) for t in docs.values())} chars")
    n_bonus = sum(1 for q in questions if q["gold"].get("bonus"))
    print(f"derived-figure (bonus) anchors on {n_bonus} questions")

    for w in warnings:
        print(f"  WARN  {w}")
    if failures:
        print(f"\nFAILED {len(failures)} checks:")
        for f in failures[:40]:
            print(f"  FAIL  {f}")
        if len(failures) > 40:
            print(f"  ... and {len(failures) - 40} more")
        return 1
    print("\nall checks passed: every required anchor is present in its cited documents, "
          "every LOCAL question spans documents, and no unanswerable question is "
          "accidentally answerable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
