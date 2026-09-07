# Architecture, and what must never be true

**Read this before you change anything under `android-app/app/src/`.**

This file exists because debugging on this project moves to cheaper models from
here. The failure that costs the most is not a wrong fix — it is a confident
change to something whose reason nobody read. Almost every constant, regex and
early return in this codebase was put there by a measured failure, and the
measurement is usually recorded in the comment directly above it.

So the rule for working here is short:

> **If you are about to change a constant, a regex, a stoplist or an early
> return, read the doc comment attached to it first. If the comment records a
> number, your change has to beat that number, not merely look tidier.**

Everything below was derived by reading the source, not by summarising other
documents. **The tree it was read from is `3647056` plus the uncommitted Phase 1
work that was landing in `data/**` at the same time** — so `UserCorpusDb`,
`DocumentIngest` and `Models` were read in their in-progress form and are ahead of
what `git show 3647056:…` will give you. Where that matters it is flagged in
place. Where a claim could not be verified from the source, it says so.

---

## 1. The shape of the thing

An offline-first Android RAG app over one college's documents. A question goes
in; a routed, extractive, cited answer comes out. No generative model is
involved unless an operator installed a `config.json` that turns one on.

```
                        AskFragment (ui/ask)
                              │  raw question text
                              ▼
                        QueryRouter.answer()          retrieval/
      ┌───────────────────────┼───────────────────────┐
      │ 1. CompoundQuestion.split — two questions?    │
      │ 2. classify: RouteRules → RoutePrototypes     │
      │ 3. PremiseCheck.gradeScale — false premise?   │
      └───────────────────────┼───────────────────────┘
                              ▼
        ┌──────────┬──────────┴──────────┬──────────┐
      TABULAR    GLOBAL               LOCAL       FACT
    ScopeGate   HybridSearch      GraphTraverse  HybridSearch
    SqlTemplates  (k=30, dedupe   + HybridSearch    (k=10)
    TabularQueries by document)      (k=10)
        │           │                   │            │
        │           └───────────┬───────┴────────────┘
        │                       ▼
        │              AnswerComposer.compose()        answer/
        │              backed by AnswerCheck
        │                       │
        │                  abstained?
        │                       ├── no  → answer + passages + citations
        │                       └── yes → TopicGate → CloudAnswer (optional)
        ▼
   deterministic SQL answer            (blank → falls through to FACT)
```

Two SQLite files sit underneath, searched as one corpus:

| File | Written by | Survives app update? | Opened |
|---|---|---|---|
| `brain.db` | `scripts/export_mobile_bundle.py`, offline | **No** — re-copied from the APK asset when `built_at_utc` changes | `PRAGMA query_only = ON` |
| `user_corpus.db` | the app, on-device | **Yes** — never re-copied | read/write, three connections |

**Chunk ids are disjoint by construction**, which is what lets one fused ranking
address two database files with no namespace tag:

| Band | Ids | Meaning |
|---|---|---|
| bundle | `< 1_000_000_000` (highest shipped: 493) | the college's own corpus, in `brain.db` |
| own | `[ID_BASE, INSTITUTION_ID_BASE)` | a document **this student** imported |
| institution | `>= INSTITUTION_ID_BASE = 2_000_000_000` | a document the registrar published and this device synced |

`isUserChunk(id) = id >= ID_BASE` is the **routing** predicate — which
connection `HybridSearch` fetches a fused id from — and is true for both writable
bands. `isOwnChunk` / `isInstitutionChunk` / `provenanceOf` answer the *different*
question of whose document it is. Do not use one for the other: the file's own
comment records that once any institution chunk exists at 2e9, allocating the
next import above it would make it read as a college document for the rest of its
life.

> **Phase 1 (shared corpus, sync, provenance) is landing in `data/**` while this
> document is being written.** The three-band scheme above, `SCHEMA_VERSION = 3`
> and `SYNCED_CATEGORY` are in the working tree at time of writing but were not
> in `3647056`. Check `UserCorpusDb`'s companion object rather than trusting this
> table if the two disagree.

---

## 2. `retrieval/`

### `QueryRouter`

**One job.** Take a question, decide which of the four routes should answer it,
run that route, and hand back an `AnswerResult` with a trace of how it got
there.

**In.** A raw query string. **Out.** `AnswerResult(route, answer, passages,
sources, trace, abstained)`.

**The four routes and how one is chosen.** Classification is three stages, in
this order, and the order is load-bearing:

1. **Blank input is rejected before routing.** An empty query matches no rule,
   lands on TABULAR's roster branch, and prints every student's name on screen.
   The guard is the first thing in `answer()`.
2. **`RouteRules.classify` — deterministic, no database, no embedder.** Returns
   null for "no rule fired".
3. **`RoutePrototypes.classify` — cosine against three averaged exemplar
   vectors.** Returns null when the embedder is absent or when the top two
   routes are within `MARGIN` of each other.
4. **Default: `Route.FACT`.** This mirrors the backend, where any classifier
   failure also resolves to FACT.

| Route | What it is for | Retrieval | `topK` |
|---|---|---|---|
| `TABULAR` | student records — counts, averages, lookups | `SqlTemplates` → `TabularQueries`, deterministic SQL | n/a |
| `FACT` | one fact from one document | `HybridSearch` | `FACT_TOP_K = 10` |
| `GLOBAL` | corpus-wide synthesis | `HybridSearch`, **deduped by document** | `GLOBAL_FANOUT_K = 30` |
| `LOCAL` | follow a relation between named entities | `GraphTraverse` edges + `HybridSearch` | `LOCAL_VECTOR_K = 10` |

**Must never be true of it:**

- **It must never consult auth, licence state, sync or the network.** There is
  not one reference to `Identity`, `Licensing` or `Entitlement` anywhere in
  `retrieval/` or `answer/`, and that absence is the enforcement. Verified by
  grep at `3647056`.
- **A TABULAR miss must keep falling through to FACT.** `answerTabular` returns
  a blank answer into `answerVector(..., Route.FACT, ...)`. The comment records
  that this single line moved backend FACT accuracy from 50% to 94%. Removing it
  turns every unmatched records-shaped question into an abstention.
- **The route label must stay honest.** A LOCAL question that misses the graph
  stays labelled LOCAL and degrades to vector context. Relabelling it FACT would
  inflate route accuracy without answering anything better.
- **The cloud fallback must stay off for every half of a compound question after
  the first.** A fragment such as "what happens if I miss it", handed to a
  general model without the sentence that gave "it" a referent, is exactly the
  confident-and-wrong shape this codebase keeps paying to remove.
- **`answerCompound` must return null on fewer than two answered halves.** One
  answered half plus one abstention is what the unsplit path already produces,
  so the split can only add. Weakening this makes the split able to subtract.

### `RouteRules`

**One job.** Stage 1 of classification, byte-faithful to the pre-LLM block of
`classify_query` in `retrieval/router.py`. Pure: no database, no embedder, no
I/O, unit-testable in milliseconds.

**Must never be true of it:**

- **Order must not be tidied.** `AGG_KW` is checked before `SUPERLATIVE_SCORE`,
  which is checked before `SUBJECT_DIFFICULTY`, which is checked before
  `isCohortOverview`, which is checked before `FACT_ATTR`. Each of those
  placements has a comment naming the question it would otherwise steal.
- **A bare superlative must never join `AGG_KW`.** "Minimum attendance
  percentage" is a genuine FACT question; a bare "minimum" in the aggregate list
  claims it for the student tables.
- **`STUDENT_RECORD`'s two alternations are deliberately asymmetric.** The first
  takes `students?`; the second takes only the singular `student`. Widening the
  second to the plural claims "summary of the scholarship results for students",
  a document question pinned as one in `RoutingTest`.
- **`isCohortOverview` requires all three of cue, scope and *not* excluded.**
  Each condition alone over-claims; the comment names the question each one
  would wrongly take.

### `RoutePrototypes`

**One job.** Stage 2 — cosine of the query vector against one averaged exemplar
vector per route, standing in for the backend's Ollama classify call.

**In.** A `QueryEmbedder` and a `Map<Route, FloatArray>` from
`RoutePrototypesData` (generated by `scripts/export_route_prototypes.py`).
**Out.** A `(Route, detail)` pair, or **null** meaning "no opinion".

**`MARGIN = 0.05`** is the required lead of the top prototype over the
runner-up. Measured on 15 held-out questions:

| Setting | Overall | LOCAL+GLOBAL | Precision when it fires |
|---|---|---|---|
| rules only | 5/15 | 0/10 | — |
| margin 0.02 – 0.05 | 12/15 | 7/10 | 11/11 |
| margin 0.08 | 11/15 | — | starts abstaining on questions it had right |

**Must never be true of it:**

- **Failing must always mean "did not help", never "confidently wrong".** Every
  query below the margin falls to FACT.
- **`MARGIN` is not a local knob.** Changing it changes which route fires, which
  changes `topK`, which switches dedupe-by-document on or off, which can divert
  a question to the graph path. See §6.

### `HybridSearch` (and `VectorSearch`, `LikeSearch`)

