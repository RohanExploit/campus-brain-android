# Starting Claude Code on another machine

Clone, then paste the block below into Claude CLI as your first message. It is
written to be pasted verbatim — it tells the agent what exists, what does not,
what it must not do, and how to find out what the other machine is doing.

```bash
git clone https://github.com/RohanExploit/startup-research-rag.git
cd startup-research-rag
claude
```

---

## The prompt

> You are picking up an existing project on a second machine. Another machine is
> working on the same repository under the same GitHub account, so assume you
> are not alone in it.
>
> **Read these three files before doing anything else, in this order:**
> 1. `CHECKPOINT.md` — current state, measured numbers, what is in flight, known defects
> 2. `TEAMWORK.md` — the rules for working in parallel on an unprotected `main`
> 3. `.claims/` — one file per machine, saying what each is working on right now
>
> Then run `powershell -File scripts\claim.ps1 -Show` and tell me what the other
> machine has claimed, and what the last five pushes were.
>
> **What this project is.** "Company Brain" — a local-first, multi-tenant RAG
> system for educational institutions. Four retrieval routes behind one question
> box: TABULAR (live SQL over DuckDB), FACT (FAISS vector search), LOCAL
> (knowledge-graph multi-hop), GLOBAL (corpus-wide fan-out). Generation is a
> local Ollama model on a 4 GB GPU. Zero cloud egress, enforced by a test.
> Benchmarked at 88.9% over 208 questions.
>
> **What a clone does NOT give you.** These are gitignored and the project will
> not run without them:
> - `data/tenants/**` — the document corpus, FAISS index, graph and DuckDB
>   files. **This exists in exactly one place, on an external drive.** There is
>   no other source. Do not attempt to regenerate it.
> - `.env` — copy `.env.example` and fill it in
> - `.encryption_key` — must be copied from the original machine. Regenerating
>   it makes existing encrypted data permanently unreadable.
> - `.venv312/` — `python -m venv .venv312` then
>   `.venv312\Scripts\pip install -r requirements.txt`
> - `dashboard/node_modules/` — `cd dashboard && npm install`
>
> If the corpus is absent, say so and stop rather than working around it. Most
> of the system is meaningless without it, and a "fix" that makes tests pass
> without it is worse than a clear failure.
>
> **Standing rules, which override convenience:**
> - **Never force-push `main`.** The other machine may have unpushed work, and a
>   force-push erases it silently with no recovery on their side.
> - Always `git pull --rebase origin main` before pushing. Run
>   `git config --global pull.rebase true` once on this machine so it is automatic.
> - Claim a lane before starting work:
>   `powershell -File scripts\claim.ps1 -Lane <lane> -Task "<what>"`.
>   Lanes are `mobile`, `retrieval`, `dashboard`, `eval`, `docs`. Release when
>   you stop.
> - Do not edit `README.md` or `CHECKPOINT.md` if another machine holds the
>   `docs` lane. Write a new file under `docs/` instead — a new file never
>   conflicts.
> - Push only when the suite is green: `.venv312\Scripts\python.exe -m pytest -q`.
> - Never delete anything derived from student data.
> - Do not change accuracy-affecting behaviour without measuring before and
>   after using the frozen RUN/SCORE split in `tests/eval/`.
>
> **Two plans are in flight. Do not start either without checking claims first:**
> - `docs/superpowers/plans/2026-08-26-android-client.md` — Flutter phone
>   client, Task 1 of 10 done. There is no Android toolchain on any of our
>   machines, so **CI is the compiler**: you verify by pushing and reading the
>   Android workflow run, not by building locally.
> - `docs/superpowers/plans/2026-08-26-phase4-contact-with-reality.md` — 13
>   tasks, none started.
>
> **One known trap.** `data/` and `Dataset/` are gitignored, and several things
> pass locally only because a previous machine had un-declared dependencies
> installed. If a test passes for you and fails in CI, suspect an undeclared
> dependency before suspecting the test.
>
> Do not start writing code yet. Report what you found: current branch, whether
> CI is green, what the other machine has claimed, and what you believe the next
> sensible task is. Wait for me to confirm before touching anything.

---

## After it reports back

Claim your lane before the first edit:

```powershell
powershell -File scripts\claim.ps1 -Lane mobile -Task "Android Task 2 — Answer model"
```

Give each machine a distinct commit author name so the log stays readable when
both push under the same account:

```powershell
git config user.name "Rohan (laptop)"   # or "Rohan (desk)"
```

The account and email stay the same; only the display name differs.
