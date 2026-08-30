# PHASE −1 — Security hardening (branch `phase-2-retrieval-upgrade`)

Security-only phase that precedes any retrieval measurement. Code changes committed
in `d4e5acf`; the data-side actions below operate on gitignored trees and out-of-repo
storage (nothing student-derived is deleted — retention hold honored).

## What changed

### −1.1 Secrets quarantined; student data RETAINED in place
- **Moved out of the repo** → `../_QUARANTINE_secrets_pii_moved_from_repo/` (a sibling
  dir, outside the repo, so no tool scoped to the repo can read it): the Anthropic,
  Ollama, and data.gov API-key files and the Firebase debug-token file. These held
  live key material in filename and/or contents. They were always gitignored, so
  **never entered git history** — this addresses on-disk plaintext exposure only.
- **Retained in place** (retention obligation covers all student-derived data): the
  raw student CSVs (`Dataset/Indian_Students_Data.csv`, `Dataset/archive/…`,
  `Dataset/students.csv`) were briefly moved, then **restored to their original
  locations**. No student-derived source was deleted or modified.
- **ACTION REQUIRED (user, manual):** rotate the exposed `sk-ant-api03` Anthropic key
  at the Anthropic console. Claude did not and will not attempt this. No key material
  appears in any commit, log, or report (verified: `git log -p` and a tracked-file
  scan find no full-length key; only the public `sk-ant-api03` prefix noun is used).

### −1.2 External LLM egress now default-OFF
- `config.ALLOW_EXTERNAL_LLM` default flipped `1 → 0`. The NVIDIA 70B cloud fallback in
  `generation/answer.py` can no longer fire silently (it would ship document context —
  student names/emails — off-machine and swap the model under measurement).
- `tests/eval/run_eval.py` hard-forces egress off (`enforce_no_egress()`) with a loud
  assert; `tests/test_eval_no_egress.py` locks the default + the wiring. `test_rag.py`
  updated to opt into egress explicitly for its no-key-fallback assertion.

### −1.3 Bulk PII evicted from the served FACT vector index (index only)
- Rebuilt `data/tenants/tenant_1/embeddings/` **by filtering existing vectors** (no
  re-embed): evicted every chunk from a source whose email-bearing chunk count exceeds
  `VECTOR_PII_EMAIL_BULK_THRESHOLD` (=5) — `Indian_Students_Data.md` (4,990) +
  `students.md` (34) = **5,024 chunks**. **Index 5,769 → 745 vectors**; drift guard
  consistent (`ntotal == len(chunks)`).
- The 2 legitimate author-contact chunks (a paper's own contact line — ground truth
  for FACT question F05) are **preserved**.
- New ingestion guard `ingestion/embed.bulk_pii_sources()` keeps bulk-PII sources out
  of future rebuilds, logging source names + counts only (never contents).

## Artifacts touched vs NOT touched (−1.3 scope confirmation)
| Artifact | State |
|---|---|
| `embeddings/` (npy, chunks.json, pkl, faiss.index) | **REBUILT** (filtered → 745) |
| `embeddings_backup_pre_pii_20260818/` | **ADDED** (original 5,769, reversible) |
| `chunked/` (incl. `Indian_Students_Data_chunks.json`, 4,990) | **intact — not touched** |
| `parsed/` (incl. `Indian_Students_Data.md`, 4.47 MB) | **intact — not touched** |
| `raw/`, source CSVs in `Dataset/` | **intact — not touched / restored** |
| `tabular.duckdb`, `analytics.duckdb` | **intact — sha256 unchanged** |

## Invariants (verified)
- TABULAR route: **21/22** before and after (T22 is the pre-existing 1 fail).
- `tabular.duckdb` sha256 `e9cdb8a4…54bde`; `analytics.duckdb` `230bb54e…e25be5` — unchanged.
- Test suite: **230 passed, 1 skipped, 0 failed** (227 baseline + 3 new egress tests).
- TABULAR reads `tabular.duckdb` read-only and imports no vector code, so the index
  eviction cannot affect it (verified).

## Contamination analysis — are historical eval numbers trustworthy?
Because `ALLOW_EXTERNAL_LLM` was **default-ON** until this phase AND a `NVIDIA_API_KEY`
is present in `.env`, a successful cloud-70B answer was **possible** on any Ollama
exception during past runs.

- **TABULAR-derived numbers are structurally clean.** The TABULAR route returns the DB
  result directly and never calls `generate_answer`, so it never reaches the cloud path.
  (This covers the AB-fix / grade-scale deltas, which are TABULAR-route.)
- **FACT / LOCAL / GLOBAL numbers — and therefore the 60.87% overall and the
  GLOBAL noise-floor study — are potentially contaminated and cannot be certified
  clean.** These routes call `generate_answer`, which could have silently used the 70B.
- **It is UNKNOWABLE whether the fallback actually fired.** App logging streams to the
  console only (`utils/logging_config` uses `basicConfig`, no file handler), so the
  fallback markers ("Falling back to EXTERNAL…", "NVIDIA API fallback") were never
  persisted. No log evidence exists either way — this is not proof it never triggered.
- **Recommendation:** treat every non-TABULAR historical number as provisional. Re-establish
  the baseline under `ALLOW_EXTERNAL_LLM=0` (now the default + eval-enforced) in Phase 0
  before using any prior number as a comparison anchor.

## Forward pointer (Phase 0.7 — not executed here)
Stress-test kit located at `Dataset/Untested stresskit as of 4pm 18-08-2026/`
(contains `golden/golden_fact.json` + `scripts/validate_kit.py`). To be validated,
committed, `golden.lock`-frozen, and ingested into a **new `tenant_stress`** (never
tenant_1, never pooled) during Phase 0 — not before.