**One job.** Fuse a keyword arm and a vector arm into one ranked chunk list, over
two database files, and pack the result into a context budget.

**In.** Query text, `topK`, and `perArm` (default **20**).
**Out.** `Result(chunks, ftsHits, vecHits, bothCount, usedLikeFallback, userHits)`.

**The two arms.**

- *Keyword.* `FtsSearch` if FTS5 is available, else `LikeSearch`. Run over the
  bundle and (when present) over the user corpus, then **interleaved by rank**,
  never merged by score. bm25 is corpus-relative: a newly imported three-page
  note systematically flatters itself against a 493-chunk corpus, so sorting the
  two lists together by raw bm25 would let it outrank the Attendance Policy on
  the word "attendance" purely for being a smaller haystack.
- *Vector.* `VectorSearch`, brute force over every stored embedding. Vectors are
  loaded once into one flat `FloatArray` at warm-up — reading them per query
  measured 108 ms on the demo device. Stored vectors are already L2-normalised
  (max |norm−1| = 1.2e-07), so cosine is a plain dot product. The two files'
  cosines *are* directly comparable — same model, same normalisation — so that
  merge needs no rescaling. The keyword merge does.

**RRF fusion.** `score(id) = Σ 1/(RRF_K + rank + 1)`, `RRF_K = 60`. RRF rather
than a weighted sum because bm25 is unbounded-negative and corpus-dependent
while cosine sits in [−1, 1]; any weighted combination needs a per-query
normalisation that is fragile on small result sets. RRF reads only ranks, so it
degrades cleanly to "whatever the other arm said" when one arm returns nothing —
which is exactly what happens when the ONNX embedder is absent.

**`perArm` is the window, `topK` is the answer.** `perArm = 20` is how deep each
arm looks; `topK` is how many fused chunks come out. At `perArm = 20`, RRF rank 1
is worth 0.01639 and rank 20 is worth 0.01250 — so a chunk *reordered inside* the
window costs ~0.0002, and a chunk **crossing the rank-20 boundary** costs 50× that
and, with no keyword support, leaves the fused list entirely. Shallow churn is
free; deep churn is what hurts.

**Must never be true of it:**

- **`pack()` must always include the best chunk, even if oversized.** It mirrors
  `_fact_context` in `retrieval/router.py`: include the first, then stop before
  the first chunk that would overflow.
- **`pack()` must stay the single place `section` is relabelled.** All three
  downstream readers — citation list, passage headings, and the "closest
  material" line of an abstention — read `section`, and all three once printed
  the signatory's name at the foot of a circular. See `DocTitles`.
- **The two keyword lists must never be sorted together by raw bm25.**

**Known open, not a defect to "fix" blind.** `CLAUDE.md` records that the 65–74%
condonation chunks sit outside `FACT_TOP_K`, so the compound attendance answer
names only the Below-65% tier and the battery's expectation is met lexically
rather than substantively. Raising `FACT_TOP_K` to reach them reshapes every
answer's context budget; treat it as a measured experiment, not a fix.

### `FtsSearch`

**One job.** The FTS5 keyword arm, plus the sanitiser that turns a question into
a MATCH expression.

**In.** A bare `SQLiteConnection` — deliberately not a `BrainDb`, so the identical
search runs over `user_corpus.db`, which has the same schema and the same
tokenizer. **Out.** Up to `topK` chunks, scored `-bm25` so higher is better.

`available` is probed lazily with a real MATCH. False means the caller must fall
back to `LikeSearch`. This is not theoretical: the platform SQLite behind
`android.database.sqlite` has no fts5 module (measured on vivo I2501, Android 16,
SQLite 3.44.3), which is why the app links `BundledSQLiteDriver`.

**`sanitize()` — three rules, each measured:**

1. Tokens are joined with an **explicit `OR`**. FTS5's implicit conjunction
   requires every term, which returns nothing for a natural-language question.
2. Filler is dropped, using **`AnswerCheck.isFiller`** — not a list local to this
   file. Under OR, bm25 rewards a chunk for every term it carries, and "what",
   "is", "if", "it" are carried by most of the corpus. On *"what happens if I
   miss it attendance"* the four filler words outvoted "miss" and "attendance",
   and the Attendance Policy's own tier table (chunk 150) came back at rank 25 of
   25. Filtered, it is rank 4.
3. **`AnswerCheck.contentTerms` is deliberately NOT reused whole.** It drops
   tokens of two characters or fewer and splits on non-alphanumerics, so "70" and
   "60%" do not survive it. Measured on *"am I eligible for a scholarship if my
   attendance is 70 percent"*: with the number kept, the row carrying the Alumni
   Grant's 80% threshold is the 4th keyword hit and the answer names it; using
   `contentTerms` it leaves the window and the answer silently lists one scheme
   fewer.

**Must never be true of it:** an empty MATCH expression must never reach SQLite —
it is a syntax error, not an empty result. A question made entirely of filler
falls back to the unfiltered tokens.

**Measured, unshipped lead** (from `CLAUDE.md`): dropping the stoplist from the
OR expression moves a needed chunk from rank >20 to rank 4, with 0 losses and 3
gains *in isolation* — but it reshapes ranks for every query and re-fuses against
the vector arm. It is a hardware experiment, not an edit.

### `TabularQueries` and `SqlTemplates`

**One job.** The deterministic half of the TABULAR route. `SqlTemplates` decides
*which* question is being asked; `TabularQueries` runs the SQL that answers it.
Ported from `retrieval/sql_templates.py` rule-for-rule and in the same order.

**Out.** `TemplateResult(answer, debugSql, template)`. These are the only answers
the app is allowed to state as fact.

**`match` vs `resolve` — use `resolve`.** Matching is half the decision. A
template that matches part of a question and silently ignores the rest answers a
*different* question, with real data and a TABULAR badge on it. Measured:

```
"how many students below 6 SGPA also failed"  ->  "35 students failed."
"which subject has the lowest pass rate"      ->  "Pass percentage: 90.5%."
```

Both numbers are correct. Neither answers what was asked, and a wrong number
wearing the deterministic badge is worse than an abstention, because that badge
is the app's promise that no model made the figure up. `Resolution.Partial`
carries the ignored `Constraint`s, and `QueryRouter.answerTabular` is the only
place that obligation is discharged — it appends `SqlTemplates.caveat(ignored)`.
The intent cascade owes the same declaration and pays it through
`SqlTemplates.unmodelled`.

**Must never be true of it:**

- **The ordering must not be made symmetrical.** The pass/fail-percentage branch
  must be checked before the generic fail-count branch, or "what is the fail
  percentage" is swallowed.
- **`Constraint` must stay a closed vocabulary, never a leftover-token test.** A
  leftover-word test refuses "is the pass rate good or bad" and "which subject has
  the most failures", both answered correctly today, because ordinary English
  carries words no template will ever model.
- **`dynamic_sql` must stay a dead branch that returns empty.** A small on-device
  model writing SQL against student records is a fabrication risk, not a feature.
  Empty routes the question to FACT instead.
- **Grade vocabulary comes from `Grades`, never re-hardcoded.** A second copy of
  that mapping is what caused `AB` to be miscounted as a failure across three
  subsystems once already.
- **`tabular_error` traces must log the exception type only, never a payload.**
  Those rows are student records.

### `ScopeGate`

**One job.** Refuse student-record questions that are about somebody else's
institution.

**In.** The raw query, in original case. **Out.** The institution named, or null.

The tabular store holds one college, and every template matches on keywords
alone — so *"how many students failed at IIT Bombay"* matched `result_count` and
answered **35**, this college's figure, presented as IIT Bombay's, with a TABULAR
badge and no hedge. An adversarial battery caught it. It is the most dangerous
class of error the app can make, because the answer looks exactly like a right
one.

**Must never be true of it:**

- **It must apply to TABULAR only.** A FACT question mentioning Oxford is
  answerable — the corpus contains research papers that cite other institutions.
  A *student-record* question about one is unanswerable by construction.
- **`FOREIGN` must stay word-boundary matched.** A plain `contains` on "mit"
  fires inside "admit" and "submit" and refuses half the admissions questions.
- **`NAMED_INSTITUTION` must run against the raw query, not the lowercased one.**
  Capitalisation is the only signal that "Pune University" is a name while "the
  university" is not.
- **The refusal must name the institution back.** An unexplained "I don't know"
  reads as breakage; naming it shows the app understood and is declining on
  scope.

### `CompoundQuestion`

**One job.** Split a question that is really two questions — and, much more
importantly, know when not to.

These two look identical to a naive split on "and" and are opposites:

```
"how many students failed AND what is the pass percentage"        two questions
"how many students failed more than one subject AND have SGPA below 6"   one
```

The second is a conjunction of *constraints*; answering its halves separately
produces two numbers, neither of them the intersection asked for — the same
class of failure `SqlTemplates`' constraint guard exists to stop.

**The discriminator is whether each half is itself interrogative.** "have SGPA
below 6" is not a question; "what is the pass percentage" is. That single test
separates every case in the battery.

**Must never be true of it:**

- **It must stay conservative.** On any doubt it returns the query unsplit, and
  the caller falls back to today's exact behaviour.
