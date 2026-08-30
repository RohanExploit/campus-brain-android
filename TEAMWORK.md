# Working in parallel on this repo

Written for a hackathon: several people, sometimes the same GitHub account,
sometimes the same machine image, all pushing to `main` at once.

`main` is deliberately unprotected. That means nothing stops a mistake, so the
rules below are the only thing that does. They are short on purpose.

---

## The three rules that actually matter

**1. Never force-push `main`.**

```bash
git push --force            # never
git push --force-with-lease # still never, on main
```

One force-push while a teammate has unpushed work erases it with no warning and
no recovery on their side. This is the single most expensive mistake available
in this repo. If you think you need to force-push `main`, you need a branch
instead.

**2. Always rebase before you push.**

```bash
git pull --rebase origin main
git push origin main
```

Not a plain `git pull`. A plain pull creates a merge commit every time two
people are working, and after a day the history is unreadable. `--rebase`
replays your commits on top of theirs and keeps the line straight.

Set it once per machine so you cannot forget:

```bash
git config --global pull.rebase true
```

**3. Push small and often.**

Every completed unit of work, not every session. Work that lives only on one
laptop is work nobody else can build on, and — as this project has already
learned once — work that a disconnected drive takes with it.

---

## Who is working on what

Before starting anything:

```powershell
powershell -File scripts\claim.ps1 -Show
```

Prints every machine's claim and the last five pushes. Then claim your lane:

```powershell
powershell -File scripts\claim.ps1 -Lane mobile -Task "Android Task 2 — Answer model"
powershell -File scripts\claim.ps1 -Release     # when you stop
```

Claims live in `.claims/`, one file per machine. That is deliberate: a single
shared file would conflict every time two machines updated it at once, which is
the exact situation it exists to prevent. The script commits only your own claim
file and rebases before pushing, so it can never carry unrelated work or clobber
anyone.

Claims are advisory, not locks. Nothing enforces them. If one looks stale, ask
before assuming, then take the lane and update your own file.

---

## Lanes: how to avoid conflicts instead of resolving them

Two people editing different directories never conflict. Two people editing the
same file always do. So the way to work in parallel is to agree who owns what
*before* starting, not to get good at merge resolution.

| Lane | Paths | Conflicts with |
|---|---|---|
| **Phone client** | `mobile/**`, `.github/workflows/android.yml` | nothing else |
| **Retrieval / API** | `retrieval/**`, `api/**`, `generation/**`, `ingestion/**`, `config.py` | itself only |
| **Dashboard** | `dashboard/**` | itself only |
| **Evaluation** | `tests/**`, `audit/**` | Retrieval, if signatures change |
| **Docs** | `docs/**`, `README.md`, `CHECKPOINT.md` | everyone — see below |

Claim a lane out loud before you start. Two people in one lane is fine if they
are in different files; two people in one *file* is the thing to avoid.

### Docs are the exception

`README.md` and `CHECKPOINT.md` are the files everybody wants to touch and the
ones that conflict hardest, because edits land in the same few paragraphs.

Rule: **one person owns docs at a time.** If you need to record something and
you do not own docs, put it in a new file under `docs/` and let the owner fold
it in later. A new file never conflicts.

---

## Branch when the work is risky

Push straight to `main` for anything small and working. Use a branch when the
change could leave `main` broken for someone else:

- a refactor that touches many files
- anything that changes a function signature others call
- work you will not finish today

```bash
git switch -c mobile/voice-input
# ... work, commit ...
git push -u origin mobile/voice-input
```

Naming: `<lane>/<what>`. Examples: `mobile/voice-input`, `retrieval/negation-fix`,
`docs/runbook`.

Merge it back yourself when it is green — no PR ceremony needed at this size:

```bash
git switch main
git pull --rebase origin main
git merge --no-ff mobile/voice-input
git push origin main
git branch -d mobile/voice-input
git push origin --delete mobile/voice-input
```

`--no-ff` keeps the branch visible in history, which is worth it when several
people are moving at once.

---

## Same GitHub account on two machines

If two people push as the same account, git history cannot tell you who did
what — and during a hackathon that matters when something breaks at 2am.

Set a distinct author per machine so the log stays readable:

```bash
# on laptop A
git config user.name  "Rohan (laptop)"
# on laptop B
git config user.name  "Rohan (desk)"
```

The account and email stay the same; only the display name differs. Costs
nothing, and `git log --format='%an %s'` becomes useful instead of uniform.

---

## What does NOT come from `git clone`

A fresh clone will not run. These are gitignored on purpose and must be moved
across by hand:

| Missing | What it is | How to get it |
|---|---|---|
| `data/tenants/**` | The document corpus, FAISS index, graph, DuckDB files | Copy from the machine that has it. **This exists in one place. Back it up before travelling.** |
| `.env` | Secrets and runtime config | Copy `.env.example` and fill it in |
| `.encryption_key` | Encryption key for stored data | Copy from the original machine — regenerating it makes existing encrypted data unreadable |
| `.venv312/` | Python virtualenv | `python -m venv .venv312 && .venv312\Scripts\pip install -r requirements.txt` |
| `dashboard/node_modules/` | Node dependencies | `cd dashboard && npm install` |

The corpus is the one that hurts. It is not in git, it took hours of GPU time
to build, and it currently exists on a single drive.

---

## When you hit a conflict anyway

```bash
git pull --rebase origin main
# conflicts appear
git status                    # see which files
# edit each conflicted file, keep both intentions
git add <file>
git rebase --continue
git push origin main
```

If it goes wrong and you want out:

```bash
git rebase --abort            # back to where you were, nothing lost
```

`--abort` is always safe. Reach for it before reaching for `--force`.

---

## Before you push, thirty seconds of checks

```bash
git status                              # nothing unexpected staged
.venv312\Scripts\python.exe -m pytest -q   # backend untouched or still green
```

If you changed `dashboard/`:

```bash
cd dashboard && npm run build
```

If you changed `mobile/`: push and read the Android workflow run. There is no
Android toolchain on the build machines, so CI is the compiler, not a check.

---

## Commit messages

State what changed and why it needed changing. The diff already says what the
code does; the message is for the person who has to understand the decision six
weeks later.

Not: `fix bug`
Yes: `fix(router): negated questions no longer answer with the inverted count`
