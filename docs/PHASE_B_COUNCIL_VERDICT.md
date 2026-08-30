# Phase-B — Council + Parliament verdict (2026-08-21)

Branch `phase-2-retrieval-upgrade`, from HEAD `91967e5`. Owner away; autonomous run under
pre-registered gates. Process: five-lens expert council → a tabled motion → four-bench
adversarial debate (retrieval reform / measurement integrity / product & safety / systems &
continuity) → this ruling. All findings below were *measured* by a bench, not asserted.

## The three findings that decided the night

**1. The GLOBAL metric does not measure comprehension.** An 887-char dump of every number in
the stress corpus, with zero reasoning, scores **7/22 — exactly today's GLOBAL score**. A
perfect-recall probe (entire corpus in context) scored **1/7**: G001's answer was factually
perfect (94.1 / 92.4 / 79.4 / 60.7 with correct citations) and was marked FAIL because the
corpus writes "Computer Science and Engineering" while the gold demands `CSE`. Under
word-boundary matching the *ceiling with the whole corpus in context is 9/22* — 13 of 22 golds
are unsatisfiable from corpus text. Retrieval-free random-chunk controls (500 draws, seed 7):

| context | LOCAL /20 | GLOBAL /22 |
|---|---|---|
| random k=3 | 3.5 | 1.8 |
| random k=15 | 11.2 | 3.0 |
| random k=30 | 15.7 | 7.6 |
| whole corpus, word-bounded | 18 | 9 |

Any GLOBAL delta measured tonight would be uninterpretable **in sign**, not merely in magnitude.

**2. The graph layer is measurably net-negative on the serving path — but the evidence has a
known bias.** LOCAL graph edges contain 2/20 golds; vector k=3 contains 18/20. On five questions
`link_entities` returns a confident junk node (`'the campus'`, `'committee'`) and
`router.py:184` falls back only when `edges` is *empty*, blocking a fallback that already holds
the answer. Community summaries (58,819 chars) are **larger than the entire corpus** (28,310) and
carry no document facts — `summarize_communities.py:19` prompts with entity *names* only.
**Bias conceded:** the metric is substring-of-answer, LOCAL golds are name strings, and a
graph edge projection discards the sentence. That is partly a property of the encoding.

**3. Two live product defects outrank every score on the board.** No answer carries a citation
(`_fact_context` drops the `metadata` that `VectorSearch.search` already returns), while
`generation/answer.py:68` orders the model to emit a `### 3. Citations / Sources` block over
summaries containing no source names — every passing GLOBAL answer today ships invented
provenance. And student names + roll numbers reach any allowlisted chat user:
`allowlist.json` already holds `"roles": {admin, registrar, faculty, student}` as **dead data
read by nothing**, `telegram_bot.py:64-67` discards user identity at the bot boundary, and
`sql_templates.py:78-90` emits an unbounded `ORDER BY`-no-`LIMIT` roster that
`api/main.py:297-303` returns verbatim.

## Rulings on the four contested questions

**Q1 — Is hand re-golding legitimate?** **Rejected as a gate; resolved architecturally instead.**
The night's highest-leverage change is splitting `run_eval.py` into **RUN** (GPU; writes every
full answer + length + timings to append-only JSONL) and **SCORE** (CPU; free replay). Once
answers are frozen on disk *before* any scorer is written, every scorer variant — v1,
word-boundary, hard-token, rotated placebo, random-k control — is a free, re-runnable,
auditable replay, and self-serving scoring becomes detectable rather than preventable.
**v1 on stored answers is the sole decision variable.** Everything else is reported diagnosis.
The mechanical `derive_gold` repair (comma-normalised digit groups, corpus-provable acronym
aliases, arithmetic golds tagged `derived:true` and never edited) is admitted as a *reported*
scorer only, if time remains. No gold file is edited by the bench that implements the change.

**Q2 — Delete the graph?** **No.** One synthetic 74-chunk corpus, n=20, with a placebo floor of
11.2, is not a mandate to dismantle the product's headline architecture. Both branches survive
behind flags; no artifact is rebuilt; `self.gs`/`self.cs` are retained (three tests
`setattr` them). The default flips **only** if the LOCAL arm beats both the baseline *and* the
random-k=15 control under the McNemar rule — otherwise it lands measured, defaulted to today's
behaviour, for the owner to flip.

**Q3 — Scores or provenance?** **Provenance and safety first, and they are not competitors.**
Provenance ships in `metadata["sources"]` **only** — the context string stays byte-identical,
because 15 of 120 stress golds and 16 of 46 tenant_1 golds pass on source-label text *alone*
(G01 passes on `rag` from the RAG-MicroSim filename). Putting labels in context would hand the
model free gold tokens. The PII **mechanism** is built behind `PII_ROLE_GATE=0` with a
byte-equality test against today's output; OFF authors no policy, and flipping it is the
owner's one-line call.

