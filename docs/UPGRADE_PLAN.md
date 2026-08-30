# Company Brain — Upgrade Plan (non-security)

_Authored 2026-08-10. Scope decided via MCQ: Retrieval quality · Performance · Dashboard real-time + pro UI/UX · Python 3.12/uv/ruff. Design driver: **the dataset will keep growing** (more result sheets, more tenants, more sources)._

## Non-goals
- **No security work** — the 5→8 phase security campaign already shipped (RBAC, PII egress gate, fail-closed auth, hermetic tests). Don't re-open it.
- **No framework bumps for their own sake** — frontend is already current (Next 16.3 / React 19.2 / Tailwind v4); FastAPI 0.141 / Pydantic v2.13 are current. Only Python interpreter + unpinned libs lag.

## Cross-cutting requirements (apply to every phase)
- **CR1 — Multi-entry data renders as tables.** Whenever a result carries **≥2 records** (student lists, per-subject rows, SQL analytics results, `/review`, `/tenants`), it must be presented as a **table**, not prose:
  - _Answer layer_ (`generation/answer.py`): when context/result is a record set, emit a **Markdown table** (deterministic, not LLM-freeform) with a short prose summary above it.
  - _API layer_: multi-row endpoints return `{columns: [...], rows: [...]}` (already close in `/review`) so the UI renders without guessing.
  - _Dashboard_: a shared `<DataTable>` component (sortable, sticky header, zebra, empty/loading states) used everywhere a set appears.
- **CR2 — Professional UI/UX.** A real design system (type scale, spacing tokens, color roles, states), not default-Tailwind slop. Applies to every dashboard page. Use the `frontend-design` / `impeccable` guidance.
- **CR3 — Quality is measured.** No retrieval/answer change lands without the golden eval harness (Phase 1) green in CI.

---

## Phase 0 — Platform foundation
Reproducible base before anything changes.
- Python **3.10 → 3.12**; recreate `venv` (or move to `uv`-managed).
- Adopt **uv** (project + tool mgmt) and **ruff** (lint/format) — `modern-python` skill.
- **Pin** the unpinned deps: `docling`, `sentence-transformers`, `faiss-cpu`, `duckdb`, `ollama` clients → exact versions in `requirements.txt` (or `pyproject.toml`).
- CI: run tests on 3.12; add `ruff check`.
- **Acceptance:** full suite green on 3.12 (currently 173p/1s); `ruff` clean; lockfile committed; `graphify` + skills still resolve.
- **Files:** `requirements.txt`/`pyproject.toml`, `.github/workflows/ci.yml`, `venv`.

## Phase 1 — Scalable, measurable retrieval
The core future-proofing.
- **HNSW index**: `retrieval/vector_search.py` FAISS flat → `IndexHNSWFlat` (or IVF+PQ if RAM-bound). Keep the same build/query API; rebuild path via ingestion.
- **Cross-encoder reranker**: rerank FAISS top-k before answer synthesis (`retrieval/router.py`). Config-gated (`RERANK_ENABLED`, model id in `config.py`); CPU model, cache loaded.
- **Golden eval harness** (CR3): extend `audit/fixtures/regression_benchmark.json` + `audit_19` into a standalone `eval/` runner producing recall@k / answer-similarity; wire into CI as a gate. Run on synthetic (hermetic) fixtures so it works with zero real tenant data.
- **Acceptance:** eval harness runs in CI and reports metrics; HNSW returns ≥ same top-k quality as flat on the golden set at lower latency; reranker improves answer-similarity on the golden set (numbers recorded).
- **Files:** `retrieval/vector_search.py`, `retrieval/router.py`, `config.py`, `ingestion/embed.py`/`vector_store.py`, new `eval/`, `.github/workflows/ci.yml`.

## Phase 2 — Performance & serving
Scale with *active* load, not total corpus.
- **Cache**: query→answer and text→embedding caches keyed by `(tenant, corpus_version, query)`; invalidate on ingest. (`config.py` has cache clears already — extend.)
- **Async batching**: batch embedding + concurrent retrieval legs of the 4-way router.
- **LRU eviction** of the per-tenant `routers` dict in `api/main.py` (today it grows unbounded — memory blows with many tenants). Bound size, evict LRU, lazy-reload.
- **Ollama tuning**: keep-alive + `num_ctx`/`num_predict` per query type; document VRAM budget.
- **Acceptance:** P99 for TABULAR < existing SLA under a 10-tenant concurrency test; memory bounded as tenant count rises; cache hit-rate reported.
- **Files:** `api/main.py`, `retrieval/router.py`, `retrieval/tabular_queries.py`, `generation/answer.py`, `config.py`.

## Phase 3 — Dashboard real-time + professional UI/UX
- **SSE token streaming** Ollama → FastAPI → query console (`generation/answer.py` stream path, new `/query/stream`, `dashboard/app/page.tsx`).
- **CR2 design system**: tokens (type scale, spacing, color roles, radii, shadows), consistent components, loading/empty/error states, responsive, a11y. Pages: query console, `audit`, `health`, `review`, `tenants`, `upload`.
- **CR1 `<DataTable>`**: shared sortable table (sticky header, zebra, pagination, empty/loading), used for review queue, tenant list, SQL analytics results, and any multi-row answer.
- **Metrics viz**: wire audit P99 + `cost.json` into a small dashboard panel.
- **Acceptance:** answers stream token-by-token; every multi-row view uses `<DataTable>`; design-review pass clean (no AI-slop flags); Lighthouse/a11y sane.
- **Files:** `dashboard/app/*`, `dashboard/src/components/*` (new `DataTable`, design tokens), `dashboard/src/lib/api.ts`, backend stream endpoint.

## Phase 4 — Future-proof ingestion
Make new data sources cheap.
- **Adapter interface**: abstract the result-PDF parser behind a `SourceAdapter` (parse → canonical records). `adapters/result_pdf_adapter.py` becomes adapter #1.
- **Fix `exam_results.department = NULL`**: derive department from roll/subject reference (`docs/reference/dbatu_old_pattern_subjects_cse_ai.md`) inside the adapter.
- **DBATU scraper** (the paused idea) becomes a thin *fetch* feeding an adapter — deferred, easy once the interface exists.
- **Acceptance:** a second synthetic adapter registers without touching the pipeline core; `department` populated for known patterns; ingestion still hermetic-testable.
- **Files:** new `adapters/base.py`, `adapters/result_pdf_adapter.py`, `ingestion/parse_tabular.py`, `ingestion/pipeline.py`.

---

## Sequencing
`0 → 1 → 2 → 3 → 4`. Phase 0 unblocks all. Phase 1's eval harness (CR3) guards Phases 1–2. CR1/CR2 land in Phase 3 but the API shape (`{columns, rows}`) should be set in Phase 2 so the UI consumes it directly.

## Rollout
Each phase = its own branch off `main`, tests + eval green, `/code-review` before merge, fast-forward to `main` (local repo, no remote). Update `graphify update .` after structural changes so the graph stays current.
