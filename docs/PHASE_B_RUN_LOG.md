# Phase-B autonomous run — 2026-08-21

Branch `phase-2-retrieval-upgrade`, from `91967e5`. Owner away; pre-registered gates from
`docs/PHASE_B_COUNCIL_VERDICT.md`. Every number below came from the split harness
(`run_eval.py --answers` → `score_answers.py`), so every scoring claim can be replayed
from the frozen answer files without a GPU.

## Preflight

`.venv312` pinned (bare `python` is 3.10 and cannot even collect 11 of the test files).
233 passed / 1 skipped · ruff clean · `tabular.duckdb` `e9cdb8a4…54bde` ·
`analytics.duckdb` `230bb54e…e25be5` · 5.1 GB free RAM, no competing GPU job ·
Ollama up. Backups taken before anything ran:
`data/tenants/{tenant_1,tenant_stress}/{graph,embeddings}_bak_20260821T001029Z` and
`tenant_1/duckdb_bak_20260821T001029Z` — `data/` is entirely gitignored, so file copies
are the only rollback that exists.

## Baseline (P2) — the anchor every later number is measured against

| corpus | overall | FACT | GLOBAL | LOCAL | TABULAR | route-class | ans len (med) | latency med/max |
|---|---|---|---|---|---|---|---|---|
| tenant_stress (n=120) | 75.8% (91) | 73/78 | 6/22 | 12/20 | — | 77.5% | 88 ch | 1.8 s / 17.4 s |
| tenant_1 (n=46) | 71.7% (33) | 7/11 | 2/7 | 3/6 | **21/22** | 89.1% | 148 ch | 1.05 s / 17.2 s |

**Reproducibility finding, unprompted and important:** this differs from the overnight log
(stress 76.7% / GLOBAL 7; tenant_1 73.9% / GLOBAL 3) *with no code change between them*.
Re-running the identical binary moves GLOBAL by ±2 questions. **At temp=0 the GLOBAL slice
is not deterministic**, so on n=22 (and n=7) a ±2 swing is indistinguishable from any
intervention that isn't worth ≥5 questions. Every GLOBAL delta below is read against that.

## Accepted

### P1 — RUN/SCORE split (`124e4bc`)
Answers are frozen to JSONL as they are produced; scoring is a CPU replay. This is what
resolved the debate's central dispute — a scorer can no longer be authored after seeing
the numbers it judges, because the answers already exist on disk. Also: abort after 3
consecutive generation failures (a dead Ollama otherwise completes the eval at ~0% and a
gated harness "reverts" a good change), `--resume` for a killed run, and answer-length +
latency in every summary.

### P3 — provenance and honesty (`d3df465`, `7aab7fb`)
- Deleted the GLOBAL prompt's mandated `### 3. Citations / Sources` section. Its context
  is community summaries generated from bare entity *names*, which contain no source
  names — the instruction could only be satisfied by invention, so every passing GLOBAL
  answer was shipping a fabricated citation block.
- Real provenance: `metadata["sources"]` from the retrieval path; dashboard `SourcesPanel`;
  telegram admin-only. Labels never enter the context string — 15/120 stress and 16/46
  tenant_1 golds pass on source-label text *alone*, so labels in context would inflate
  every subsequent score.
- Student-identity role gate: mechanism only, `PII_ROLE_GATE=0`. `AllowlistManager.get_role()`
  finally reads the `roles` map that has sat unread in `allowlist.json`; both bots now send
  the sender id they previously discarded; roster templates render identity through
  `_student_label()`. Byte-equality with today's output asserted by test.
