# Phase 4 — Contact With Reality

**Date:** 2026-08-26
**Status:** design, pending implementation plan

## What Phase 4 is

Stop shipping answers that are confidently wrong, then get one stranger to type
into the system.

Phase 4 is not a measurement phase and not an onboarding phase. Three rival
strategies were written and each was attacked by two independent critics. Both
critics of the two losing plans landed the same blow without conferring:
roughly twenty days of internal metrology produces artifacts whose only
audience is the person who already knows the truth. No incubator analyst clones
a pre-seed repo and runs a verifier. No registrar has ever asked for a sealed
held-out set.

The only information this project cannot generate internally is a question
typed by somebody who is not Rohan. Everything here either removes an obstacle
to getting that, or is that.

## Decisions taken

These were founder decisions, not derived. They bind the rest of the document.

| Decision | Choice |
|---|---|
| Pilot deployment | Runs on Rohan's laptop; partner reaches it over LAN or tunnel. Full on-premises on customer hardware is the production architecture, shipped when someone is paying. |
| Offline claim | Stays as the headline. It describes the architecture and it is true. No document currently claims a pilot is already running on-premises, so nothing needs walking back. |
| Investor access | A tunnel to the laptop, opened deliberately and closed after. Not a hosted deployment, not a fake-data sandbox. |
| Team | Solo. Every step in this plan is executed by one person, and no other person is named in it or in any documentation it produces. |
| Data authority | The laptop is college-granted with permission to process academic data, so this college's records are covered. That authority does not extend to a second college's data or to publishing on the public internet. |

## Constraints

- One person, one laptop, RTX 2050, 4 GB VRAM, no cloud budget.
- No paying customer, no signed design partner, no institution running the system.
- The incubator demo must keep working throughout. Anything that risks it needs
  a stated mitigation.
- `data/` is gitignored. `git ls-files data` returns zero files. The 161-document
  `tenant_1` corpus exists in exactly one place on one machine.

## The plan

Six steps, serial, because there is one person — with one exception.

**Step 0, running from day 1 alongside everything else: outreach.** It is the
only calendar-bound work in the phase. A partner conversation has weeks of
latency that no amount of code buys back, and the 15-business-day stop in step 6
cannot start counting until the first approach is sent. Solo, this is an hour a
day of sending and replying, batched around the code work — not a step that
waits its turn. Everything else below runs strictly in order.

Target list depends on step 4's outcome: if the parser generalises across
DBATU-affiliated colleges, aim there; if it does not, aim at document-only
institutions, which is the wider funnel. Until step 4 lands on day 2, open with
document-only, since it needs no data agreement and lands on FACT, the
best-measured route.

### 1. Freeze the demo and back up the only tenant that exists — 0.5d

Copy `data/tenants/tenant_1` to external storage and commit a sha256 manifest.
Turn the scripted queries in `docs/DEMO_RUNBOOK.md` into a pytest that runs
against live `tenant_1` and asserts today's answers. Tag the commit.

Steps 2 and 4 rewrite the routing cascade and the ingestion manifest schema —
the two things the demo runs on — during an active incubator cycle. Neither is
safe without a rollback point and a tripwire.

**Done when:** external copy exists with its manifest committed at
`docs/tenant1_backup.sha256`; `pytest tests/test_demo_script.py` passes against
live `tenant_1`; git tag `demo-known-good` points at HEAD.

### 2. Kill the confidently-wrong answers and the two UI lies — 3d

One commit block. Hermetic, GPU-free, TDD against the 83 existing routing tests.

- **Polarity.** A normalisation module checked inside both
  `sql_templates.match_template` (the branch at 377-380 whose guard set has no
  polarity term) and `intent.classify_tabular_intent`. Both, because the stage-1
  prefilter has already committed to TABULAR and has no concept of polarity.
  `PASS`/`FAIL` and `pass_percentage`/`fail_percentage` invert. `student_count`
  does not invert and must refuse rather than guess.
- **Synonyms**, same normaliser: `paper` → `subject`, and `scoring N or higher`
  → an SGPA threshold rather than a name search.
