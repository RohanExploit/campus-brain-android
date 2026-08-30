# Phase 0 — Clean Baseline (egress-OFF)

_Generated 2026-08-19 on branch `phase-2-retrieval-upgrade`. Pre-registered baseline
that all later phases (A: ICETIS ingestion fix, B: generation ceiling) are measured
against. **No retrieval logic was changed in Phase 0** — this is instrumentation only._

## Why re-baseline

Historical FACT/GLOBAL/LOCAL numbers were taken with `ALLOW_EXTERNAL_LLM` defaulting
**ON**, so an Ollama hiccup silently swapped in a cloud 70B — the numbers measured an
unknown mixture of models and are not trustworthy (see `PHASE_MINUS1_SECURITY.md`).
Phase −1 flipped the default OFF and made the eval **hard-force** egress off
(`tests/eval/run_eval.py::enforce_no_egress`, regression-tested in
`tests/test_eval_no_egress.py`). Every number here is the **local `qwen3:4b-instruct-2507-q4_K_M`
only**, no cloud fallback.

## Two corpora, never pooled

| Corpus | What it is | n | Purpose |
|---|---|---|---|
| `tenant_1` | Real (post-PII-eviction, 745 vectors, 71 sources) | 46 (FACT 11) | Continuity with historicals; polluted; small-n |
| `tenant_stress` | Synthetic stresskit, fully span-annotated | 120 (FACT 66) | The measurement instrument (statistically powered) |

`tenant_1`'s FACT n=11 is noise-tier (Wilson 95% CI ≈ ±29pp). The stresskit's n=66
FACT is why it exists — a 20pp change is detectable there, not on tenant_1.

## Result 1 — tenant_1 (real corpus, cleaned index)

Run: `python tests/eval/run_eval.py` (golden_set.json, 46 q).

| Route | Clean egress-OFF | Old (contaminated) |
|---|---|---|
| FACT | **36.4% (4/11)** | 27.3% (3/11) |
| GLOBAL | 42.9% (3/7) | — |
| LOCAL | 50.0% (3/6) | — |
| TABULAR | **95.5% (21/22)** | 95.5% |
| **Overall** | **67.4% (31/46)** | 60.87% |

- **TABULAR invariant held** (21/22; T22 is the known-fail). Confirms the security
  rebuild and the stress-tenant ingest did not disturb the tabular path.
- FACT rose 3→4 vs the contaminated historical. Most plausible cause: Phase −1 evicted
  5024 bulk-PII email chunks (5769→745 vectors), removing retrieval poison from the
  FACT top-k. Not attributable to any retrieval change (there were none).
- ICETIS questions (F08/F09/F10) remain unanswerable — the dropped-at-ingestion
  brochure is still absent from the served index. This is Phase A.

## Result 2 — tenant_stress (the instrument)

Ingested clean into a fresh `tenant_stress` (12 corpus md → 74 chunks → 74 vectors;
PII guard did not trip — synthetic corpus). Entity graph + community summaries built
for the GLOBAL/LOCAL routes. `tenant_1`, `tabular.duckdb`, `analytics.duckdb` untouched
(checksums verified unchanged).

Run: `python tests/eval/run_eval.py --golden tests/eval/golden_stress.json`.

| Slice | Accuracy | Notes |
|---|---|---|
| FACT (all answerable) | **50.0% (33/66)** | end-to-end, includes misrouting |
| &nbsp;&nbsp;FACT lexical | 52.6% (10/19) | |
| &nbsp;&nbsp;FACT paraphrase | 48.9% (23/47) | ≈ lexical → reranker unlikely to help (see below) |
| GLOBAL | 18.2% (4/22) | synthesis-hard + 9/22 misrouted to TABULAR |
| LOCAL | 50.0% (10/20) | |
| UNANSWERABLE (abstain=correct) | 41.7% (5/12) | 7/12 misrouted to TABULAR → error, not abstention |
| Route classification (answerable) | **44.4%** | ← the dominant failure mode |

### The headline finding: routing, not retrieval, is the FACT bottleneck

**FACT accuracy _conditional on the question actually reaching the FACT path_ = 28/29
= 96.6%.** When a FACT question routes correctly, retrieval + generation answers it almost
every time. The problem is upstream: of 66 FACT questions, only **29 route to FACT**;
**31 misroute to TABULAR**, 4 to LOCAL, 2 to ERROR.

The router classifier (an LLM prompt) decides on the question's *surface form* — "What is
the annual tuition for an OBC student?" looks like a database query — **blind to what the
tenant's corpus actually contains.** `tenant_stress` answers fees/credits/rules from
*documents*, but the classifier ships those questions to a TABULAR path that (here) has no
`tabular.duckdb`, so they hard-fail (`source PII store not found`) or abstain.

Implication for the phase plan:
- **The entire FACT gap (50% → ~96% ceiling) is routing, not retrieval quality.** A
  cross-encoder reranker or embedder swap would move the 29 already-correct questions
  ≈0 — it operates *after* the router has already discarded the other 37.