- **`carryOver` must only repair halves whose subject is a pronoun.** Rewriting a
  half that names its own subject changes what retrieval sees for no reason.
- **More than `MAX_PARTS = 3` halves is prose, not a question.**

### `CorpusWords`

**One job.** Answer "does this word appear anywhere in the corpus" — which is a
*different and much larger* question than "did retrieval find it".

`AnswerCheck.unsupportedSubject` needs both. Retrieval returns ten chunks out of
493, so an ordinary English word is missing from them constantly: *"how long does
a bonafide certificate take"* retrieves the right notice, which says neither
"long" nor "take". Measured over the bundle: "long" 13 chunks, "take" 11 —
ordinary vocabulary one notice happens not to use. "Cheating" 0, "plagiarism" 0,
"wifi" 0. That is a different fact about the question, and the one worth
abstaining on.

**Must never be true of it:** a failed probe must report **"the corpus knows
it"**, never the reverse. `getOrElse { true }`. A broken query must not be able
to turn every answer into an abstention.

### `GraphTraverse`

**One job.** The LOCAL route's graph arm — entity linking plus a 2-hop
neighbourhood walk over `graph_edges` (3,213 edges in the shipped bundle).

**Must never be true of it:** a bundle with no `graph_edges` table must not throw.
Every method returns empty and the router degrades to vector context, matching
the backend's behaviour on an entity-link miss. Imported and (in Phase 1) synced
documents currently carry **no edges at all**, so nothing reaches them by
following a reference — see `docs/fullstack-plan.md` Phase 3.

---

## 3. `answer/`

### `AnswerComposer`

**One job.** The *policy*: speak, or stay silent, and with what caveat. The
*evidence* lives in `AnswerCheck`. Splitting them is what made the decision
testable on the JVM against real chunk text instead of only on a device.

**In.** Query, packed chunks, an optional `prefix` (graph edge text), an optional
`CorpusVocabulary`. **Out.** `Composed(lead, passages, abstained, reason,
offTopic)`.

**There is no generative model in this path, and that is a choice, not a gap.**
The alternatives measured for a phone are a ~550 MB Gemma at 2–5 s to first token
or a 1.9 GB one at 6–15 s. On stage, an instant cited extract beats a spinner.
What this does instead is pick the sentence that actually answers the question and
keep the passage it came from one tap away.

**One slot, three compositions, tried in order** (mutually exclusive by `Need`,
so the order is documentation rather than precedence):

1. `applyToStated` — the student stated a number, so answer *their* question
   rather than reading the rule back at them.
2. `tierConsequences` — the question asks what follows and the rule is a tier
   table, so the tiers are the answer.
3. `schemeConditions` — the question asks whether something is allowed and the
   eligibility matrix states the condition in a cell.

Then `bestAnswer` searches **every** chunk, not just the top-ranked one. The
measured failure: an answering sentence in chunk two while chunk one — a
document's signature block — ranked first, and the app abstained on a question it
was holding the answer to.

**Must never be true of it:**

- **It must never invent.** Abstaining is not the same as answering nothing. The
  nearest material is offered instead, explicitly labelled as *not* an answer.
- **The lead must never be concatenated in front of the passage.** The lead is by
  construction a sentence out of the passage; doing that printed it twice.
- **The `ABSTENTION` string must stay byte-identical** to `kAbstentionSentence` in
  the Flutter app's `prompt_builder.dart`, so both clients say the same thing when
  they know nothing.
- **`offTopic` must keep being carried out of this file.** The caller uses it to
  decide *not* to try the cloud. An ordinary abstention is handed to the cloud —
  correct, the corpus missed a question a general model may know. An off-topic one
  must not be: there is nothing to ground on, and the answer would be a general
  model's idea of which students at *this* college were caught cheating.

### `AnswerCheck`

**One job.** Before the app says anything, ask whether the text it retrieved
actually answers the question that was asked. Deterministic and offline. 1,300
lines, and roughly half of them are the recorded reason for the other half.

A model-based verifier was considered and rejected: `CloudAnswer`'s `GROUNDED:`
marker already demonstrates the problem — a ~2B on-device model will not reliably
emit it, and a missing marker reads as not-grounded, so correct answers get
mislabelled. **A check that only works when the network does is not a check the
abstention decision can be built on.**

**`Need` — what shape of answer the question demands:**

| `Need` | Cue | Shape gate in `satisfiesShape` |
|---|---|---|
| `COUNT` | "how many", "number of", "count of" | `statesCount` — a number *followed by the question's own noun* |
| `QUANTITY` | "how much/long/often", "what percentage", threshold word + measurable | a digit **or** a spelled cardinal |
| `ELIGIBILITY` | a stated `%` **and** an eligibility cue | a digit, deliberately **digits only** |
| `CONSEQUENCE` | "what happens", "what are the consequences" | `CONSEQUENCE_TEXT` — the **only gate in the file that adds abstentions** |
| `PERMISSION` | permission modal **and** a verb of doing | none — `RULE_TEXT` is a *preference*, not a filter |
| `OTHER` | everything else | none |

`OTHER` is kept deliberately large. Every shape rule is a new way to refuse a
question the corpus could have answered, and over-refusal is the failure this
file mostly exists to fix.

**`CONSEQUENCE` is a hard gate and `PERMISSION` is not, and that asymmetry is
measured.** As a gate, `RULE_TEXT` turned *"can I apply for a scholarship"* from
an answer into an abstention — the scheme notices state their rule in a sentence
that does not contain the word "apply". As a preference it can only change which
sentence wins, never whether one does. Meanwhile `CONSEQUENCE_TEXT` has to be a
gate: the only sentence in the whole corpus containing the word "happens" is a
sentence about *procedure notices existing*, topic overlap ranks it first and can
never rank it anywhere else, and nothing short of refusing it works.

**`STOPWORDS`** is not a generic stoplist. Four blocks were added by four measured
abstentions, and each block's comment names the question that failed:

- `much`, `many`, … — *"how much attendance do I need"* abstained while holding
  the answer.
- `available`, `offered`, `exist`, … — *"what scholarships are available"*
  abstained with the scholarship documents retrieved and named in the refusal.
- `list`, `show`, `find`, `name`, … — the verb that says how to *present* the
  answer says nothing about what the answer is *about*.
- `happen`, `happens`, `consequence`, … — "happens" occurs in exactly 4 of 493
  chunks, all scholarship procedure notices: simultaneously the rarest and the
  least informative word in the question. "Consequence" is worse, being a **column
  heading** in both attendance tables.

**`SENTENCE_SPLIT` has two consumers and only one of them has a guard.**

- `sentencePieces` → `sentencesOf` applies the `ABBREVIATIONS` merge, so
  `"A late fee of Rs. 50 per day applies…"` is not decapitated into a 17-character
  fragment below the 25-character floor. The list is closed and counted: the six
  tokens that actually precede a mid-sentence full stop anywhere in the 248 campus
  chunks — `Dr.` 89, `No.` 62, `Rs.` 51, `Prof.` 49, `Mrs.` 24, `Mr.` 14 — plus
  `Ms.`. Nothing else in the corpus fires at all.
- `requiredMinimums` splits on `SENTENCE_SPLIT` **raw, with no merge**, and that
  is deliberate: *"Rajarshi Shahu Maharaj Merit Scholarship is open to … with
  family income up to Rs. 8,00,000 per annum, a minimum attendance of 75%"* is two
  sentences today, and the 75% is correctly recorded as the **institute's general**
  figure precisely because the scheme name is in the other half. Merge them and
  that threshold silently changes scope.

  *Note:* the task brief framed this as "sentence extraction and money figures".
  The money-figure protection is the `ABBREVIATIONS` merge, not the regex; the
  regex's second, unguarded consumer is threshold **scoping**. Both break if you
  touch `SENTENCE_SPLIT`, for different reasons.

**`unsupportedSubject` — three conditions, all required:**

1. At least **two** subject terms (`subjectTerms` = content words that
   `TopicGate.isDomainVocabulary` does *not* claim). One word absent is usually a
   phrasing accident: *"can I WRITE the exam with 60% attendance"* has exactly one,
   and "write" is genuinely in 0 of 493 chunks because the policy says "appear
   for".
2. None of them appears in any retrieved **chunk** — checked over whole chunks,
   not over the candidate sentence, because a word can be everywhere in the
   retrieved material and still not be in any sentence `sentencesOf` may quote.
3. A non-null `CorpusVocabulary` says the word is absent from the **whole corpus**.
   Missing from ten chunks is weak evidence; missing from 493 is strong.

**Must never be true of it:**

- **It must have no database and no I/O.** Vocabulary is supplied by the caller.
  A null vocabulary means the question could not be asked, and every rule then
  resolves *toward answering*.
- **A threshold must never be used without its scope.** `Threshold(value, scope)`
  exists because the app once told a student with 70% attendance that they
  qualified — the 70% is real and belongs to exactly one scheme, the Sports and
  Cultural Excellence Scholarship, whose own notice calls it a *relaxation*
  against the institute's general 75%. "A number without its scope is not a
  threshold, it is a digit."
