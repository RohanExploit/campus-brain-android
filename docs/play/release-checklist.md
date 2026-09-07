# Campus Brain — what remains before the first Play upload

Ordered by what blocks what. Items 1–7 are hard blockers: the upload is
rejected, or the submission is a policy risk, until each is done. Items 8–15 are
required-but-mechanical. Items 16–19 are things found while reading the code
that someone should decide about, not necessarily before v1.

Everything here is grounded in a file in this repo. Where a fact could not be
established from the repo it is marked **`TODO`** rather than guessed.

Current state: `versionCode = 1`, `versionName = "0.1"`, `minSdk = 26`,
`targetSdk = 36`, `compileSdk = 36`, `namespace/applicationId =
com.campusbrain.app`, not yet published
(`android-app/app/build.gradle.kts`).

---

## Blockers

### 1. The release build is signed with the debug key — Play will reject it

`android-app/app/build.gradle.kts`:

```kotlin
signingConfig = signingConfigs.findByName("release")
    ?: signingConfigs.getByName("debug")
```

`keystore.properties` is gitignored and, per `CLAUDE.md`, the upload keystore
was **lost with the drive** and never existed on GitHub. So a build on any
current machine silently produces a debug-signed release, and Play refuses an
artifact signed with a debug certificate.

Do:
- Generate a **new** upload keystore. The app has never been published, so this
  costs nothing now and becomes unrecoverable-without-a-key-reset the moment it
  is. Put it at `android-app/keystore/` (already gitignored) with a
  `keystore.properties` beside it.
- Back it up somewhere that is not this machine. The drive failure is the whole
  reason this item exists.
- Consider making the fallback loud rather than silent — e.g. fail the
  `release` build when `keystore.properties` is absent, instead of quietly
  swapping in the debug config. A silent fallback is how a debug-signed AAB
  reaches an upload dialog.

### 2. Decide whether `config.json` ships to institutional devices

This is the question the Data Safety form hangs on. See `data-safety.md`,
"READ THIS FIRST".

Both network paths — the cloud answer fallback
(`answer/CloudAnswer.kt:58 loadConfig`) and Supabase identity
(`data/auth/AuthConfig.kt:46 findConfigFile`) — read the same `config.json` from
`getExternalFilesDir(null)` then `filesDir`. The file is not in the repo, not in
`app/src/main/assets/`, and nothing in the app writes it.

**`TODO: state, in writing, whether a provisioned deployment places this file on
student devices and which keys it carries.`** Then fill the Data Safety form
from Column A or Column B accordingly. Do not submit the form before this is
settled.

### 3. Build and upload an Android App Bundle, not an APK

New apps must ship as `.aab`. Nothing in the repo builds one:
`.github/workflows/android-app.yml:72` runs `:app:assembleDebug` only.

Do: run `:app:bundleRelease` with the new signing config, and confirm the
resulting AAB is signed with the upload key and not the debug key
(`apksigner verify --print-certs` on an APK extracted via `bundletool`).

### 4. Fix `welcome_private_body` — it contradicts the Data Safety form

`android-app/app/src/main/res/values/strings.xml` currently tells the student:

> "…nothing on the path from your question to its answer opens a connection at
> all."

`CloudAnswer` is invoked from `QueryRouter.answer()`
(`retrieval/QueryRouter.kt:287`), which *is* that path, and it POSTs the
question and the retrieved passages. The sentence is true only for a device with
no `config.json`.

Play requires in-app disclosures to agree with the Data Safety declaration. A
replacement string is drafted in `data-safety.md` §5. Sweep the same claim out
of `README.md`, `docs/pitch.md` and the decks while you are there.

### 5. Account deletion — built, but not yet applied or hosted

Google Play requires apps that let users create an account to offer account
deletion — in-app **and** through a URL reachable without installing the app.
Both halves are now written. Three things remain, and each one is a blocker on
its own:

**5a. Apply the migration.** `supabase/migrations/20260907000000_delete_my_account.sql`
creates `public.delete_my_account()`, a `SECURITY DEFINER` function that derives
the account from `auth.uid()` and takes no parameters, so it cannot be aimed at
another user. It deletes `usage_events`, `memberships` and the `auth.users` row,
and returns `true` when a user row existed. It has **never been run against the
project** — nobody here has applied it or seen it succeed. Two things to watch
when it is applied:

- if any table not named in the migration references `auth.users` with the
  default `NO ACTION`, the final `DELETE` raises a foreign-key violation and the
  function deletes nothing. That is the designed failure and it is loud; add the
  table to the migration rather than working around it.
