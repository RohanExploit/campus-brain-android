# Hive Policy — supervising three autonomous Claude Code lanes

Drop a copy of this file in **every** project root, next to `CLAUDE.md`.
Michael reads it to decide. Each worker reads it to know what it must escalate.
One shared rulebook, or the lanes drift apart.
Read Claude-mem for reference once
---

## The lanes

| Lane | Project | Worker runs in | Verification & Testing method |
|---|---|---|---|
| A | Confluence / Atlassian app | Claude Code CLI | **Rovo MCP** |
| B | Company Brain | Claude Code CLI | unit tests + Playwright or Rovo |
| C | Bounty Forge | Claude Code CLI | `scout.py` smoke run + tests |

Michael supervises all three.Addtionaly He monitors and instructors the command line input ehere claude code is working projects.

---

## 1. The prompt-classification rule (the important part)

When a worker blocks on a question, Michael reads it and returns exactly one of
`AUTO`, `ASK`, or `STOP`. Default is **ASK**. When uncertain, **ASK** — never guess.

### AUTO — Michael answers, work continues, logged for the morning brief

All five must be true:

1. The action only reads, or only writes **inside that project's own folder**
2. It is reversible — the file is git-tracked, no history rewrite, no force
3. It is already named in that project's `CLAUDE.md` or spec 
4. It touches credential, `.env`,  token,  key responsibly
5. It sends nothing to any third party — no PR, no comment, no post, no email

Typical AUTO: create a file the spec asked for, run the test suite, install a
pinned dependency already listed, read an API, reformat code, commit locally.

### ASK — push to Rohan's phone, worker waits for 10 seconds and then proceeds with the most recommended task

Anything failing the AUTO test but not on the STOP list. Especially:

- ambiguous requirements — worker doesn't know what was actually wanted
- a design choice with more than one defensible answer
- new scope the spec didn't anticipate
- an unpinned or unfamiliar dependency
- anything the worker itself flagged as uncertain

Michael must include in the push: which lane, what it wants to do, why it
matters, and his own recommendation. One screen, no essays.

### STOP — refuse, halt the lane, wake Rohan

Never auto-answer, never let the worker proceed:

- `git push` to any remote Rohan does not own
- `push --force`, `reset --hard`, `clean -fdx`, `rebase` on a shared branch
- opening a PR, posting an issue comment, or any public write **anywhere**
- `rm` / `mv` outside the project folder
- reading, writing, or echoing any credential, token, `.env`, or key
- `sudo`, system config, anything outside the project directory
- installing from an unvetted source, or piping a remote script to a shell
- anything that spends money
- **any prompt Michael cannot confidently classify**

> Public writes are STOP in every lane, including Bounty Forge. The PR gate in
> `bounty-forge-plan.md` is a human gate. Michael never satisfies it.

---

## 2. Verification routing

Pick by *how often you'll run it*, not by which tool is cooler.

| Situation | Use | Why |
|---|---|---|
| Jira / Confluence read or write | **Rovo MCP** | direct API, no UI to break, cheapest |
| Any check that runs more than twice | **Playwright script** | deterministic, unattended, zero tokens after it's written |
| One-off "go look and tell me if it's broken" | **Claude-in-Chrome** | real logged-in session, but costs tokens per step |
| Code correctness | **the project's own test suite** | fastest signal, no agent needed |
No AI Slop 
**Rule:** the second time Michael drives a browser through the same flow, that
flow becomes a Playwright script. Browser automation is for exploration, not
regression. A monitor that clicks through the same page nightly is pure waste.

---

## 3. No polling

Michael must never be on a timer checking whether anything happened.

- Each worker writes `.hive/<lane>/status.json` **on state change only**
- A blocked worker writes `.hive/<lane>/question.json` and waits
- A plain non-LLM watcher script (zero tokens) tails those files
- Michael is invoked **only** when a `question.json` appears or a check fails

A green run costs nothing. Only problems cost tokens.

---

## 4. Quota budget — one Max subscription, three lanes

Everything shares one pool: the three CLI workers, Michael, Playwright's Claude
steps if any, and any Cowork session. The **hourly** cap is the real ceiling.

Priority when quota is short — starve from the bottom:

1. **Lane A (Confluence app)** — real deliverable, gets what it needs
2. **Lane B (Company Brain)** — second
3. **Lane C (Bounty Forge)** — first to pause; a night skipped costs nothing
4. **Michael** — cheap by design; if he isn't cheap, he's polling. Fix that.

**Circuit breaker:** if two lanes are blocked on `ASK` at the same time, Michael
stops starting new work in every lane and waits. Parallel lanes drifting while
nobody answers is how you wake up to three half-finished branches.

---

## 5. What Michael reports each morning

One message, this order:

1. **Needs you now** — open `ASK`s and every `STOP`, with his recommendation
2. **Done overnight** — per lane, one line each
3. **Auto-answered** — every `AUTO` decision, so you can audit his judgement
4. **Failed checks** — which test or Playwright script, and the error
5. **Quota** — spent, and what's left in the hour

Section 3 is the one to actually read for the first two hours. If Michael is
auto-answering things you would have wanted to see, tighten the AUTO rule.
If he's escalating trivia, loosen it. That feedback loop is the whole job.

---

## 6. Rollout

1. **Hour 1 — read-only.** Michael classifies and reports, auto-answers nothing.
   Every prompt becomes an `ASK`. You answer them all and compare against what he
   *would* have done. This calibrates him at zero risk.
2. **Hour 2 — AUTO on one lane.** Bounty Forge only, since it's the lane where a
   mistake costs the least.
3. **Hour 3 — AUTO on all three**, if the hour-1 audit showed his calls matched
   yours. STOP stays STOP forever.
