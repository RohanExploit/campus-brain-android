# Google Play Data Safety — answers to enter, question by question

**App:** Campus Brain (`com.campusbrain.app`), versionCode 1 / versionName 0.1
**Prepared from:** the source tree at `android-app/`, read on 2026-09-07.
Paths below are relative to `android-app/app/src/main/java/com/campusbrain/app/`
unless stated otherwise.

---

## READ THIS FIRST — the one decision that changes several answers

**Two network paths exist and both are gated on a single file, `config.json`,
that has to be placed on the device. Neither is compiled in, and neither ships
in the APK.**

- `answer/CloudAnswer.kt:58 loadConfig` → `:404 findConfigFile` looks for
  `config.json` in `getExternalFilesDir(null)` then `filesDir`.
  `:414 parseConfig` returns `null` unless it finds a model key or a model URL.
- `data/auth/AuthConfig.kt:46 findConfigFile` reads **the same file, same name,
  same order** for the Supabase URL and anon key. With no file,
  `data/auth/Identity.kt` reports `EnrolResult.NotConfigured` and no identity
  call is ever made.
- There is no `config.json` anywhere in the repo, none in
  `app/src/main/assets/`, and nothing in `main/` writes one — the only
  `writeText` call sites are `data/BrainDb.kt:99` (corpus stamp) and
  `diag/CrashLog.kt:333/338` (crash log).

So a bare APK installed from Play, with no file pushed to it, **collects and
transmits nothing at all**.

**Do not declare that.** The Data Safety form has one answer per data type and
no "depends on configuration" option, and this product is sold per institution
with a provisioned deployment. Declaring "collects nothing" on the strength of
an unprovisioned build is exactly the technically-true declaration that gets an
app pulled after a reviewer decompiles the APK, finds `api.groq.com` and a POST
that carries the user's question, and concludes the form was wrong.

**Two columns are given below.** Enter **Column A** if any shipped or supported
deployment provisions `config.json`. Enter Column B only if you can state that
no deployment ever will, and that the code paths will be removed.

> **`TODO: decide and record.` Does `config.json` reach student devices in an
> institutional deployment, and does it carry (a) a Supabase URL + anon key,
> (b) a model key or model URL, or both?** This cannot be determined from the
> repo — the file is not in it, is not written by the app, and the provisioning
> mechanism is not in this repository. Everything below hangs on the answer.

Column A below assumes **both** are provisioned, which is the conservative
declaration. If only identity is provisioned and the cloud fallback is not, use
Column A for section 3.1 and Column B for section 3.3 / 3.4, and say so in your
internal record.

---

## 1. Preliminary questions

### 1.1 "Does your app collect or share any of the required user data types?"

| | Answer |
|---|---|
| **Column A (configured)** | **Yes** |
| **Column B (bare APK, nothing provisioned)** | **No** |

*Why (A):* `data/auth/SupabaseAuth.kt:60 signUp` transmits an email and a
password; `retrieval/QueryRouter.kt:287` transmits the user's question and the
retrieved passages to `answer/CloudAnswer.kt:81 answerWithProvenance`.

### 1.2 "Is all of the user data collected by your app encrypted in transit?"

| | Answer |
|---|---|
| **Column A** | **No — as the code stands today.** See the fix in the checklist; once applied, **Yes**. |
| **Column B** | n/a |

*Why:* The two hosted destinations are HTTPS and hard-coded —
`answer/CloudAnswer.kt:286` (`https://api.groq.com/...`), `:287`
(`https://api.anthropic.com/...`) — and `data/auth/AuthConfig.kt:73` **rejects
any Supabase URL that does not start `https://`**.

But `answer/CloudAnswer.kt:414 parseConfig` applies **no scheme check** to
`ollama_url` or `device_url`, and the documented examples in the same file are
`http://10.0.0.5:11434` and `http://127.0.0.1:11434`. If an institution
configures a LAN model server, the question and the retrieved passages go over
**plain HTTP**. `127.0.0.1` never leaves the handset and is not a transit
concern; a LAN address is.

**Recommended action:** add the same `startsWith("https://")` guard that
`AuthConfig` already has (or explicitly allow only loopback for `device_url`),
then answer **Yes**. Until then the honest answer is **No**, and answering
"Yes" would be a false declaration for any deployment that uses the LAN tier.

### 1.3 "Do you provide a way for users to request that their data is deleted?"

