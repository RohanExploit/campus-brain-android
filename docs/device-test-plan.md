# Device test plan

What to run the moment a phone is attached, in order, and what each step is
actually for. Written because device time has been scarce on this project and
has repeatedly been spent rediscovering the same traps.

**Everything here is what the JVM harness cannot reach.** 362 JVM tests already
run the real engine against the real corpus with the real embedder, including
both batteries (`JvmAdversarialBatteryTest`, `JvmHardQueryBatteryTest`). Do not
spend device time re-checking retrieval logic the JVM already covers. Spend it
on the four things only hardware can answer: the real SQLite writes, the real
`ContentResolver`, the real UI, and whether the **release** build works at all.

Throughout: `ADB=/e/toolchains/android-sdk/platform-tools/adb.exe`

---

## 0. Prerequisites

```
$ADB devices -l
```

Expect one device in state `device`. If it says `unauthorized`, accept the RSA
prompt on the phone.

If the list is empty, the fault is below adb. Check whether Windows sees the
phone at all — a Samsung enumerates as `VID_04E8`:

```
powershell -NoProfile -Command "Get-PnpDevice -PresentOnly | Where-Object { $_.InstanceId -like 'USB\VID*' } | Select-Object -ExpandProperty InstanceId"
```

If `VID_04E8` is absent, no amount of adb work will help: it is the cable or the
port. A charge-only cable is visually identical to a data one and is the usual
cause. This cost hours once already, spent on drivers and toggles that were
never the problem.

---

## 1. Install the RELEASE build first — highest value in this plan

**The release build has never run on a device.** Every device session so far
tested debug. Release differs in signing and packaging, and it is what ships.

```
cd android-app
GRADLE_USER_HOME='E:\gradle-home' ./gradlew :app:assembleRelease --console=plain --no-daemon --no-parallel -Pkotlin.compiler.execution.strategy=in-process

$ADB uninstall com.campusbrain.app
$ADB install app/build/outputs/apk/release/app-release.apk
$ADB shell monkey -p com.campusbrain.app -c android.intent.category.LAUNCHER 1
```

The uninstall is not optional: debug and release are signed differently, and an
existing debug install rejects the release with an opaque ddmlib stack trace
that names nothing.

Check: does it launch, does the header report the corpus, does one question
answer. **If the release build fails where debug worked, stop and fix that** —
nothing else here matters until it does.

Reinstall the debug build afterwards; the instrumented batteries need it.

---

## 2. The three instrumented batteries

Start logcat capture **before** the run. Samsung `io_stats` chatter has flushed
the buffer and destroyed a run's output before.

```
$ADB logcat -c
$ADB logcat -s HARDBAT:I ADVBAT:I INGEST:I > E:/scratch/device-run.log &

GRADLE_USER_HOME='E:\gradle-home' ./gradlew :app:connectedDebugAndroidTest --console=plain -Pandroid.testInstrumentationRunnerArguments.class=com.campusbrain.app.HardQueryBatteryTest
```

Repeat for `AdversarialBatteryTest` and `IngestDeviceTest`.

**Do not export `MSYS_NO_PATHCONV=1` for the whole shell.** It breaks gradlew
with `Could not find or load main class GradleWrapperMain`. Scope it inline to a
single adb call if a path genuinely needs protecting.

### What to compare against

Device and JVM run the same engine, so **the two should now agree**. A
disagreement is the finding: it means something is environment-dependent, which
is worth more than either score by itself. JVM currently scores adversarial
23/23, hard 19/20. Diff the device transcript against
`app/build/reports/jvm-battery/*.txt`.

---

## 3. Document ingestion — the one path the JVM cannot reach

`IngestDeviceTest` is the only coverage for the real SQLite write and the FTS5
external-content companion insert. Its fixture (an invented robotics society,
a rupee amount, a lab number) appears nowhere in the bundle, so a hit can only
have come from the import.

Two things to confirm in the log before believing the result:

1. **The baseline abstains before the import.** Without that the test proves
   nothing.