- **Two different institute-wide minima must produce a refusal, not a pick.**
  Picking one would be a coin toss reported as a ruling.
- **`Band.highExclusive` must stay.** "Below 65%" and "65% to 74%" are adjacent
  and 65 belongs to exactly one of them; inclusive-on-both-sides makes the answer
  depend on parse order.
- **`ELIGIBILITY` must stay digits-only.** A figure written in words cannot be
  compared against 60.0, so admitting one only widens the pool of sentences quoted
  back at a student who asked for a ruling — a loosening with no verdict behind it,
  on the one path where a wrong answer reads as permission.

### `PremiseCheck`

**One job.** Catch a question whose premise the records contradict, and correct
the premise instead of answering the question.

*"How many students got an A+ grade"* has no true answer — this scale runs EX,
AA, AB … FF. The app used to route it TABULAR, match no template, fall through to
FACT, and narrate the column documentation of an unrelated CSV: *"31 G1 - first
period grade (numeric: from 0 to 20)"*.

**Must never be true of it:**

- **It must stay narrow.** It fires only on a closed vocabulary the app owns the
  ground truth for (`Grades`), and only when a grade-shaped token sits directly
  beside the word "grade". A general false-premise detector would have to *guess*
  whether a premise is false, re-creating the exact failure it removes.
- **The correction must be returned as an answer, not an abstention.** Abstaining
  hands it to the cloud fallback, which will cheerfully describe some other
  institution's A+ scale as though it were this one's.
- **The scale must be read from `Grades`, never restated.** See the `AB`
  miscount.
- **`NOT_A_GRADE` must keep "A" and "I".** They are the two single letters that
  are English words. Every other letter asked as "a *X* grade" is a real question.

### `TopicGate`

**One job.** Decide whether a question is in scope for a "college and campus
life" answer, before the router is allowed to reach for the cloud.

**The bias is deliberate, asymmetric, and the mirror image of
`AnswerComposer`'s.** There, a wrong confident answer is the expensive failure, so
the bar for silence is low. Here it runs the other way: a false "educational"
costs one useless general answer about the weather; a false "not educational"
refuses a legitimate student mid-demo with "that's not what I do".

Three entry points on one vocabulary list, and the differences matter:

| Function | Asks | Used by |
|---|---|---|
| `isEducational` | is this in scope for a cloud answer at all? — **true for anything ambiguous** | `QueryRouter.withCloudFallback` |
| `namesCampusSubject` | does the query actually *say* something about campus? | the narrower cloud suppression |
| `isDomainVocabulary` | does this **one word** carry information about *which* campus question? | `AnswerCheck.subjectTerms` |

**Must never be true of it:** the three must never diverge onto separate lists. A
gap between "words the gate treats as campus vocabulary" and "words the answer
check treats as uninformative" would be invisible until it mis-answered.

### `CloudAnswer`

**One job.** One optional fallback call — Groq, then a laptop Ollama, then an
on-device Ollama — used *only* when `AnswerComposer` has already abstained and
`TopicGate` has judged the question in scope.

**Config-gated, and the gate is a file that is neither in the repo nor written by
the app.** `loadConfig()` looks for `config.json` in the external files dir then
the internal one. A missing file is the **normal, expected, offline-by-default
state**, not an error, so it returns null. `answerWithProvenance` returns null on
any failure whatsoever — no config, no network, timeout, non-2xx, unparsable JSON
— and never throws into the caller.

**This is the one honest caveat on "nothing ever leaves the device."** In a
*configured* build, `QueryRouter` joins the retrieved passages into `contextText`
and this file POSTs them with the question — **including passages from the user's
own imported documents**. That is a **configuration** boundary, not a structural
one, and it must be described that way in code, in docs and to anyone asking. The
welcome screen once got this wrong.

**Must never be true of it:**

- **A missing config must never be an error.**
- **The API key must never appear in source, logs or trace output**, and nothing
  here writes it back out.
- **The `grounded` label must follow what the model reported, not whether context
  was supplied.** Retrieval returns its top chunks whether or not they are
  relevant: a revaluation question pulled an unrelated research paper and was
  correctly answered from general knowledge, while a bonafide question retrieved
  the right notice and returned that notice's own office, timeline and phone
  number. Labelling both "not from your college's records" understated the second
  as badly as the reverse would overstate the first.
- **No model name in the user-facing label.** What matters to a student is
  provenance, not which vendor answered.

---

## 4. `data/`

### `BrainDb`

**One job.** Open the read-only offline corpus: chunks, FTS index, embeddings,
graph edges, student tables.

**Opened through `BundledSQLiteDriver`, not `android.database.sqlite`.** Measured
on the demo device: a query against `chunks_fts` fails with *"no such module:
fts5"*. AOSP enables FTS3/FTS4 and not FTS5 — the same reason Room ships `@Fts3`
and `@Fts4` and no `@Fts5`.

**Resolution order:** external files dir (the `adb push` target, first so a
rebuilt corpus can be swapped in at a venue) → internal storage → the bundled
asset, copied out on first run. It **re-copies whenever the asset's
`built_at_utc` differs from the stamp**, which is exactly what an app update
does.

**Must never be true of it:**

- **Nothing may ever write to it.** `PRAGMA query_only = ON` at open. This is a
  stronger guarantee than "we use a transaction", and it is asserted by
  `JdbcSQLiteAdapterTest`.
- **A missing bundle must be loud.** `BrainDbMissingException`, not an empty
  database. Serving zero results looks like "the corpus has nothing on that",
  which is a lie the user cannot distinguish from a real answer.
- **Nothing durable may be stored here.** The re-copy destroys it on the next
  release.

### `UserCorpusDb`

**One job.** A second, writable corpus holding only what the user added. Never
`brain.db`.

**In.** `PendingDocument(docId, title, sourceUri, addedAtUtc, chunks, sizeBytes)`.
**Out.** Chunk count written; `documents()`, `importedCount()`, `importedBytes()`.

**Must never be true of it:**

- **A document must be written whole or not at all.** `BEGIN IMMEDIATE`, not a
  deferred transaction — the write locks are taken up front, so a failure happens
  before any row exists rather than halfway through 50 embeddings. A half-ingested
  document is *worse* than a rejected one: it is silently missing content the user
  believes is searchable.
- **The `chunks_fts` companion insert is mandatory.** It is an external-content
  table (`content='chunks'`), so it does **not** index a row just because `chunks`
  got one. Skip it and the document is stored and permanently unfindable by
  keyword.
- **On `remove`, content must be read out BEFORE anything is deleted.** An
  external-content FTS5 table keeps no copy of the text; the delete command has to
  be handed the exact indexed values back. Delete the `chunks` row first and the
  index can never be cleaned up — the terms stay and a removed document goes on
  matching queries with nothing left to cite.
- **The schema must stay byte-for-byte the bundle's, tokenizer included**
  (`porter unicode61`). A different analyzer gives the user's documents
  systematically different bm25 scores.
- **Chunk ids must start at `ID_BASE`, and each band must allocate inside its
  own range.** Disjoint id spaces are what let `HybridSearch` fuse two files with
  no namespace tag — and, since Phase 1, what tells a student's own import from a
  synced college document. Allocating a `MAX(id) + 1` across bands rather than
  within one is a silent misattribution bug, not a crash.
- **A failure to open must be survivable.** `openOrCreate` returns null and every
  caller degrades to the bundle alone. A broken user database must never take the
  college's documents down with it.
- **`importedCount()` must return 0, not throw, on a broken database.** "The disk
  is unreadable" is not a statement about anybody's licence.
- **`migrate` owns `PRAGMA user_version`** — `EntitlementStore`, `LicenseStore`
  and `AnalyticsStore` all create tables on this same file and none of them touch
  it.

### `DocumentIngest` and `ImportAllowance`

**One job.** Turn a picked file into indexed, searchable chunks — extract, chunk,
embed, write — and enforce the import cap before any of that happens.

**Out.** `IngestResult`: `Ok` | `Unsupported` | `Failed` | `LicenseRequired`.

**`ImportAllowance` is pure arithmetic over four numbers**, split out of `ingest`
(which needs a `Context` and a `ContentResolver`) so the boundary condition is
covered by a test that cannot be skipped:

- `documentCap`: **`used >= cap`, not `>`**, against the count *before* this
  import. A cap of one means one document may exist, so the second import is the
  one refused. Written against the prior count because the count after does not
  exist yet, and inventing it is how a cap of one admits two.
- `byteCap`: accounts for the incoming file, because a 40 MB allowance with 39 MB
  used still admits a 500 KB timetable. Incoming KB round **up** (so a thousand
  1-byte files cannot defeat a cap); stored total rounds **down** (so an institution
  is never charged for a kilobyte the app cannot point at).

**Must never be true of it:** `LicenseRequired` **blocks a new import and does
nothing else**. An expired licence, a lowered cap, a tier that went back to FREE —
none of them may hide, lock or delete a document already added. `DocsFragment`'s
list, `DocumentIngest.added` and `DocumentIngest.remove` behave identically at
every tier and in every licence state. `IngestResult` has four cases and adding
another is a **deliberate compile break** at every exhaustive `when` — a call site
that silently ignored a licence refusal would show the student nothing at all when
their import quietly did not happen. Never add an `else ->` branch to one of those
`when`s; that ends the protection for every future case.

