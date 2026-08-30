# Verified metrics — pitch pack

Every number here was produced by a command in this repo, on this hardware, and can be
re-run. Where a claim is *not* verified, it is marked as such — the last section lists what
we have **not** measured, so that a judge's follow-up question has an honest answer.

**Measurement conditions, identical for every row below**
| | |
|---|---|
| Model | `qwen3:4b-instruct-2507-q4_K_M` (4B params, Q4) via Ollama, **fully local** |
| Hardware | NVIDIA RTX 2050, **4 GB VRAM**, consumer laptop |
| Cloud calls | **zero** — `ALLOW_EXTERNAL_LLM=0`, enforced by test, not by policy |
| Temperature | 0 |
| Benchmark | 208 questions over a 30-document corpus (`tests/eval/golden_bench.json`) |
| Scorer | `tests/eval/run_eval.py::score` — frozen, identical across all rows |

---

## 1. Architecture comparison — the headline table

All three are *our implementations* of each architecture, run on the same corpus, model,
hardware and scorer. This is an architecture comparison, not a product comparison (see §7).

| Architecture | Overall | FACT (97) | GLOBAL (57) | LOCAL (54) |
|---|---|---|---|---|
| **Naive RAG** — top-3 vector chunks, no routing | 62.5% (130/208) | 88 | 34 | **8** |
| **GraphRAG-style** — community summaries + graph edges | 69.7% (145/208) | 94 | 20 | 31 |
| **This system** — routed, chunk fan-out + hybrid graph | **88.9% (185/208)** | **95** | **46** | **44** |

**+26.4 points over naive RAG. +19.2 points over the GraphRAG-style design.**
On multi-hop relational questions (LOCAL) the gap is **8/54 → 44/54 — 5.5×**.

Reproduce:
```bash
python tests/eval/run_eval.py --golden tests/eval/golden_bench.json --answers run.jsonl
python tests/eval/score_answers.py --answers run.jsonl --golden tests/eval/golden_bench.json
```

## 2. What each fix was worth, measured in isolation

Routing accuracy is held at 100% (`--force-route`) so route *quality* is separated from
route *selection* — otherwise every number is the product of two different failures.

| Change | GLOBAL | LOCAL | Overall |
|---|---|---|---|
| Baseline (community summaries + graph edges) | 20/57 (35.1%) | 31/54 | 69.7% |
| **+ GLOBAL chunk fan-out** | **47/57 (82.5%)** | 31/54 | 82.7% |
| **+ LOCAL hybrid context** | 47/57 | **44/54 (81.5%)** | **88.9%** |

Each was accepted only after passing a **pre-registered** statistical rule on **two
independent runs**:

| Change | Pair 1 | Pair 2 | Threshold | Verdict |
|---|---|---|---|---|
| GLOBAL fan-out | b=28, c=1 | b=27, c=1 | net > 11 | **ACCEPT** (both) |
| LOCAL hybrid | b=12, c=0 | b=12, c=1 | net > 5 / 7 | **ACCEPT** (both) |
| LOCAL vector-only | b=15, c=3 | b=14, c=3 | net > 11 | **REJECTED** (failed pair 2) |
| Context window 4096 | — | — | latency < 45 s | **REJECTED** (55.5 s) |

*(b = questions fixed, c = questions broken, McNemar discordant pairs.)*
**Two of four candidate improvements were rejected by our own gates.** That is the point of
having them.

## 3. Quality guarantees the accuracy number alone does not show

| Property | Measured | Meaning |
|---|---|---|
| **Abstention on unanswerable questions** | **20/20** | Never invents an answer when the corpus lacks one |
| **Artifact floor** | 19.2% | A content-free answer scores 19.2%; our 88.9% is 4.6× that |
| **Tabular accuracy (real corpus)** | **21/22 (95.5%)** | SQL over DuckDB, exact figures, no LLM paraphrase |
| **Cross-document arithmetic** | 14/24 | Honest weak spot — a 4B model quotes tables better than it adds them |
| **Median latency** | **1.85 s** | End-to-end on a 4 GB laptop GPU |
| **Provenance** | every retrieval answer | Source document + section returned in `metadata.sources` |

The **artifact floor** deserves a sentence: we score every answer against the *next*
question's gold. Anything that passes that way was earned by verbosity, not comprehension.
Ours is flat at 19.2% across every configuration — the gains are real, not length.

