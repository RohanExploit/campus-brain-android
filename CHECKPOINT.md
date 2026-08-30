# CHECKPOINT — Company Brain

Snapshot to resume work from a cold start. Last updated **2026-08-28**.

Working agreement for parallel work: `TEAMWORK.md`.
Measured status: `PROJECT_STATE.md`. Older trail: `OVERNIGHT_LOG.md`.

---

## Where things stand

- **Branch:** `main`. All work is on `main`; there are no live feature branches.
- **Remote:** https://github.com/RohanExploit/startup-research-rag — **public**.
- **Tests:** **310 passed, 1 skipped** (`pytest -q`, ~40s warm, hermetic).
- **Benchmark:** **88.9% (185/208)** on the golden set — FACT 96.9%, GLOBAL 84.2%, LOCAL 79.6%. Route classification 54.3% (see *Known defects*). Latency 1.8s median.
- **Paraphrase stability:** 60% — the share of questions answered identically across every phrasing a user might type. A harder number than accuracy and the more honest one.
- **tenant_1 corpus:** 161 documents · 369 students · 2,952 exam records · 12 policy documents.
- **Model:** `qwen3:4b-instruct-2507-q4_K_M`, `num_ctx 2048`, `temperature 0`, on a 4 GB RTX 2050. Cloud egress off by default and test-enforced.

### Read this before anything else

**The corpus exists in exactly one place.** `data/` is gitignored, `git ls-files data` returns zero files, and `data/tenants/tenant_1/` took hours of exclusive GPU time to build. It currently lives on a single external drive. That drive disconnected mid-session on 2026-08-27, which cost nothing only because everything tracked was already pushed.

Backing it up is Task 1 of the Phase 4 plan and it has not been done yet.

---

## Bring it up

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo_up.ps1
```

Starts Ollama, the API and a production build of the dashboard, waits for each to answer, then fires a real query and prints the result. Cold start is ~90s, almost all of it loading the model into 4 GB of VRAM. Stop with `scripts\demo_down.ps1`.

| | |
|---|---|
| Dashboard | http://localhost:3000 |
| Phone view | http://localhost:3000/m |
| API health | http://127.0.0.1:8000/health |

Demo runbook with the four-question walkthrough: `docs/DEMO_RUNBOOK.md`.

---

## Work in flight

### Android phone client — Task 1 of 10 done

Plan: `docs/superpowers/plans/2026-08-26-android-client.md`
Spec: `docs/superpowers/specs/2026-08-26-android-client-design.md`

A Flutter client in `mobile/` that asks the existing engine a question — typed, spoken or photographed — and shows the answer with its sources. Purely additive: it calls the `/query` endpoint that already exists and needs no backend change.

- **Done:** Task 1 — Flutter scaffold, pinned dependencies, CI workflow producing a debug APK, and the generated `android/` tree committed.
- **Next:** Task 2 (Answer model) through Task 10 (runbook). Task 7 is the first installable working app; Tasks 8 (voice) and 9 (camera OCR) are additive and droppable.
- **No Android toolchain exists on the build machine** — no Flutter SDK, no Android SDK, no Gradle, and the local JDK is 23 while the Android Gradle Plugin needs 17. **CI is the compiler, not a check on it.** Verify by pushing and reading the Android workflow run.
- `android/` is committed on purpose. The workflow only runs `flutter create` when it is absent. Regenerating it per build would silently discard the manifest permissions and the cleartext-HTTP config, producing an APK that builds green and then fails every request on device.

### Phase 4 — designed, not started

Plan: `docs/superpowers/plans/2026-08-26-phase4-contact-with-reality.md`
Spec: `docs/superpowers/specs/2026-08-26-phase4-design.md`

Thirteen tasks, ~14 days. Back up the corpus and freeze the demo as a test, kill the confidently-wrong answers, open an investor tunnel safely, find out whether the tabular parser generalises to a second college, repair ingestion, then get 100 real questions from people outside the team.

Twelve rejected proposals are recorded in the spec with the objection that killed each — including a CI accuracy gate, which cannot work because CI has no corpus and replaying frozen answers is invariant to source changes.

---

## Known defects

Documented rather than hidden. Each has a task in the Phase 4 plan.

1. **Negation inverts the answer.** *"How many students did not pass?"* matches the keyword `pass` and answers *"334 students passed"* — confidently, on the one code path that bypasses every abstention safeguard, because `api/main.py` returns TABULAR answers without calling the generator. Highest-severity live-demo risk.
2. **Synonym gaps.** *"paper"* does not reach the subject regex, so a subject-scoped question answers with the global failure count. *"scoring 8 or higher"* routes to a search over student **names**.
3. **One GLOBAL phrasing landmine.** *"Summarize the overall academic performance of the institute"* abstains while three near-identical phrasings answer fine. Avoid it in demos.
4. **Route classification reads 54.3%** against 88.9% answer accuracy. Largely a measurement artifact: the scorer compares against a route the router already reassigned to FACT, so every successful rescue scores as a routing failure. Do not "fix" it by suppressing the fallback that is rescuing answers.
5. **Both documented ingestion paths are broken.** `pipeline.py` expects a manifest schema `ingestion/parse.py` does not write, so it raises `OperationalError` on every tenant on disk.

---

## Recent trail

```
59dac38  docs: working agreement for parallel work on an unprotected main
737ce17  build(mobile): commit the generated android tree
4e0890d  fix(mobile): add widget test matching CompanyBrainApp
49d148a  build(mobile): Flutter scaffold and CI that produces an APK
02882bf  fix(plan): commit the generated android tree instead of regenerating it
78bdc22  docs(plan): Android client implementation plan, 10 tasks
80424ab  docs(spec): Android client design
2a56534  docs(plan): Phase 4 implementation plan, 13 tasks
2a43260  chore(repo): remove hackathon-specific framing and a personal name
2e345e4  docs(spec): Phase 4 design — Contact With Reality
19426cf  fix(audit): six layout defects on the Enterprise Audit Suite page
3c5430e  feat(demo): one-command bring-up and a runbook for live demos
```

---

## Setting up a second machine

`git clone` alone will not run. See `TEAMWORK.md` for the full list; the short version:

| Missing | How to get it |
|---|---|
| `data/tenants/**` | Copy from the machine that has it. There is no other source. |
| `.env` | Copy `.env.example`, fill it in |
| `.encryption_key` | Copy from the original machine — regenerating it makes existing encrypted data unreadable |
| `.venv312/` | `python -m venv .venv312 && .venv312\Scripts\pip install -r requirements.txt` |
| `dashboard/node_modules/` | `cd dashboard && npm install` |

---

## Standing rules

- **Push only after the suite is green.** `pytest -q` must read 310 passed, 1 skipped.
- **Never force-push `main`.** See `TEAMWORK.md`.
- Nothing derived from student data gets deleted.
- Measure before and after any change that claims an accuracy effect, using the frozen RUN/SCORE split — answers written to disk before scoring, so no scorer can be written after seeing the numbers it judges.