### `TextChunker`

**One job.** Split a document into the same *shape* of chunk the bundle already
holds, because a chunk shaped differently retrieves differently.

Measured from `brain.db` (493 chunks): median 568 chars, `MAX_CHARS = 1000`,
boundaries on paragraph and heading breaks, `section` carrying the enclosing
heading. `MIN_CHARS = 80`; below that a chunk is merged rather than standing
alone.

**Must never be true of it:** it must have no Android types, no I/O, no database.
And the ceiling must stay matched to the bundle — a user document chunked into
3,000-character blocks would dominate the 5,000-char context budget and crowd the
college's own documents out of every answer it appeared in; chunked into single
sentences it would lose to them every time on bm25. Matching the existing corpus
is the only setting that makes a user's document compete on merit.

### `QueryLog`

**One job.** In-memory usage counters. No Android, no SQLite, no clock of its
own.

**`record(route, abstained, citedDocIds)` — and there is no parameter on it that
could carry a question.** Not a `String?` defaulted to null, not an `Any?` bag.
`QueryLogTest.'record cannot be handed a question'` asserts this **reflectively**,
against the signature rather than against today's call sites: no parameter type
may be assignable to `CharSequence`. `citedDocIds` is a `Collection` — erased, and
doc ids are the app's own filenames, not the student's words.

**Read this carefully, because it is easy to overstate.** *`record` cannot carry
query text.* The **class** can, through `recordText`, which is a separate opt-in
door: off by default (`keepQueryText = false`), a no-op until an admin ticks the
box on this device, capped at `MAX_TEXTS = 200` oldest-dropped, persisted to
`analytics_query_text`, and local-only — nothing in `QueryLog`, `AnalyticsStore`
or the export path opens a socket, and the export is the Android share sheet
handing the user their own file. State the invariant about `record`, not about the
class.

**Why the counters are not at the chokepoint.** `QueryRouter.answer()` is the
obvious home and is deliberately not used. `user_corpus.db` runs on the default
rollback journal; `UserCorpusDb.write` holds an EXCLUSIVE lock for a whole
document. A per-query INSERT in the answer path would add a **third** writer, on
the hot path, to a contended file, for durability a route histogram does not need.
A question that stalls because someone is adding a syllabus is a real defect;
losing a session's counts to a crash is a rounding error.

**Must never be true of it:** `Estimates` must never expose a bare hours figure.
Every `Line` carries both `queries` (measured) and `minutesEach` (assumed) so no
caller can render a total without the assumption in hand. A deck that says "saved
340 staff hours" with no visible assumption is a claim the buyer cannot check, and
once they work out it was a constant times a count, every measured figure beside
it is suspect too.

### `AnalyticsStore`

**One job.** Persist the aggregates between runs. Three counts per route, one per
document, the admin's minutes assumption, the opt-in flag, and — only if the box
is ticked — a capped text sample.

**Must never be true of it:** there must be no row for "a question" beyond the
opt-in sample. A per-query table would be a log of what a named student asked the
college, on the college's own phone. Every write is a **single statement** (UPSERT
with `+=`, not read-modify-write), so it can never hold an EXCLUSIVE lock while an
import waits, and two flushes racing on the two connections cannot lose one.

---

## 5. `data/auth/`

The whole package exists so that **auth is never a dependency of retrieval**. The
structural argument, from `Identity`'s own header: if entitlement were reachable
through `BrainRepository`, the first person who needed it inside the ask path
would find it already wired there. It is not, and there is nothing there to reach
for.

### `Identity`

**One job.** Composition root for identity. Opens the local `EntitlementStore`,
publishes whatever was already granted, and owns the one online moment
(`enrol`, `refreshIfDue`, `deleteAccount`).

`init` is **entirely local**: no network call, no token check. On a device in
airplane mode that is the whole of the auth system's startup work.

It stands apart from `BrainRepository` for two reasons, both structural: it must
be readable **before** the repository is `Ready` (a sign-in screen cannot wait on
an 86 MB ONNX model), and auth must never become a retrieval dependency.

**Must never be true of it:** `EnrolResult.Rejected` carries a `stage` and **no
message**. The server's own prose was the obvious field and is exactly the wrong
one — `SupabaseHttp.errorMessage` falls back to the first 120 characters of the
response body, which on campus wifi is a captive portal's HTML. Leaving the field
out is a stronger guarantee than a comment.

### `Entitlement` / `Entitlements` / `EntitlementState`

**One job.** What the institution granted this device, and for how long it stays
granted with the network gone. Pure functions, `nowMs` always passed in.

**`EntitlementState.retrievalAllowed` is `true` for every value of the enum, and
`EntitlementTest` sweeps the enum exhaustively.** It is a property of the enum
rather than a constant elsewhere precisely so a future state that forgets to set
it fails the test instead of quietly killing offline retrieval.

The claim being defended: an access token lives 3600 seconds. If any part of the
ask path consults one, the airplane-mode demo stops working at minute 61 and the
product's commercial claim goes with it.

**Must never be true of it:**

- **No state may withhold an answer.** `LAPSED` — the institution's own licence
  suspended or expired — still answers, with a banner. The college paying late is
  not the student's fault, and a phone that has already been handed the corpus
  taking it hostage is not a behaviour worth shipping.
- **`Entitlements.of` must return null rather than half an entitlement.** A
  garbled response, a captive-portal HTML page parsed as JSON, or a truncated read
  must never overwrite a good local grant with a broken one.
- **`graceUntil` must cast to `Long` before multiplying.** `365 * 86_400_000`
  overflows a signed Int and lands the deadline in 1970 — instantly stale for the
  tenant who bought the longest window.

### `SupabaseAuth`

**One job.** Sign-up, sign-in, token refresh against Supabase GoTrue, over plain
`HttpURLConnection` and `org.json`. No new Gradle dependency: `supabase-kt` was
weighed at +4–7 MB unminified on a 49 MB APK for six HTTP calls.

**Must never be true of it:** the only things that may cross the wire from this
file are **an email address, a password, an enrolment code, and tokens**. No query
text, no corpus content, no retrieved chunk, no student record. The enforcement is
that no method here accepts any of those as a parameter.

### `ControlPlane`

**One job.** The five PostgREST calls this app is allowed to make, and nothing
else: `redeem`, `fetchGrant`, `fetchCorpusVersion`, `postUsage`, `deleteAccount`.

**Must never be true of it:** no endpoint may take a question, a retrieved
passage, a document, an embedding or a student's marks — and the enforcement is
structural: **no function has a parameter that could carry one.** Tenancy is never
sent; it is resolved server-side by `current_tenant_id()` from `memberships`.
Proven against the live project: cross-tenant reads returned `[]`, a
privilege-escalation write returned 42501.

### `EntitlementStore`, `LicenseStore`

**One job.** Where the entitlement, the refresh token, the licence and the install
id live between runs.

Both take a raw `SQLiteConnection`, never a `Context`, so every line is runnable
in a JVM unit test with no Robolectric and no device. That matters more than usual
here: the thing being tested is whether a bad server response can destroy a good
local grant, and that is not a property you want to first observe on a student's
phone.

**Must never be true of them:**

- **They live in `user_corpus.db`, not `brain.db`.** `brain.db` is re-copied on
  every update; a licence written there would be destroyed by the next release and
  an institution would re-key every phone after every build. (Strictly, the
  survive-an-update property comes from the *location* — anything in `filesDir` has
  it — so a separate `entitlement.db` would work equally well. Reuse was chosen to
  avoid a second file for two rows.)
- **Every write must be a SINGLE statement.** No `BEGIN`, no multi-statement
  transaction, so neither store can ever be the thing holding a lock while a
  fifty-embedding import waits.
- **WAL must stay off.** Retrieval reads this same file through a different
  connection; changing the journal mode underneath it is not a local change.
- **Every method must fail soft, downward.** `load` → null, `save` → false. A
  store that cannot be read leaves the app on the compiled-in default, which is
  the **lowest** tier and the **smallest** cap — never unlimited.

### `License` / `LicenseKey` / `Licensing`

**One job.** What a paying institution bought, the signed string that proves it,
and the caps that follow.

**Asymmetric, not an HMAC, and the reason is `isMinifyEnabled = false`** (ONNX
Runtime and the bundled SQLite both resolve classes reflectively). The APK
decompiles cleanly, so any secret compiled into it is readable by the first person
to unzip it. A shared HMAC key would let them mint unlimited INSTITUTIONAL keys.
The app ships a **P-256 public key and nothing else**; verification is
`java.security.Signature` doing local elliptic-curve arithmetic — no network, no
clock server, no dependency outside the JDK.

`ShippedKeyTest` verifies a genuinely issued licence through the `DEFAULT`
parameter, so the compiled `PUBLIC_KEY_B64` and the private half can never
silently drift apart again. That gap is how a public key with no matching private
half once shipped unnoticed.