| | Answer |
|---|---|
| **Column A** | **No** — as the code stands today. This is a submission gap; see below. |
| **Column B** | n/a (nothing is collected) |

*Why:* `data/auth/SupabaseAuth.kt:125 signOut` and `data/auth/Identity.kt:291
signOut` clear the **local** session and entitlement only.
`data/auth/ControlPlane.kt` contains exactly four calls — `:75 redeem`,
`:124 fetchGrant`, `:171 fetchCorpusVersion`, `:194 postUsage` — and **none of
them deletes anything**. The GoTrue user record and the `memberships` row
persist server-side with no route to remove them.

Google Play requires account-creating apps to offer an account-deletion route,
in-app **and** via a web URL reachable without installing the app. This has to
be built or contracted (e.g. a registrar-operated request address plus a
documented server procedure) before the account path ships. See
`release-checklist.md`, item 5.

---

## 2. Data types — the ones to mark as collected

Play's taxonomy is used verbatim below. For every type, "Collected" means
transmitted off the device; on-device-only storage is **not** declared here (but
is described in `privacy-policy.md`, section 3).

### 2.1 Personal info → **Email address**

| Field | Column A | Column B |
|---|---|---|
| Collected | **Yes** | No |
| Shared | No | — |
| Processed ephemerally | No (the account persists) | — |
| Required or optional | **Optional** — users can choose whether to provide it | — |
| Purpose | **Account management** | — |

*Justification:* `data/auth/SupabaseAuth.kt:60 signUp` / `:66 signIn` POST
`{"email", "password"}` to Supabase GoTrue.
Mark **optional** on two independent grounds: enrolment itself is optional —
retrieval never consults it (`data/auth/Identity.kt` header; the router has no
entitlement reference) — and a student who gives no address gets a random
`.invalid` one generated on-device (`data/auth/SupabaseAuth.kt:292
syntheticEmail`). The generated address is still transmitted and still stored,
so it is still a collected email address; do not treat it as an exemption.

### 2.2 Personal info → **User IDs**

| Field | Column A | Column B |
|---|---|---|
| Collected | **Yes** | No |
| Shared | No | — |
| Processed ephemerally | No | — |
| Required or optional | Optional (same reasoning as 2.1) | — |
| Purpose | **Account management** | — |

*Justification:* Supabase issues a user id (`data/auth/SupabaseAuth.kt:
parseTokenResponse`, `jwtSubject`), it is persisted on device by
`data/auth/EntitlementStore.kt`, and it is presented to the server on every
subsequent control-plane request as a bearer token.
This is *not* a device identifier and not an advertising id — see 2.9.

### 2.3 Personal info → **Other info** (the enrolment code)

| Field | Column A | Column B |
|---|---|---|
| Collected | **Yes** | No |
| Shared | No | — |
| Processed ephemerally | **See caveat** | — |
| Required or optional | Optional | — |
| Purpose | **Account management** | — |

*Justification:* `data/auth/ControlPlane.kt:75 redeem` POSTs `{"p_code": ...}`
to the `redeem_enrolment_code` RPC. The code identifies the institution and the
cohort, so it is institution-linked personal info rather than free text.

> **Caveat — do not tick "processed ephemerally" on the strength of the
> comment.** The KDoc at `ControlPlane.kt:69-74` states the code is matched
> server-side against a sha256 of its uppercased form and the plaintext is never
> stored. That is a claim about a Postgres function which **is not in this
> repository**, so it is unverified here. Confirm the server-side function
> before ticking ephemeral; if you cannot, leave it unticked.

**Password:** transmitted at `SupabaseAuth.kt:60/66`. Play's taxonomy has **no
"password" or "credentials" data type**, so there is no box to tick. It is
covered by the Email address entry with purpose *Account management*. Noted here
deliberately so that its absence from the form is a decision on the record
rather than an omission.

### 2.4 App activity → **In-app search history** — the student's question text

**This is the entry that matters most, and it is the one the project's own prose
gets wrong.**

| Field | Column A | Column B |
|---|---|---|
| Collected | **Yes** | No |
| Shared | **Yes** — see the sharing note below | — |
| Processed ephemerally | No — cannot be claimed; the provider's retention is not under your control | — |
| Required or optional | **Required** — see the note below | — |
| Purpose | **App functionality** | — |

