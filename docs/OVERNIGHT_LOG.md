# Overnight autonomous run — 2026-08-19

Branch `phase-2-retrieval-upgrade`. Autonomous, pre-registered decision rules; user away.
Baseline = commit `5c996e2` (Phase 0). See `docs/PHASE_0_BASELINE.md`.

## Morning summary
_(written last)_

## Guardrails (checked after every change; fail → revert change, log, continue)
- TABULAR 21/22; `tabular.duckdb`/`analytics.duckdb` sha256 unchanged; egress OFF; suite ≥230p/1s.
- No commit on red. tenant_1 index backed up before any rebuild; PII guard (threshold=5) stays on.
- Product-decision ambiguity → SKIP + log. Nothing irreversible.
- Stop + report if: 2 phases fail gate / invariants break unrecoverably / Ollama dies.

## Pre-registered baselines (from 5c996e2, temp=0 deterministic)
- **stress** (n=120): FACT 33/66 (50%) end-to-end · route-class 44.4% · FACT-conditional 28/29 (96.6%) · GLOBAL 4/22 (18.2%) · LOCAL 10/20 (50%) · abstain 5/12 · overall 43.3%.
- **tenant_1** (n=46): FACT 4/11 (36.4%) · GLOBAL 3/7 · LOCAL 3/6 · TABULAR 21/22 · overall 67.4%.

---

## A.1 — Router TABULAR-miss → FACT fallback ✅ ACCEPTED (commit below)

**Change:** `config.TABULAR_FACT_FALLBACK` (default ON). In `retrieval/router.py`: when a
query is classified TABULAR but the tenant has no `tabular.duckdb` (document-only tenant),
skip the void tabular route and answer from the FACT vector path, reporting the route as
FACT so the answer is synthesised (not returned raw). A secondary empty/except guard covers
tenants that HAVE a db but a specific lookup yields nothing. FACT retrieval extracted to
`_fact_context()`.

**Two-step debug:** first cut (trigger on empty/exception) gave only +1 FACT — the DB-lookup
returns a non-empty graceful sentinel (`"No tabular/student data available…"`), not empty/raise,
so the guard missed. Fixed by a no-`tabular.duckdb` fast-path check at the branch top → catches
every no-db case regardless of how each lookup fails.

**Measured delta (stress n=120, egress-off, temp=0):**

| slice | before (5c996e2) | after A.1 | Δ |
|---|---|---|---|
| FACT (all) | 50.0% (33/66) | **93.9% (62/66)** | **+29 q** |
| &nbsp;FACT lexical | 52.6% | 94.7% | |
| &nbsp;FACT paraphrase | 48.9% | 93.6% | |
| GLOBAL | 18.2% (4/22) | 31.8% (7/22) | +3 |
| LOCAL | 50.0% | 60.0% | +2 |
| abstention | 41.7% (5/12) | 91.7% (11/12) | +6 |
| route-class | 44.4% | 75.0% | +30.6pp |
| overall | 43.3% | **76.7%** | +33.4pp |

**Regression gate — tenant_1 (has a real db, guard must not fire): IDENTICAL.**
FACT 4/11 · GLOBAL 3/7 · LOCAL 3/6 · TABULAR 21/22 · overall 67.4% — byte-for-byte the
Phase-0 baseline. Suite 230p/1s. duckdb checksums unchanged. ruff clean. New hermetic tests
`tests/test_router_tabular_fallback.py` (3).

**Caveat:** recovered questions convert at the FACT-conditional rate, and some tabular-shaped
FACT questions (e.g. "OBC tuition" → Rs 71,000) still miss on *retrieval* (wrong chunk pulled),
not routing — that residue is the ceiling A.2/embedder work would chase, not this lever.

## A.2 — ICETIS re-ingest + source-coverage assert ✅ ACCEPTED (commit below)

**Root cause of the ICETIS drop:** the served index was **stale**, not mis-filtered. Phase-1
rebuilt tenant_1 by *filtering the old 5769-vec index* (evicting bulk PII) rather than
re-embedding from `chunked/`. ICETIS (28 chunks) + "Rutuja fees" were chunked *after* the
original index build, so they were never in the old index and the filter-rebuild couldn't
add them. Diagnosis: 75 chunked sources vs 71 indexed; the 4 missing = 2 bulk-PII (correctly
evicted) + ICETIS + Rutuja-fees (wrongly absent). PII guard was NOT the cause (ICETIS has 2
email chunks, threshold is 5).

**Fix:** re-embed tenant_1 from `chunked/` (PII guard still active) + rebuild faiss. Added a
durable **coverage assert** in `ingestion/embed.py`: every chunked source must be either
embedded or explicitly PII-dropped, else raise — so a silent drop can never recur.

