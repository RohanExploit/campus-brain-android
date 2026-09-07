# Campus Brain — Privacy Policy

**App:** Campus Brain (`com.campusbrain.app`)
**Effective date:** `TODO: effective date`
**Contact:** `TODO: contact email`
**Published at:** `TODO: privacy policy URL` (Google Play requires a publicly reachable URL for this document)

This policy describes what Campus Brain does with information. It is written to
be accurate to the app as built, not to be reassuring. Where behaviour depends
on how your institution has configured the app, that is said plainly rather than
averaged into a general claim.

---

## 1. What the app is

Campus Brain answers questions about your institution — fees, timetables,
attendance rules, scholarships, placements, examination records — by searching a
set of documents and record tables that are stored **on your phone**, inside the
app. It shows you which document each answer came from.

The search runs on the device. There is no Campus Brain server that receives
your questions, and there is no Campus Brain server that holds your
institution's documents or student records.

---

## 2. The short version

- Your questions, the institution's documents, and the student record tables are
  read **on the phone**.
- Nothing is uploaded automatically for advertising, profiling, or product
  analytics.
- Three things can send data off the phone, and **each of them is off unless
  your institution has turned it on, or unless you deliberately start it**:
  1. **Enrolment and licence identity** — an email address, a password, and an
     enrolment code, sent to Supabase (the identity service your institution
     uses). Only if you choose to enrol.
  2. **The optional cloud answer fallback** — when the institution's documents
     genuinely do not answer your question, the app may send **your question and
     the passages it retrieved** to a third-party language-model service for
     general guidance. Only if your institution has configured this.
  3. **Anything you share yourself** — a usage summary or a crash log that you
     explicitly send through Android's share sheet.
- Uninstalling the app deletes everything the app stored on the phone. The app
  sets `allowBackup="false"`, so none of it is copied into Android/Google cloud
  backup either.

---

## 3. What is stored on your device

All of the following lives in the app's own storage on your phone and is not
transmitted by the app:

| What | Where | Notes |
|---|---|---|
| The institution's document corpus, search index, embeddings, and student record tables | `brain.db`, in app storage | Read-only. Prepared by your institution and shipped with the app. |
| Documents you import yourself (a timetable, a syllabus, a notice) | `user_corpus.db`, in app-private storage | Searched alongside the institution's documents. You can remove them. |
| Usage counters | app-private database | Counts only: how many questions per retrieval route, how many were abstentions, and which documents were cited. **No question text.** |
| A sample of question text — **only if an administrator on this device turns it on** | app-private database | Off by default. Capped at 200 entries. Turning the setting off deletes the sample that was already collected. Never transmitted, and never included in the usage export. |
| Your sign-in session and entitlement, if you enrolled | app-private database | Access token, refresh token, user id, institution id, licence state. |
| A crash log | app-private storage, `diagnostics/crash.log` | Timestamp, app version, Android version, device model, thread name, stack trace. Nothing is sent anywhere automatically. |

Question text, marks, attendance figures and document contents are **never**
written into the usage counters. The counter function has no parameter that a
question could be passed through.

---

## 4. What can leave your device

### 4.1 Enrolment and licence identity (optional; only if configured)

If your institution has configured identity for its deployment and you choose to
enrol, the app sends the following to **Supabase**, an identity and database
service:

- an **email address** — either one you type, or, if you would rather not give
  one, a randomly generated address at a reserved `.invalid` domain that can
  never receive mail;
- a **password** you choose;
- an **enrolment code** issued by your institution;
- afterwards, the session tokens returned to the app, on each request.

The app then reads back only: your membership row (institution id, role,
status), your institution's licence row (display name, licence state, offline
grace period), and metadata describing whether a newer document bundle exists
(a version number, a build timestamp, a checksum, a size).

**Nothing else is sent on this path.** No question, no document, no retrieved
passage, no mark, no attendance figure. The functions that make these calls have
no parameter that could carry one.

Enrolling is optional. The app answers every question without it, offline,
forever. If you never enrol, nothing on this path happens at all.

### 4.2 The optional cloud answer fallback (only if configured)

Campus Brain does not write answers. It finds the sentence in your institution's
documents that answers your question and shows it to you with its source.

When the documents genuinely contain nothing on a question that is still about
college or campus life, the app may — **only if your institution has placed a
configuration file with a model key or a model address on the device** — send
the following to a third-party language-model service:

- **the text of your question**, and
- **the passages the search retrieved**, which may include text from your
  institution's documents and from documents you imported yourself.

The reply is then sent back a second time to the same service to be checked
before you see it. Answers produced this way are labelled on screen as general
guidance rather than as something from your institution's records.

Depending on the configuration file, the destination is one of:

- **Groq** (`api.groq.com`), or
- **Anthropic** (`api.anthropic.com`), or
- a **model server address chosen by your institution** — for example a machine
  on the campus network, or a model running on the phone itself.

**If no configuration file is present on the device, this path never runs and
nothing is sent.** A build installed from Google Play does not contain that
file; it has to be placed on the device.

### 4.3 Things you send yourself

- **Usage export.** An administrator can share a plain-text usage summary
  through Android's share sheet, choosing the destination app. The export
  contains counts, per-route figures, the administrator's own time assumptions,
  and the list of documents nothing has cited. **Question text is never
  included, at any setting.**
- **Crash log.** From the self-test screen you can share the crash log through
  the share sheet. The text is shown to you in full before you send it. Nothing
  is sent automatically, and the app never reads the Android system log
  (`logcat`), which can contain your content.

---

## 5. What is never sent, under any configuration

- Your marks, grades, attendance, results, or roll number.
- The institution's record tables.
- Your location. The app requests no location permission.
- Your contacts, calendar, photos, camera, microphone, or files outside the ones
  you explicitly share into the app.
- An advertising identifier. There is no advertising, no ad SDK, no analytics
  SDK, and no crash-reporting SDK in this app.
- The on-device sample of question text, if an administrator has enabled it.

---

## 6. Permissions the app requests

- `INTERNET` and `ACCESS_NETWORK_STATE` — used only by the two optional paths in
  sections 4.1 and 4.2. Searching and answering never use the network. With both
  paths unconfigured, the app makes no network request at all.