> **Why "Required", not "Optional".** Play's required/optional question asks
> whether *the user* can choose not to provide the data. A student typing a
> question has no toggle: there is no setting, no prompt and no consent screen
> anywhere in `ui/` for the cloud fallback, and `retrieval/QueryRouter.kt`
> decides on its own. The *institution* chooses, by provisioning
> `config.json` — but the institution is not the user. Answering "Optional"
> because a deployment might not enable it confuses the two, and is the
> reading a reviewer will not accept. Declare **Required** for a deployment
> where the fallback is provisioned. If you would rather it be genuinely
> optional, add a per-student on/off control and a first-use prompt; then
> "Optional" becomes true rather than argued.

*Justification:* `retrieval/QueryRouter.kt:287` calls
`answer/CloudAnswer.kt:81 answerWithProvenance(query, contextText)`, and `query`
is the student's typed question verbatim. It is POSTed to
`api.groq.com` (`CloudAnswer.kt:286`), to `api.anthropic.com` when the key
begins `sk-ant-` (`:287`), or to an operator-supplied host (`:253
callOllamaAt`). The question is sent **twice** — a draft pass and a verifier
pass (`CloudAnswer.kt:81-160`).

Conditions under which it fires, all of which must hold: a `config.json` with a
key or URL exists; `AnswerComposer` abstained; `answer/TopicGate.kt
isEducational` returned true (`QueryRouter.kt:250`); the question is not the
second half of a compound question; and the corpus does not merely hold nothing
on a named campus subject (`QueryRouter.kt:279`). A student cannot see or
control whether this is on.

### 2.5 Files and docs → **Files and docs** — retrieved passages, including the user's own imports

| Field | Column A | Column B |
|---|---|---|
| Collected | **Yes** | No |
| Shared | **Yes** — same note as 2.4 | — |
| Processed ephemerally | No | — |
| Required or optional | **Required** — same reasoning as 2.4; importing a document is optional, but once imported the student has no control over whether its passages are sent | — |
| Purpose | **App functionality** | — |

*Justification:* `retrieval/QueryRouter.kt:285` builds `contextText` by joining
the retrieved `passages` and hands it to the cloud call at `:287`. Those
passages are **not** limited to the institution's bundle:
`data/BrainRepository.kt:66-73` wires `UserCorpusDb` into
`HybridSearch.UserArms`, and `retrieval/HybridSearch.kt:101-187` fuses both
corpora into one ranked list. So **text from a document the student imported
themselves — a timetable, a syllabus, a notice — can be transmitted.**

This directly contradicts the "the corpus never leaves the device" framing. It
is the single most important correction on this page.

> **Note on the "Shared" answer for 2.4 and 2.5.** Play treats a transfer to a
> *service provider* processing on your behalf as collection but not sharing.
> Whether Groq or Anthropic qualify depends on the contract you hold with them,
> and no such contract is visible in this repo. **Declare Shared = Yes unless
> you have a data-processing agreement establishing service-provider status**,
> and record the reasoning either way. An operator-supplied `ollama_url` host is
> a third party under anyone's reading unless the institution runs it itself.

---

## 3. Data types to mark as NOT collected — with the reason each "no" holds

The distinction the reviewer cares about: **structurally impossible** (no code
path exists) versus **currently not configured / not called** (one edit away).
Both are marked.

| Play data type | Answer | Basis | Kind of "no" |
|---|---|---|---|
| Location → Approximate, Precise | No | No location permission in `AndroidManifest.xml`; no `LocationManager`/`FusedLocation` reference anywhere | **Structural** |
| Personal info → Name, Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation | No | No such field is read or transmitted. The bundled corpus contains 369 student names, but it is read-only app content and no path transmits it — see section 4 | **Structural** |
| Financial info (all) | No | No payments, no billing library, no purchase flow | **Structural** |
| Health and fitness (all) | No | No such data exists in the app | **Structural** |
| Messages → Emails, SMS or MMS, Other in-app messages | No | No messaging surface, no SMS/telephony permission | **Structural** |
| Photos and videos | No | No camera or media permission; `AndroidManifest.xml` intent filters accept text, markdown, csv, docx and pdf only | **Structural** |
| Audio files | No | No microphone permission, no audio code | **Structural** |
| Calendar | No | No calendar permission or provider access | **Structural** |
| Contacts | No | No contacts permission or provider access | **Structural** |
| Web browsing history | No | No WebView browsing, no history capture | **Structural** |
| App activity → **App interactions** | **No — but see the note below** | `data/auth/ControlPlane.kt:194 postUsage` exists and would transmit `{tenant_id, user_id, event, route, latency_ms, ok}` (`:280 USAGE_KEYS`). Its only wrapper is `data/auth/Identity.kt:283 reportUsage`, and **`reportUsage` has no call site anywhere in `app/src/main`** | **Configuration/wiring — one line from being live** |
| App activity → Other user-generated content | Covered under Files and docs (2.5); do not double-declare | `retrieval/QueryRouter.kt:285` | — |
| App activity → Installed apps, Other actions | No | Nothing enumerates packages or transmits interaction events | **Structural** |
| App info and performance → **Crash logs** | **No** | `diag/CrashLog.kt` writes to `filesDir/diagnostics/crash.log` and nothing in that file opens a socket. Sharing is user-initiated through `ui/selftest/SelfTestFragment.kt:117-122` (`ACTION_SEND` + `createChooser`), which Play exempts as a user-initiated action, and the text is shown in full before sending | **Structural for auto-collection**; user-initiated share is exempt |
| App info and performance → Diagnostics, Other app performance data | No | No Crashlytics, no Sentry, no Firebase, no analytics SDK — see `android-app/app/build.gradle.kts` dependency list, which has none | **Structural** |
| Device or other IDs | No | No advertising id, no `ANDROID_ID`, no IMEI, no install id transmitted. `diag/CrashLog.kt:factsOf` reads `Build.MODEL` but writes it to a local file only | **Structural** |