2. **`added()` is empty at the start.** The test now clears leftovers first,
   because `user_corpus.db` survives between runs and the free tier caps imports
   at one document — an uncleared device would be refused with `LicenseRequired`
   before reading a byte, and the log would look exactly like an ingestion
   regression.

Then check two items fixed since the last device run, with their expected text:

- `how long can I borrow robotics equipment` — the fixture writes "fourteen
  days" in words, which the answer check used to reject.
  Expect: `Equipment may be borrowed for a maximum of fourteen days.`
- Imported answers should no longer truncate at a source line wrap.
  Expect: `The Quasar Robotics Society meets every Thursday at 5:30 pm in
  Laboratory 7B of the Mechanical Engineering block.`

Then do what no test does: **share a real PDF and a real .docx into the app**
from another app. `ContentResolver`, the share intent and `singleTask` delivery
have no automated coverage at all.

---

## 4. UI, and one specific question that needs settling

**The suggestion chips.** I reported these dead after tapping one and seeing
nothing happen. A later reading found the listener *is* attached
(`AskFragment.kt:66`) and what was missing was any press feedback. A ripple was
added specifically to make this decisive:

- **Ripple fires, no answer** → fault is downstream in `router.answer`.
- **No ripple at all** → the touch is intercepted; capture
  `$ADB shell uiautomator dump` and the view hierarchy.

Settle it either way. A control that looks tappable and is not is worse than no
control.

Then walk the screens that have never been seen on hardware:

- **Welcome**, on a genuinely first launch (uninstall or clear app data first).
  Four panes, fade-through between them, a visible skip. It must never reappear
  once dismissed, and dismissing must land on a working Ask tab.
- **Enrolment** — tap the status pill. With no `config.json` present, expect the
  "no institution to enrol with" outcome. That is the correct result, not a
  failure.
- **Licence screen** — long-press the status pill, and paste a real key:
  ```
  python scripts/issue_license.py --private-key android-app/keystore/campus_brain_license_private.pem --tenant-id demo_inst --tenant-name "Demo Institute" --tier INSTITUTIONAL --expires 2030-01-01 --max-docs 500 --max-total-kb 200000
  ```
  The tier should flip and the import cap should rise. This is the only test
  that exercises the compiled `PUBLIC_KEY_B64` on real hardware.
- **Admin analytics** — reachable only at INSTITUTIONAL or owner.
- **Self test** — long-press the title. The crash-log section should appear, and
  the share action only when a log exists.

**Known non-bug:** a thin sliver at x=0 in screenshots is the Samsung Edge panel
overlay. Proven with dumpsys, uiautomator and pixel sampling. Do not re-report.

---

## 5. Airplane mode — the demo, and the product's central claim

```
$ADB shell cmd connectivity airplane-mode enable
```

Ask five questions spanning all four routes. Every one must answer exactly as it
did online. Any degradation here is the most serious finding available on this
project: retrieval gating on network or auth is the one thing the design
forbids, and the commercial claim rests on it.

Re-enable afterwards.

---

## 6. Worth measuring while the device is attached

- **Cold start** to a usable Ask screen. The 86 MB fp32 embedder loads at
  startup and has never been timed on hardware.
- **First-query latency** against subsequent ones. `CorpusWords` runs a memoised
  `instr` scan over 493 chunks on first use; it should show up exactly once.
- **Install size** from Settings, against the 113 MB APK.
- **Memory** via `$ADB shell dumpsys meminfo com.campusbrain.app` after several
  questions. The ONNX session is the thing to watch.

---

## Recording results

Write findings to `docs/device-runs/YYYY-MM-DD.md`: what ran, the scores, and
the full text of anything that disagreed with the JVM run. Keep the logcat
capture. A device session whose results are not written down has to be repeated,
and this project has repeated several.

Where a device answer differs from the JVM answer, **say which is authoritative
and why** rather than assuming the device wins. The JVM harness runs the same
code against the same corpus; a difference usually names an environment fact
worth understanding, not a bug in one of them.