**Must never be true of `Licensing`:**

- **No value of `Tier` and no state of this object may stop a question being
  answered.** FREE is not a trial: all four routes, the bundled corpus, the
  document browser and the self test work permanently, offline, with no key and no
  account. The only thing a licence governs is the cap on adding a **new**
  document.
- **Every failure resolves to `Caps.FREE` — never to zero and never to
  unlimited.** No licence, an unreadable store, an expired key, a decode that
  threw: all land on the lowest tier and the smallest cap. Resolving *upward* is
  the failure mode where the app gives away the product to the one device whose
  disk is broken, and to every device an attacker can break it on.
- **A rejected key must leave the prior licence exactly where it was.** The write
  is after the verification, with no `clear()` in between, and there is no branch
  in `decide` that writes on failure or clears at all. An admin who pastes last
  year's key on top of this year's must not thereby downgrade a working
  institutional install. `decide` is split out of `apply` specifically so this can
  be asserted with no `Context`, no SQLite native and no initialised singleton —
  because the way it breaks is invisible.
- **A licence key must never be logged.** It is a bearer credential; a logcat line
  containing one is a licence key published to every app on the phone that can read
  logs.
- **`removeLicense` removes the licence row and nothing else.** Every imported
  document stays imported, listed and searchable.

### `AccountDeletion`

**One job.** Decide what the server's answer *means*, and whether that meaning is
enough to start deleting things off this phone.

**Must never be true of it:** clearing is only ever done on a server answer that
**positively settled** the question. Offline, a captive portal, a refused token, a
migration that has not been applied — all leave this device exactly as it was,
still enrolled, still able to try again. Getting that backwards signs a student
out of an account that still exists and tells them it is gone.

---

## 6. `embed/`

### `MiniLmEmbedder`

**One job.** Supply a 384-dimensional query vector, from the exact HuggingFace
all-MiniLM-L6-v2 graph, through ONNX Runtime.

**Out.** `FloatArray(384)`, mean-pooled over real tokens, L2-normalised.

**Vectors are only comparable if they come from the same weights.** The corpus
vectors in `brain.db` were produced by `sentence-transformers/all-MiniLM-L6-v2`
(`ingestion/embed.py`). A query vector from any other model — MediaPipe
TextEmbedder's Universal Sentence Encoder is 100-dimensional and a different space
entirely — makes cosine similarity **noise dressed up as a ranking**. There are
exactly two correct options: these weights, or no vector arm at all.

**Nothing downstream can detect a mismatch.** Wrong pooling or a tokenizer
divergence does not raise; it produces vectors, and rankings, that are simply
wrong. Two guards exist: `scripts/export_minilm_onnx.py` refuses to write the
model unless it reproduces sentence-transformers to 1e-4 (measured 6e-08), and
`SelfTest` embeds known chunks on-device and asserts cosine ≥ 0.99 against their
stored vectors.

**Must never be true of it:**

- **It must degrade to `isReady = false`, never throw.** Without an embedder the
  app runs FTS5-only and the router resolves everything unmatched to FACT —
  degraded, but exactly the backend's behaviour when its classifier call fails.
- **Mean pooling, not `[CLS]`.** Taking the CLS token runs, returns 384 floats,
  and makes every similarity quietly wrong.
- **fp32 stays.** int8 per-channel dynamic quantisation to ~23 MB *was* measured:
  mean cos(fp32, int8) = 0.994 over 89 real questions, vector top-1 unchanged on
  93%. It still changes the **packed context set on 45% of queries**, because the
  damage arrives through two amplifiers — the RRF rank-20 boundary, and
  `RoutePrototypes.MARGIN = 0.05`, which 13 of 89 queries sit within 0.01 of. The
  worst case flips `what documents are needed for the post matric scholarship`
  from FACT to GLOBAL, dedupe-by-document then discards the rest of the correct
  document, and the chunk headed "Documents to attach" vanishes — *while the vector
  top-20 set is identical*. Cosine and top-5 are the wrong metrics here. int8 did
  ship once and scored 22/23 adversarial, 14/20 hard; the extractive composer
  absorbed most of it. That is the margin that build was surviving on.

### `WordPieceTokenizer`

**One job.** BERT-uncased WordPiece matching HuggingFace's `BertTokenizer` for
this model (`do_lower_case=true`, `strip_accents=null`).

**This is the highest-risk file in the vector arm, and the risk is that it fails
quietly.** A subtly wrong tokenizer still returns ids, the model still returns a
vector, and the rankings still look plausible. Faithfulness details that are not
optional: accents *are* stripped (`strip_accents=null` resolves to follow
`do_lower_case`); CJK codepoints get spaced (this corpus has none, but omitting it
is a silent divergence); control characters are dropped and all whitespace kinds
collapse.

**Must never be true of it:** `encode` must reserve two slots for `[CLS]`/`[SEP]`
(`maxLen - 2`), and the attention mask must stay all-ones — mean pooling divides by
its sum.

---

## 7. The test layers

| Layer | Where | Runs on | What it is for |
|---|---|---|---|
| **JVM unit tests** | `app/src/test/…` | plain JVM, `./gradlew :app:testDebugUnitTest` | 384 tests at `3647056`. Pure logic: routing rules, answer checks, licence arithmetic, chunking, tokenizer, store failure modes. |
| **JVM battery harness** | `app/src/test/…/jvm/` | plain JVM | The **whole production stack** wired up on a desktop, over the real `brain.db`, the real FTS5 index, the real 493 embeddings and the real ONNX MiniLM. |
| **Device batteries** | `app/src/androidTest/…` | a physical handset | The scoreboard. |

Four files live in `androidTest`: `HardQueryBatteryTest`, `AdversarialBatteryTest`,
`IngestDeviceTest` and the older `QueryBatteryTest`. **"The three batteries" means
the first three** — those are the ones `docs/device-test-plan.md` §2 tells you to
run, in that order.

**`jvm/JvmCorpus`** builds the pipeline once per JVM. It is the app's own code
throughout — `BrainDb`, `FtsSearch`, `VectorSearch`, `HybridSearch`,
`TabularQueries`, `GraphTraverse`, `RoutePrototypes`, `QueryRouter`, and through
the router `SqlTemplates`, `AnswerComposer`, `AnswerCheck`. Exactly three
departures, all deliberate and all documented in the file:

1. the driver is `jvm/JdbcSQLite`, because `sqlite-bundled` ships Android `.so`
   files only and `BundledSQLiteDriver().open()` raises `UnsatisfiedLinkError` off
   a device. It is a thin shim over the same C SQLite with FTS5 on.
2. `cloud = null`, which `QueryRouter` already documents as the supported "no
   Context wired in" state and treats exactly like a cloud call that failed —
   which is also what the app does in airplane mode.
3. `userArms = null` — the bundled corpus alone, matching a fresh install.

**Why this exists at all:** until it did, every agent working on retrieval
hand-transcribed the pipeline into Python to check itself, and *a
reimplementation can agree with itself while disagreeing with the app.*

**`JvmCorpus.vectorArm` means the model LOADED, not that the file exists.**
`modelAssetPresent` is the separate question. `armLabel` prints
`fts5+vector` or `KEYWORD-ONLY (no ONNX embedder)` so a degraded run can never be
mistaken for full coverage in a log someone reads six months from now.

**Why a probe miss is reported, not failed.** Several probes are documented open
defects — paraphrase stability was measured at 60% before the JVM files existed —
so a red suite would mean "the known bugs are still there", which is not what a
red suite should mean. The regression floors are the guard instead, and they are
skipped entirely when `vectorArm` is false, so nothing silently passes a weaker
bar:

| Battery | Probes | Measured 2026-09-07 | `FLOOR` |
|---|---|---|---|
| `JvmAdversarialBatteryTest` | 23 | 23/23 | **22** |
| `JvmHardQueryBatteryTest` | 20 | 19/20 | **18** |

One below measured, on purpose: ONNX Runtime's float results differ in the last
bits across platforms and `RoutePrototypes` fires on a 0.05 margin, so one
borderline question can classify differently on another machine. One probe of
slack still catches a real regression and cannot go red for arithmetic noise.

> **`docs/fullstack-plan.md` Phase 4 says "adversarial ≥ 23, hard ≥ 19". The code
> says 22 and 18.** The code is what runs. If the floors are raised, raise them
> from a *measured* run and update the `FLOOR` comment with its date.

**Must never be true of the test layers:**

- **The three `androidTest` batteries are the scoreboard and must not be edited to
  make a score move.** Their JVM ports say so in their own headers: "the
  androidTest file is untouched and stays the on-device scoreboard".
- **A probe's expectation must not be loosened to make it pass.** Change the
  engine, or record the miss.
- **A pass count must come from the run being described.** See `CLAUDE.md`.

---

## 8. If you are about to change X, read Y first

These are the places where a local-looking edit has non-local consequences. Every
row is verifiable in the code.