## 4. Tests we conducted

**280 automated tests, 50 files, 100% passing** (`pytest -q` → `280 passed, 1 skipped`).

**Retrieval & routing**
`test_router.py` · `test_router_intent.py` · `test_router_fallback.py` ·
`test_router_tabular_fallback.py` · `test_sql_route.py` · `test_template_matcher.py` ·
`test_entity_link.py` · `test_rag.py`

**Evaluation integrity** *(built this cycle — these test the tests)*
`test_bench_integrity.py` — 12 checks that the benchmark cannot silently degrade
`test_gold_derivation_v2.py` — 14 checks on gold-answer derivation
`test_bench_tabular.py` — 8 checks that SQL golds match the rows queried
`test_eval_no_egress.py` — the evaluation cannot secretly call a cloud model

**Security & privacy**
`test_pii_role_gate.py` · `test_tenant_isolation.py` · `test_api_auth.py` ·
`test_api_rbac.py` · `test_allowlist.py` · `test_answer_egress.py` ·
`test_upload_ownership.py` · `test_upload_filename.py` · `test_safe_store.py` ·
`test_tabular_queries_guardrails.py`

**Data & ingestion**
`test_parse.py` · `test_parse_tabular.py` · `test_table_extract.py` · `test_grade_scale.py` ·
`test_record_schema.py` · `test_name_extraction.py` · `test_result_pdf_adapter.py` ·
`test_production_import.py`

**Plus a 21-check production audit suite** (integrity, hallucination, prompt injection,
tenant isolation, RBAC) with 5 deployment-blocking gates.

## 5. Benchmark design — why our numbers are trustworthy

Most RAG demos are scored on questions written after seeing the answers. Ours are not.

- **Golds are correct by construction.** A single world model (`tests/eval/bench/world.py`)
  renders *both* the 30 documents and the 208 questions, so a gold cannot disagree with the
  corpus.
- **Multi-hop questions are proven multi-hop.** `validate_bench.py` locates the bridge
  entity and the answer in *disjoint documents*, so a question labelled multi-hop cannot be
  a lookup in disguise.
- **The validator rejected 15 of our own questions** before they shipped, including 11 hops
  that were silently collapsed when we enriched the corpus.
- **Answers are frozen before scoring** (`--answers` JSONL), so no scorer can be written
  after seeing the numbers it judges.
- **Every scorer variant is a free CPU replay**, so a scoring fix re-scores all historical
  runs without re-running the model.

## 6. Differentiators that are structural, not tuning

| | |
|---|---|
| **Runs on 4 GB VRAM** | A consumer laptop GPU. No A100, no cloud bill. |
| **Zero egress by default** | Student data never leaves the machine. Enforced by test. |
| **Multi-tenant isolation** | Per-tenant data trees, scoped API keys, path-traversal guards. |
| **Exact figures, not paraphrase** | Numeric answers come from SQL, not from an LLM's memory. |
| **Cites its sources** | Document + section on every retrieval answer. |
| **PII role gate** | Mechanism built and tested; ships OFF because who may see student names is a policy decision, not an engineering one. |

## 7. What we have NOT measured — state this plainly if asked

- **No head-to-head against any named product or library.** We have not benchmarked against
  Microsoft GraphRAG, LangChain, LlamaIndex, ChatGPT, Claude, Perplexity or any commercial
  RAG platform. The "GraphRAG-style" row in §1 is *our implementation* of that architecture,
  not the published library.
- **One synthetic corpus.** 30 documents, single domain, English, clean text. No OCR noise,
  no scanned PDFs, no multilingual content in the benchmark.
- **Small-model results only.** Every number is a 4B local model. A larger model would
  likely score higher on the arithmetic weak spot.
- **The real tenant corpus is mostly filler.** Production data is ~90% synthetic documents;
  only ~6 are genuine institutional documents.
- **Single-sample runs.** GLOBAL varies ±2 questions between identical runs at temperature 0;
  headline numbers are one run, not an average with a confidence interval.

**If a judge asks for a comparison against a named tool, the honest answer is: "we
benchmarked architectures, not vendors — here is the harness, it takes about 20 minutes to
add a competitor."** That is a better answer than a table we cannot defend.
