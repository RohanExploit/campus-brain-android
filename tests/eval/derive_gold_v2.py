"""Repair the stresskit gold derivation (Phase-B). CPU-only; no model, no GPU.

The stresskit ships HUMAN-authored `expected_answer` prose (plus answer_span /
supporting_docs), authored before this branch existed. `adapt_golden.py::derive_gold`
regex-scrapes that prose into substring anchors, and three properties of that scrape make
the resulting metric unable to recognise a correct answer:

  1. NUM_RE splits Indian digit grouping. "Rs. 2,96,35,000" becomes the four anchors
     2 / 96 / 35 / 000, of which "2" and "000" are free in any numeric answer while the
     real figure is required nowhere. The same fault yields an anchor 142000 that the
     corpus -- which writes 1,42,000 -- never contains.
  2. CODE_RE lifts acronyms out of the human's prose ("CSE is best") even when the corpus
     only ever writes the expansion ("Computer Science and Engineering"). A factually
     perfect answer quoting the corpus is then scored FAIL. Verified: CSE, DPDP and PG
     appear zero times across the 12 corpus documents.
  3. Anchors are all-or-nothing under `contains`, so a figure the human DERIVED by
     arithmetic (5.13 = 4.62 + 0.513; the 2,96,35,000 total) sits in the same conjunction
     as figures quoted from a table -- and one unquotable token fails the whole question
     regardless of how much reasoning the answer got right.

The rules below are mechanical, blind to any system answer, and re-runnable by an auditor
from the kit plus the corpus:

  R1 NUMBER NORMALISATION - commas inside digit runs are stripped on BOTH sides before
     matching, so 1,42,000 == 142000, and a grouped figure yields ONE anchor, not four.
  R2 CORPUS-PROVABLE ALIASES - an acronym anchor is satisfied by any corpus phrase whose
     significant-word initials match it (Computer Science and Engineering -> CSE; Digital
     Personal Data Protection Act -> DPDP by prefix). Aliases are DERIVED from the corpus,
     never typed here, and each is printed with the phrase it came from in the audit file.
  R3 REQUIRED vs BONUS - an anchor with no corpus-provable form (verbatim, normalised or
     aliased) cannot be quoted from the documents, so it is a DERIVED anchor: recorded as
     `bonus` and scored as its own sub-metric. This is deliberately not the same as
     deleting it. Those anchors are the arithmetic questions -- the only part of the
     GLOBAL slice that measures reasoning rather than retrieval -- so they stay visible,
     just not conjoined with the quotable ones.

Short anchors (<=3 chars) additionally match on word boundaries, so an 'AI' anchor stops
matching the "ai" inside chair/maintain/available/said.

Writes tests/eval/golden_stress_v2.json + golden_stress_v2_audit.md. The v1 file is NOT
touched and remains the decision variable; v2 is reported alongside it.

Usage:  python tests/eval/derive_gold_v2.py
"""
import json
import re
from collections import Counter
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
KIT = PROJECT_ROOT / "Dataset" / "Untested stresskit as of 4pm 18-08-2026" / "golden"
CORPUS = PROJECT_ROOT / "data" / "tenants" / "tenant_stress" / "parsed"
OUT = PROJECT_ROOT / "tests" / "eval" / "golden_stress_v2.json"
AUDIT = PROJECT_ROOT / "tests" / "eval" / "golden_stress_v2_audit.md"

# A grouped number is ONE anchor: 2,96,35,000 / 1,42,000 / 51.30 / 94.1
GROUPED_NUM_RE = re.compile(r"\b\d{1,3}(?:,\d{2,3})+(?:\.\d+)?\b|\b\d+(?:\.\d+)?\b")
CODE_RE = re.compile(r"\b[A-Z]{2,5}\b")
PROPER_RE = re.compile(r"\b(?:Dr\.?\s+|Shri\s+|Smt\.?\s+)?[A-Z][a-z]+(?:\s+[A-Z][a-z]+)+\b")
# Capitalised phrases in the corpus that an acronym could abbreviate. Uses [^\S\n] (space
# but NOT newline) rather than \s: with \s the pattern strides across paragraph breaks and
# invents phrases like "Credit Structure\n\nEvery", whose initials happen to spell CSE —
# which would then license an answer saying "Credit Structure Every" to satisfy a CSE
# anchor. An alias must come from a phrase that actually reads as one phrase.
_SP = r"[^\S\n]"
PHRASE_RE = re.compile(rf"\b(?:[A-Z][a-z]+)(?:{_SP}+(?:of|and|for|the|in){_SP}+|{_SP}+)"
                       rf"(?:[A-Z][a-z]+)(?:(?:{_SP}+(?:of|and|for|the|in){_SP}+|{_SP}+)[A-Z][a-z]+){{0,4}}\b")
JOINERS = {"of", "and", "for", "the", "in", "a"}
STOP = {"the", "and", "for", "are", "not", "only", "per", "cent", "with", "that", "this",
        "from", "his", "her", "two", "one", "who", "what", "which", "department",
        "heads", "head"}