| If you touch… | Read first | Because |
|---|---|---|
| `AnswerCheck.SENTENCE_SPLIT` | `sentencePieces`, `ABBREVIATIONS`, **and** `requiredMinimums` | Two consumers, one guarded and one not. The guarded one keeps `"Rs. 50"` from decapitating a sentence; the unguarded one decides whether a 75% threshold is recorded as the **institute's general** figure or as a **scheme's** relaxation. |
| `AnswerCheck.STOPWORDS` | `AnswerCheck.bestAnswer` **and** `FtsSearch.sanitize` | It is both the topic-overlap vocabulary *and* the keyword arm's filler list, deliberately one list. A keyword arm hunting different words than the check demands is how a chunk gets retrieved and then rejected. |
| `AnswerCheck.contentTerms` | `FtsSearch.sanitize`'s comment on why it is *not* reused | It destroys `"60%"`, `"70"` and `"A+"`. That is correct for topic overlap and wrong for keyword search. |
| `RoutePrototypes.MARGIN` | `QueryRouter.answerSingle`, `HybridSearch.FACT_TOP_K` / `GLOBAL_FANOUT_K`, `scripts/export_minilm_onnx.py` | Changing the margin changes the **route**, which changes `topK` (10 → 30), switches **dedupe-by-document** on, or diverts to the graph path. 13 of 89 measured queries sit within 0.01 of it. |
| the ONNX embedder (re-quantise, re-export, swap weights) | `scripts/export_minilm_onnx.py`'s header, `MiniLmEmbedder`, `SelfTest` | int8 changed the packed context set on **45% of queries** while leaving the vector top-20 identical. Cosine and top-5 will tell you it is fine. Run the batteries. |
| `TopicGate.EDUCATIONAL_TERMS` | `AnswerCheck.subjectTerms`, `unsupportedSubject`, `QueryRouter.withCloudFallback` | One list read three ways: cloud gate, campus-subject test, and "does this word identify anything". Adding a word makes it *less* distinctive everywhere at once. |
| `HybridSearch.FACT_TOP_K` / `perArm` / `RRF_K` | `HybridSearch.pack`, `CONTEXT_BUDGET_CHARS`, both JVM batteries | RRF weights are 0.01639 at rank 1 and 0.01250 at rank 20; the boundary is 50× more expensive to cross than any reorder inside it. |
| `TextChunker.MAX_CHARS` / `MIN_CHARS` | `HybridSearch.CONTEXT_BUDGET_CHARS`, `TextChunker`'s header | Chunk shape decides whether an imported document competes on merit or crowds the college's documents out of the budget. |
| `RouteRules`' rule order | every comment in that file | Each placement names the question the next rule would otherwise steal. |
| `SqlTemplates` branch order / `Constraint` | `SqlTemplates`' header, `QueryRouter.answerTabular` | `resolve` creates an obligation that only `answerTabular` discharges. A correct number answering a different question is worse than an abstention. |
| `UserCorpusDb`'s schema or tokenizer | `BrainDb`, `HybridSearch.mergeKeyword`, `UserCorpusDb.migrate` | Two files searched as one corpus. A different analyzer silently reprices every user document. |
| `UserCorpusDb.ID_BASE` / `INSTITUTION_ID_BASE` | `HybridSearch.search`'s `missing` split, `isUserChunk` vs `isOwnChunk` vs `provenanceOf` | Disjoint id spaces are the only thing separating the databases in one fused ranking **and** the only thing separating a student's import from a synced college document. |
| anything in `data/auth/` | `Entitlement`'s header, `Licensing`'s header | The rule is that none of it may reach the ask path. Adding an import of it to `retrieval/` or `answer/` breaks the one invariant the product is sold on. |
| `QueryLog.record`'s signature | `QueryLogTest.'record cannot be handed a question'` | A reflection test fails on any `CharSequence` parameter. That is the point. |
| a battery floor (`FLOOR`) | `JvmAdversarialBatteryTest`'s companion comment | Raise it only from a measured run, and update the date in the comment. |
| the `androidTest` batteries | this section | Don't. They are the scoreboard. |

---

## 9. The invariants

Each rule below carries **where it is enforced** and **what breaking it would look
like**. The enforcement class is the important column:

- **Structural** — the code shape makes it impossible or a compile error.
- **By test** — a named test fails.
- **Convention** — nothing catches it. This is the fragile set.

### I1. Retrieval never gates on auth, licence state, sync or the network

> Airplane mode answers everything, forever.

**Enforced:** *mixed — the strongest part is convention.*

| Part | Class | Where |
|---|---|---|
| No auth/licensing reference exists in `retrieval/` or `answer/` | **Convention** | verified by grep at `3647056`; nothing prevents an import being added |
| `EntitlementState.retrievalAllowed` is true for every state | **By test** | `EntitlementTest.'every entitlement state still answers questions'` — exhaustive over the enum |
| Every `Tier` has a positive import allowance, so no tier is a dead end | **By test** | `LicenseTest.'no tier withholds an answer'` |
| `Identity.init` and `Licensing.init` make no network call | **Structural** | no HTTP type is constructed on either path |
| Auth is not reachable through `BrainRepository` | **Structural** | `Identity` and `Licensing` are separate objects with their own connections |

**Breaking it looks like:** the airplane-mode demo answering for exactly 60
minutes and then abstaining, because an access token lives 3600 seconds. Or a
question failing on a device whose enrolment lapsed. Both are silent on a
connected desk and fatal in a room.

**Honest caveat.** `LicenseTest`'s own comment says it plainly: if a future commit
adds `val canAsk: Boolean` to `Tier`, that test does not fail. What is asserted is
the checkable thing — every tier allows at least one import — not the whole rule.
`CLAUDE.md` currently states this as *"there are no entitlement references anywhere
outside `data/auth/` except three lines of `MainActivity`"*. **That form is not
accurate** (see §10). The accurate form is: *nothing in `retrieval/` or `answer/`
references auth or licensing at all.*

### I2. Licence failures resolve downward to free, never to locked

**Enforced:** **by test**, over a structural default.

- `Licensing.Caps.FREE` is the compiled-in answer to every failure —
  `Licensing.capsFor` returns it for null and for expired, and there is no branch
  that returns "unknown, allow it".
- `LicenseTest`: `a null licence resolves to free, not to unknown`; `an expired
  licence drops to the free allowance, not to zero`; `the free default is the
  lowest tier and the smallest cap`; `a paying customer is never worse off than a
  free one` (`coerceAtLeast` the free floor).
- `LicenseStore` fails soft in one direction only: `load` → null → FREE.