- **New highest-yield lever: a graceful TABULAR-miss → FACT fallback** (when TABULAR finds
  no DB / empty result, retry as FACT). That alone would recover ~31 questions at the
  observed ~96% FACT hit-rate.
- **paraphrase ≈ lexical** (48.9% vs 52.6%) — the stresskit's built-in reranker diagnostic
  says a reranker would _not_ differentially help. Confirms the council's reranker-deferral.

**Caveat (instrument bias):** the stresskit deliberately contains no TABULAR data
(README: "TABULAR is at 95.5% and needs no help; this kit … [goes] nowhere near it"). A
real mixed tenant would have some of these questions legitimately answered by TABULAR, so
the 47% misroute rate is an upper bound. But the *mechanism* — corpus-blind routing with no
fallback when the routed path is empty — is real and reproduces on any document-only tenant.

## Scoring methodology (transparency on bias)

The stresskit ships prose gold answers, not `run_eval`'s `{mode, expect}` schema. The
adapter (`scratchpad/adapt_stress_golden.py` → `tests/eval/golden_stress.json`) derives
a **deterministic, auditable** scorer per question:

- **Anchors** extracted from each gold answer: numbers/percentages, short all-caps codes
  (FF, XX, AB, CSE), proper-noun phrases (Dr. Vasant Rane).
- Has ≥1 hard anchor (number/code) → `contains` (**every** anchor must appear).
- Else proper nouns → `contains_any`.
- Else soft prose → `contains_any` on the 2 longest content words (**13/66 FACT flagged
  NEEDS_MANUAL** — see audit table `scratchpad/stress_scorer_audit.md`).
- Unanswerable → `insufficient` (refusal-marker scorer); abstain = correct.

**Known scorer limitations** (documented so deltas, not absolutes, are trusted):
1. Yes/no questions can false-pass — e.g. F004 gold "No, AB is…" scores on the token
   `AB`, so a wrongly-polarised "Yes, AB is failing" would also match. Affects a handful
   of FACT items.
2. Soft-prose `contains_any` over-credits (either of two words passes).
3. Multi-number `contains` can over-match on a lone digit (e.g. `4` in `4,25,000`).

Because the scorer is **fixed and deterministic**, it applies identically before/after a
change, so the **paired McNemar delta across phases is valid even where absolute
calibration is imperfect**. Absolute numbers should be read with the limitations above.

## Invariants asserted (all held)

- TABULAR 21/22 (T22 known fail).
- `tabular.duckdb` sha256 prefix `e9cdb8a4…` unchanged.
- `analytics.duckdb` sha256 prefix `230bb54e…` unchanged.
- `tenant_1` index = 745 vectors (post-PII-eviction count) unchanged.
- Egress forced OFF for every eval run (guard + regression test).

## Candidate Phase-A levers (ranked by measured evidence)

Phase 0 changes the ranking the council produced (which only had tenant_1 to look at):

1. **Router TABULAR-miss → FACT fallback (NEW, stresskit-derived).** Biggest measured lever:
   recovers ~31/66 FACT questions at the observed ~96% FACT hit-rate. Also a real robustness
   fix for any document-only tenant. **Needs a tenant_1 confirmation run** before committing
   (tenant_1 route-classification was 89%, so the effect there is smaller — this lever is
   corpus-dependent).
2. **ICETIS ingestion drop (council, tenant_1).** Still valid: F08/F09/F10 unanswerable
   because the brochure is absent from the served index. Fix = re-ingest + a post-embed
   assert that every `chunked/*` source appears in the index. ~+3pts on tenant_1.
3. **Generation ceiling / abstention (Phase B).** On the stresskit, generation is *not* the
   ceiling once routing+retrieval land the context (96.6% conditional). Re-scope Phase B to
   GLOBAL synthesis (18.2%) and abstention quality, not FACT extraction.

## What Phase 0 does NOT claim

No retrieval-technique conclusion beyond "reranker/embedder-swap won't move FACT" (supported
by paraphrase≈lexical and the 96.6% conditional). These are the **starting lines**; every
later phase is measured as a **paired McNemar delta** off these numbers on the stresskit,
never pooled with tenant_1.

## Reproduce

```
# 1. ingest stresskit into a fresh tenant (copy .md → chunk → embed → faiss → graph)
python tests/eval/ingest_stress.py all      # Ollama must be up; writes data/tenants/tenant_stress/ (gitignored)
# 2. adapt golden to run_eval schema
python tests/eval/adapt_golden.py           # → tests/eval/golden_stress.json (committed)
# 3. run, egress forced off
python tests/eval/run_eval.py --golden tests/eval/golden_stress.json --out baseline.json
```
Committed artifacts: `tests/eval/{ingest_stress,adapt_golden}.py`,
`tests/eval/golden_stress.json`, `tests/eval/golden_stress_scorer_audit.md`. The
`tenant_stress` data dir is gitignored (regenerable via step 1). `*_results.json` (raw
answer rows) is gitignored — the stress corpus has no student PII, but the guard is global.
