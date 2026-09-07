# Fullstack execution plan

Goal, in the user's words: *an Android app that works on the internet, where we
can ingest documents and get cross-document answers, with the same architecture
we have now.*

Debugging is explicitly **out of scope** for these phases and will be done later
by cheaper models. So every phase below is written to front-load the decisions
that are expensive to get wrong, and to leave behind invariants and tests that
make later debugging mechanical rather than exploratory.

---

## The architectural decision that shapes everything

There are two ways to make this "fullstack", and only one of them keeps the
product.

**Rejected: move retrieval to a server.** Ask a question, POST it, get an answer.
This is the obvious reading of "works on the internet", and it destroys the
thing the product is sold on. Retrieval stops working offline, query text and
student records start crossing the network on every question, the "records never
leave the institution's hardware" claim dies, and the per-query cost stops being
zero. It also throws away the entire on-device engine — 384 tests of routing,
templates and answer checking.

**Chosen: move *distribution* to the server, keep retrieval on the device.**
The server stores the institution's corpus — chunks, embeddings, metadata — and
devices sync it down into the local store they already have. Every question is
still answered locally, offline, from `brain.db` plus `user_corpus.db`. What the
network buys is that a document the registrar adds appears on 400 phones, and
that answers can span documents nobody put on that phone by hand.

Consequences worth stating:

- **Airplane mode still answers everything.** The claim survives intact.
- **Zero server compute per question.** Free-tier viable at 400 students.
- **The existing engine is reused, not replaced.** Sync writes into
  `user_corpus.db`, which `HybridSearch` already fuses with the bundle.
- The honest caveat, already true today: the optional cloud fallback in
  `CloudAnswer` does send passages when an institution configures it. That is a
  configuration boundary and is documented as one. Nothing here widens it.

**Embedding compatibility is the hard constraint.** Vectors are only comparable
if they come from the same model. The app ships all-MiniLM-L6-v2 (384-dim, fp32)
and `brain.db`'s vectors came from it. Anything the server stores must come from
the same weights, or cosine similarity is noise wearing a ranking's clothes. See
the header of `scripts/export_minilm_onnx.py`, which measured exactly this.

**The trick that avoids server compute entirely:** the app already chunks,
extracts and embeds documents on-device (`DocumentIngest`, `TextChunker`,
`MiniLmEmbedder`). So the registrar imports a document **on their own phone**,
using the pipeline that already exists and is already tested, and uploads the
resulting chunks and vectors. No server-side Python, no embedding service, no
model hosting, no cold starts. The identical model produced both sides by
construction rather than by discipline.

---

## Phase 1 — Shared corpus: schema, upload, sync

The core of "fullstack". Everything else depends on it.

**Server (Supabase, project `campus-brain-dev`, ref `cdsakhcvdwbbafdcwdux`).**
Migrations under `supabase/migrations/`, following the one already there.

- `corpus_documents` — tenant-scoped: `doc_id`, `title`, `category`,
  `size_bytes`, `chunk_count`, `published_at`, `published_by`, `revision`.
- `corpus_chunks` — `doc_id`, `ordinal`, `section`, `content`,
  `embedding vector(384)` (pgvector), plus a generated `tsvector` for keyword
  search.
- RLS on both, tenancy from `current_tenant_id()` via `memberships` — never a
  client-supplied claim. This pattern is already proven here: cross-tenant reads
  returned `[]` and privilege escalation returned 42501.
- Write access limited to `admin`/`registrar` roles. Students read only.
- Bump `corpus_versions` on publish; that table already exists and the client
  already fetches it.

**Client.**
- `data/sync/CorpusSync.kt` — pull changed documents since a local watermark,
  write into `user_corpus.db` through the existing `UserCorpusDb.write` path so
  the FTS5 companion insert and vector blob encoding stay in one place.
- `data/sync/CorpusUpload.kt` — the registrar path: run the existing
  `DocumentIngest` pipeline, then upload chunks and vectors.
- Sync is **resumable and idempotent**: interrupted on chunk 30 of 50, the next
  run completes it without duplicating rows. Key on `(doc_id, ordinal)`.
- Sync is **never on the ask path**. It runs on a lifecycle boundary or an
  explicit pull-to-refresh. A failed sync degrades to the corpus already on the
  phone, silently and correctly.

**Deliverables:** migrations; the two client files; a `provenance` marker
distinguishing bundle / institution / user-added; tests for the watermark
arithmetic, idempotent re-sync, and "a failed sync leaves the local corpus
answerable".