- **Sentinel fallback** at `router.py:336`, differentiated. `Error executing
  SQL:`, `Query rejected by guardrail:` and `Failed to reach Ollama.` always
  fall back to FACT. `Query returned no results.` falls back only when the query
  reached `dynamic_sql` without matching a template or intent; otherwise it
  renders as an honest zero. A blanket fallback would manufacture a fluent wrong
  answer where a correct robotic one already stood.
- **`agg_kw` tightening** by word boundary only. Replace the substring `top `
  with `\btop\b` plus a negative lookbehind for lap/desk. Never delete a
  keyword: a false positive costs one wasted Ollama call, a false negative
  costs the demo.
- **Auth defaults.** `REQUIRE_API_KEY` defaults to 1 and a `0.0.0.0` bind
  without it is refused. `LLM_PII_REDACTION` defaults to 1. Both are
  prerequisites for the investor tunnel in step 3.
- **The hours-not-days honesty fixes.** `dashboard/src/app/upload/page.tsx:178`
  stops printing "Ingestion complete! Promoted to live database." when
  `_process_upload` only calls `parse_main()`. Add the two missing
  `tests/__init__.py` and `tests/eval/__init__.py`. Delete or rename
  `tests/test_router_fallback.py` and `tests/test_tabular.py`, which collect
  zero tests while carrying the name of the most load-bearing routing
  behaviour. Relabel `audit_06_multi_tenant_isolation` in the README to point at
  the ten real RBAC tests that earned the claim.

The negation bug is the reason this is first. "How many students did not pass?"
answers "334 students passed" — numerically inverted, formatted as a finished
confident sentence, on the one code path that structurally bypasses every
abstention safeguard in the system (`api/main.py:330-336` short-circuits TABULAR
without ever calling `generate_answer`). `docs/DEMO_RUNBOOK.md:122` already
documents it as a caveat the presenter must talk around. That is the live demo
today.

**Done when:** the twelve negated and synonym phrasings the scouts executed each
return a correct answer or an explicit refusal, never an inverted one, as new
passing tests; each of the four sentinel-producing call sites at
`tabular_queries.py:554/561/589/592` has a test asserting its fallback
behaviour; the step-1 demo-script test still passes unchanged; `pytest -q` green
in CI; `curl` to `/upload` without a key returns 401 under default env.

**Deliberately not in this step:** nothing already sent to a third party is
silently edited.

### 3. The investor tunnel — 0.5d

A documented command that opens a public link to the running stack, and a
documented command that closes it.

Hard prerequisite: step 2's auth defaults, **plus** a password at the tunnel
edge (`ngrok http 3000 --basic-auth`, or Cloudflare Tunnel with Access). A
tunnel exposes every route, not just `/m`. Without both, the link grants anyone
who finds it write access to the corpus and read access to 369 real students'
names, roll numbers and marks. Tunnel URLs are scanned; obscurity is not access
control. The college's authorization covers processing this data on this
laptop, not publishing it.

**Done when:** `docs/INVESTOR_LINK.md` gives the open and close commands; the
link refuses an unauthenticated request at the edge, verified by `curl` without
credentials returning 401 before any application code is reached; the runbook
states plainly that the link is live only while the laptop is on and must be
closed after each call.

### 4. Find out in an afternoon whether a second college is days or weeks — 0.25d

Download a publicly available semester result PDF from another DBATU-affiliated
college and run `ingestion/parse_tabular.py` on it. The parser silently skips
any PDF lacking the literal string `Total Marks(` in its first three pages.

If it parses, the repeatability claim is real and the target list is every
DBATU-affiliated college. If it does not, the target list becomes document-only
institutions and the DBATU format constraint is dropped — which is the wider
funnel, and needs none of the DBATU-specific code.

This is the highest information per hour available in the entire phase and
nobody has ever run it.

**Done when:** `docs/parser_experiment.md` records the outcome against a named
external college's PDF, with the row count it produced or the reason it was
skipped.

### 5. Make ingestion work once, end to end, on a folder nobody hand-curated — 4d

Scoped to what unblocks a stranger's first corpus, not to productizing.

