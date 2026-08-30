# PROJECT_STATE

## 1. WHAT WORKS TODAY (measured)
- Deterministic eval baseline (tests/eval/baseline.json, 46-question golden set, tenant_1): **overall answer accuracy 60.87%**, **route-classification accuracy 84.78%**. Reproducible across two back-to-back runs (temp=0).
- Per-route answer accuracy: **TABULAR 21/22 (95.5%)**, GLOBAL 3/7 (42.9%), FACT 3/11 (27.3%), LOCAL 1/6 (16.7%).
- Full test suite green: **227 passed, 1 skipped, 0 failed** (the skip is a manual PDF-parsing diagnostic).
- Demo path verified live: all 5 headline queries (name result, failed≥4, pass %, top subject failures, top-5 SGPA) route to deterministic TABULAR SQL templates and return correct, DB-cross-checked answers.
- TABULAR route is solid: counts/averages/SGPA thresholds, roll-number lookup, order-independent fuzzy name lookup, per-subject grade lookup, and a hallucination guard for non-existent rolls — all verified against tabular.duckdb/analytics.duckdb (369 students).
- LOCAL route revived via deterministic entity linking (e3596f6); graph coverage seeded from ICETIS brochure + Rutuja fees (8d76741). Grade scale corrected: AB passes (8.5), FF-only is the academic fail.

## 2. KNOWN-WEAK
- FACT weakest at 27.3% (3/11) even after depth k=10 + entity-link confidence gate + attribute routing (62b6469) — not yet measured green.
- LOCAL graph coverage only 1/6 correct in eval (3/6 target); graph is thin. GLOBAL 3/7 and re-reads/churns the graph per query.
- SGPA/aggregate "insufficient" hallucination-guard cases rely on narrow template phrasing.
- Data-quality: RAG-MicroSim .docx marked TABLE_BROKEN in validation_log.md (0 pages parsed).
- Eval noise floor not broadly measured (only minor G01 context-ordering variance seen; does not flip its pass).

## 3. ARCHITECTURE
- Retrieval router (retrieval/intent.py + router.py) classifies each query → TABULAR / FACT / LOCAL / GLOBAL, each backed by its store (DuckDB tabular/analytics, vector FACT, knowledge graph for LOCAL/GLOBAL).
- Frontend: Next.js dashboard (dashboard/) — documents / health / review / tenants / upload pages; FastAPI backend (api.main:app) on port 8000.
- Model: qwen3:4b-instruct-2507-q4_K_M via Ollama, num_ctx 2048, temperature 0 (deterministic).

## 4. NEXT 3 LEVERS (if resumed)
1. Raise FACT to green — carry the k=10 + confidence-gate change (62b6469) to a measured win on the 11 FACT questions.
2. Grow LOCAL graph coverage from 1/6 toward 3/6 via merge-swap ingest.
3. Reduce GLOBAL churn — stop re-reading the graph per query; stabilize the 3/7 GLOBAL answers.