**Risks to decide now, not later:** the free tier's row and storage limits at
400 students × N documents; whether vectors are stored as pgvector or as bytes
(pgvector enables server-side search later, bytes are simpler and enough for
sync); and what happens when a document is unpublished — soft delete, so a phone
that missed the window does not keep answering from a withdrawn notice.

---

## Phase 2 — Registrar tooling

Phase 1 makes a shared corpus possible; this makes it usable by a person who is
not a developer.

- An in-app admin surface, gated on role, reusing the existing document import
  flow: pick a file, watch it index, then **Publish to institution**.
- A published-documents list with revision, publisher and date; unpublish;
  republish a corrected version.
- The consequence of publishing stated before it happens: *this will appear on
  every enrolled device.*
- Reuse `AdminAnalyticsFragment`'s patterns and the existing design tokens. No
  new colours, no new dimens.

**Deliverables:** the fragment, the role gate, publish/unpublish/revise, tests
for the role gate and for the state machine (draft → published → withdrawn).

---

## Phase 3 — Cross-document answers

Today's answer path is extractive and single-passage by design — there is no
generative model, and that is a choice, not a gap (`AnswerComposer`'s header).
"Cross-document" must therefore mean *retrieval and composition across
documents*, not free generation.

Three concrete pieces, in order of value:

1. **Graph edges for institution documents.** The bundle has 3,213 edges from an
   offline extraction step; imported documents currently have none, so nothing
   reaches them by following a reference. Extract edges at publish time — on the
   registrar's device, where the document is already parsed — and sync them.
   This is what makes the LOCAL route work across documents.
2. **Multi-passage composition.** `AnswerComposer` already fuses passages for
   `tierConsequences` and `schemeConditions`. Generalise: when two documents each
   answer half a question, cite both. `CompoundQuestion` already splits the
   question; the composer is the piece that needs to answer twice.
3. **Cross-document premise checks.** `PremiseCheck` corrects a false premise
   against the grade scale. The same shape catches a scholarship rule
   contradicted by an attendance rule in a different document.

**Bar:** every clause traceable to a retrieved passage. A correct abstention
still beats a confident near-miss. Any change here is measured on the JVM
batteries before and after, and the answer *text* diffed — not just the score,
since a probe can keep passing while its answer gets worse. That has already
happened once on this project.

---

## Phase 4 — Make the codebase safe for cheaper models

This phase exists because of the constraint in the request: debugging will be
done later by weaker models. Their failure mode is not stupidity, it is
confidently changing something whose reason they did not read. So the work is to
make the reasons unmissable and the guardrails automatic.

- **Pin the invariants as tests, not comments.** Retrieval never gates on auth;
  licence failures resolve downward to free; `QueryLog` cannot carry query text;
  imported documents survive licence expiry. Several are already asserted —
  finish the set, and name each test after the rule it defends.
- **A regression gate in CI**: adversarial ≥ 23, hard ≥ 19. Already floored;
  wire it so a drop fails the build loudly rather than appearing in a log.
- **Extend `CLAUDE.md`** with the traps that have actually cost time here:
  heredocs eating `\n`, staging a file while an agent is mid-write, quoting a
  test count from stale XML, editing the battery to make a score move.
- **A `docs/architecture.md`** naming each subsystem, its one job, and what must
  never be true of it — so a small model can find the boundary without reading
  every file.

---

## Phase 5 — Device, then release

Device work is already specified in `docs/device-test-plan.md`. Then:

- Apply the account-deletion migration; verify against the live project.
- Host the privacy policy and the account-deletion page; fill the three TODO
  placeholders.
- Make the enrolment route discoverable enough for a reviewer (recorded as
  blocker 5b — the status-pill gesture is undocumented).
- Screenshots and a feature graphic, which need the device.
- Decide `versionName` — `0.1` reads as unfinished on a store listing.

---

## Sequencing

Phase 1 is the only true dependency: 2 and 3 both need the shared corpus. Phase
4 can run alongside anything. Phase 5 needs a phone.

The expensive-to-get-wrong decisions all sit in Phase 1 — schema and RLS shape,
vector storage format, sync idempotency, and the choice not to move retrieval
server-side. Those are worth doing while the strongest model is available.
Phases 2 and 3 are additive and testable on the JVM harness. Phase 4 is
deliberately last-but-parallel: it is the handover to whoever debugs this next.
