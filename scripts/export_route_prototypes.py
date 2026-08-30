"""Generate RoutePrototypes.kt: one averaged exemplar vector per query route.

Why this exists
---------------
retrieval/router.py classifies in three stages: deterministic rules, an Ollama
call, then FACT on any exception. Stage one only ever emits TABULAR or FACT, so
on a phone -- where there is no Ollama -- LOCAL and GLOBAL would be unreachable
and two of the four advertised routes would be dead code.

This replaces the LLM tier with something a phone can run: embed a handful of
exemplar questions per route with the SAME MiniLM that embedded the corpus,
average them into one prototype per route, and at query time take the nearest
prototype by cosine. Deterministic, costs nothing beyond the query embedding the
vector arm already computes, and its failure mode -- "no clear winner" -> FACT --
is the same as the backend's `except: return "FACT"`.

The margin matters more than the winner. Requiring the top prototype to beat the
runner-up by RoutePrototypes.MARGIN means an ambiguous question falls to FACT
rather than being confidently mis-routed, which is the failure the backend's
history says is expensive.

Usage:
    python scripts/export_route_prototypes.py
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

OUT_KT = ("android-app/app/src/main/java/com/kriet/campusbrain/retrieval/"
          "RoutePrototypesData.kt")

# Exemplars per route. Written against the KRIET corpus rather than in the
# abstract: the router's rule lists were tuned on a research-paper corpus, and a
# college corpus brings vocabulary ("condonation", "bonafide", "SPOC") those
# rules never saw.
#
# TABULAR is deliberately absent. Every tabular question is already caught by
# the deterministic rules in RouteRules; adding a prototype for it would let a
# fuzzy vector match override an exact rule, which is the wrong precedence.
EXEMPLARS = {
    "GLOBAL": [
        "What kinds of scholarships are available?",
        "Summarise the events calendar for this year",
        "What are the main themes of the student handbook?",
        "What services does the library offer?",
        "Give me an overview of the placement process",
        "What support is available for students overall?",
        "What are the different types of notices issued?",
        "Describe the range of student activities on campus",
    ],
    "LOCAL": [
        "Which SPOC handles hostel allotment and who do they report to?",
        "How does the attendance defaulter procedure relate to condonation?",
        "Which companies visited and what roles did they offer?",
        "Who is responsible for the anti-ragging committee and grievance redressal?",
        "What is the connection between semester registration and fee payment?",
        "Which department runs the incubation centre and who coordinates it?",
        "How do the training programmes connect to the placement drives?",
        "Who signs the bonafide certificate and which office issues it?",
    ],
    "FACT": [
        "What is the last date for fee payment?",
        "What is the minimum attendance percentage?",
        "Who is the anti-ragging committee chairperson?",
        "When does the odd semester examination begin?",
        "What is the late fee amount?",
        "Where is the student section located?",
        "What documents are needed for a bonafide certificate?",
        "How many credits does the data structures laboratory carry?",
    ],
}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=OUT_KT)
    ap.add_argument("--min-separation", type=float, default=0.05,
                    help="reject if two prototypes are closer than this")
    args = ap.parse_args(argv)

    from sentence_transformers import SentenceTransformer

    # Same model as ingestion/embed.py. A prototype from a different model would
    # sit in a different space from the query vector it is compared against.
    model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")

    prototypes: dict[str, np.ndarray] = {}
    for route, sentences in EXEMPLARS.items():
        vecs = model.encode(sentences, normalize_embeddings=True)
        mean = vecs.mean(axis=0)
        # Re-normalise: the mean of unit vectors is not itself a unit vector, and
        # the Kotlin side compares with a plain dot product.
        mean = mean / np.linalg.norm(mean)
        prototypes[route] = mean.astype(np.float32)
        spread = float(np.mean(vecs @ mean))
        print(f"{route:<7} {len(sentences)} exemplars, mean cosine to prototype {spread:.3f}")

    # If two prototypes sit almost on top of each other the margin test can never
    # separate them, and the classifier silently answers FACT to everything.
    # Better to fail here than to ship a stage that never fires.
    names = list(prototypes)
    print()
    worst = 1.0
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            sim = float(np.dot(prototypes[a], prototypes[b]))
            worst = min(worst, 1.0 - sim)
            print(f"  {a} vs {b}: cosine {sim:.3f}")
    if worst < args.min_separation:
        print(f"\nREFUSING: closest pair differs by {worst:.3f} < {args.min_separation}. "
              "The margin test could never separate them, so the prototype stage "
              "would never fire and LOCAL/GLOBAL would stay unreachable.")
        return 1

    out_path = PROJECT_ROOT / args.out
    out_path.parent.mkdir(parents=True, exist_ok=True)

    def fmt(v: np.ndarray) -> str:
        body = ", ".join(f"{x:.6f}f" for x in v)
        wrapped, line = [], "        "
        for tok in body.split(", "):
            if len(line) + len(tok) + 2 > 96:
                wrapped.append(line.rstrip()); line = "        "
            line += tok + ", "
        wrapped.append(line.rstrip().rstrip(","))
        return "\n".join(wrapped)

    lines = [
        "package com.kriet.campusbrain.retrieval",
        "",
        "import com.kriet.campusbrain.data.Route",
        "",
        "/**",
        " * GENERATED by scripts/export_route_prototypes.py -- do not edit by hand.",
        " *",
        " * One averaged, L2-normalised exemplar vector per route, in the same 384d",
        " * space as the corpus (all-MiniLM-L6-v2). Stands in for the Ollama classify",
        " * call that retrieval/router.py makes and a phone cannot.",
        " *",
        " * TABULAR has no prototype on purpose: those queries are caught by the exact",
        " * rules in RouteRules, and a fuzzy vector match must not be able to override",
        " * an exact rule.",
        " */",
        "object RoutePrototypesData {",
        "",
        "    val VECTORS: Map<Route, FloatArray> = mapOf(",
    ]
    for route, vec in prototypes.items():
        lines.append(f"        Route.{route} to floatArrayOf(")
        lines.append(fmt(vec))
        lines.append("        ),")
    lines += ["    )", "}", ""]

    out_path.write_text("\n".join(lines), encoding="utf-8")
    print(f"\nwrote {out_path}  ({len(prototypes)} prototypes x {len(next(iter(prototypes.values())))}d)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
