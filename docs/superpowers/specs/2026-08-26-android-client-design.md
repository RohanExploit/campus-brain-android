# Android client — design

**Date:** 2026-08-26
**Status:** design, approved, pending implementation plan

## What this is

A native Android app that asks the Company Brain a question — typed, spoken, or
photographed — and shows the answer with the documents it came from.

The engine already exists, is benchmarked at 88.9% over 208 questions, and runs
on the laptop. This app is the phone client for it. It is the phone-first half
of a system whose other half is already built and measured.

## Hard constraint: purely additive

Nothing that exists today changes. Not `retrieval/`, not `api/`, not
`generation/`, not `dashboard/`, not `tests/`.

This is achievable because the app needs no new backend capability:

- It calls the existing `POST /query` endpoint with the existing request shape
  (`{query, tenant_id}`) and reads the existing response
  (`{query_type, answer, context_used, metadata.sources}`).
- It authenticates with the existing `X-API-Key` header.
- It is a native HTTP client, so CORS does not apply — no `allowedDevOrigins`
  or CORS allowlist change.
- OCR runs on the device, so no image upload endpoint is needed.

The only operational change is a launch flag that already exists:
`scripts\demo_up.ps1 -Lan -ApiKey <key>`.

**Verification:** after the app is built, `pytest -q` and `cd dashboard && npm
run build` must both give the same result they give today. If either changes,
the constraint has been violated.

## Decisions taken

| Decision | Choice | Why |
|---|---|---|
| Where the model runs | On the laptop; the phone is a client | The engine needs 4 GB of VRAM. The event's own framing is "Office Kit bridging the phone to deeper laptop compute when you need it." |
| Framework | Flutter | Already shipped a Flutter Android app. Genuinely native UI, not a webview. |
| Where it builds | GitHub Actions | No Android SDK, no Flutter SDK, no Gradle locally, and the installed JDK is 23 while the Android Gradle Plugin wants 17. CI runners have all of it. |
| OCR | On-device (ML Kit) | Keeps the backend untouched, works with no laptop, and counts as on-device AI. |
| Timing | Built before the event | Founder decision, taken with the "built at the venue" norm noted. |

## Scoring context

The event rubric is 75% jury, 25% device telemetry:

| Weight | Criterion | Scored by |
|---|---|---|
| 30% | End product quality — does it work, would someone keep using it | Jury |
| 20% | Novelty and impact | Jury |
| 15% | Creative phone use — camera, voice, on-device AI | HackTracker |
| 15% | Technical depth — architecture, robustness, real use of hardware | Jury |
| 10% | Office Kit usage — phone/laptop bridge | HackTracker |
| 10% | Demo and presentation | Jury |

Two consequences for this design:

1. **The 15% names camera, voice and on-device AI.** This app has all three:
   Android speech recognition, the camera, and ML Kit OCR running locally.
2. **Office Kit is not something this app integrates with.** It is a bridge
   tool — screen mirror, shared clipboard, file transfer, remote control —
   between phone and laptop, and its 10% is measured on usage while building,
   not on any app capability. There is nothing to build for it.

## Architecture

```
┌────────────────────────── loaner phone ──────────────────────────┐
│                                                                  │
│   ask_screen                                                     │
│     ├── mic_button ──────► speech_service (Android STT)          │
│     ├── camera button ───► ocr_service (ML Kit, on-device)       │
│     └── text field                                               │
│                    │                                             │
│                    ▼                                             │
│              brain_client ──── HTTP + X-API-Key ────────┐        │
│                    ▲                                    │        │
│              answer_card ◄── Answer model               │        │
│                                                         │        │
│   settings_screen ──► app_config (persisted URL + key)  │        │
└─────────────────────────────────────────────────────────┼────────┘
                                                          │
                            laptop hotspot / USB tether   │
                                                          ▼
┌──────────────────── laptop (unchanged) ──────────────────────────┐
│   POST /query  →  router  →  TABULAR / FACT / LOCAL / GLOBAL     │
│                              DuckDB · FAISS · graph · Ollama     │
└──────────────────────────────────────────────────────────────────┘
```

Voice and camera are both **input methods for the same question box**. Neither
introduces a second answer path. Speech produces text; OCR produces text; both
land in the composer where the user can edit before asking. This is why they
cost so little: the entire downstream path is the one that already works.

## File structure

