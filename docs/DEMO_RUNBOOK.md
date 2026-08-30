# Demo runbook

For a live 5–10 minute walkthrough. Written for the E-Cell IIT Bombay incubator
session; works for any investor or pilot demo.

## Bring it up

```powershell
powershell -ExecutionPolicy Bypass -File scripts\demo_up.ps1
```

Starts Ollama, the API, and a production build of the dashboard, then fires one
real query and prints the answer. If that last line doesn't print a route and a
number, do not walk into the room — read `debug_outputs\api_demo.err`.

Cold start is ~90 seconds, almost all of it loading the 4B model into 4 GB of
VRAM. **Run it before the meeting, not during.** Once warm, queries are 0.1–3s.

| | |
|---|---|
| Dashboard | http://localhost:3000 |
| Phone view | http://localhost:3000/m |
| Stop | `powershell -File scripts\demo_down.ps1` |

Demo on `tenant_1` — it is the only tenant with a full index (161 docs, 369
students, DuckDB). `tenant_2` has no embeddings and will answer "I don't have
enough information" to everything.

### Showing it on a phone

`demo_up.ps1` binds loopback by default. For the phone client over wifi:

```powershell
powershell -File scripts\demo_up.ps1 -Lan -ApiKey "pick-something-long"
```

The key is mandatory on purpose. `/upload`, `/review`, and `/documents` have no
auth of their own, so binding `0.0.0.0` without `REQUIRE_API_KEY=1` hands
everyone on the conference wifi write access to the corpus.

## The four-question demo

One question per retrieval route. The point is not that it answers — it is that
it *picks the right machine* for each question without being told, and shows
which one it picked.

**1. TABULAR — live SQL, not retrieval**

> How many students failed at least 2 subjects?

Returns 16 students with roll numbers. Say the thing worth saying: this is a
`GROUP BY ... HAVING COUNT(DISTINCT subject_code) >= 2` executed against DuckDB
at query time. No LLM invented the 16. Ask the room for a threshold — "at least
3", "more than 1" — and run it live. It re-runs the SQL.

**2. LOCAL — multi-hop across a knowledge graph**

> Which trust runs DACOE Karad?

Answer: *Shri G. K. Gujar Memorial Charitable Trust runs DACOE Karad.* Open
**Context Used** and show the graph path — `Dr. Ashok G. Gujar → ESTABLISHED_BY
→ Dr. Daulatrao Aher College of Engineering`. That fact is not in any single
sentence in any document. The Sources panel lists the 6 documents it crossed.

**3. FACT — grounded lookup with provenance**

> What is the fee structure?

Returns the actual ₹ figures with the source document named. Point at the
Sources panel: every number is traceable to a file, which is the whole
difference between this and pasting a PDF into ChatGPT.

**4. ABSTENTION — the one that sells it**

> What NIRF rank did the college get?

*"I don't have enough information to answer that."*

This is the slide investors remember. The corpus does not contain an NIRF rank,
so the system says so instead of producing a confident, plausible, wrong number.
Follow it immediately with:

> What does SGPA mean?

*"I don't have enough information to answer that from your documents. General
knowledge (not from your institution's records): SGPA stands for Semester Grade
Point Average..."*

Two different kinds of "I don't know", correctly told apart: it refuses to
invent an institutional fact, but still explains a general term — and labels
which is which. Institutions ask about this before they ask about accuracy.

## Numbers you can quote

All measured on this laptop, RTX 2050, 4 GB VRAM.

| | |
|---|---|
| Answer accuracy | **88.9%** (185/208 held-out questions) |
| By route | FACT 96.9% · GLOBAL 84.2% · LOCAL 79.6% |
| Latency | 1.8s median, 16.1s p-max |
| Cloud calls | **0** — enforced by a test, not a promise |
| Corpus | 161 documents, 369 student records, one tenant |

The zero is the differentiator for education: student records never leave the
building, so this clears a data-protection review that any cloud RAG fails.

Paraphrase stability is **60%** — the share of questions answered identically
across every way a user phrases them. Quote it if asked how it holds up with
real users. It is a harder metric than accuracy and most teams don't measure it
at all; being able to name your own weak number is worth more in that room than
one more decimal on the headline.

## Known rough edges

Do not get surprised by these on stage.

- **Avoid**: *"Summarize the overall academic performance of the institute"* —
  this exact phrasing returns "I don't have enough information". Use
  *"Give me an overall summary of the results"* or *"What are the key themes
  across the documents?"*, both of which return full GLOBAL briefs.
- **Negation is not handled**: *"how many students did not pass"* answers the
  question about passing. Known bug, fix queued.
- **Route badge vs. answer**: route classification is 54.3% against the labels
  even though answers are 88.9% correct, because a TABULAR miss falls back to
  FACT and still answers. If someone reads the badge closely, that's the reason.
- First query after a long idle can take ~15s while Ollama reloads the model.
  Fire a throwaway query before you present.
