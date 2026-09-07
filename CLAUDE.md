# Campus Brain — working notes

## Where things are (read this first)

| What | Where |
|---|---|
| This repo | `E:\projects\campus-brain-android` (gradle root is `android-app/`) |
| Remote | `github.com/RohanExploit/campus-brain-android`, branch `main` |
| Gradle user home | `E:\gradle-home` (`GRADLE_USER_HOME`, set at User scope) |
| Other recovered repos | `E:\projects\R-recovered\` |

**Never put caches, build output, or scratch files on `C:`** — the user has asked
for it to stay clear. `E:` is the internal NVMe and is the right place.

**`R:` is dead.** It was a JMicron USB external drive that dropped off the bus
repeatedly on 2026-09-06 and then corrupted. It used to hold the project, the
Android SDK and the Gradle cache. Any path starting `R:\` in older notes,
`local.properties`, or a comment is stale. This repo was recovered by cloning
from GitHub, so **only committed work survived**.

## Lost with the drive, and what to do about it

- **Android SDK** (`R:\toolchains\android-sdk`) — must be reinstalled before any
  local build. `ANDROID_HOME` still points at the dead path. Cloud CI is
  unaffected: the runner brings its own.
- **Play upload keystore** (`android-app/keystore/campus-brain-upload.jks`) and
  its `keystore.properties`. Gitignored, so never on GitHub. The app has **not**
  been published, so generating a fresh upload key costs nothing — do that
  rather than trying to recover it. Once published, this becomes unrecoverable
  without a Play key reset.
- **Licence signing private key** (`campus_brain_license_private.pem`). Its
  public half is compiled into `LicenseKey.PUBLIC_KEY_B64`, so that constant is
  now useless: nobody can mint a key that matches it. Generate a new pair with
  `scripts/issue_license.py --generate-keypair` and replace the constant. No
  licence has been issued to anyone, so nothing breaks.
- **`app/src/main/assets/minilm/`** (~23 MB ONNX embedder). Gitignored and
  regenerable via `scripts/export_minilm_onnx.py`. Without it the app falls back
  to keyword-only retrieval, which it handles and states in the UI.
- **One agent's uncommitted multi-hop work** — see "Known open work" below.

`brain.db` **is** committed (deliberately, see its note in `android-app/.gitignore`),
so the corpus survived and a CI-built APK can still answer.

## Build

```
cd android-app
GRADLE_USER_HOME='E:\gradle-home' ./gradlew :app:assembleDebug :app:testDebugUnitTest
```

`local.properties` is gitignored and must point at wherever the SDK gets
reinstalled. A machine-specific JDK pin belongs in `E:\gradle-home\gradle.properties`,
**never** in `android-app/gradle.properties` — a committed absolute path there
broke every CI run until 2026-09-06.

Cloud CI (`.github/workflows/android-app.yml`) runs the full JVM suite on every
push and uploads an installable debug APK as an artifact. When local builds are
unavailable, push and read CI — that is the faster path, not a fallback.

## The one rule that outranks every feature

**Retrieval must never gate on auth or licence state.** A user with no licence,
an expired one, or a storage failure still gets every answer, in airplane mode,
forever. The corpus, student records and query text never leave the device;
only identity does. This is the product's entire commercial claim.

Enforced structurally rather than by convention: there are no entitlement
references anywhere outside `data/auth/` except three lines of `MainActivity`
startup, and `QueryLog.record` has no string-shaped parameter, so query text
cannot enter the counters. Keep it that way.

Every licence failure resolves **downward to free, never to locked** — an
expired licence, an unreadable store or a corrupt key all yield the free
allowance. Import caps block new imports only; nothing already imported is ever
hidden or deleted.

## Verification habits that have earned their place

- **Numbers must come from the run you are describing.** Check test-XML
  timestamps before quoting a pass count; stale results have been reported as
  fresh here more than once.
- **Simulate before claiming a routing change is safe.** Agents transcribe the
  pipeline into Python, first reproduce the expectations already pinned in the
  tests, and only then diff old versus new. That method has caught unintended
  changes three times.
- **The three `androidTest` batteries are the scoreboard** — do not edit them to
  make a score move.
- A correct abstention beats a confident near-miss. There is no generative model
  in the answer path and that is a choice, not a gap.
- **Kotlin through a shell heredoc eats `\n`** and produces `Syntax error:
  Expecting '"'`. Use raw strings or a file-writing tool. This has bitten five times.

## Known open work

- **Multi-hop answers.** An agent diagnosed and fixed these, and the work was
  lost with the drive before it could be committed. Only the two enum names
  `Need.CONSEQUENCE` and `Need.PERMISSION` survive, stubbed to `OTHER`.
  The diagnosis was: `Need` had nothing between `ELIGIBILITY` (requires a stated
  number) and `OTHER` (no shape enforced), so numberless multi-hop questions were
  decided by topic overlap alone — and topic overlap cannot tell a rule from the
  paperwork beside it. Also found: `SENTENCE_SPLIT` breaks after `Rs.`, which
  truncates every money figure, and `mentions()` has no word boundary, so `miss`
  matches inside `submission`.
- **`what happens to my scholarship if I am debarred`** is genuinely
  unanswerable — no document states it. It should abstain, not answer.
- **Device verification** — nothing has run on hardware since roughly 2026-09-06
  18:00. The release build has never run on a device at all.
- **Play submission** — needs a Data Safety declaration and a privacy policy URL.
  Be accurate: identity (email/password) is transmitted to Supabase; content and
  queries never are.

## Package name

`com.campusbrain.app`. Renamed from `com.kriet.campusbrain` on 2026-09-06,
before first publish, because Play keys a listing on it permanently. Do not put
a specific institution's name in the app's own prose, comments or fixtures — the
name is data that arrives in the licence grant. The exceptions are `ScopeGate`
and `DocCatalog`, where the real institution name is corpus content and is what
makes the app refuse questions about other colleges.
