# Performance Runbook

## In-process caches (implemented, measured)

| Optimization | Before | After | Notes |
|---|---|---|---|
| SentenceTransformer model load (`retrieval/vector_search.py::_get_model`) | ~6484 ms **per** `VectorSearch` instance | ~0.001 ms (shared) | Loaded once per process, shared across tenants. Multi-tenant routers no longer reload. |
| Query-embedding cache (`VectorSearch.search`) | re-encode every call | cached per query string | Repeated identical queries skip encoding. |
| SQL analytical templates (`retrieval/sql_templates.py::_rows`) | ~20 ms cold | ~0.15 ms warm (78–173×) | Bounded cache keyed by `analytics.duckdb` mtime → auto-invalidates on rebuild. `clear_sql_cache()` to force. |

Measured on this box, Ollama offline, tenant_1 data (369 students / 2952 subject rows).

## Ollama server flags (apply on the machine running `ollama serve`)

These are **server-side environment variables** — they cannot be set from app code
and were NOT measurable here because Ollama was not running. Set them before
starting the server:

```powershell
# Windows PowerShell
$env:OLLAMA_FLASH_ATTENTION = "1"     # Flash Attention: faster attention, less VRAM
$env:OLLAMA_KV_CACHE_TYPE   = "q8_0"  # quantize KV cache (q8_0) -> lower VRAM on the 4B model
$env:OLLAMA_KEEP_ALIVE      = "10m"   # keep model resident between requests
ollama serve
```

```bash
# Linux/macOS
OLLAMA_FLASH_ATTENTION=1 OLLAMA_KV_CACHE_TYPE=q8_0 OLLAMA_KEEP_ALIVE=10m ollama serve
```

- `keep_alive: "10m"` is also sent per-request in the app payloads (router, answer,
  tabular, api warmup), so the model stays hot for 10 minutes after each call.
- **`num_ctx` is 2048 everywhere** (verified across all call sites) — do not raise it
  on the 4 GB-VRAM box; it directly drives KV-cache size.

## LLM-path latency (measured live — Ollama 0.19.0, qwen3:4b-instruct-2507-q4_K_M, warm)

| Path | Latency | Result |
|---|---|---|
| Router classify (`classify_query`) | 214–265 ms | correct (FACT / GLOBAL) |
| Answer generation (`generate_answer`) | ~640 ms | correct |
| LLM text-to-SQL fallback (`generate_and_run_sql`, unmatched query) | ~1071 ms | valid SQL + correct rows (top-5 by total_marks); guardrails + 1-shot self-correction active |

Notes:
- The analytical SQL route (templates) is **LLM-free** and stays sub-ms warm — the
  canonical `failed >= N` queries never pay these LLM costs.
- Measured against the running desktop Ollama (may not have the server flags above);
  re-measure after setting `OLLAMA_FLASH_ATTENTION`/`OLLAMA_KV_CACHE_TYPE` for the
  VRAM/throughput gains.