- Index **745 → 774 vectors** (+29 = ICETIS 28 + Rutuja 1). Coverage assert: 73 embedded + 2
  PII-dropped = 75 chunked ✓.
- **PII re-verified:** bulk sources `Indian_Students_Data.md`/`students.md` absent; only 4
  legit author-contact email chunks remain (≤2/source) — same policy as Phase-1, F05-safe.

**Measured delta — tenant_1 (n=46):**

| Route | before A.2 | after A.2 |
|---|---|---|
| FACT | 36.4% (4/11) | **63.6% (7/11)** (+3: F08/F09/F10 now answerable) |
| TABULAR | 95.5% (21/22) | **95.5% (21/22)** (invariant held) |
| GLOBAL / LOCAL | 3/7 · 3/6 | 3/7 · 3/6 (unchanged) |
| overall | 67.4% | **73.9%** |

**Gates:** suite 233p/1s · tabular/analytics.duckdb checksums unchanged (embed doesn't touch
duckdb) · ruff clean. Index backed up at `embeddings_backup_pre_icetis_20260819/` (gitignored).
**Invariant update: tenant_1 index is now 774 vectors, not 745.**

## B — GLOBAL synthesis + abstention ⏸️ DIAGNOSED, FIX DEFERRED (no commit)

**Root cause found:** the GLOBAL route dumps **every** community summary as context —
58,819 chars (~14.7k tokens) for tenant_stress's 110 communities — into a `num_ctx=2048`
window. Ollama silently truncates ~86%, so the model synthesises from an arbitrary prefix.

**Experiment (reverted):** rank summaries by query-term overlap and pack the top ones under
a 5000-char budget (`get_relevant_summaries`). Result: GLOBAL **31.8% → 22.7% (worse)**,
overall 76.7% → 75.0%. Focused retrieval *hurts* GLOBAL because these questions need
**breadth** (15/22 are cross-document synthesis; "summarize the themes" wants coverage, not
the single most-relevant community). Arbitrary truncation of all-summaries beat focused
selection. **Reverted per guardrails (not a clear win).**

**Recommendation (daylight work, needs design review):** the correct fix is a **map-reduce /
hierarchical GraphRAG** GLOBAL: run the LLM over each community summary (or batches) to
extract query-relevant points, then reduce those into a final answer — instead of one
truncated pass. This is a real feature, not a tuning tweak; it also interacts with the
over-fragmentation (110 communities from 74 chunks → Louvain resolution likely too fine).
Both deserve attention when you're at the keyboard, not an unattended commit. Community
summaries were also visibly noisy at ingest (one chunk produced degenerate JSON on the 4B
extractor) — worth auditing summary quality before investing in map-reduce.

---

## Morning summary

Three-item queue run autonomously off the Phase-0 baseline (`5c996e2`). **2 accepted &
committed, 1 diagnosed & deferred.** All invariants held throughout; every accepted change
passed its regression gate before commit.

| Phase | Outcome | Headline | Commit |
|---|---|---|---|
| A.1 router fallback | ✅ committed | stress FACT **50% → 93.9%**, overall 43.3% → 76.7%; tenant_1 zero-regression | `9d3a44c` |
| A.2 ICETIS re-ingest | ✅ committed | tenant_1 FACT **36.4% → 63.6%**, overall → 73.9%; index 745→774; TABULAR 21/22 held | `9083a9b` |
| B GLOBAL synthesis | ⏸️ deferred | diagnosed num_ctx truncation; relevance-hack made it worse → needs map-reduce (daylight) | — |

**Net after the night:**
- **stress** (instrument): FACT 50% → **93.9%** · overall 43.3% → **76.7%** · route-class 44% → **75%** · abstention 42% → **92%**.
- **tenant_1** (real): FACT 36.4% → **63.6%** · overall 67.4% → **73.9%** · TABULAR **21/22 held**.

**Guardrails never tripped:** suite 233p/1s at each gate; `tabular`/`analytics.duckdb`
checksums unchanged; egress OFF; PII re-verified after the tenant_1 re-embed (bulk rosters
absent). tenant_1 index backed up pre-re-embed.

**New config knobs (both default ON, safe):** `TABULAR_FACT_FALLBACK`.
**Invariant update:** tenant_1 index is now **774 vectors** (was 745).

**Open items for you:**
1. Decide GLOBAL direction (map-reduce GraphRAG vs. coarser Louvain resolution) — see B above.
2. F05 (author-contact email) still fails on tenant_1 — separate from ICETIS; low priority.
3. The stress `paraphrase ≈ lexical` result stands → reranker/embedder-swap still not worth it.