The app requests no other permission — no storage, location, contacts, camera or
microphone permission. (Android also lists one internal, signature-level
permission, `com.campusbrain.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which
the AndroidX libraries add so the app's own components can talk to each other. It
grants no access to anything on your phone and no other app can hold it.)

---

## 7. Third parties

| Party | When | What it receives |
|---|---|---|
| **Supabase** | Only if identity is configured **and** you enrol | Email address (real or generated), password, enrolment code, session tokens |
| **Groq** or **Anthropic** | Only if the cloud fallback is configured **and** the documents do not answer your question **and** the question is about college life | Your question and the retrieved document passages |
| **A model server your institution names** | Same conditions, if that is what the configuration specifies | Your question and the retrieved document passages |
| **The app you pick in the share sheet** | Only when you tap share | The usage summary, or the crash log |

Each of these services handles the data it receives under its own terms and
retention policy, which Campus Brain does not control. `TODO: link your
institution's Supabase and model-provider terms here if you enable those paths.`

There is no advertising network, no analytics provider, and no crash-reporting
provider.

---

## 8. Retention and deletion

**On your device.** Counters and the optional question-text sample accumulate
until they are cleared. Turning the question-text setting off deletes the sample
immediately. The crash log holds at most about 32,000 characters and older
entries are dropped as new crashes are recorded; you can clear it from the
self-test screen. Documents you imported stay until you remove them, and survive
app updates.

**When you uninstall.** Everything above is removed with the app. Because the
app sets `android:allowBackup="false"`, none of it is copied into Android's
automatic cloud backup, so an uninstall is a real deletion rather than a
deletion that a restore would undo.

**Signing out.** Signing out inside the app deletes the session tokens and the
entitlement record from the phone. Answering is unaffected — it never depended
on them.

**Deleting the account.** The enrolment screen offers **Delete this account**.
It asks for confirmation, naming what goes and what stays, and then calls a
function on the institution's server that removes the account, the membership
row and any usage rows belonging to it. The function derives the account from
the caller's own signed-in identity and takes no parameters, so it cannot reach
another person's data.

What deletion removes: the account, the membership that ties it to the
institution, the licence grant, and the usage rows recorded against it.

What deletion leaves alone, on purpose: the college's bundled documents, every
answer the app can give, and any documents added by the student. None of that
belonged to the account, and the app keeps working exactly as before — offline,
indefinitely, with no sign-in.

A student who cannot open the app can request the same deletion by writing to
`TODO: contact email`. See `account-deletion.md` for the full description,
which is the page hosted for Google Play's web-accessible requirement.

---

## 9. Students under 18

Campus Brain is intended for students at a post-secondary institution, and some
of them are under 18. The app is **not** directed at children under 13 and must
not be listed as such.

The app does not build a profile, does not target advertising, and asks for no
personal information beyond what is described in section 4.1 — and enrolment can
be completed with a generated address that identifies nobody, because it is the
institution's enrolment code, not the email address, that establishes that a
student belongs.

If the cloud fallback is enabled for a deployment, a student's typed question is
sent to a third-party model service. Institutions enrolling minors should
consider whether to enable that path at all, and should obtain whatever consent
local law requires before doing so.

If you believe a child under 13 has provided personal information through this
app, contact `TODO: contact email` and it will be deleted.

---

## 10. Changes to this policy

If the app's data behaviour changes, this policy and the Google Play Data Safety
declaration are updated in the same release, before that release is published.

---

## 11. Contact

`TODO: contact email`

---

<!-- ============================================================
     NOT PART OF THE PUBLISHED POLICY.
     Source map, so every claim above can be re-checked against the code.
     Delete this block before pasting the policy onto a hosting page.
     ============================================================ -->

## Appendix — source map (internal; remove before publishing)

Paths are relative to `android-app/app/src/main/java/com/campusbrain/app/`.

| Claim | Established by |
|---|---|
| Retrieval is local; the corpus is a bundled SQLite file opened read-only | `data/BrainDb.kt:open`, `data/BrainDb.kt:openAt` (`PRAGMA query_only = ON`) |
| Router and search never open a socket | `retrieval/QueryRouter.kt`, `retrieval/HybridSearch.kt`, `retrieval/FtsSearch.kt`, `retrieval/TabularQueries.kt` — no `URL`/`HttpURLConnection` import anywhere in `retrieval/` |
| Only two files in `main/` open a connection | `answer/CloudAnswer.kt:170` and `:253`; `data/auth/SupabaseAuth.kt:351` (`SupabaseHttp.call`) |
| Cloud fallback transmits the question **and** retrieved passages | `retrieval/QueryRouter.kt:285` builds `contextText` from `passages`; `:287` passes it to `answer/CloudAnswer.kt:81 answerWithProvenance(query, context)` |
| Retrieved passages can include user-imported documents | `data/BrainRepository.kt:66-73` wires `UserCorpusDb` into `HybridSearch.UserArms`; `retrieval/HybridSearch.kt:101-187` fuses both corpora into one result list |
| Cloud destinations | `answer/CloudAnswer.kt:286` (Groq), `:287` (Anthropic, selected when the key starts `sk-ant-`), `:253 callOllamaAt` (operator-supplied `ollama_url` / `device_url`) |
| Cloud path is inert without a device-side `config.json` | `answer/CloudAnswer.kt:58 loadConfig`, `:404 findConfigFile`, `:414 parseConfig` (returns null when there is no key and no Ollama URL). No file named `config.json` exists in the repo or in `app/src/main/assets/`, and no code writes one |
| Identity path is inert without the same `config.json` | `data/auth/AuthConfig.kt:46 findConfigFile`, `data/auth/Identity.kt EnrolResult.NotConfigured` |
| Identity sends only email, password, enrolment code, tokens | `data/auth/SupabaseAuth.kt:60 signUp`, `:66 signIn`, `:99 refresh`; the class KDoc states the boundary and no method takes a query or a record |
| Only four control-plane calls exist, none carrying content | `data/auth/ControlPlane.kt:75 redeem`, `:124 fetchGrant`, `:171 fetchCorpusVersion`, `:194 postUsage` |
| Synthetic email at a reserved `.invalid` domain | `data/auth/SupabaseAuth.kt:292 syntheticEmail`, `data/auth/AuthConfig.kt DEFAULT_SYNTHETIC_DOMAIN` |
| Counters cannot carry question text | `data/QueryLog.kt:67 record(route, abstained, citedDocIds)` — no free-text parameter |
| Opt-in question-text sample, default off, deleted when switched off | `data/QueryLog.kt:48 keepQueryText`, `:84 recordText`; `data/AnalyticsStore.kt:49` (table), `:166 setKeepQueryText` (`DELETE FROM analytics_query_text` when switched off) |
| Question text is never exported | `ui/admin/AdminAnalyticsFragment.kt:247` and `UsageExport` |
| Usage export and crash log are user-initiated share-sheet text | `ui/admin/AdminAnalyticsFragment.kt:226-231`, `ui/selftest/SelfTestFragment.kt:117-122` (`ACTION_SEND`, `EXTRA_TEXT`, `createChooser`) |
| Crash log is local, capped, and excludes logcat | `diag/CrashLog.kt` header, `:61 DIR_NAME`, `FILE_CAP_CHARS`, `:297 fileIn`, `:304 clear` |
| Only two permissions, with the reason in the manifest | `android-app/app/src/main/AndroidManifest.xml` |
| `allowBackup="false"` | `android-app/app/src/main/AndroidManifest.xml` |
| Sign-out is local only; no server-side delete exists | `data/auth/SupabaseAuth.kt:125 signOut`, `data/auth/Identity.kt:291 signOut`; `data/auth/ControlPlane.kt` has no delete call |