- until it is applied, PostgREST answers `404 / PGRST202`, which the client
  reads as *"Your institution could not be reached"* — nothing is deleted and no
  local state is cleared. Pinned in `DeleteAccountTest`.

**5b. Give the in-app route a discoverable home.** It exists — enrolment screen,
bottom, **Delete this account** — but the enrolment screen is itself reached
only by tapping the header status pill, an undocumented gesture, and a reviewer
who cannot find the control will fail the submission for not having one.
`MainActivity` and the nav host own that entry point. Either surface it
somewhere a reviewer lands without being told, or write the exact path into the
Play Console review notes: *tap the status pill in the header → Enrol this
device → Delete this account*.

**5c. Host `docs/play/account-deletion.md` and fill its TODOs.** Play wants the
URL to work without installing the app. It needs `TODO: contact email`, an
effective date, and a stated handling commitment (who acts on emailed requests
and within how long). Enter the URL in Play Console **and** back into the
document header.

Follow-up, not a blocker on its own: `privacy-policy.md` §8 still says "the app
has no in-app control and no server call that deletes a Supabase account", and
its appendix source map repeats it. That is now false and must be rewritten to
point at this route before the policy is published. Once it is, Data Safety §1.3
can be answered **Yes**.

What was built, for review: `ControlPlane.deleteAccount` + `classifyDelete`,
`Identity.deleteAccount`, `AccountDeletion` (the outcome→result and
what-gets-cleared decisions, Android-free), `EntitlementStore.clearAccount` +
`ACCOUNT_TABLES`, `ui/auth/DeleteAccount{Fragment,ViewModel,Copy}`, and
`DeleteAccountTest` (12 tests). The local wipe empties `auth_session` and
`entitlement` and **nothing else** — imported documents, the analytics store and
the licence are in the same file and are asserted to survive.

### 6. Publish the privacy policy at a real URL

`docs/play/privacy-policy.md` is written and self-contained. It needs:
- `TODO: contact email` filled in — it appears twice (header and §11) plus once
  in §9;
- `TODO: effective date`;
- the internal **Appendix — source map** block deleted before publishing;
- hosting somewhere stable, and the URL entered in Play Console **and** back
  into the document header.

### 7. Add an HTTPS check to the LAN model tiers, or declare "not encrypted in transit"

`answer/CloudAnswer.kt:414 parseConfig` accepts `ollama_url` and `device_url`
with **no scheme validation**, and the documented examples in the same file are
`http://10.0.0.5:11434` and `http://127.0.0.1:11434`.
`data/auth/AuthConfig.kt:73` already does the right thing for Supabase —
`if (!url.startsWith("https://")) return null`.

If a LAN tier is configured, the question and the retrieved passages travel in
clear over the campus network, and "all user data is encrypted in transit"
becomes a false Data Safety answer.

Do: mirror `AuthConfig`'s guard for `ollama_url` (loopback `device_url` may
reasonably stay exempt, since it never leaves the handset), then answer **Yes**.

---

## Required, mechanical

### 8. Store graphics