### Note on App interactions

`postUsage` is written, tested (`app/src/test/.../SupabaseAuthTest.kt:195-239`
pins the payload to exactly six keys with an equality assertion) and wired as
far as `Identity.reportUsage` — and then nothing calls it. Declaring **No** is
correct for the build as it stands. Declare it and record the reason, because
the day someone adds one call from a UI screen, the form becomes wrong:
`user_id` plus `tenant_id` plus an event stream is **App interactions**,
collected, purpose *Analytics*, and it must be added to the form in the same
release. Put a comment to that effect at `Identity.kt:283` if you want the
tripwire in the code rather than in this document.

---

## 4. Things that are *not* Data Safety entries but will be asked about

- **The 369 student records in the bundled corpus.** `app/src/main/assets/brain.db`
  carries `students(roll_no, name, sgpa, estimated_sgpa, total_marks, result,
  is_supply, seat_cancelled)` and `student_subjects(...)` — 369 rows, 2,952
  subject rows, `tenant_id = tenant_canon`. This is **app content shipped in the
  APK**, not data collected from the user, so it is not a Data Safety entry.
  Two consequences that are not Play questions but are real:
  1. `android-app/.gitignore` states these rows are curated synthetic data. A
     real deployment substitutes a real institution's corpus, which puts **real
     student PII inside a public Play binary**. That is a distribution decision
     to make deliberately, not a Data Safety answer.
  2. `retrieval/TabularIntent.kt` routes `name_search`, `record_by_roll` and a
     roster branch, so any holder of the app can query another student's marks
     from the bundled tables. Not a Play policy violation — it is the
     institution's own data on the institution's own app — but it is an access
     control question the institution has to have answered.
- **On-device-only storage is not declared.** The opt-in question-text sample
  (`data/QueryLog.kt:48/84`, `data/AnalyticsStore.kt:49`), the usage counters,
  and the crash log never leave the device and therefore are not Data Safety
  entries. They **are** described in `privacy-policy.md` section 3, which is
  where Play expects on-device storage to be disclosed.

---

## 5. Consistency check before you submit

Play requires the Data Safety declaration, the privacy policy, and **in-app
disclosures** to agree.

**`welcome_private_body` in `android-app/app/src/main/res/values/strings.xml`
currently says:**

> "Questions, marks and attendance are read on the device and are never
> uploaded. This is structural rather than a promise: nothing on the path from
> your question to its answer opens a connection at all."

That sentence is **false in a configured deployment.** `CloudAnswer` is called
from `QueryRouter.answer()` — squarely on the path from a question to its
answer — and it opens a connection carrying the question and the retrieved
passages. It is true only for a build with no `config.json`.

Fix the copy before submitting, or the in-app claim contradicts the form. A
truthful version that keeps the strength of the claim:

> "Your marks and attendance are read on this phone and are never uploaded.
> Answers come from the documents already on the device. If your college has
> switched on the optional general-guidance fallback, a question the documents
> cannot answer — and the passages we found — are sent to a language-model
> service, and the answer is labelled when that happens."

Same check applies to any deck, README or store copy repeating "nothing leaves
the device".
