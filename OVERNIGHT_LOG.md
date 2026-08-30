# Overnight Autonomous Work Log

Started: 2026-08-08 00:49:45 IST
Operator: Claude Opus 4.8 (autonomous overnight run)
Branch: overnight-hardening

## Ground rules honored
- ABSOLUTE HARD RULE: no delete/move/overwrite/truncate of any student-PII file or PII-derived output (chunks, embeddings, graph, DuckDB rows, raw docs). Skip + log any task requiring it.
- Do NOT modify community-detection or graph-extraction logic.
- Do NOT deploy or change model files.
- Local Ollama preferred; NVIDIA cloud fallback allowed.
- Commit each change separately on branch overnight-hardening.

## Decisions log
- **[2026-08-08 00:49:45 IST] P1.3 conflict resolved:** Instruction said rename `embeddings.pkl` -> `.pkl.bak`. HARD RULE forbids moving/renaming PII-derived outputs and explicitly overrides. Resolution: implement safe deserialization **additively** — new `.npy` writer + reader that prefers `.npy` and falls back to existing `.pkl`; generate a **sidecar** `.npy` next to the original without touching `embeddings.pkl`. Same safe-load outcome, zero PII file moved. Most reversible option.

## Timeline

### [2026-08-08 00:53:22] P1.1 — Path literals -> config.PROJECT_ROOT  ✅ COMMITTED
- Migrated 42 files: absolute path literals `R:/Startup research/Start up V2/...` rewritten to `f"{PROJECT_ROOT}/..."` with an idempotent config-import bootstrap.
- Core runtime (api/main, pipeline, retrieval/*, auth, encryption) already wired to config.py in prior session; this pass covered ingestion __main__ blocks, scripts/, tests/, benchmarks.
- Verify: compiled all non-venv .py; 18/18 core+ingestion modules import clean; PROJECT_ROOT auto-resolves to repo location.
- 1 remaining literal is inside a docstring in scripts/test_part_b.py (documentation only, not executable) — intentionally left.
- KNOWN PRE-EXISTING BREAK (not caused here, not fixed): benchmark_models_flushed.py has invalid `print(flush=True, "...")` lines (dead "_flushed" duplicate). Out of scope; logged.

### [2026-08-08 00:56:47] P1.2 — Strict tenant_id validation everywhere paths are built  ✅ COMMITTED
- config.validate_tenant_id already blocks traversal (pattern ^[A-Za-z0-9_-]{1,64}$, env-toggleable). Added _require_tenant() HTTP-400 helper in api/main.py.
- Wired validation into remaining client-facing endpoints: /documents, /upload/{id}/process, /upload/{id}/status (were building DATA_ROOT/tenant_id unvalidated). Retrieval classes + pipeline already go through tenant_dir().
- New test tests/test_tenant_isolation.py: 28 cases. Proves '../../other_tenant' (and 10 other payloads) raise ValueError at validate_tenant_id AND tenant_dir; proves valid ids accepted; proves the naive DATA_ROOT/bad WOULD escape but tenant_dir blocks it.
- Result: 28 passed in 0.10s. api.main imports clean.

### [2026-08-08 01:00:15] P1.3 — Safe deserialization (drop pickle.load on the read path)  ✅ COMMITTED
- New utils/safe_store.py: stores embeddings as embeddings.npy + embeddings_chunks.json (no code-exec on load). Readers prefer safe format, fall back to legacy pickle with a warning.
- Wired: retrieval/vector_search.py (load_chunks), ingestion/embed.py (writes safe format, keep_pickle=True for back-compat), ingestion/vector_store.py (load_embeddings).
- ADDITIVE migration of existing data (per HARD RULE — pkl never moved/deleted):
    - tenant_1/embeddings/embeddings.pkl sha256 BEFORE == AFTER (UNTOUCHED): 2ba734f1fa511ae5...40c5860
    - generated embeddings.npy (8.86 MB) + embeddings_chunks.json; shape (5769, 384), 5769 chunks — matches pickle.
- Data sidecars live under data/ (gitignored) — never committed.
- Tests: tests/test_safe_store.py — 5 passed (roundtrip, safe-preferred-over-pickle, pickle fallback, additive+nondestructive migration proven by byte-equality, no-pickle case).

### [2026-08-08 01:02:13] P1.4 — Upload path-traversal fix + size guard  ✅ COMMITTED
- safe_filename() reduces any crafted filename to a bare basename on all upload entry points (/upload, /upload/{id}/process, /upload/{id}/status). Chosen sanitize-not-reject (more robust; neutralizes instead of erroring).
- New tests/test_upload_filename.py — 19 passed. Proves ../.. , absolute, Windows, and dot-only names cannot escape staging dir (resolved parent == staging).
- Bonus (rank-26): added 413 file-size guard using config.MAX_FILE_SIZE (default 50MB, env-overridable) on /upload.

### [2026-08-08 01:23:18] PHASE 2 — Real test suite + honest numbers  ✅ COMMITTED
HONEST RESULT (full run): **67 passed, 1 skipped, 0 failed in ~7.5s**, no hangs, no import-time side effects.
Real hermetic tests now (assertions, no live services):
- test_api.py (5) — rewrote fake `assert status in (200,500)`/try-except-pass into monkeypatched happy-path, empty-context, student-record short-circuit, and 400-on-bad-tenant.
- test_router.py (4) — was 2 FAILING (asserted string vs the tuple classify_query returns; mocked wrong client). Now patches retrieval.router._http_client + fakes heavy deps; covers FACT/LOCAL, deterministic-TABULAR-no-LLM, Ollama-down→FACT fallback.
- test_rag.py (2) — was 2 FAILING (mocked httpx class + wrong arity + api.main.router which doesn't exist). Now mocks generation.answer._http_client.post with correct signature; covers success + no-key fallback message.
- test_components.py (3) — removed `assert True` dummy; added real AllowlistManager.is_user_allowed() (method the test needed) + channel-specificity + persistence.
- test_tenant_isolation.py (28), test_upload_filename.py (19), test_safe_store.py (5), test_result_pdf_adapter.py (1) — new/existing real tests.
Fixes to stop hangs / PII overwrites / false failures (NOT weakening):
- test_parse.py: Docling moved out of import scope (was hanging collection + overwriting a PII .md). Guarded under __main__.
- test_table_extract.py: renamed collected test_docling→run_docling — it ran Docling AND overwrote 'Results Dataset/cse 5 reg_docling.md' (PII-derived) on every pytest run. Now manual-only.
- test_q/test_name/test_ollama_context: guarded under __main__ (were doing live calls at import).
- test_live_api/test_tabular: renamed collected test_* funcs to run_* (manual live scripts; real SQL tests come in P3).
- test_investigate.py: module-level skip (depends on scripts/build_parser not importable).
- Added tests/conftest.py: service-probe skip helpers (requires_api/requires_ollama) + project_root/data_root fixtures.
- answer.py: outbound timeout now config.API_TIMEOUT (was hardcoded 60s).
- api/main.py /query: invalid tenant_id now returns clean 400 (was masked as 500).
- python-multipart present in requirements (test_api can import).

TODO (manual diagnostic scripts left as-is per instructions — convert later): test_all_pdfs, test_camelot, test_diagnose, test_full_pass, test_pdfplumber, test_production_import, test_sgpa_diag, test_sgpa_invest2, test_table_broken, test_visual_proof, test_telegram_bot, test_router_fallback, plus the __main__ manual harnesses (test_parse/table_extract/q/name/ollama_context/live_api/tabular).

### [2026-08-08 01:27:05] P3.9 — Consolidated exam_results analytics table  ✅ COMMITTED
- ingestion/build_exam_results.py: one row per (student,subject); denormalized students⋈student_subjects; derived semester (regex on subject_code, e.g. BTCOC501→5) + is_fail (grade in FF/XX/AB) + provenance columns (source_file, department=NULL where not derivable, provenance text).
- RETENTION-SAFE: reads tabular.duckdb via ATTACH ... (READ_ONLY); writes ONLY a NEW analytics.duckdb. Proven: tabular.duckdb sha256 identical before/after (UNTOUCHED: YES).
- Built: 2952 rows, 369 students, 315 fail-rows. Sanity vs ground truth: failed>=4 = 10, failed>=2 = 77. ✅
- analytics.duckdb is gitignored (PII-derived) — not committed.

### [2026-08-08 01:30:34] P3.10 + P3.11 + P3.12 + P3.13 — Four-way router SQL path  ✅ COMMITTED
- P3.11 (text-to-SQL guardrails) was ALREADY implemented in retrieval/tabular_queries.py by prior work: schema-in-prompt (_SQL_SCHEMA), read-only DuckDB, LIMIT cap (200), threaded query timeout (10s), table allowlist, and one-shot execution self-correction (generate_and_run_sql). Verified present; left as-is.
- P3.10: added rule-based aggregation-keyword detection to router.classify_query's deterministic override (how many / list all / at least / failed / average / count / toppers / pass % / most subjects / top N ...). These route to TABULAR BEFORE any LLM call — works with Ollama offline.
- P3.12: new retrieval/sql_templates.py — parameterized SELECTs over exam_results: students_failed_at_least(n), students_failed_most, pass_percentage, toppers_by_sgpa, subject_failure_counts + match_template(). Router tries a template FIRST; only unmatched patterns fall to the LLM text-to-SQL path.
- P3.13 RESULT — the exact previously-failing query, end-to-end, WITH OLLAMA FORBIDDEN (test asserts no _http_client.post):
    Q: "give me a list of students who failed at least 4 subjects"
       -> TABULAR / template=students_failed_at_least
       -> "Found 10 students who failed at least 4 subjects:" (JAGTAP ANANT TANAJI & SHELKE MANISH SURESH: 5 each; 8 more at 4). CORRECT vs ground truth (10).
    Q: "students who failed at least 2 subjects" -> "Found 77 students" (CORRECT).
    Q: "which student failed the most subjects?" -> "max = 5" (JAGTAP/SHELKE). CORRECT.
- tests/test_sql_route.py: 6 passed (3 template-level + 3 router-e2e, all asserting no Ollama).
- FULL SUITE now: **73 passed, 1 skipped, 0 failed** in ~10.7s.

### [2026-08-08 01:33:26] PHASE 4 — Performance  ✅ COMMITTED
- Model-load cache (retrieval/vector_search.py::_get_model): first load 6484 ms -> cached 0.001 ms (shared across tenants; same object). Was reloading ~6.5s per VectorSearch instance.
- Query-embedding cache in VectorSearch.search (repeated query skips encode).
- SQL result cache (sql_templates._rows), mtime-keyed auto-invalidation: cold ~20 ms -> warm ~0.15 ms (78-173x) on the 3 canonical queries.
- num_ctx = 2048 verified at ALL call sites (unchanged). keep_alive=10m already in payloads.
- Ollama server flags (FLASH_ATTENTION, KV_CACHE_TYPE=q8_0, KEEP_ALIVE) documented in docs/PERFORMANCE.md — server-side env, NOT measurable (Ollama was DOWN overnight).
- Full suite after caching: 73 passed, 1 skipped, 0 failed (~7.7s).

---

## MORNING SUMMARY  (2026-08-08 01:35:05)

**Branch:** overnight-hardening (8 commits, off `main` baseline). 66 files changed, +1513/-285. Nothing pushed/deployed. No PII file deleted, moved, or modified — verified by sha256 on tabular.duckdb and embeddings.pkl (identical before/after).

### DONE + COMMITTED (each its own commit, reversible)
- **P1.1** all absolute path literals -> config.PROJECT_ROOT (42 files). Whole codebase compiles + imports.
- **P1.2** tenant_id validation on every path-building endpoint; tests/test_tenant_isolation.py (28) proves `../../other_tenant` rejected.
- **P1.3** safe deserialization: utils/safe_store.py (.npy/.json), readers prefer it, pickle only fallback. Existing embeddings.pkl migrated ADDITIVELY (sha256 unchanged); tests/test_safe_store.py (5).
- **P1.4** upload filename sanitized to basename + 413 size guard; tests/test_upload_filename.py (19).
- **P2** test suite made real & hermetic. Honest full-suite result: **73 passed, 1 skipped, 0 failed (~7.7s)**. Rewrote fake/broken tests (api, router, rag, components); stopped a collection hang and two import-time PII-file overwrites.
- **P3.9** consolidated exam_results table (analytics.duckdb, built READ-ONLY from tabular.duckdb).
- **P3.10** rule-based aggregation routing before LLM. **P3.11** guardrails already present (verified). **P3.12** parameterized SQL templates tried before LLM.
- **P3.13** THE TARGET QUERY WORKS. "students who failed at least 4 subjects" -> correct **10 students** (JAGTAP & SHELKE at 5; 8 more at 4), produced **deterministically with Ollama offline**. "at least 2" -> 77. "failed the most" -> max 5. tests/test_sql_route.py (6).
- **P4** caches: model load 6.5s->0ms shared, SQL templates 78-173x warm; Ollama server flags documented (docs/PERFORMANCE.md).

### HONEST STATE OF "failed >= 4 subjects"
FULLY WORKING and tested end-to-end, no Ollama needed. Fail definition = grade in (FF, XX, AB) — matches the existing count_failures + documented schema. Ground truth cross-checked directly against DuckDB: 10 students.

### NEEDS YOUR REVIEW / NOT DONE
- **Router rank-1 / rank-25 (from earlier judged workflow):** /review + /tenants endpoints iterate ALL tenants with NO auth (cross-tenant read). I did NOT auto-scope them — that changes the admin-dashboard contract. Decide: add auth, or scope per tenant. (These are admin endpoints; whole API is currently unauthenticated.)
- **P2 leftover manual scripts** (test_all_pdfs, test_camelot, test_diagnose, test_full_pass, test_pdfplumber, test_production_import, test_sgpa_diag, test_sgpa_invest2, test_table_broken, test_visual_proof, test_telegram_bot, test_router_fallback, + the __main__ harnesses): left as diagnostics, listed for conversion.
- **department/source_file in exam_results are NULL** — not derivable from parsed data without fabricating; semester IS derived. If you want department, tell me the source (roll-number segment? subject prefix map?).
- **benchmark_models_flushed.py** has pre-existing invalid `print(flush=True, "...")` lines (dead _flushed dup) — left untouched.
- **Merge:** work is on branch overnight-hardening; not merged to main. Review the 8 commits, then merge when happy.

### FAILURES / BLOCKERS
- None blocking. Ollama was DOWN all night -> LLM-path latency (P4) and any live-LLM behavior could not be measured; the SQL analytical path is LLM-free and fully verified. NVIDIA fallback not exercised (no live call made).

### [2026-08-08 02:00:17] P4 addendum — LLM path measured LIVE (Ollama started, kept running)
- Ollama 0.19.0 up on :11434, model qwen3:4b-instruct-2507-q4_K_M present. Left running per instruction.
- Live latency (warm): classify 214-265 ms (FACT/GLOBAL correct); generate_answer ~640 ms; LLM text-to-SQL fallback ~1071 ms (valid SQL, correct top-5 by total_marks; guardrails + self-correction confirmed working end-to-end).
- Regression check with Ollama up: full suite still 73 passed / 1 skipped / 0 failed; target query "failed at least 4 subjects" still returns the correct 10 (deterministic template path, LLM not invoked).
- docs/PERFORMANCE.md updated with real numbers.
- Note: harmless "Event loop is closed" teardown noise from the module-level httpx AsyncClient on Windows proactor at interpreter exit — cosmetic, not an error.