None exist. The only icon in the repo is an adaptive vector at
`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — fine for the launcher
(minSdk 26 covers it), but Play separately requires an uploaded raster icon.

Needed: 512 × 512 hi-res icon, 1024 × 500 feature graphic, 2–8 phone
screenshots. See `store-listing.md` for a suggested screenshot set. **Check no
screenshot shows a real student name** — the shipped corpus is synthetic, a
deployed one may not be.

### 9. Data Safety form

Fill from `data-safety.md`, after item 2 is decided. Do not submit before items
5 and 7 are resolved, because two of its answers depend on them.

### 10. Content rating questionnaire and target audience

Target audience **13 and over**, deliberately excluding under-13 — under-13
pulls the app into the Families policy, which makes the third-party model call
in `CloudAnswer` a much harder problem. See `store-listing.md`.

### 11. App access declaration

Declare that no credentials are needed. Answering never gates on auth or licence
state (`CLAUDE.md`, "The one rule that outranks every feature"), so a reviewer's
fresh install answers questions immediately. Leaving this blank invites a
review hold.

### 12. Ads and in-app purchases: both No

No ad SDK and no Play Billing dependency in
`android-app/app/build.gradle.kts`.

### 13. Restore the embedder asset before promising anything about it

`app/src/main/assets/minilm/` (~23 MB ONNX) is gitignored and was lost; it is
regenerable via `scripts/export_minilm_onnx.py`. Without it the app runs
keyword-only and says so in the header
(`MainActivity.kt` — `"$docs documents · keyword only"`).

Either ship it, or keep the listing free of any semantic-search claim — which
`store-listing.md` already does.

### 14. Regenerate the licence signing keypair

`CLAUDE.md`: the licence signing private key was lost, so the compiled
`LicenseKey.PUBLIC_KEY_B64` constant can never be matched. No licence has been
issued, so nothing breaks — but a shipped build whose licence path can never
succeed is a defect a buyer will find. Run
`scripts/issue_license.py --generate-keypair` and replace the constant.
`app/src/test/.../ShippedKeyTest.kt` exists and should be checked after.

### 15. Run the release build on hardware — it never has

`CLAUDE.md`: nothing has run on a device since ~2026-09-06 18:00, and **the
release build has never run on a device at all**. Minimum before upload:
install the signed release, confirm the corpus opens
(`BrainDb.open` → `BrainDbMissingException` is the failure to look for), ask one
question of each of the four routes, confirm airplane mode answers identically,
and exercise the share-a-document import (`MainActivity.handleSharedDocument`).

---

## Found while reading; decide, but not necessarily before v1

### 16. `arm64-v8a` only

`android-app/app/build.gradle.kts` sets `abiFilters += "arm64-v8a"`. Play
accepts this; it narrows the device catalogue (no armeabi-v7a, no x86_64) and
blocks emulator testing. The comment in the file already flags the emulator
consequence. Decide whether the reduced reach matters for the first release.

### 17. The bundled corpus in a real deployment

`app/src/main/assets/brain.db` ships inside the APK: 493 chunks, 369 rows in
`students(roll_no, name, sgpa, estimated_sgpa, total_marks, result, is_supply,
seat_cancelled)`, 2,952 in `student_subjects`, `tenant_id = tenant_canon`.
`android-app/.gitignore` states these are curated synthetic rows.

A real deployment substitutes a real institution's corpus, which puts **real
student PII inside a binary distributed on a public store**. That is a
deliberate decision to take with the institution — not a Play policy violation,
and not something the Data Safety form asks about, but it is the single largest
data-protection exposure in the product.

### 18. Any user can query another student's record

`retrieval/TabularIntent.kt` routes `name_search`, `record_by_roll` and a roster
branch against the bundled tables, and `QueryRouter.answer()` guards only the
empty query (which would otherwise print the whole roster —
`QueryRouter.kt:49-51`). There is no per-student scoping: whoever holds the app
can ask for anyone's marks.

Defensible for a single institution's own app on its own students' phones, and
indefensible if anyone assumes otherwise. Get it stated explicitly in the
institution's agreement.

### 19. `postUsage` is one call site away from changing the Data Safety form

`data/auth/ControlPlane.kt:194 postUsage` would transmit
`{tenant_id, user_id, event, route, latency_ms, ok}`. Its only wrapper,
`data/auth/Identity.kt:283 reportUsage`, has **no caller anywhere in
`app/src/main`**, which is why "App interactions" is declared not-collected.

If anyone wires it up, the Data Safety form must gain an *App interactions*
entry (collected, purpose *Analytics*) in the same release. Consider putting
that sentence in a comment at `Identity.kt:283` so the tripwire lives in the
code.

### 20. A user-facing string tells students to run `adb push`

`data/BrainDb.kt:open` throws `BrainDbMissingException` with
`"adb push brain.db /sdcard/Android/data/<pkg>/files/brain.db"` in the message,
and `strings.xml` shows failure text to the student. Correct for a demo device,
wrong on a consumer store listing. Confirm which of these strings can actually
reach a student's screen, and give that path institution-facing copy instead.

---

## Cannot be determined from this repo

| Unknown | Why |
|---|---|
| Whether `config.json` is provisioned to student devices, and with which keys | The file is not in the repo, is not written by the app, and the provisioning mechanism lives outside this repository |
| Contact email, effective date, privacy-policy hosting URL | Not present anywhere in the repo |
| Whether the server-side `redeem_enrolment_code` really discards the plaintext code | The Postgres function is not in this repository; only the KDoc at `ControlPlane.kt:69-74` asserts it |
| Groq / Anthropic / Supabase retention, sub-processors, and whether a DPA makes them service providers rather than third parties | No contracts or terms in the repo; this decides the "Shared" answer for question text |
| Whether a deployed corpus contains real student PII | The committed one is documented as synthetic; deployed ones are built outside this repo |
| Play's current tag list | Console-side, changes over time |
| Whether the release build works on hardware | It has never been run on a device |