- Fix the manifest schema collision. `pipeline.py:35-36` expects
  `(filepath, hash, last_indexed_at)` while `ingestion/parse.py:20-30` writes
  `(doc_id, file_hash, parse_status)` to the same `manifest.db`, so `pipeline.py`
  raises `OperationalError` on every tenant on disk. `parse.py`'s schema wins:
  all four on-disk manifests and `GET /documents` already use it.
- Per-file checkpointing and resume in `ingestion/extract_entities.py:114-141`,
  which today makes thousands of serial Ollama calls and only writes after the
  last one, so a crash at chunk 5,700 loses a night of exclusive GPU time. The
  loop is already file-outer, so this is a few lines.
- Delete the generic word `student` from `excluded_keywords` at
  `extract_entities.py:102-107`, which would silently drop a partner's
  `student_handbook.pdf` out of the graph with no error.
- Give `parse_tabular.py` `--pdf-dir` and `--tenant` flags instead of the three
  hardcoded DBATU paths at lines 468-473. Document it as a separate second
  command. Do not wire it into the eight-stage pipeline.
- Delete the 43-line `ingestion/pipeline.py` stub whose every step is commented
  out, which anyone exploring by directory finds before the real one.
- Write `docs/ONBOARD_RUNBOOK.md` as the exact commands. Make `README.md:271-273`
  true or remove it.

All of it runs against a clone at `tenant_1_migrate`. Cut over only after the
step-1 demo-script test passes against the clone.

Both documented ingestion paths are broken today. That is the one claim a
technical reviewer can falsify in ninety seconds from a clean checkout, sitting
directly under the README Quick Start.

**Done when:** one documented command ingests a directory of five PDFs
(including one named `students_*.pdf`) into a brand-new `tenant_smoke`, exits 0,
produces non-empty `chunked/`, `embeddings/`, `graph/`, and answers one FACT and
one LOCAL question correctly; `parse_tabular.py --pdf-dir <dir> --tenant
tenant_smoke` builds a queryable `tabular.duckdb` with no code edits; killing the
run mid-extraction and rerunning resumes rather than restarting; the demo-script
test passes against `tenant_1` after cutover; `ls ingestion/pipeline.py` returns
not-found.

### 6. Get 100 real questions from people who are not Rohan — 6d

The outreach that feeds this step started on day 1 (see step 0). This step is
the install, the capture and the grading — the work that begins once someone has
said yes, or once the 15-business-day stop has fired.

**Preferred:** a design partner institution. Qualify on a sponsor with authority
to release the data, at least five named users, and a dated two-week window.
Hardware is no longer a qualification criterion — the system runs on Rohan's
laptop and they reach it over LAN or tunnel.

**Pre-committed fallback, executed if no partner qualifies within 15 business
days:** recruit 15-20 individual students and faculty as users of `tenant_1`'s
existing corpus over the `/m` phone PWA. They are strangers, their questions are
unscripted, consent is per-person and trivial, and there is no institutional
calendar to wait on. This is a real outcome, not a consolation prize.

Either way: append-only query logging capturing timestamp, the **pre-fallback**
classifier decision and the served route (`router.py:277` and `:339` both
overwrite `qtype` before anything records it — a three-line change), retrieved
sources, answer, and latency. PII-scrubbed before it leaves the machine.

At capture time, randomly reserve 30% of queries sealed and unread. Grade the
visible 70% blind, with route and sources stripped from the grading sheet, as
correct / wrong / refused / **wrong-and-confident**. Fix the top three failure
classes in frequency order, each with the real failing query added as a
permanent test. Score the sealed 30% once, at the end.

That sealed 30% is a better held-out set than any the team could author, and it
is free.

**Done when:** `docs/week1_queries.md` reports at least 100 logged queries from
at least five distinct non-team users, stating separately how many were typed
while Rohan was in the room; the wrong-and-confident count is the headline
number, not overall accuracy; at least three real failing queries now pass as
permanent regression tests; the sealed 30% scored exactly once with its score
published whatever it is.

## Explicitly not doing

Each was proposed by a rival plan and killed by a named objection.

