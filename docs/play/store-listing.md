# Google Play store listing — Campus Brain

Every claim below is checked against the source. Notes marked **why this is
safe** exist because the neighbouring wording would not have been.

No institution is named anywhere in this listing. The institution's name arrives
in the licence grant and in corpus content, never in the app's own prose — see
`CLAUDE.md`, "Package name".

---

## App name

```
Campus Brain
```

12 / 30 characters.

---

## Short description

```
Your college's own documents, answered offline, with the source shown.
```

70 / 80 characters.

**Why this is safe:** offline answering is the default and only local path
(`data/BrainDb.kt`, `retrieval/*` — no network code); citations are attached to
every locally-answered result (`data/Models.kt:61 AnswerResult.sources`,
rendered as chips by `ui/ask/MessageAdapter.kt:168-172`). It says "your college's
own documents", not "knows everything about your campus".

---

## Full description

```
Campus Brain answers questions about your college from your college's own
documents — fees, timetables, attendance rules, scholarships, placements and
examination records — and it does it on your phone.

WORKS WITH NO NETWORK

The documents, the search index and the record tables are already on the
device. Turn on airplane mode and ask the same question: you get the same
answer, at the same speed. There is no server to be slow, no server to be down,
and no queue outside an office.

EVERY ANSWER SHOWS WHERE IT CAME FROM

An answer arrives with the college document it was read from listed underneath.
Tap it and you land on the passage itself. Nothing is offered that you cannot go
and check — which is the point, because a confident wrong answer about a fee
deadline costs a student real money.

IT QUOTES, IT DOES NOT PARAPHRASE

An answer from your college's documents is the sentence the document actually
says, picked out and shown to you — not a rewrite of it, and not a summary that
might have dropped the condition that mattered. Where the documents are silent,
Campus Brain says they are silent rather than inventing a plausible figure. A
correct "the documents do not say" is worth more than a confident near-miss.

YOUR RECORDS STAY ON YOUR PHONE

Marks, attendance and results are read on the device and are not uploaded.
There is no Campus Brain server holding student records — the college prepares
the document set and it travels with the app. The app asks for no location, no
contacts, no photos and no storage permission.

ADD YOUR OWN DOCUMENTS

Share a timetable, a notice or a syllabus into Campus Brain from any app — or
open it with Campus Brain from your file manager — and it is searched alongside
the college's own documents straight away. It stays on this phone, it survives
app updates, and you can remove it whenever you like.

ASK IN PLAIN ENGLISH

"How much is the second-year fee?" "What is the attendance rule for a medical
absence?" "What is my SGPA?" Campus Brain works out which part of the corpus can
answer the question and searches there.

WHAT IT IS NOT

It is not a chatbot and does not try to be. It will not write your assignment,
and it will not answer questions that are nothing to do with college life.

OPTIONAL GENERAL GUIDANCE

Some institutions switch on a fallback: when the college's own documents cover
nothing on a campus question, the app can ask an outside language-model service
for general guidance instead of simply refusing. Where that is switched on, the
question and the passages found are sent to that service, and the answer is
clearly labelled as general guidance rather than as something from your
college's records. Where it is not switched on, the app makes no network request
at all. Full detail is in the privacy policy.

Campus Brain is licensed to institutions. Answering is never licensed and never
gated: every question is answered from the documents already on the phone, at
every tier, with or without a licence and with or without a network.
```

3,022 / 4,000 characters.

### What was deliberately kept out

| Not written | Because |
|---|---|
| "AI-powered assistant that knows your campus" | The local answer path is extractive, not generative — `answer/AnswerComposer.kt:76 compose` ends at `val lead = applied ?: finding!!.sentence` (`:177`), i.e. a sentence lifted out of a retrieved passage. Calling it AI would misdescribe it, and would then be contradicted by the one place a model *is* used. |
| "It does not compose answers" as an unqualified claim | True of the local path only. With the fallback configured, a language-model service composes the reply (`answer/CloudAnswer.kt:81`). The heading is "IT QUOTES, IT DOES NOT PARAPHRASE" and is scoped to answers from the college's documents, with the fallback paragraph carrying the exception. |
| "Nothing ever leaves your device" | False whenever `config.json` is provisioned — `retrieval/QueryRouter.kt:285-287` sends the question and the retrieved passages. The "OPTIONAL GENERAL GUIDANCE" paragraph exists to keep the listing consistent with the Data Safety form. |
| "Understands the meaning of your question" | The neural embedder (`app/src/main/assets/minilm/`) is gitignored, absent from the repo, and lost with the drive. Without it the app falls back to keyword-only retrieval and says so in the header (`MainActivity.kt` — "keyword only"). Do not promise semantic search until the asset ships. |
| "Answers instantly" / any latency figure | Per `CLAUDE.md`, nothing has run on hardware since ~2026-09-06 and the release build has never run on a device, so there is no current measurement to stand behind. |
| Multi-hop question support | Broken and known-broken — see `CLAUDE.md`, "Known open work". |
| Any institution or university name | Sold per-institution; the name arrives as data. |
| "Secure" / "encrypted" | See `data-safety.md` §1.2: the LAN model tier has no HTTPS check. Do not claim encryption in marketing copy until that is fixed. |

---

## Category and tags

- **App category:** Apps → **Education**
  (Not Games. Not Productivity: the content is institutional reference
  material and the audience is students of one institution.)
- **Tags** — Play allows up to 5, chosen from its fixed list in Console rather
  than typed. Closest matches to request:
  `Education` · `College & University` · `Study Tools` · `Documents` ·
  `Reference`
  `TODO: confirm against the tag list Console actually offers; the list changes
  and cannot be verified from this repo.`

## Store settings that go with the listing

- **Contains ads:** No. There is no ad SDK in `android-app/app/build.gradle.kts`.
- **In-app purchases:** No. Licensing is sold to the institution out of band —
  see `data/auth/License.kt`; there is no Play Billing dependency.
- **Target audience:** **13 and over.** Students at a post-secondary institution
  include 17-year-olds, so an 18+ selection would be wrong; but the audience must
  **not** include under-13, which would pull the app into the Families policy and
  make the third-party model call in `answer/CloudAnswer.kt` a much harder
  problem. `TODO: if the cloud fallback is enabled for a deployment, re-review
  whether 16+ or 18+ is the right selection.`
- **Content rating questionnaire:** no violence, no user-to-user communication,
  no user-generated content shared between users, no purchases, no location
  sharing. Expect Everyone / PEGI 3, subject to the ads and data questions.
- **App access:** the reviewer needs **no credentials**. Answering never gates on
  auth or licence state (`CLAUDE.md`, "The one rule that outranks every
  feature"), so a fresh install answers questions immediately. State that in the
  App access section rather than leaving it blank.

## Graphics still to produce

None of these exist in the repo — the only icon present is an adaptive vector at
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`.

- 512 × 512 PNG hi-res icon (32-bit, alpha) — **required**
- 1024 × 500 feature graphic — **required**
- 2–8 phone screenshots, min 320 px on the short edge — **required**
  Suggested set, all of which the app can actually show: an answer with its
  citation chip; the same answer in airplane mode; the source passage after
  tapping the chip; the share-a-document import; an honest abstention.
  Use the synthetic corpus, and check no screenshot shows a real name.
- 7-inch and 10-inch tablet screenshots — optional, only if you declare tablet
  support.