def strip_group_commas(text: str) -> str:
    """R1: 1,42,000 -> 142000, applied identically to golds and to answers."""
    return re.sub(r"(?<=\d),(?=\d)", "", text)


def load_corpus() -> str:
    return "\n".join(p.read_text(encoding="utf-8", errors="replace")
                     for p in sorted(CORPUS.glob("*.md")))


def build_alias_map(corpus: str) -> dict:
    """R2: acronym -> corpus phrases whose significant-word initials match it.

    Sub-phrases are registered too, not just whole matches. "Department of Computer
    Science and Engineering" abbreviates to DCSE, but the acronym in use is CSE — which is
    the SUB-phrase "Computer Science and Engineering". Registering only whole matches makes
    the alias depend on where the sentence happened to start, so the same corpus fact
    resolves or fails according to punctuation.
    """
    aliases = {}
    for phrase in dict.fromkeys(PHRASE_RE.findall(corpus)):
        tokens = re.split(r"(\s+)", phrase)                    # keep separators
        words = [(i, t) for i, t in enumerate(tokens)
                 if t.strip() and t.lower() not in JOINERS]
        for a in range(len(words)):
            for b in range(a + 2, min(a + 6, len(words)) + 1):  # 2..5 significant words
                span = words[a:b]
                text = "".join(tokens[span[0][0]:span[-1][0] + 1])
                initials = "".join(w[1][0].upper() for w in span)
                # exact ("Computer Science and Engineering" -> CSE) and prefix
                # ("Digital Personal Data Protection Act" -> DPDPA covers DPDP)
                for n in range(2, len(initials) + 1):
                    bucket = aliases.setdefault(initials[:n], [])
                    if text not in bucket:
                        bucket.append(text)
    return aliases


def corpus_forms(anchor: str, corpus_norm: str, corpus: str, aliases: dict) -> tuple:
    """(provable, accepted_forms) for one anchor.

    `provable` answers a question about the CORPUS: can this anchor be quoted from the
    documents at all, verbatim or under an alias? That is what decides required vs bonus.

    `accepted_forms` answers a different question, about the ANSWER: which surfaces should
    count as having said it? Always both — the anchor itself and any corpus expansion.
    Requiring only the expansion would reject an answer that correctly writes "CSE", which
    is the same class of mistake as the original scorer rejecting one that writes
    "Computer Science and Engineering", just pointing the other way.
    """
    forms = [anchor]
    verbatim = bool(re.search(rf"\b{re.escape(anchor)}\b", corpus_norm, re.IGNORECASE))
    aliased = False
    if anchor.isupper() and anchor in aliases:
        for phrase in aliases[anchor]:
            if phrase in corpus and phrase not in forms:
                forms.append(phrase)
                aliased = True
    return (verbatim or aliased), forms


def derive_gold_v2(ans: str, corpus: str, corpus_norm: str, aliases: dict,
                   answer_anchors: list | None = None, cited: str | None = None) -> dict:
    """`answer_anchors`, when the question generator supplies them, are what the question
    actually asks for — stated at generation time instead of inferred from prose.

    Inference is fine for a fact ("168 credits"), but it cannot tell the subject of a
    multi-hop answer from its scenery: "The HPC Laboratory belongs to Computer Science and
    Engineering, headed by Dr. Meera Joshi" yields three proper nouns, and a scorer that
    accepts any of them passes an answer that names the laboratory to a question asking who
    heads the department. Each stated anchor becomes its own required group (AND), so the
    answer must contain the thing that was asked for.
    """
    # Provability is judged against the CITED documents when the question names them.
    # Against the whole corpus, a computed count like "4" is "provable" purely because the
    # digit 4 appears somewhere unrelated — which would file a figure the documents never
    # state as a required anchor, and mark the retriever wrong for not inventing it.
    scope = cited if cited is not None else corpus
    scope_norm = strip_group_commas(scope)

    if answer_anchors:
        required = []
        for a in answer_anchors:
            _provable, forms = corpus_forms(str(a), scope_norm, scope, aliases)
            required.append(forms)
        return {"mode": "anchors", "required": required, "bonus": [],
                "aliases": {}, "kind": "stated"}

    ans_norm = strip_group_commas(ans)
    anchors = list(dict.fromkeys(
        GROUPED_NUM_RE.findall(ans_norm) + CODE_RE.findall(ans)))
    required, bonus, alias_used = [], [], {}

    for a in anchors:
        provable, forms = corpus_forms(a, scope_norm, scope, aliases)
        if provable:
            required.append(forms)          # a group: ANY form satisfies it
            if forms != [a]:
                alias_used[a] = forms[1:]
        else:
            bonus.append(a)                 # R3: derived / not quotable

    if not required and not bonus:
        propers = list(dict.fromkeys(PROPER_RE.findall(ans)))
        if propers:
            return {"mode": "anchors", "required": [propers], "bonus": [],
                    "aliases": {}, "kind": "proper_noun"}
        words = sorted({w for w in re.findall(r"[A-Za-z]{4,}", ans)
                        if w.lower() not in STOP}, key=len, reverse=True)[:2]
        return {"mode": "anchors", "required": [words] if words else [], "bonus": [],
                "aliases": {}, "kind": "soft_prose"}

    return {"mode": "anchors", "required": required, "bonus": bonus,
            "aliases": alias_used,
            "kind": "derived_only" if not required else "quotable"}