**Breaking it looks like** either half: a storage failure read as *unlimited*
(the product given away to any device an attacker can break), or an expired
licence read as *zero* (a student on that phone can no longer add their own
timetable, and the college paying late becomes the student's problem).

### I3. Import caps block new imports only; nothing already imported is hidden or deleted

**Enforced:** **structural + by test.**

- `IngestResult.LicenseRequired` carries `used`, `cap`, `tier`, `limit` — and
  **no field that could instruct a removal**. There is no path from this type to
  `UserCorpusDb.remove`.
- `LicenseTest.'lowering a cap refuses new imports and deletes nothing'`: at
  `used = 50` against `cap = 1`, the refusal reports 50 rather than trimming it.
- `LicenseTest.'the document cap binds at used equals cap'`,
  `'the byte cap accounts for the incoming file'`,
  `'a one byte file costs a kilobyte, so tiny files cannot defeat the cap'`.
- `Licensing.removeLicense` clears the licence row only.

**Breaking it looks like:** an institution's licence lapsing on a Friday and four
hundred phones losing the documents the registrar spent a term importing.

### I4. `QueryLog.record` cannot carry query text

**Enforced:** **structural + by test** — and stated precisely, because an earlier
claim about this was slightly too broad.

- `record(route: Route, abstained: Boolean, citedDocIds: Collection<String>)`.
  No string-shaped parameter exists.
- `QueryLogTest.'record cannot be handed a question'` asserts it **reflectively**
  against the signature: no parameter type may be assignable to `CharSequence`. A
  `String?` defaulted to null would satisfy every behavioural test and still be a
  hole.
- `AnalyticsStore` has no per-query row; `'a usage row carries exactly six keys
  and none of them is free text'` and `'the export carries no question text'`
  cover the transmit and export paths.

**What is *not* covered by that invariant:** `QueryLog.recordText` exists, and
`AskFragment` calls it on every answer. It is inert unless `keepQueryText` was
turned on by an admin **on this device**, defaults to false
(`'the raw text sample is off by default'`), is capped at 200 oldest-dropped
(`'the raw text sample is capped'`), and persists to `analytics_query_text`. It
is local-only: nothing in `QueryLog`, `AnalyticsStore` or the export path opens a
socket, and "export" is the Android share sheet handing the user their own file.

**Say "`record` cannot carry query text", not "`QueryLog` cannot".** The second is
false, and a reader who discovers `recordText` after being told the second will
distrust everything else in this file.

**Breaking it looks like:** a `String?` parameter added to `record` "for
debugging", and six months later a table of what named students asked their
college.

### I5. `brain.db` is read-only; user data lives in `user_corpus.db`

**Enforced:** **structural + by test.**

- `BrainDb.openWith` runs `PRAGMA query_only = ON` on every connection, including
  the JVM test one.
- `JdbcSQLiteAdapterTest.'query_only really is read-only, so the bundle cannot be
  written'` — a `CREATE TABLE` against `JvmCorpus.db` must fail.
- `BrainDb.open` re-copies the asset whenever `built_at_utc` changes. Anything
  written into `brain.db` would be destroyed by the next release; `user_corpus.db`
  is never re-copied and never stamped.
- `UserCorpusDb` is the only writable corpus, and `EntitlementStore`,
  `LicenseStore` and `AnalyticsStore` all live in that same file for the same
  reason.

**Breaking it looks like:** an app update silently deleting every imported
document, every entitlement and every licence — and doing it on the release build,
weeks after the change, with nothing in the diff that looks like a delete.

### I6. A correct abstention beats a confident near-miss

**Enforced:** **by test in the local path; convention at the configuration
boundary.**

- There is **no generative model** in `AnswerComposer` / `AnswerCheck`. Answers
  are sentences lifted from retrieved chunks, or verbatim table cells, or
  deterministic SQL.
- Many tests defend specific shapes of it: `'a count question is not answered by
  a CSV column list'`, `'a sentence about procedure notices existing is not a
  consequence'`, `'no verdict is invented when the corpus states no threshold'`,
  `'the average SGPA of students who failed is refused, never rendered as zero'`,
  `'a question the corpus has no field for abstains instead of narrating'`,
  `'no tier table means no invented ladder'`.
- **The caveat, and it is a real one:** in a build where an operator installed a
  `config.json` with a model key, `CloudAnswer` *is* a generative model in the
  answer path — reached only after `AnswerComposer` abstained, labelled
  `[General guidance, not from your college's records]` or `[From your college's
  documents, with general guidance where they were silent]`, and given the
  retrieved passages as grounding. That is a **configuration** boundary, not a
  structural one. Nothing in the repo enables it and the app never writes the file.

**Breaking it looks like:** a wrong confident answer about a fee deadline. One of
those discredits every correct answer beside it.

### I7. The three `androidTest` batteries are the scoreboard and must not be edited to make a score move

**Enforced:** **convention only.** Nothing prevents an edit. The JVM ports say
"the androidTest file is untouched" in their headers, and `CLAUDE.md` says it, and
that is all.

**Breaking it looks like:** a green run and a worse app — a probe's `expect`
string quietly widened, or a probe deleted, and no diff anywhere that reads as a
regression.

### The fragile set — invariants held by convention alone

These are the ones a small model can break without anything going red. **Treat a
diff that touches them as needing a human read.**

| # | Rule | Why nothing catches it |
|---|---|---|
| **I1a** | No `retrieval/` or `answer/` file imports `data/auth/` | Enforced by absence. Adding an import compiles and every test passes. |
| **I1b** | Retrieval makes no network call | Same. `QueryRouter` already constructs `CloudAnswer` on one path, so "no HTTP here" is not even locally true. |
| **I7** | The `androidTest` batteries are not edited to move a score | No test tests a test. |
| **I6a** | No generative model is introduced into the local answer path | Nothing asserts the absence of a dependency. |
| **I5a** | Nothing durable is written to `brain.db`'s *directory* | `query_only` covers writes through the connection, not a file dropped beside it that the next re-copy orphans. |
| **I3a** | Every `IngestResult` `when` stays exhaustive | Exhaustiveness is a compile error *today*; a `else ->` branch added anywhere silently ends that protection. |
| **I4a** | `recordText`'s opt-in stays default-false and local | Defaults are tested; "local" is enforced by there being no socket, which is again an absence. |
| **CI** | A skipped battery is visible in the run | Addressed by the workflow change described in §11 — but a job-summary line is documentation, not a gate. |

### Recommended tests (out of scope for this change — `app/src/` is untouched here)

Phase 4's first bullet asks for the invariants pinned as tests. This pass could
not write them. Here is the list, one line each, for whoever can:

1. **`retrieval/` and `answer/` import nothing from `data/auth/`** — a source-scan
   or ArchUnit-style test over the two package directories. This is the single
   highest-value missing test in the repo: it converts the product's central claim
   from convention to structural.
2. **No file in `retrieval/` or `answer/` references `java.net`, `HttpURLConnection`
   or `okhttp` — except `CloudAnswer`** — same mechanism, explicit allowlist of one.
3. **`AnswerComposer.compose` produces byte-identical output for the same chunks
   regardless of `Licensing` state** — a direct, cheap assertion of I1 at the seam
   that matters.
4. **A `RetrievedChunk` from `user_corpus.db` still surfaces after `capsFor`
   returns `Caps.FREE`** — pins I3 end-to-end rather than at the arithmetic.
5. **A checksum or probe-count assertion over the `androidTest` battery files** —
   makes I7 catchable, even crudely.

---

## 10. Where the code contradicts its own documentation

Recorded here rather than fixed, because fixing them means editing files outside
this change's scope.

1. **`CLAUDE.md`: "there are no entitlement references anywhere outside
   `data/auth/` except three lines of `MainActivity` startup."**
   Measured at `3647056`: `MainActivity` has **five** (lines 87, 93, 100, 160,
   233), and there are also references in `data/DocumentIngest.kt`
   (`Licensing.Caps`, `Licensing.caps()` — by design, that *is* the import cap),
   `ui/ask/AskFragment.kt`, `ui/admin/AdminAnalyticsFragment.kt`,
   `ui/license/LicenseFragment.kt`, `ui/auth/*`.
   **The material claim holds and is stronger than the stated one:** `retrieval/`
   and `answer/` contain zero references to auth or licensing. That is the form to
   repeat.

2. **`docs/fullstack-plan.md` Phase 4: "a regression gate in CI: adversarial ≥ 23,
   hard ≥ 19."**
   The code says `FLOOR = 22` and `FLOOR = 18`, each one below its measured value
   on purpose, with the ONNX-float-nondeterminism argument written out in the
   companion comment. The plan document is aspirational; the constants are what
   runs.

3. **The brief for this document described `SENTENCE_SPLIT` as affecting "sentence
   extraction and money figures".** Reading the code: the money-figure protection is
   the `ABBREVIATIONS` / `endsWithAbbreviation` merge inside `sentencePieces`, not
   the regex itself. The regex's *second* consumer is `requiredMinimums`, which
   splits on it **unguarded and deliberately**, and where a change reshuffles
   which **scope** a threshold is attributed to. Both consumers break if you touch
   it — for different reasons.

4. **`AnswerCheck.unsupportedSubject` corrects its own earlier comment, in place.**
   An earlier version claimed "debarred" lives in a pipe row that `sentencesOf`
   drops. The conclusion was right and the reason was wrong: `SENTENCE_SPLIT`
   breaks after the full stop ending the consequence cell, so the row arrives with
   two pipes, under the three-pipe bar, and is kept. The word is refused a quote by
   the two-term floor in `bestAnswer`. This is the house style and it is a good
   one — **correct a comment in place rather than deleting it**, so the next reader
   sees the mistake was found.

5. **`EntitlementStore` states its own rationale is partly weaker than it reads.**
   The header says the survive-an-update property comes from the *location*
   (`filesDir`), not from `user_corpus.db` specifically, and that a separate
   `entitlement.db` would work equally well. Worth knowing before "fixing" the
   file-sharing as if it were load-bearing.

6. **Not a contradiction, but a documentation gap worth naming.** `CLAUDE.md`'s
   "Known open work" records that the compound attendance answer meets its battery
   expectation **lexically rather than substantively** — the 65–74% condonation
   chunks sit outside `FACT_TOP_K`. A green probe there is not evidence the answer
   is right. `docs/fullstack-plan.md` Phase 3 makes the same point generally: *a
   probe can keep passing while its answer gets worse, and that has already
   happened once on this project.* **Diff the answer text, not just the score.**

---

## 11. CI

`.github/workflows/android-app.yml` runs the full JVM suite on every push to
`android-app/**` and uploads an installable debug APK.

The ONNX embedder is gitignored (86 MB, regenerable), so the workflow fetches it
from release `assets-v1` with `continue-on-error: true`. That non-fatal choice is
deliberate and argued in the workflow's own comments: a release that is missing or
renamed should show up as **reduced coverage in the log**, not as a red build on
unrelated work.

The gap this change closes: when that fetch failed, the batteries skipped their
floors (`if (JvmCorpus.vectorArm)`), the build still passed, and **nothing said
coverage had been reduced**. A "Retrieval coverage" step now writes an explicit
verdict into the job summary and the log, before the tests run, naming whether the
run will include the vector arm.

**Read this before changing it:** the check is an **asset presence** check, which
is a proxy. `JvmCorpus.vectorArm` is true only when the model actually *loaded* —
`JvmCorpus.modelAssetPresent` exists precisely to tell "no model here" from "the
model would not load". The authoritative per-run statement is the line
`JvmCorpus ready: fts=… vectors=… prototypes=… vecs=…` that `JvmCorpus.build()`
prints into the test output. The summary step says which of the two it is
reporting. Do not make a missing asset fail the build.