**Q4 — num_ctx?** **4096, amended, or nothing.** The real ceiling is `API_TIMEOUT=60`
(`config.py:114`), not the bots' 120 s. Measured: 2048 is the only fully-GPU-resident setting
(33.9 tok/s); 4096 costs 46% of decode (18.3 tok/s, ~34 s worst case — inside 60 s); 8192 costs
79% and breaches the timeout; 10240 gives 165 s. Adopted **only** bundled with
`num_predict 512→320`, `OLLAMA_KV_CACHE_TYPE=q8_0`, `budget 5000→9000`, and **reversed pack
order** — Ollama keeps the prompt *tail* while `_fact_context` packs *best-first*, so silent
truncation currently discards the highest-ranked chunks. 8192+ is refused outright.

## Adopted (in execution order)

| # | Change | Gate | Revert |
|---|---|---|---|
| P0 | Preflight: duckdb + graph + embeddings backups, pin `.venv312`, checksums, 233p/1s, ruff, RAM ≥4 GB, Ollama health | any mismatch → abort, touch nothing | n/a |
| P1 | **Split `run_eval.py` into RUN / SCORE**; full answers + length + timing to JSONL; resume-by-id | scoring path provably identical | `git revert` |
| P2 | **Baseline gate**, unmodified HEAD on the split harness | this is the only baseline that counts | n/a |
| P3 | **T2.2** delete fabricated citation block · **T2.1** provenance via `metadata["sources"]` (filenames sanitised) · **T2.3** PII mechanism behind `PII_ROLE_GATE=0` · **T3.1** wire `LOUVAIN_SEED`, ingestion `temperature: 0` | `pytest` 233+/1, `tsc --noEmit`, byte-equality test; then one confirm eval | `git revert` per commit |
| P4 | **T1.1 amended** (ctx 4096 + num_predict 320 + q8_0 + budget 9000 + reverse pack) | TABULAR 21/22, FACT not down, max row < 45 s | `OLLAMA_NUM_CTX=2048` |
| P5 | **T1.2** LOCAL vector arm behind `LOCAL_GRAPH_CONTEXT` (keyword-only args) | beats baseline **and** random-k=15 control, McNemar | flag |
| P6 | **T1.3** GLOBAL fan-out behind `GLOBAL_CHUNK_FANOUT`, **default OFF**, k=18 | measured as an arm only; not a night goal | flag |
| P7 | Free CPU replays: word-boundary scorer, rotated placebo, random-k control, length distribution | report-only, never decides | n/a |

## Refused

Map-reduce GLOBAL (2,420 inferences/gate; ceiling no better than k=30 chunks). Coarser Louvain
and **any** Louvain re-run — it pulls node `Darekar Rutuja Dnyaneshwar` (fee receipt: name,
receipt no. 1137, address, amounts) into a community, whose name then enters
`summarize_community()`'s prompt and is broadcast by `get_all_summaries()` on every GLOBAL
query; the vector PII guard does not cover this path. Rebuilding tenant_1 summaries or
entities. Summaries-from-chunk-text on any tenant holding real documents. Any `ingestion/*.py`
`__main__` execution (every one hardcodes `tenant_1`; `parse_tabular.py` would rebuild
`tabular.duckdb`). Any tenant_1 re-embed. num_ctx > 4096. Hand re-golding as a gate. The
DEV/HOLD split (halves n to 11/10 — self-defeating against a ±20 pp rule). Reranker, embedder swap.

## Escalated to the owner — not decided tonight

1. **Who may see student names.** The mechanism ships OFF; the policy is a human decision.
2. **`students_failed_at_least` has no `LIMIT`** — an unbounded roster dump. Capping it changes
   shipped answers.
3. **tenant_1's corpus is ~90% synthetic filler** (20 `HR_Policy_*`, 15 `Project_Proposal_*`,
   14 `funsd_train_*`, 10 `Financial_Report_*`, 5 UCI CSVs; ~6 genuine institute documents).
   No retrieval work fixes a corpus that lacks the answers.
4. **Whether the graph layer should exist in the serving path at all**, and whether to fund the
   repair (`nx.Graph()` → `DiGraph` at `build_graph.py:26`; 6 declared relation types vs 125
   produced; 58 undeclared edge endpoints incl. hallucinated `'Shri Nitin Kharcho'` beside
   `'Shri Nitin Kharche'` from the same chunk).
5. **tenant_1's GLOBAL route answers from a stale partition** — `communities.json` covers 667 of
   970 current graphml nodes; 5 of its 69 summaries read `"Summary generation failed."`
   Its 3/7 GLOBAL is measured against a stale artifact. Fixing it requires the Louvain re-run
   that is PII-blocked above.

## Hard stops armed for the run

Any PII assertion firing · duckdb checksum change · `ALLOW_EXTERNAL_LLM` truthy or non-loopback
`OLLAMA_BASE_URL` · PII guard threshold moved · `ntotal != len(chunks)` or index shrink ·
3 consecutive generation error-strings (a dead Ollama otherwise reports ~0% and the harness
would "revert" a good change) · GPU OOM or CPU-offload after the ctx change · 2 gate failures ·
TABULAR below 21/22 · suite below 233 passed · ruff non-clean · any backup missing before a mutation.
