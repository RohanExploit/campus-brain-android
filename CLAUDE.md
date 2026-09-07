# Campus Brain — working notes

## Where things are (read this first)

| What | Where |
|---|---|
| This repo | `E:\projects\campus-brain-android` (gradle root is `android-app/`) |
| Remote | `github.com/RohanExploit/campus-brain-android`, branch `main` |
| Gradle user home | `E:\gradle-home` (`GRADLE_USER_HOME`, set at User scope) |
| Other recovered repos | `E:\projects\R-recovered\` |
| **What each subsystem must never do** | **`docs/architecture.md` — read it before touching `app/src/`** |

**Never put caches, build output, or scratch files on `C:`** — the user has asked
for it to stay clear. `E:` is the internal NVMe and is the right place.

**`R:` is dead.** It was a JMicron USB external drive that dropped off the bus
repeatedly on 2026-09-06 and then corrupted. It used to hold the project, the
Android SDK and the Gradle cache. Any path starting `R:\` in older notes,
`local.properties`, or a comment is stale. This repo was recovered by cloning
from GitHub, so **only committed work survived**.

## Lost with the drive, and what has been done about it

All of these were gitignored, so none of them was ever on GitHub. All are now
regenerated and living in `android-app/keystore/`, which is gitignored.
**That folder is the only thing here not backed up by git — copy it somewhere
off this machine.** The previous copy died with the drive.

- **Android SDK** — reinstalled at `E:/toolchains/android-sdk`.
  `local.properties` and `ANDROID_HOME` point at it.
- **Play upload keystore** — regenerated (RSA 4096, alias `campus-brain`).
  Passwords in `keystore.properties` beside it. Nothing is published yet, so
  this was free to replace; after the first upload it would not be.
- **Licence signing key** — regenerated. `ShippedKeyTest` now verifies a
  genuinely issued licence through the DEFAULT parameter, so the compiled
  `PUBLIC_KEY_B64` and the private half can never silently drift apart again.
  That gap is how a public key with no matching private half shipped unnoticed.
- **`app/src/main/assets/minilm/`** — regenerated fp32 (86 MB), verified against
  sentence-transformers to 1.19e-07. Keep fp32: int8 was measured and flips the
  retrieval ROUTE on 3 of 89 real questions. See the header of
  `scripts/export_minilm_onnx.py` for the numbers.
- **One agent's uncommitted multi-hop work** — rebuilt, see below.

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
forever. Retrieval is local, the records live on the phone, and only identity
goes to a server. This is the product's entire commercial claim — state it with
the caveat below, not more strongly than that.

How it is actually held, stated exactly, because a looser version of this
sentence was in this file and was wrong (see `docs/architecture.md` §10):
**nothing in `retrieval/` or `answer/` references auth or licensing at all** —
verified by grep, and that absence is the whole enforcement, so a single added
import breaks it with every test still green. `Licensing` and `Identity` are
reached from `MainActivity` startup (five lines, not three),
`data/DocumentIngest` (the import cap, which is what a licence governs), and a
handful of `ui/` screens — never from the ask path.
`QueryLog.record` takes doc ids and route labels and has no string-shaped
parameter at all; a reflection test enforces that. Note that the *class* can
hold text through `recordText`, which is off by default, admin-opt-in, capped
and local — so say "`record` cannot carry query text", not "`QueryLog` cannot".
Keep all of it that way.

**One important caveat, stated because the welcome screen once got it wrong.**
"Nothing ever leaves the device" is true of a bare build and false of a
configured one. If an operator installs a `config.json` with a model key,
`QueryRouter` joins the retrieved passages into `contextText` and `CloudAnswer`
POSTs them with the question — including passages from the user's own imported
documents. It is off by default and the file is neither in the repo nor written
by the app, but it is a **configuration** boundary, not a structural one.
Describe it that way, in code comments and to anyone asking.

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

## Traps that have cost time here

Tooling and environment, all confirmed in this repo. The three habits above are
the *verification* traps; these are the ones that waste an hour before you get
as far as verifying anything.

- **Stage explicit paths. Never `git add -A`.** More than one agent works this
  tree at once (`.claims/`, `TEAMWORK.md`), and `-A` sweeps up whatever another
  one is mid-write. `scripts/claim.ps1` commits only its own claim file for
  exactly this reason. `git status` before every push: *nothing unexpected
  staged.*
- **An XML comment cannot contain `--`.** It is an XML syntax error, not a
  warning. This codebase's prose style uses `--` as an em dash everywhere in
  Kotlin and Markdown; every commented file under `app/src/main/res/` drops it,
  and that is not a coincidence. Use an em dash or a colon in XML.
- **Android string resources need `\'` for an apostrophe.** A bare `'` fails
  the resource compile. See `strings.xml` — every one of `don\'t`,
  `college\'s`, `this college\'s` is escaped.
- **Do not export `MSYS_NO_PATHCONV=1` shell-wide.** It breaks `gradlew` with
  `Could not find or load main class GradleWrapperMain`. Scope it inline to the
  single `adb` call that needs a path protecting. (`docs/device-test-plan.md`.)
- **Uninstall before installing a release build.** Debug and release are signed
  differently, and an existing debug install rejects the release with an opaque
  ddmlib stack trace that names nothing. Reinstall debug afterwards — the
  instrumented batteries need it.
- **A green CI can still mean reduced coverage.** The 86 MB ONNX embedder is
  gitignored and fetched from release `assets-v1` with `continue-on-error`. If
  that fetch fails the JVM batteries skip their regression floors and the build
  goes green anyway. The workflow now writes an explicit "Retrieval coverage"
  line into the job summary; **read it before quoting a CI run as evidence.**

## Known open work

- **Multi-hop answers — rebuilt and shipped.** `Need.CONSEQUENCE` and
  `Need.PERMISSION` are emitted and gated. Still open underneath it: the
  65-74% condonation chunks sit outside `FACT_TOP_K`, so the compound
  attendance answer names only the Below-65% tier, and the battery's
  expectation is met lexically rather than substantively.
- **A measured, unshipped retrieval lead**: dropping the stoplist from
  `FtsSearch.sanitize`'s OR expression moves a needed chunk from rank >20 to
  rank 4, with 0 losses and 3 gains in isolation — but it reshapes ranks for
  every query and re-fuses against the vector arm. Worth trying on hardware.
- **`what happens to my scholarship if I am debarred`** is genuinely
  unanswerable — no document states it. It should abstain, not answer.
- **Device verification** — nothing has run on hardware since roughly 2026-09-06
  18:00. The release build has never run on a device at all.
- **Play submission** — the package is drafted in `docs/play/`: privacy policy,
  Data Safety answers, store listing, release checklist. Two known blockers in
  there: Play requires an account-deletion route for apps that create accounts,
  and `ControlPlane` has none; and the contact email / policy URL are still
  TODO placeholders.

## Package name

`com.campusbrain.app`. Renamed from `com.kriet.campusbrain` on 2026-09-06,
before first publish, because Play keys a listing on it permanently. Do not put
a specific institution's name in the app's own prose, comments or fixtures — the
name is data that arrives in the licence grant. The exceptions are `ScopeGate`
and `DocCatalog`, where the real institution name is corpus content and is what
makes the app refuse questions about other colleges.