def load(name):
    return json.loads((KIT / name).read_text(encoding="utf-8"))["questions"]


def main(kit=None, corpus_dir=None, out=None, audit=None, tenant_id="tenant_stress",
         version="stress-2"):
    global KIT, CORPUS, OUT, AUDIT
    KIT = Path(kit) if kit else KIT
    CORPUS = Path(corpus_dir) if corpus_dir else CORPUS
    OUT = Path(out) if out else OUT
    AUDIT = Path(audit) if audit else AUDIT

    corpus = load_corpus()
    corpus_norm = strip_group_commas(corpus)
    aliases = build_alias_map(corpus)

    questions, audit = [], []
    for route, fname in [("FACT", "golden_fact.json"),
                         ("GLOBAL", "golden_global.json"),
                         ("LOCAL", "golden_local.json")]:
        for q in load(fname):
            docs_cited = q.get("supporting_docs") or (
                [q["source_doc"]] if "source_doc" in q else [])
            cited_text = "\n".join(
                (CORPUS / d).read_text(encoding="utf-8", errors="replace")
                for d in docs_cited if (CORPUS / d).exists()) or None
            gold = derive_gold_v2(q["expected_answer"], corpus, corpus_norm, aliases,
                                  q.get("answer_anchors"), cited_text)
            item = {"id": q["id"], "route": route, "query": q["question"], "gold": gold}
            if "phrasing" in q:
                item["phrasing"] = q["phrasing"]
            questions.append(item)
            audit.append((q["id"], route, gold, q["expected_answer"]))

    for q in load("golden_unanswerable.json"):
        questions.append({"id": q["id"], "route": "FACT", "query": q["question"],
                          "gold": {"mode": "insufficient", "expect": []},
                          "gap_type": q.get("gap_type"), "unanswerable": True})

    spec = {"version": version,
            "tenant_id": tenant_id,
            "description": ("Stresskit golds re-derived from the same human expected_answer "
                            "prose with digit-group normalisation, corpus-provable acronym "
                            "aliases, and required/bonus separation. v1 remains the decision "
                            "variable; this file is reported alongside it."),
            "questions": questions}
    OUT.write_text(json.dumps(spec, indent=2, ensure_ascii=False), encoding="utf-8")

    kinds = Counter(g["kind"] for _, _, g, _ in audit if "kind" in g)
    n_alias = sum(1 for _, _, g, _ in audit if g.get("aliases"))
    n_bonus = sum(1 for _, _, g, _ in audit if g.get("bonus"))

    lines = ["# Stresskit gold derivation v2 — audit\n",
             "Every anchor below was classified mechanically from the corpus. `required` "
             "groups are satisfied by ANY listed form (that is what an alias is); `bonus` "
             "anchors have no corpus-provable form — they are figures the human gold "
             "derived by arithmetic, scored as their own sub-metric rather than conjoined "
             "with the quotable anchors.\n",
             f"- {len(questions)} questions · {n_alias} use a corpus-derived alias · "
             f"{n_bonus} carry bonus anchors · kinds: {dict(kinds)}\n",
             "| id | route | required (any-of groups) | bonus (derived) | aliases | expected_answer |",
             "|---|---|---|---|---|---|"]
    for id_, route, g, ans in audit:
        req = " · ".join("/".join(grp) for grp in g.get("required", []))
        lines.append(
            f"| {id_} | {route} | {req[:90]} | {', '.join(g.get('bonus', []))} | "
            f"{'; '.join(f'{k}->{v[0]}' for k, v in (g.get('aliases') or {}).items())[:60]} | "
            f"{ans.replace('|', chr(92) + '|')[:70]} |")
    AUDIT.write_text("\n".join(lines), encoding="utf-8")

    print(f"wrote {OUT.name} ({len(questions)} questions)")
    print(f"  aliases applied: {n_alias} questions · bonus anchors: {n_bonus} questions")
    print(f"  kinds: {dict(kinds)}")
    print(f"  audit -> {AUDIT.name}")


if __name__ == "__main__":
    import argparse
    ap = argparse.ArgumentParser(description="Derive scoreable anchors from a kit + its corpus")
    ap.add_argument("--kit", default=None, help="directory of golden_*.json (human prose)")
    ap.add_argument("--corpus", default=None, help="directory of the corpus .md files")
    ap.add_argument("--out", default=None)
    ap.add_argument("--audit", default=None)
    ap.add_argument("--tenant-id", default="tenant_stress")
    ap.add_argument("--version", default="stress-2")
    a = ap.parse_args()
    main(a.kit, a.corpus, a.out, a.audit, a.tenant_id, a.version)