| File | Responsibility |
|---|---|
| `mobile/lib/main.dart` | App entry, theme, route table |
| `mobile/lib/config/app_config.dart` | Base URL and API key; load/save via `shared_preferences` |
| `mobile/lib/api/brain_client.dart` | `POST /query` and `GET /health`; typed results; timeout and error mapping |
| `mobile/lib/models/answer.dart` | `Answer.fromJson` — route, answer text, sources |
| `mobile/lib/screens/ask_screen.dart` | The primary screen |
| `mobile/lib/screens/settings_screen.dart` | Server address, API key, connection test |
| `mobile/lib/widgets/answer_card.dart` | Answer, route badge, source list |
| `mobile/lib/widgets/mic_button.dart` | Voice control, listening state |
| `mobile/lib/services/speech_service.dart` | `speech_to_text` wrapper, permission handling |
| `mobile/lib/services/ocr_service.dart` | Camera capture + ML Kit text recognition |
| `mobile/android/app/src/main/res/xml/network_security_config.xml` | Permits cleartext HTTP to private ranges only |
| `mobile/test/` | Unit and widget tests, run in CI |
| `.github/workflows/android.yml` | Builds a debug APK on push, uploads as an artifact |

`.github/workflows/android.yml` is a **new** workflow file. It does not modify
the existing Python CI job, and it is scoped with `paths: ['mobile/**']` so a
backend commit never triggers an Android build.

The reverse is not true and is left alone deliberately: `ci.yml` triggers on
every push with no path filter, so a mobile-only commit still runs the Python
suite. Adding a `paths-ignore` there would mean editing an existing file, which
this design forbids. The cost is a few wasted CI minutes; the benefit is that
the additive constraint stays literally true.

Each file has one responsibility, and the two services (`speech`, `ocr`) share
no state with each other — both simply return a `String` to the composer. That
boundary is what keeps either one removable without touching the other.

## The screens

**Ask screen.** The mic is the largest control on the screen — that is the
phone-first thesis made visible, not a decoration. Below it a text field, a
camera button, and a submit control. After a query: an answer card showing the
route badge (TABULAR / FACT / LOCAL / GLOBAL), the answer, and the source
documents listed underneath. The route badge matters — it is the visible proof
that four different retrievers exist behind one question box.

**Settings screen.** Base URL, API key, and a **Test connection** button that
calls `GET /health` and reports green or red.

That button is the most operationally valuable thing in the app. On a venue
floor, when the phone cannot reach the laptop, it turns a blind debugging
session into one tap.

## Connectivity, designed for a hostile network

**Venue wifi will likely not work, and this is the single largest delivery
risk.** Conference networks commonly enable client isolation: devices reach the
internet but not each other. The entire client/laptop design fails at check-in
if that is discovered live.

Order of preference, to be written into the runbook and rehearsed before the
event:

1. **Laptop hotspot.** Laptop runs the hotspot, phone joins it. No third party
   in the path, no isolation policy.
2. **Phone hotspot.** Phone shares its connection, laptop joins.
3. **USB tethering.** Cable between the two. Slowest to set up, hardest to
   break.
4. **Venue wifi.** Assume it does not work. Do not plan around it.

The app supports all four identically because the server address is editable
and persisted — switching networks is a settings edit and a connection test,
not a rebuild.

## Android platform requirements

These are the things that make the app work on a device rather than only in a
build log. Each was a gap found in review, and the first one is fatal if missed.

**Cleartext HTTP must be permitted, scoped to private ranges.** Since Android 9
(API 28) cleartext HTTP is blocked by default. This app's entire connection
model is plain HTTP to a LAN address, so without this it builds, installs, and
then fails every request on device with `CLEARTEXT communication not
permitted`. `res/xml/network_security_config.xml` permits cleartext for private
address ranges only — not `*` — so the app cannot be pointed at a plaintext
public host:

```xml
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">192.168.0.0</domain>
    <domain includeSubdomains="true">10.0.0.0</domain>
    <domain includeSubdomains="true">172.16.0.0</domain>
    <domain includeSubdomains="true">localhost</domain>
  </domain-config>
</network-security-config>
```

referenced from `AndroidManifest.xml` via
`android:networkSecurityConfig="@xml/network_security_config"`.