- **Repairing the 208-question benchmark's gold defects and publishing a
  corrected number.** The honest response to an unasked-for claim is to stop
  making it, which costs zero days. Three days manufacturing a worse number
  converts a private problem into a public artifact, aimed at a diligence
  process that does not happen at pre-seed. The defects are recorded for
  whenever someone actually asks.
- **A sealed 60-80 question held-out set authored in-house.** "Documents Rohan
  has not read" is close to the empty set, and no git history can prove he did
  not read the sources. Step 6's sealed 30% of a stranger's real log is strictly
  better and free.
- **A 100-150 question TABULAR benchmark.** Measurement infrastructure for a
  route with 13 hardcoded templates whose whole behaviour space fits on one page.
  Step 2's ~25 hermetic tests cover the regression need.
- **A CI accuracy regression gate.** CI runs on `ubuntu-latest` and `.gitignore`
  excludes `data/`, `*.duckdb`, `*.index` — there is no corpus, no index, no
  Ollama and no GPU. Worse, replaying frozen answers is invariant to source
  changes: you could delete `retrieval/` entirely and the replay would still
  score 185/208, so a deliberately reverted polarity check could not turn CI red.
- **`verify.ps1` / a reproducibility harness.** Every artifact it would replay is
  gitignored on purpose, because eval run artifacts contain answer text with
  student names. A fresh clone has nothing to replay.
- **`docs/CLAIMS.md`.** A due-diligence artifact for a diligence process that
  does not occur at this stage. The hours-not-days half survives in step 2.
- **Wiring the dashboard Upload button to the real pipeline.** A web request
  cannot own a multi-hour exclusive-GPU job; a job queue with progress reporting
  is a project, not a bullet. Step 2 relabels the button honestly in twenty
  minutes and step 5 makes the command-line path work.
- **Chasing the 54.3% route-classification number.** `run_eval.py:231` compares
  against a route that `router.py:277/:339` already reassigned, so every
  successful rescue scores as a routing failure. Fixing it as stated means
  suppressing a fallback that is currently rescuing answers. Step 6 logs the
  pre-fallback decision so the metric stops slandering itself; nothing optimises
  it.
- **Any investment in the GLOBAL route.** It served 3 of 57 GLOBAL-shaped
  questions in the measured run; at `GLOBAL_CHUNK_FANOUT=1` it calls the same
  `_fact_context` under the same character budget, so the only real difference is
  the prompt; and 16 of its 57 golds structurally cannot fail. No instrument
  exists that could tell whether the work helped.
- **Model, reranker, embedding or prompt upgrades.** No headroom on a 4 GB card,
  and `api/main.py:330-336` short-circuits TABULAR without ever calling
  `generate_answer` — the entire generation stack is downstream of the defect
  that matters most.
- **Generalising away from the DBATU exam domain.** All 13 SQL templates, the
  DuckDB schema, the router's keyword list and the parser are DBATU-shaped. Step
  4 answers whether generality is even needed, in two hours instead of a phase.
- **LAN deployment tooling** — configurable `API_BASE`, a wider CORS allowlist,
  key rotation, a tenant provisioning UI. With one partner and a handful of
  users, `/m` over the LAN proxy already works. Revocation for a customer base of
  one is deleting a line from a JSON file.
- **Paraphrase stability as a reported metric.** It is 6 of 10 groups, one group
  swings it 10 points, and `score_paraphrase.py:110` counts uniformly-wrong as
  stable — so it is directly gameable by refusing more often. Stop reporting it
  rather than investing in it.

## Open risks

- **Single point of failure on the corpus.** Step 1 mitigates, but the backup is
  a one-time snapshot, not a routine.
- **The 15-business-day partner stop is likely to trigger**, not unlikely. The
  fallback branch in step 6 is the probable path and should be planned for as
  the default, not the exception.
- **The investor tunnel is the highest-risk artifact in this plan.** It is the
  one thing that puts real student PII on a reachable address. Its prerequisites
  are not optional.
- **The pitch deck was rebuilt during the repo hygiene pass** (`docs/pitch_deck.pptx`,
  generated by `scripts/build_deck.js`). The copy previously sent to a third party
  is not this copy. Anything quoted from the old version should be checked against
  the current build before it is repeated.