- Determinism debt: `LOUVAIN_SEED` wired (Louvain ran unseeded — 109 vs 110 communities on
  consecutive runs), ingestion LLM calls pinned to temperature 0 (were at Ollama's 0.8).

**Measured (stress, vs baseline):** FACT b=0/c=0, LOCAL b=0/c=0 — byte-identical.
GLOBAL b=2/c=0 → **INCONCLUSIVE, claimed as nothing** (needs 5). Answer length unmoved
(88 ch), artifact floor unmoved (12). tenant_1: TABULAR 21/22 held.
A correctness/honesty change that moved no metric it wasn't supposed to move.

## Rejected

### P4 — context capacity (ctx 4096 + budget 9000 + k=15) — **REJECTED, reverted**

| | overall | FACT | GLOBAL | LOCAL | latency med/max |
|---|---|---|---|---|---|
| P3 | 77.5% (93) | 73/78 | 8/22 | 12/20 | 1.89 s / 18.2 s |
| P4 | **75.0% (90)** | 73/78 | 5/22 | 12/20 | **4.28 s / 55.5 s** |

Failed its pre-registered latency gate (`max < 45 s`) at 55.5 s — half a second from
`API_TIMEOUT=60`, past which `generate_answer` returns an error string, not a slow answer.
Bought nothing on any slice. This is exactly the shape the systems bench predicted from
`/api/ps` residency: 2048 is the only fully-GPU-resident setting on a 4 GB card, and 4096
costs 46% of decode throughput. The knobs (`OLLAMA_NUM_CTX`, `CONTEXT_BUDGET_CHARS`,
`FACT_TOP_K`, `fe48a2c`) stay in the tree at today's values — the experiment is now an env
var rather than a diff — but the defaults do not move.

### P5 — LOCAL vector arm (`7be2d7d`) — landed behind a flag, default unchanged

| slice | graph (default) | vector arm |
|---|---|---|
| LOCAL | 12/20 | **15/20** (b=4, c=1) |
| FACT | 73/78 | 75/78 (LOCAL-misrouted FACT questions get usable context) |
| overall | 93/120 | 96/120 |

tenant_1 canary: unchanged on every slice, TABULAR 21/22 held. Under the repaired v2 golds
the same frozen answers read LOCAL 12→16, GLOBAL 7→9, overall 93→101.

**Not accepted.** The pre-registered rule needs b≥7 at c=1; b=4 (v1) and b=5 (v2) are
INCONCLUSIVE, and the rule was registered in advance precisely so it could not be argued
past at 3 a.m. Two replication attempts died in Ollama reload thrash (free RAM ~1.8 GB) —
the P1 circuit breaker aborted both rather than let a dead engine report ~0% and trigger a
"revert" of a good change. So the arm ships flagged **off**, and the question moves to the
bench, where n is large enough to answer it.

## The instrument was rebuilt (`8708fcf`, `e90f1e3`, `07ef7f2`)

Every open decision died on the same limit: at n=20 (LOCAL) and n=22 (GLOBAL), with a
measured ±2 run-to-run swing at temperature 0, only a ~20pp effect is resolvable. So the
benchmark was rebuilt rather than argued about.

`tests/eval/bench/` renders **30 documents** and **208 questions** from a single world
model (`world.py`), so a gold cannot be wrong about the corpus — there is no scraping step
in between. Slices: **FACT 97** (77 + 20 unanswerable) · **GLOBAL 57** · **LOCAL 54**,
corpus 26.5k chars → 69 chunks, ingested as its own `tenant_bench` (re-ingesting
`tenant_stress` would have moved the instrument every baseline above was measured against).

What makes it discriminating rather than merely larger:
- Facts are **split across documents on purpose**: a laboratory maps to a department only
  in the infrastructure register, that department's head only in the faculty handbook, its
  placement rate only in the annual report.
- Eight department profiles **share a shape and vocabulary** and differ only in names and
  numbers, so landing in *a* profile is visibly not landing in *the right* profile.
- Deliberate distractors: two faculty share a surname; custodians are technical staff who
  are never heads of department; one vendor is also a recruiter.
- Questions needing a **computed** figure are marked derived, so arithmetic is scored as
  its own sub-metric instead of being conjoined with retrieval.

`validate_bench.py` gates the benchmark before anything is measured with it, and it
**rejected 15 questions that would otherwise have shipped**: counting questions whose cited
document never states the count; four vendor→lab questions that were single-document
lookups mislabelled LOCAL; and eleven hops that the department profiles silently collapsed
when the corpus was enriched. Twelve tests now pin those properties, including
`validate_bench.main()` itself.

## First bench measurement — and it overturns the P5 reading

Both LOCAL arms, 208 questions, run back to back behind one RAM preflight
(`tests/eval/bench/run_arms.py`, 3.3 GB free, no thrash).

| slice | graph arm (default) | vector arm | McNemar |
|---|---|---|---|
| **LOCAL** | **41/54** | **41/54** | b=2, c=2 → **REJECT** |
| FACT | 84/97 | 88/97 | b=4, c=0 → inconclusive (needs 5) |
| GLOBAL | 43/57 | 46/57 | b=3, c=0 → inconclusive (needs 5) |
| overall | 168/208 (80.8%) | 175/208 (84.1%) | |
| answer length (median) | 73 ch | 92 ch | artifact floor unmoved, 39→39 |

**On questions that are verifiably multi-hop, the graph and the vector arm score
identically.** The "+3 LOCAL" measured on the old 20-question kit does not replicate at
n=54. The earlier reading was not wrong arithmetic — it was a slice too small to separate a
real effect from noise, which is exactly why the pre-registered rule refused to accept it.

Where the flag actually acts, from the frozen answers:

| expected → actual route | n |
|---|---|
| FACT → FACT | 83 |
| **GLOBAL → FACT** | **50** |
| LOCAL → LOCAL | 27 |
| **LOCAL → FACT** | **27** |
| FACT → LOCAL | 14 |
| GLOBAL → GLOBAL | **3** |

45 questions took the LOCAL path and the flag changed 37 of their answers — but only 27 of
those 45 are LOCAL questions. The vector arm's gains are on the 18 FACT/GLOBAL questions
that were **misrouted into** LOCAL, not on LOCAL work. (2 answers changed outside the LOCAL
path: the known temp-0 nondeterminism.)

**So the flag stays default-off.** Both slices that improved are inconclusive under the
pre-registered thresholds, and the slice it was built for shows nothing.

### What the bench says the real defect is

**Route classification is 54.3%.** The GLOBAL route serves **3 of 57** GLOBAL questions; 50
go to FACT. Half the LOCAL questions never reach the LOCAL path. Yet GLOBAL still scores
43-46/57 — because the FACT chunk path answers those questions perfectly well without the
community summaries. That is the council's "chunks beat summaries" claim, now confirmed
end-to-end on an instrument whose golds are known to be satisfiable.

Two caveats recorded against these numbers, so nobody over-reads them later:
- **GLOBAL's artifact floor is 19/57** — a third of that slice is passable by generic
  in-domain text. LOCAL's floor is **1/54** and FACT's 19/97, so LOCAL is the cleanest
  measurement on the bench and GLOBAL the softest.
- **Derived-figure anchors: 13/24.** The 4B model does manage some cross-document
  arithmetic here, against 1/5 on the old kit.

## Pre-registration for the confirmatory run (written BEFORE it was executed)

The routing-cell table shows the vector arm is not a LOCAL improvement at all — it is a
**robustness fix for the LOCAL path when the router misdelivers to it**:

| cell | graph arm | vector arm |
|---|---|---|
| FACT → FACT | 90.4% | 90.4% |
| GLOBAL → FACT | 84.0% | 84.0% |
| **LOCAL → LOCAL** | **81.5%** | **81.5%** — identical, no LOCAL gain |
| LOCAL → FACT | 70.4% | 70.4% |
| **FACT → LOCAL** | 64.3% | **92.9%** |
| **GLOBAL → LOCAL** | 0/4 | **3/4** |
| **GLOBAL → GLOBAL** | **1/3 (33%)** | 1/3 — the worst cell on the board |

Because the benefit is spread across whichever slice the router happens to misdeliver, a
**per-slice** endpoint cannot see it: it splits one effect into three underpowered pieces.
The aggregate over all 208 questions reads b=9, c=2 → ACCEPT on the same table.

**That number does not count.** The per-slice endpoint was the registered one, and picking
an aggregate after seeing it favour the arm is the multiple-comparisons trap this whole
protocol exists to prevent. So the aggregate is pre-registered here, before the run:

- **Endpoint:** discordant pairs over all 208 bench questions, graph arm vs vector arm,
  scored by `run_eval.score` on `golden_bench.json`.
- **Rule:** the same frozen table — ACCEPT needs b ≥ 5 at c=0, 7 at c=1, 9 at c=2, 10 at
  c=3, 12 at c=4. REJECT if c ≥ b.
- **Also required:** the artifact floor must not move materially (it was 39→39), and
  median answer length must not rise by more than 20% (73→92 ch is +26%, so this is a
  live risk and is why the floor is checked alongside it).
- **Data:** a FRESH pair of runs, not the pair that motivated this rule.
- **If it fails:** the flag stays off and this section stays in the log as a rejected
  hypothesis, not a footnote.

## Confirmatory result — primary endpoint PASSES, secondary guard FAILS

Fresh pair, same protocol, 3.8 GB free RAM:

| | graph arm | vector arm |
|---|---|---|
| overall | 167/208 (80.3%) | **177/208 (85.1%)** |
| FACT | 84/97 | 88/97 |
| GLOBAL | 45/57 | 48/57 |
| LOCAL | 38/54 | 41/54 |
| median answer length | 67 ch | 92 ch |

**Pre-registered aggregate: b=12, c=2 → ACCEPT** (needed 9 at c=2). The motivating pair read
b=9, c=2. So the primary endpoint passes twice, on independent runs, and the artifact floor
is 39→39 in both — meaning the rotated arm gained nothing, which is the direct evidence that
the extra length did not buy the result.

**And the secondary guard fails as written.** I registered "median answer length must not
rise by more than 20%". It rose **+37%** (67→92). The floor evidence argues the length is a
side effect rather than the cause — chunk context is simply wordier than `A -> REL -> B`
edges — but re-reading a guard as satisfied *after* watching the primary pass is the same
error the pre-registration was written to prevent, one level down.

**Therefore the default is NOT flipped autonomously.** `LOCAL_GRAPH_CONTEXT` stays on the
graph. What the owner is being handed is:

- the primary endpoint passing twice (b=9 and b=12, c=2 both times);
- an identified mechanism — the gains sit in the `FACT → LOCAL` (64.3% → 92.9%) and
  `GLOBAL → LOCAL` (0/4 → 3/4) cells, so this is the LOCAL path becoming harmless when the
  router misdelivers, not a retrieval improvement;
- `LOCAL → LOCAL` unchanged at 81.5%, i.e. no gain on the work the route exists for;
- one failed guard, stated rather than explained away.

Flipping it is a one-line change (`LOCAL_GRAPH_CONTEXT=0`) and reversible.

## Pre-registration #2 — GLOBAL route quality, with routing held at 100%

Only **3 of 57** GLOBAL questions reach the GLOBAL route, so the route's own quality is
currently measured on a sample of three. That also creates an ordering trap: "fix the
router" is the obvious next lever, but a correct router would deliver 54 more questions
into a route that scores 33% — improving routing accuracy while *reducing* answer accuracy.
Route quality has to be measured and fixed before routing accuracy is touched.

`run_eval.py --force-route` serves each question on the route its gold declares, bypassing
the classifier. Route QUALITY and routing ACCURACY are different failures with different
fixes, and no number that is the product of both can separate them.

Registered before running:

- **Comparison:** `--force-route` on the bench, `GLOBAL_CHUNK_FANOUT=0` (community
  summaries, today's behaviour) vs `=1` (chunk fan-out, k=30 under the same char budget).
- **Endpoint:** discordant pairs on the **57 GLOBAL questions**, scored by
  `run_eval.score`. Same frozen table: ACCEPT needs b ≥ 5 at c=0, 7 at c=1, 9 at c=2,
  10 at c=3, 12 at c=4; REJECT if c ≥ b.
- **Guards:** FACT and LOCAL slices must not regress (the fan-out shares `_fact_context`,
  so a change there would show up as collateral damage); the rotated artifact floor on
  GLOBAL must not rise by more than 2 questions. **GLOBAL's floor is 19/57 — the softest
  slice on the bench — so a gain smaller than the floor movement means nothing.**
- **Length is recorded, not gated.** The previous guard failed on a +37% rise while the
  artifact floor stayed flat, which is evidence the length rule was the wrong instrument
  for this question; the floor is the direct control and is gated instead. Writing that
  down here, in advance, rather than discovering it convenient afterwards.
- **If it fails:** `GLOBAL_CHUNK_FANOUT` stays off and the community-summary path stands,
  whatever the 33%-vs-84% observational figure suggested.

## Result #2 — the GLOBAL route is the defect, and correct routing currently HURTS

With routing pinned at 100% (`--force-route`), community summaries vs chunk fan-out:

| slice | summaries (today) | chunk fan-out |
|---|---|---|
| **GLOBAL** | **20/57 (35.1%)** | **47/57 (82.5%)** |
| FACT | 88/97 | 88/97 (b=0, c=0 — no collateral) |
| LOCAL | 31/54 | 31/54 (b=2, c=2 — unchanged) |
| overall | 139/208 (66.8%) | 166/208 (79.8%) |

**Pre-registered primary: b=28, c=1 → ACCEPT** (needed 7 at c=1). The n=3 estimate of 33%
is confirmed at n=57: **35.1%**. The community-summary path is not merely weak, it is worse
than not having a GLOBAL route at all.

**The bigger finding is what forcing correct routing did to the system:**

| | actual routing (54.3% correct) | forced routing (100% correct) |
|---|---|---|
| overall | 80.8% | **66.8%** |
| GLOBAL | 79.0% | **35.1%** |
| LOCAL | 70.4% | **57.4%** |

**Correct routing costs 14 points.** The classifier's errors are load-bearing: they dump
GLOBAL and LOCAL work onto the FACT chunk path, which answers those questions far better
than their own routes do. Anyone who had "fix the 54.3% router" as the next task — as I did
— would have shipped a metric improvement and a product regression.

This also corrects an earlier reading in this log: `LOCAL → LOCAL` at 81.5% was a **biased
subsample**. The router was sending the LOCAL route only the questions it could handle;
forced to take all 54, that route scores **57.4%**.

### The floor guard failed again, and it was the wrong guard

GLOBAL's rotated artifact floor rose 17 → 22 against a registered limit of +2. The net gain
is +27 against a floor movement of +5, so the concern the guard encodes — that the gain was
bought by dumping more corpus text — is not supported by its own numbers. But that is an
argument made after seeing the result, and a bright line was set. So, as before: **the flag
is not flipped autonomously.**

The guard itself is the problem, and it has now failed twice for the same structural reason:
an absolute cap on floor movement is not comparable to an effect size. **Corrected rule,
registered here for the confirmatory run:** `(b − c)` must exceed the floor movement by at
least the table threshold for the observed `c`. On this data that reads 27 > 5 + 7 = 12,
which passes — but the decision is taken on a FRESH pair, not this one.

## Pre-registration #3 — the LOCAL route, measured the same way

Forcing correct routing exposed the LOCAL route at **55.6-57.4%**, against the 81.5% it
appeared to score when the classifier was hand-picking its questions. GLOBAL has now been
fixed by replacing summaries with chunks; LOCAL is the same shape of defect — graph edges
rendered as `A -> REL -> B`, with the sentence they came from discarded.

Registered before running:

- **Comparison:** `--force-route`, `GLOBAL_CHUNK_FANOUT=1` fixed in both arms, varying only
  `LOCAL_GRAPH_CONTEXT` (1 = graph edges, today's default; 0 = chunk context).
- **Endpoint:** discordant pairs on the **54 LOCAL questions**, `run_eval.score`.
- **Rule (the corrected one):** `(b - c)` must exceed LOCAL artifact-floor movement plus the
  table threshold for the observed `c`. LOCAL's floor is 1/54 — the cleanest slice on the
  bench — so this is a demanding test rather than a formality.
- **Guards:** FACT and GLOBAL must not regress (`c <= b` on each).
- **If it fails:** `LOCAL_GRAPH_CONTEXT` stays on the graph, and the earlier aggregate
  result stands as the only evidence for the vector arm — which, notably, was a
  *robustness* argument about misrouting, not a claim that chunks answer LOCAL better.
  Those are different claims and this is the one that tests the second.

## Result #3 — LOCAL: chunks win 15 and lose 3, and the 3 are the interesting ones

Forced routing, `GLOBAL_CHUNK_FANOUT=1` in both arms, varying only LOCAL context:

| pair | graph edges | chunk context | b | c | net | required | verdict |
|---|---|---|---|---|---|---|---|
| first | 30/54 | 42/54 | 15 | 3 | 12 | 11 | ACCEPT |
| confirmatory | 31/54 | 42/54 | 14 | 3 | **11** | 11 | **fails by one** |

One pass, one fail, both on the boundary — so the flag does **not** flip. But the diagnostic
underneath is worth more than the verdict: **the three losses are identical in both pairs**
(BL011, BL040, BL051), while the graph arm's own run-to-run noise falls on three *different*
questions (BL020, BL035, BL041). The regressions are reproducible; the noise is not.

All three are two-hop questions whose SECOND hop lives in a document the question's own
wording does not retrieve:

- **BL011** "how many faculty in the department that runs the Advanced Manufacturing
  Laboratory" — needs lab→department, then department→faculty count. Chunks fetch the lab,
  miss the handbook row, and the answer abstains. The graph walks straight through.
- **BL051** lab→custodian, then custodian→committee. Abstains.
- **BL040** gets the chair right and then pulls the *wrong* department row — answers
  Information Technology where the gold is Civil Engineering.

So the two context types fail in disjoint places: chunk retrieval carries prose but cannot
follow a relation into a document that shares no vocabulary with the question, and graph
edges carry the relation but discard the sentence. `LOCAL_CONTEXT_MODE=hybrid` supplies
both (edges first, chunks filling the remaining budget), and is registered for measurement
under the same rule: net gain over the GRAPH arm must exceed floor movement plus the table
threshold, with FACT and GLOBAL guarded against regression.

## Escalated to the owner (not decided unattended)

1. **Who may see student identities.** `PII_ROLE_GATE=1` is a one-line flip, but every
   non-admin role list in `auth/allowlist.json` is empty, so enabling it today would
   withhold names from everyone except the admin. Populate `registrar`/`faculty` first.
2. **`students_failed_at_least` has no `LIMIT`** — an unbounded roster dump. Capping it
   changes shipped answers.
3. **Source labels are raw filenames**, and one real tenant_1 document is named after a
   student (`Rutuja fees.md`). That is why bot citations are admin-only for now.
4. **tenant_1's corpus is ~90% synthetic filler** — 20 `HR_Policy_*`, 15 `Project_Proposal_*`,
   14 `funsd_train_*`, 10 `Financial_Report_*`, 5 UCI CSVs, against ~6 genuine institute
   documents. Its 7 GLOBAL golds ask *about the corpus* ("what conferences are mentioned"),
   not what a student or registrar would ask. No retrieval work fixes that.
5. **tenant_1's GLOBAL route serves a stale partition** — `communities.json` covers 667 of
   970 current graph nodes and 5 of its 69 summaries read `"Summary generation failed."`
   Refreshing it needs a Louvain re-run, which is PII-blocked: it would pull the
   `Darekar Rutuja Dnyaneshwar` fee-receipt node into a community, whose name would then be
   broadcast by `get_all_summaries()` on every GLOBAL query.
6. **Whether the graph layer belongs in the serving path at all** — and if it stays,
   whether to fund the repair: `build_graph.py:26` uses `nx.Graph()`, destroying
   `REPORTS_TO` direction at build time; the extraction prompt declares 6 relation types
   and produces 125; 58 edge endpoints are never declared as nodes, including a
   hallucinated `'Shri Nitin Kharcho'` beside the correct `'Shri Nitin Kharche'` from the
   same chunk.

## What the night established about the measuring instrument

- **GLOBAL cannot currently be improved measurably.** A zero-comprehension dump of every
  number in the corpus scores 7/22 — today's score. With the *whole corpus* in context and
  word-boundary matching, the ceiling is 9/22: 13 of 22 golds are unsatisfiable from corpus
  text (gold `CSE` vs corpus "Computer Science and Engineering"; gold `142000` vs corpus
  `1,42,000`; gold `DPDP` vs "Digital Personal Data Protection Act"). A live probe with
  perfect recall scored 1/7, failing G001 on the abbreviation while producing 94.1 / 92.4 /
  79.4 / 60.7 correctly.
- **Two golds pass on any English answer**: G003 and L010 expect `'AI'`, which matches the
  `ai` inside *chair*, *maintain*, *available*, *said*.
- **The word-boundary fix cannot simply be adopted**: it drops tenant_1 TABULAR from 21/22
  to 19/22, because gold `BC` stops matching "OBC" — 11 of the 22 TABULAR invariant golds
  have expect tokens of ≤3 characters. It ships as a *reported* scorer, never a gate.
- **Rotated-answer artifact floor on real answers: GLOBAL 0/22, LOCAL 0/20** (answers are
  short, median 88 chars). So today's scores are not length artifacts — but any change that
  lengthens answers has to be re-checked against its own rotated arm, which is now free.