**Manifest permissions:** `INTERNET`, `RECORD_AUDIO`, `CAMERA`. `RECORD_AUDIO`
and `CAMERA` are runtime permissions and must be requested at first use, not at
launch — a permission dialog before the user has seen the app is the most
common reason a demo install gets denied and then silently half-works.

**`minSdk` 21**, required by ML Kit text recognition and `speech_to_text`.
`targetSdk` 34.

**Application id:** `com.companybrain.mobile`.

**Pinned dependencies**, so a CI build a week from now produces the same APK:
`http`, `speech_to_text`, `google_mlkit_text_recognition`, `image_picker`,
`shared_preferences`, `permission_handler` — each pinned to an exact version in
`pubspec.yaml`, and the Flutter SDK version pinned in the workflow.

## Tenant selection

The request requires a `tenant_id`, so the app must choose one. It is a
dropdown on the ask screen, populated from `GET /tenants` on first successful
connection and cached, falling back to `tenant_1` when that call fails.

`GET /tenants` is already scoped by the presented key — a tenant-scoped key
returns only its own row — so the dropdown shows exactly what the key is
allowed to reach, with no client-side filtering to get wrong.

Defaulting to `tenant_1` rather than an empty selection matters for the demo:
it is the corpus with 161 documents and 369 student records, and it is what
every rehearsed question expects.

## Voice language

`speech_to_text` exposes the device's locale list. The mic control offers
English, Marathi and Hindi, defaulting to the device locale when it is one of
those and English otherwise.

This is deliberate rather than decorative: the phone-use score names voice
explicitly, and a student asking about their own result in Marathi is the
demonstration that this product is for tier-2 and tier-3 colleges rather than
an English-language pilot. Recognition is Android's own, so it costs a locale
parameter and no new dependency.

## What is shown from the response

`query_type` becomes the route badge. `answer` is the body. `metadata.sources`
becomes the source list.

`context_used` is **not** displayed. The desktop console shows it because an
operator debugging retrieval needs it; on a phone it is a wall of raw chunk
text that buries the answer. It is parsed and retained in the model so a later
"show the evidence" affordance costs nothing, but it does not render in v1.

## Error handling

Every failure the user can hit gets a specific message, never a spinner that
never resolves:

| Condition | Behaviour |
|---|---|
| No server configured | Ask screen routes to settings on first launch |
| Connection refused / DNS failure | "Can't reach the laptop" + a shortcut to the connection test |
| HTTP 401 | "API key rejected" + shortcut to settings |
| HTTP 400 (empty query) | Inline validation before the request is sent |
| Timeout (30s default) | "The laptop is still thinking" — a cold model load genuinely takes ~60s |
| Microphone permission denied | Falls back to typing, with a one-line explanation |
| Camera permission denied | Falls back to typing, with a one-line explanation |
| OCR finds no text | "No text found in that photo" — does not send an empty query |

The timeout deserves its own note: the first query after a cold start waits on
the model loading into 4 GB of VRAM. A 30-second timeout with an honest message
beats a 10-second one that reports a failure that has not happened.

## Testing

Hermetic tests, run in the same CI job that builds the APK:

- `brain_client_test.dart` — response parsing for all four routes; 401, 400,
  timeout and connection-refused paths; header construction.
- `answer_model_test.dart` — `Answer.fromJson` including a response with an
  empty `sources` list (the GLOBAL route returns one) and a missing `metadata`
  key.
- `answer_card_test.dart` — widget test: renders route badge and source list;
  renders correctly with zero sources.

No test requires a running backend, a device, or a network.

## Out of scope

Deliberately excluded, each for a stated reason:

- **On-device generation.** A 2–4B model, the FAISS index and embeddings shipped
  as app assets is days of work with a real chance of not fitting or not being
  fast enough on the handset. The laptop path works today.
- **Query history.** Least impressive per hour of work of anything considered,
  and it adds local storage and a migration concern.
- **QR pairing.** Genuinely nice, but the settings screen plus a connection test
  solves the same problem, and the connection test is worth more on the floor.
- **Office Kit integration.** Nothing to integrate with; see Scoring context.
- **iOS.** No device, no build target, not scored.

## Open risk

The app cannot be tested on the actual loaner handset before the event — the
loaner is handed over at Saturday check-in. It must therefore be verified on
another Android device beforehand, and nothing in it may depend on a
device-specific capability. The two device features it uses, Android speech
recognition and ML Kit text recognition, are both standard across Android and
present on any modern handset.
