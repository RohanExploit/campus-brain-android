# Android Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Flutter Android app that asks the existing Company Brain a question — typed, spoken, or photographed — and shows the answer with the documents it came from.

**Architecture:** Everything lives in a new `mobile/` directory and calls the `POST /query` endpoint that already exists. Voice and camera are input methods that produce a `String` into the same question box, not second answer paths — so the entire downstream path is the one already benchmarked at 88.9%. OCR runs on-device via ML Kit, which is why no backend change is needed.

**Tech Stack:** Flutter (Dart), `http`, `speech_to_text`, `google_mlkit_text_recognition`, `image_picker`, `shared_preferences`, `permission_handler`. Built on GitHub Actions; no local Android toolchain.

## Global Constraints

- **Purely additive.** No edits to `retrieval/`, `api/`, `generation/`, `dashboard/`, `tests/`, `config.py`, or `.github/workflows/ci.yml`. Verified after every task by `git status --short` showing changes only under `mobile/` and `.github/workflows/android.yml`.
- **Regression gate.** `pytest -q` must return `310 passed, 1 skipped` and `cd dashboard && npm run build` must succeed, unchanged, at the end of every task.
- **`minSdk` 21, `targetSdk` 34.** Required by ML Kit text recognition and `speech_to_text`.
- **Application id `com.companybrain.mobile`.**
- **Cleartext HTTP permitted for private ranges only** — `192.168.0.0`, `10.0.0.0`, `172.16.0.0`, `localhost`. Never `*`.
- **Runtime permissions requested at first use, never at launch.**
- **All dependencies pinned to exact versions** in `pubspec.yaml`; Flutter SDK version pinned in the workflow.
- **`context_used` is parsed and retained but never rendered.**
- **No local Android toolchain exists.** Dart unit tests can run locally only if Flutter is installed; otherwise CI is the only verification. Every task states its CI-only fallback.
- Default tenant is `tenant_1`.

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `mobile/pubspec.yaml` | Pinned dependency manifest | 1 |
| `.github/workflows/android.yml` | Builds debug APK, runs Dart tests, uploads artifact | 1 |
| `mobile/lib/models/answer.dart` | `Answer.fromJson` — route, text, sources | 2 |
| `mobile/test/answer_model_test.dart` | Model parsing incl. missing/empty fields | 2 |
| `mobile/lib/config/app_config.dart` | Base URL + API key, persisted | 3 |
| `mobile/test/app_config_test.dart` | Persistence and defaults | 3 |
| `mobile/lib/api/brain_client.dart` | `query()` and `health()`; typed errors | 4 |
| `mobile/test/brain_client_test.dart` | All four routes, 401, 400, timeout, refused | 4 |
| `mobile/lib/widgets/answer_card.dart` | Answer, route badge, source list | 5 |
| `mobile/test/answer_card_test.dart` | Widget test incl. zero sources | 5 |
| `mobile/lib/screens/settings_screen.dart` | Address, key, connection test | 6 |
| `mobile/lib/screens/ask_screen.dart` | The primary screen | 7 |
| `mobile/lib/main.dart` | Entry, theme, routes | 7 |
| `mobile/android/app/src/main/res/xml/network_security_config.xml` | Cleartext for private ranges | 7 |
| `mobile/lib/services/speech_service.dart` | `speech_to_text` wrapper + locale | 8 |
| `mobile/lib/widgets/mic_button.dart` | Voice control, listening state | 8 |
| `mobile/lib/services/ocr_service.dart` | Camera capture + ML Kit | 9 |
| `docs/MOBILE_RUNBOOK.md` | Connectivity fallbacks, install, demo script | 10 |

---

## Task 1: Project scaffold and CI that produces an APK

**Files:**
- Create: `mobile/pubspec.yaml`, `mobile/analysis_options.yaml`, `mobile/lib/main.dart` (placeholder), `mobile/.gitignore`
- Create: `.github/workflows/android.yml`

**Interfaces:**
- Consumes: nothing.
- Produces: a green CI job that uploads `app-debug.apk` as an artifact. Every later task depends on this loop existing.

**Why first:** with no local toolchain, CI *is* the compiler. Until an APK comes out of it, no other task can be verified at all.

- [ ] **Step 1: Create the Flutter manifest with pinned versions**

Create `mobile/pubspec.yaml`:

```yaml
name: company_brain
description: Phone client for the Company Brain retrieval engine.
publish_to: none
version: 1.0.0+1

environment:
  sdk: ">=3.5.0 <4.0.0"

dependencies:
  flutter:
    sdk: flutter
  http: 1.2.2
  shared_preferences: 2.3.2
  speech_to_text: 6.6.2
  google_mlkit_text_recognition: 0.13.0
  image_picker: 1.1.2
  permission_handler: 11.3.1

dev_dependencies:
  flutter_test:
    sdk: flutter
  flutter_lints: 4.0.0

flutter:
  uses-material-design: true
```

- [ ] **Step 2: Add the lint config and gitignore**

Create `mobile/analysis_options.yaml`:

```yaml
include: package:flutter_lints/flutter.yaml
```

Create `mobile/.gitignore`:

```
build/
.dart_tool/
.flutter-plugins
.flutter-plugins-dependencies
.packages
*.iml
```

- [ ] **Step 3: Write a placeholder entry point**

Create `mobile/lib/main.dart`:

```dart
import 'package:flutter/material.dart';

void main() => runApp(const CompanyBrainApp());

class CompanyBrainApp extends StatelessWidget {
  const CompanyBrainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Company Brain',
      theme: ThemeData.dark(useMaterial3: true),
      home: const Scaffold(body: Center(child: Text('Company Brain'))),
    );
  }
}
```

- [ ] **Step 4: Write the CI workflow**

Create `.github/workflows/android.yml`:

```yaml
name: Android

# Scoped to mobile/ so a backend commit never triggers an APK build.
# The reverse is not true -- ci.yml has no path filter -- and that is
# deliberate: adding one would mean editing an existing file.
on:
  push:
    paths:
      - 'mobile/**'
      - '.github/workflows/android.yml'
  workflow_dispatch:

jobs:
  build:
    name: Test and build APK
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: mobile
    steps:
      - uses: actions/checkout@v4

      # The runner ships JDK 17; the local machine has 23, which the Android
      # Gradle Plugin rejects. Pinned here so the build does not drift.
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: subosito/flutter-action@v2
        with:
          flutter-version: '3.24.5'
          channel: stable
          cache: true

      # Generate the android/ tree ONLY if it is not already committed.
      #
      # This guard is load-bearing. Task 7 commits an AndroidManifest.xml
      # carrying the INTERNET/RECORD_AUDIO/CAMERA permissions and the
      # networkSecurityConfig reference. Regenerating unconditionally would
      # overwrite it on every run, and the APK would build green, install
      # fine, and then fail every request on device with cleartext blocked
      # and no permissions granted.
      - name: Create Android project files (first run only)
        run: |
          if [ ! -d android ]; then
            flutter create --platforms=android --project-name company_brain --org com.companybrain .
          else
            echo "android/ is committed; skipping generation"
          fi

      # First run only: lets the generated tree be downloaded and committed.
      # Harmless afterwards.
      - name: Upload generated android tree
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: generated-android-tree
          path: mobile/android
          retention-days: 7
          if-no-files-found: ignore

      - name: Resolve dependencies
        run: flutter pub get

      - name: Analyze
        run: flutter analyze

      - name: Test
        run: flutter test

      - name: Build debug APK
        run: flutter build apk --debug

      - uses: actions/upload-artifact@v4
        with:
          name: company-brain-apk
          path: mobile/build/app/outputs/flutter-apk/app-debug.apk
          retention-days: 30
```

- [ ] **Step 5: Push and verify CI produces an APK**

```bash
git add mobile/ .github/workflows/android.yml
git commit -m "build(mobile): Flutter scaffold and CI that produces an APK

No Android SDK, Flutter SDK or Gradle exists locally, and the installed JDK is
23 while the Android Gradle Plugin wants 17 -- so CI is the compiler for this
project, not a check on it. This lands first because until an APK comes out of
the runner, nothing else can be verified at all. Dependencies are pinned to
exact versions so a build next week yields the same artifact."
git push origin main
```

Then watch the run:

```bash
gh run watch
```

Expected: green, with a `company-brain-apk` artifact. If `flutter create` conflicts with the existing `pubspec.yaml`, it regenerates only the missing `android/` tree and leaves `pubspec.yaml` alone — that is intended.

- [ ] **Step 6: Commit the generated `android/` tree**

This step is mandatory and is the whole reason the guard above exists. Download
the `generated-android-tree` artifact from the run, unzip it to
`mobile/android/`, and commit it.

```bash
# after unzipping the artifact into mobile/android/
git add mobile/android
git commit -m "build(mobile): commit the generated android tree

Generated once on the runner and committed so it is version-controlled rather
than regenerated per build. Task 7 adds permissions and a network security
config to AndroidManifest.xml; if this tree were regenerated each run those
edits would be overwritten and the APK would build green, install fine, and
fail every request on device."
git push origin main
gh run watch
```

Expected: the run now prints `android/ is committed; skipping generation` and
still produces the APK.

**Do not proceed to Task 2 until that line appears.** If the tree is not
committed, every manifest change in Task 7 is silently discarded.

- [ ] **Step 7: Confirm nothing else moved**

```bash
git status --short
.venv312/Scripts/python.exe -m pytest -q
```

Expected: clean tree; `310 passed, 1 skipped`.

---

## Task 2: The Answer model

**Files:**
- Create: `mobile/lib/models/answer.dart`
- Create: `mobile/test/answer_model_test.dart`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class Answer` with fields `String route`, `String text`, `List<Source> sources`, `String contextUsed`.
  - `class Source` with fields `String document`, `String? section`.
  - `Answer.fromJson(Map<String, dynamic> json)`.

- [ ] **Step 1: Write the failing test**

Create `mobile/test/answer_model_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:company_brain/models/answer.dart';

void main() {
  test('parses a TABULAR response with no sources', () {
    // The TABULAR route answers from SQL and carries no document provenance.
    final a = Answer.fromJson({
      'query_type': 'TABULAR',
      'answer': 'Fail percentage: 9.5% (35 of 369 students failed).',
      'context_used': 'Fail percentage: 9.5%',
      'metadata': {},
    });
    expect(a.route, 'TABULAR');
    expect(a.text, contains('9.5%'));
    expect(a.sources, isEmpty);
  });

  test('parses a FACT response with sources', () {
    final a = Answer.fromJson({
      'query_type': 'FACT',
      'answer': 'Fee structure: Diploma 1500.',
      'context_used': 'raw chunk text',
      'metadata': {
        'sources': [
          {'source': 'Rutuja fees.pdf', 'section': 'FEES'},
          {'source': 'brochure.md', 'section': null},
        ],
      },
    });
    expect(a.route, 'FACT');
    expect(a.sources.length, 2);
    expect(a.sources.first.document, 'Rutuja fees.pdf');
    expect(a.sources.first.section, 'FEES');
    expect(a.sources[1].section, isNull);
  });

  test('survives a missing metadata key entirely', () {
    final a = Answer.fromJson({
      'query_type': 'GLOBAL',
      'answer': 'Summary.',
      'context_used': '',
    });
    expect(a.sources, isEmpty);
    expect(a.contextUsed, '');
  });

  test('retains context_used without exposing it as the answer', () {
    final a = Answer.fromJson({
      'query_type': 'LOCAL',
      'answer': 'Shri G. K. Gujar Memorial Charitable Trust runs DACOE Karad.',
      'context_used': 'Dr. Ashok G. Gujar -> ESTABLISHED_BY -> DACOE',
      'metadata': {},
    });
    expect(a.contextUsed, contains('ESTABLISHED_BY'));
    expect(a.text, isNot(contains('ESTABLISHED_BY')));
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd mobile && flutter test test/answer_model_test.dart
```

Expected: FAIL — `Target of URI doesn't exist: 'package:company_brain/models/answer.dart'`.

If Flutter is not installed locally, push and read the CI `Test` step instead. Expected: the same failure.

- [ ] **Step 3: Write the model**

Create `mobile/lib/models/answer.dart`:

```dart
/// One provenance entry: which document an answer came from, and where in it.
class Source {
  final String document;
  final String? section;

  const Source({required this.document, this.section});

  factory Source.fromJson(Map<String, dynamic> json) => Source(
        document: (json['source'] ?? '') as String,
        section: json['section'] as String?,
      );
}

/// A parsed /query response.
///
/// `contextUsed` is retained but deliberately never rendered on the phone. The
/// desktop console shows it because an operator debugging retrieval needs it;
/// on a handset it is a wall of raw chunk text that buries the answer. Keeping
/// the field means a later "show the evidence" affordance costs nothing.
class Answer {
  final String route;
  final String text;
  final List<Source> sources;
  final String contextUsed;

  const Answer({
    required this.route,
    required this.text,
    required this.sources,
    required this.contextUsed,
  });

  factory Answer.fromJson(Map<String, dynamic> json) {
    // metadata is absent on some branches and present-but-empty on others.
    // Both mean "no provenance", so they collapse to an empty list here rather
    // than forcing every call site to null-check.
    final metadata = (json['metadata'] as Map<String, dynamic>?) ?? const {};
    final rawSources = (metadata['sources'] as List<dynamic>?) ?? const [];

    return Answer(
      route: (json['query_type'] ?? 'UNKNOWN') as String,
      text: (json['answer'] ?? '') as String,
      contextUsed: (json['context_used'] ?? '') as String,
      sources: rawSources
          .map((e) => Source.fromJson(e as Map<String, dynamic>))
          .toList(growable: false),
    );
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
cd mobile && flutter test test/answer_model_test.dart
```

Expected: `All tests passed!` (4 tests).

- [ ] **Step 5: Commit**

```bash
git add mobile/lib/models/answer.dart mobile/test/answer_model_test.dart
git commit -m "feat(mobile): Answer model with tolerant provenance parsing

metadata is absent on some API branches and present-but-empty on others, and
both mean the same thing, so they collapse to an empty source list rather than
pushing a null check into every call site. context_used is parsed and kept but
never rendered -- it is raw chunk text that buries the answer on a phone."
```

---

## Task 3: Persisted configuration

**Files:**
- Create: `mobile/lib/config/app_config.dart`
- Create: `mobile/test/app_config_test.dart`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `class AppConfig` with `String baseUrl`, `String apiKey`, `String tenantId`.
  - `static Future<AppConfig> load()`, `Future<void> save()`, `bool get isConfigured`.
  - Default `tenantId` is `'tenant_1'`; default `baseUrl` and `apiKey` are empty strings.

- [ ] **Step 1: Write the failing test**

Create `mobile/test/app_config_test.dart`:

```dart
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:company_brain/config/app_config.dart';

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('defaults to tenant_1 and reports itself unconfigured', () async {
    final c = await AppConfig.load();
    expect(c.tenantId, 'tenant_1');
    expect(c.baseUrl, '');
    expect(c.isConfigured, isFalse);
  });

  test('round-trips through save and load', () async {
    final c = await AppConfig.load();
    c.baseUrl = 'http://192.168.137.1:8000';
    c.apiKey = 'secret';
    c.tenantId = 'tenant_bench';
    await c.save();

    final again = await AppConfig.load();
    expect(again.baseUrl, 'http://192.168.137.1:8000');
    expect(again.apiKey, 'secret');
    expect(again.tenantId, 'tenant_bench');
    expect(again.isConfigured, isTrue);
  });

  test('a trailing slash on the base URL is stripped on save', () async {
    // The client joins paths with a leading slash; without this a saved
    // "http://host:8000/" produces "http://host:8000//query".
    final c = await AppConfig.load();
    c.baseUrl = 'http://192.168.137.1:8000/';
    await c.save();
    expect((await AppConfig.load()).baseUrl, 'http://192.168.137.1:8000');
  });

  test('is not configured when only the key is set', () async {
    final c = await AppConfig.load();
    c.apiKey = 'secret';
    await c.save();
    expect((await AppConfig.load()).isConfigured, isFalse);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd mobile && flutter test test/app_config_test.dart
```

Expected: FAIL — URI does not exist.

- [ ] **Step 3: Write the implementation**

Create `mobile/lib/config/app_config.dart`:

```dart
import 'package:shared_preferences/shared_preferences.dart';

/// Server address, API key and selected tenant, persisted on the device.
///
/// The address is editable rather than compiled in because the demo network is
/// not known in advance: a venue wifi may isolate clients, so the fallback is a
/// laptop hotspot, a phone hotspot, or USB tethering -- each a different IP.
/// Switching between them must be a settings edit, not a rebuild.
class AppConfig {
  static const _kBaseUrl = 'base_url';
  static const _kApiKey = 'api_key';
  static const _kTenantId = 'tenant_id';
  static const defaultTenant = 'tenant_1';

  String baseUrl;
  String apiKey;
  String tenantId;

  AppConfig({
    required this.baseUrl,
    required this.apiKey,
    required this.tenantId,
  });

  /// The API key is optional -- the server only demands it when
  /// REQUIRE_API_KEY is on -- so only the address decides usability.
  bool get isConfigured => baseUrl.isNotEmpty;

  static Future<AppConfig> load() async {
    final p = await SharedPreferences.getInstance();
    return AppConfig(
      baseUrl: p.getString(_kBaseUrl) ?? '',
      apiKey: p.getString(_kApiKey) ?? '',
      tenantId: p.getString(_kTenantId) ?? defaultTenant,
    );
  }

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    // Strip a trailing slash: paths are joined with a leading slash, so
    // "http://host:8000/" would otherwise produce "http://host:8000//query".
    final normalised = baseUrl.endsWith('/')
        ? baseUrl.substring(0, baseUrl.length - 1)
        : baseUrl;
    baseUrl = normalised;
    await p.setString(_kBaseUrl, normalised);
    await p.setString(_kApiKey, apiKey);
    await p.setString(_kTenantId, tenantId);
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
cd mobile && flutter test test/app_config_test.dart
```

Expected: `All tests passed!` (4 tests).

- [ ] **Step 5: Commit**

```bash
git add mobile/lib/config/app_config.dart mobile/test/app_config_test.dart
git commit -m "feat(mobile): persisted server address, key and tenant

The address is editable rather than compiled in because the demo network is not
known in advance -- venue wifi commonly isolates clients, so the fallback is a
laptop hotspot, phone hotspot or USB tether, each a different IP. Switching
must be a settings edit, not a rebuild. Trailing slashes are stripped on save
because paths are joined with a leading slash."
```

---

## Task 4: The API client

**Files:**
- Create: `mobile/lib/api/brain_client.dart`
- Create: `mobile/test/brain_client_test.dart`

**Interfaces:**
- Consumes: `Answer` (Task 2), `AppConfig` (Task 3).
- Produces:
  - `class BrainClient({required AppConfig config, http.Client? httpClient})`.
  - `Future<Answer> query(String question)`.
  - `Future<bool> health()`.
  - `class BrainException implements Exception` with `final String message`.

- [ ] **Step 1: Write the failing test**

Create `mobile/test/brain_client_test.dart`:

```dart
import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:company_brain/api/brain_client.dart';
import 'package:company_brain/config/app_config.dart';

AppConfig cfg() => AppConfig(
      baseUrl: 'http://192.168.137.1:8000',
      apiKey: 'k',
      tenantId: 'tenant_1',
    );

void main() {
  test('sends query and tenant_id, and the API key header', () async {
    late http.Request seen;
    final client = BrainClient(
      config: cfg(),
      httpClient: MockClient((req) async {
        seen = req;
        return http.Response(
          jsonEncode({
            'query_type': 'TABULAR',
            'answer': '16 students',
            'context_used': '',
            'metadata': {},
          }),
          200,
        );
      }),
    );

    final a = await client.query('How many students failed 2 subjects?');

    expect(seen.url.path, '/query');
    expect(seen.headers['X-API-Key'], 'k');
    final body = jsonDecode(seen.body) as Map<String, dynamic>;
    expect(body['query'], 'How many students failed 2 subjects?');
    expect(body['tenant_id'], 'tenant_1');
    expect(a.route, 'TABULAR');
  });

  test('401 reports a rejected key, not a generic failure', () async {
    final client = BrainClient(
      config: cfg(),
      httpClient: MockClient((_) async => http.Response('{"detail":"nope"}', 401)),
    );
    expect(
      () => client.query('x'),
      throwsA(isA<BrainException>().having(
        (e) => e.message, 'message', contains('API key'))),
    );
  });

  test('400 surfaces the server detail', () async {
    final client = BrainClient(
      config: cfg(),
      httpClient: MockClient((_) async =>
          http.Response('{"detail":"Query must not be empty."}', 400)),
    );
    expect(
      () => client.query(''),
      throwsA(isA<BrainException>().having(
        (e) => e.message, 'message', contains('must not be empty'))),
    );
  });

  test('a connection failure reads as cannot reach the laptop', () async {
    final client = BrainClient(
      config: cfg(),
      httpClient: MockClient((_) async => throw const SocketExceptionStub()),
    );
    expect(
      () => client.query('x'),
      throwsA(isA<BrainException>().having(
        (e) => e.message, 'message', contains("Can't reach"))),
    );
  });

  test('health returns true on 200 and false on anything else', () async {
    final ok = BrainClient(
      config: cfg(),
      httpClient: MockClient((_) async => http.Response('{"status":"ok"}', 200)),
    );
    expect(await ok.health(), isTrue);

    final bad = BrainClient(
      config: cfg(),
      httpClient: MockClient((_) async => throw const SocketExceptionStub()),
    );
    expect(await bad.health(), isFalse);
  });

  test('health does not send the API key', () async {
    // GET /health has no auth dependency on the server, so the connection test
    // must work before a key is entered -- otherwise a mistyped key looks
    // identical to an unreachable laptop.
    late http.Request seen;
    final c = BrainClient(
      config: AppConfig(baseUrl: 'http://h:8000', apiKey: '', tenantId: 't'),
      httpClient: MockClient((req) async {
        seen = req;
        return http.Response('{"status":"ok"}', 200);
      }),
    );
    await c.health();
    expect(seen.headers.containsKey('X-API-Key'), isFalse);
  });
}

class SocketExceptionStub implements Exception {
  const SocketExceptionStub();
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd mobile && flutter test test/brain_client_test.dart
```

Expected: FAIL — URI does not exist.

- [ ] **Step 3: Write the client**

Create `mobile/lib/api/brain_client.dart`:

```dart
import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import '../models/answer.dart';

/// A failure the user can be shown verbatim. Every message names what to do
/// next, because on a venue floor "Something went wrong" costs minutes.
class BrainException implements Exception {
  final String message;
  const BrainException(this.message);
  @override
  String toString() => message;
}

class BrainClient {
  final AppConfig config;
  final http.Client _http;

  /// 30 seconds, not the usual 10. The first query after a cold start waits on
  /// a 4B model loading into 4 GB of VRAM, which genuinely takes about a
  /// minute. A short timeout would report a failure that has not happened.
  static const timeout = Duration(seconds: 30);

  BrainClient({required this.config, http.Client? httpClient})
      : _http = httpClient ?? http.Client();

  Future<Answer> query(String question) async {
    final uri = Uri.parse('${config.baseUrl}/query');
    http.Response res;
    try {
      res = await _http
          .post(
            uri,
            headers: {
              'Content-Type': 'application/json',
              if (config.apiKey.isNotEmpty) 'X-API-Key': config.apiKey,
            },
            body: jsonEncode({
              'query': question,
              'tenant_id': config.tenantId,
            }),
          )
          .timeout(timeout);
    } on TimeoutException {
      throw const BrainException(
          'The laptop is still thinking. A cold model load takes about a minute — try again.');
    } catch (_) {
      throw BrainException(
          "Can't reach the laptop at ${config.baseUrl}. Check the connection in Settings.");
    }

    if (res.statusCode == 401 || res.statusCode == 403) {
      throw const BrainException('API key rejected. Check it in Settings.');
    }
    if (res.statusCode != 200) {
      throw BrainException(_detail(res) ?? 'Server error ${res.statusCode}.');
    }
    return Answer.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  /// Reachability probe. Deliberately sends no API key: GET /health has no auth
  /// dependency server-side, so the test must succeed before a key is entered.
  /// Otherwise a mistyped key is indistinguishable from an unreachable laptop.
  Future<bool> health() async {
    try {
      final res = await _http
          .get(Uri.parse('${config.baseUrl}/health'))
          .timeout(const Duration(seconds: 5));
      return res.statusCode == 200;
    } catch (_) {
      return false;
    }
  }

  String? _detail(http.Response res) {
    try {
      return (jsonDecode(res.body) as Map<String, dynamic>)['detail'] as String?;
    } catch (_) {
      return null;
    }
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
cd mobile && flutter test test/brain_client_test.dart
```

Expected: `All tests passed!` (6 tests).

- [ ] **Step 5: Commit**

```bash
git add mobile/lib/api/brain_client.dart mobile/test/brain_client_test.dart
git commit -m "feat(mobile): API client with failure messages that name the fix

Every error the user can hit gets a message that says what to do next -- on a
venue floor 'Something went wrong' costs minutes. The timeout is 30s rather
than the usual 10 because the first query after a cold start waits on a 4B
model loading into 4 GB of VRAM, and a short timeout would report a failure
that has not happened. health() deliberately omits the API key: GET /health has
no auth server-side, so the connection test must work before a key is entered,
or a typo is indistinguishable from an unreachable laptop."
```

---

## Task 5: The answer card

**Files:**
- Create: `mobile/lib/widgets/answer_card.dart`
- Create: `mobile/test/answer_card_test.dart`

**Interfaces:**
- Consumes: `Answer`, `Source` (Task 2).
- Produces: `class AnswerCard extends StatelessWidget` taking `final Answer answer`.

- [ ] **Step 1: Write the failing test**

Create `mobile/test/answer_card_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:company_brain/models/answer.dart';
import 'package:company_brain/widgets/answer_card.dart';

Widget wrap(Widget child) => MaterialApp(home: Scaffold(body: child));

void main() {
  testWidgets('renders the route badge, the answer, and the sources',
      (tester) async {
    await tester.pumpWidget(wrap(AnswerCard(
      answer: const Answer(
        route: 'FACT',
        text: 'Diploma fee is 1500.',
        contextUsed: 'raw chunks that must not appear',
        sources: [Source(document: 'fees.pdf', section: 'FEES')],
      ),
    )));

    expect(find.text('FACT'), findsOneWidget);
    expect(find.text('Diploma fee is 1500.'), findsOneWidget);
    expect(find.text('fees.pdf'), findsOneWidget);
  });

  testWidgets('renders with zero sources without an empty header',
      (tester) async {
    // TABULAR answers carry no provenance; a bare "Sources" heading with
    // nothing under it reads as a bug.
    await tester.pumpWidget(wrap(AnswerCard(
      answer: const Answer(
        route: 'TABULAR',
        text: '16 students.',
        contextUsed: '',
        sources: [],
      ),
    )));

    expect(find.text('TABULAR'), findsOneWidget);
    expect(find.textContaining('Sources'), findsNothing);
  });

  testWidgets('never renders context_used', (tester) async {
    await tester.pumpWidget(wrap(AnswerCard(
      answer: const Answer(
        route: 'LOCAL',
        text: 'The trust runs the college.',
        contextUsed: 'ESTABLISHED_BY edge dump',
        sources: [],
      ),
    )));

    expect(find.textContaining('ESTABLISHED_BY'), findsNothing);
  });
}
```

- [ ] **Step 2: Run to verify it fails**

```bash
cd mobile && flutter test test/answer_card_test.dart
```

Expected: FAIL — URI does not exist.

- [ ] **Step 3: Write the widget**

Create `mobile/lib/widgets/answer_card.dart`:

```dart
import 'package:flutter/material.dart';

import '../models/answer.dart';

/// One colour per retrieval route. The badge is not decoration: it is the
/// visible proof that four different retrievers sit behind one question box,
/// which is the whole architectural claim.
const _routeColours = <String, Color>{
  'TABULAR': Color(0xFF2EA97A),
  'FACT': Color(0xFF3B6EF5),
  'LOCAL': Color(0xFFA78BFA),
  'GLOBAL': Color(0xFF5B9BD5),
};

class AnswerCard extends StatelessWidget {
  final Answer answer;
  const AnswerCard({super.key, required this.answer});

  @override
  Widget build(BuildContext context) {
    final colour = _routeColours[answer.route] ?? Colors.grey;

    return Card(
      margin: const EdgeInsets.all(12),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
              decoration: BoxDecoration(
                color: colour.withValues(alpha: 0.15),
                border: Border.all(color: colour),
                borderRadius: BorderRadius.circular(4),
              ),
              child: Text(
                answer.route,
                style: TextStyle(
                  color: colour,
                  fontSize: 11,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.2,
                ),
              ),
            ),
            const SizedBox(height: 12),
            SelectableText(answer.text, style: const TextStyle(fontSize: 16)),

            // Only drawn when provenance exists. A bare "Sources" heading with
            // nothing under it reads as a bug, and TABULAR answers legitimately
            // carry none -- they come from SQL, not from a document.
            if (answer.sources.isNotEmpty) ...[
              const SizedBox(height: 16),
              const Text('Sources',
                  style: TextStyle(
                      fontSize: 11,
                      letterSpacing: 1.2,
                      fontWeight: FontWeight.bold)),
              const SizedBox(height: 6),
              ...answer.sources.map(
                (s) => Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    s.section == null ? s.document : '${s.document} · ${s.section}',
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: Run to verify it passes**

```bash
cd mobile && flutter test
```

Expected: `All tests passed!` (14 tests across four files).

- [ ] **Step 5: Commit**

```bash
git add mobile/lib/widgets/answer_card.dart mobile/test/answer_card_test.dart
git commit -m "feat(mobile): answer card with route badge and provenance

The badge is not decoration -- it is the visible proof that four retrievers sit
behind one question box. The sources block is drawn only when provenance
exists: TABULAR answers legitimately carry none because they come from SQL, and
a bare Sources heading with nothing under it reads as a bug."
```

---

## Task 6: Settings screen with a connection test

**Files:**
- Create: `mobile/lib/screens/settings_screen.dart`

**Interfaces:**
- Consumes: `AppConfig` (Task 3), `BrainClient` (Task 4).
- Produces: `class SettingsScreen extends StatefulWidget` with `final AppConfig config` and `final VoidCallback onSaved`.

**Why before the ask screen:** the app is unusable until an address is set, and first launch routes here.

- [ ] **Step 1: Write the screen**

Create `mobile/lib/screens/settings_screen.dart`:

```dart
import 'package:flutter/material.dart';

import '../api/brain_client.dart';
import '../config/app_config.dart';

/// Server address, API key, tenant, and a reachability test.
///
/// The test button is the most operationally valuable control in the app. When
/// the phone cannot reach the laptop on a venue floor, it turns a blind
/// debugging session into one tap -- and it is the reason the address is
/// editable rather than compiled in.
class SettingsScreen extends StatefulWidget {
  final AppConfig config;
  final VoidCallback onSaved;

  const SettingsScreen({super.key, required this.config, required this.onSaved});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late final TextEditingController _url =
      TextEditingController(text: widget.config.baseUrl);
  late final TextEditingController _key =
      TextEditingController(text: widget.config.apiKey);
  late final TextEditingController _tenant =
      TextEditingController(text: widget.config.tenantId);

  String? _result;
  bool _testing = false;

  @override
  void dispose() {
    _url.dispose();
    _key.dispose();
    _tenant.dispose();
    super.dispose();
  }

  Future<void> _test() async {
    setState(() {
      _testing = true;
      _result = null;
    });
    final probe = AppConfig(
      baseUrl: _url.text.trim(),
      apiKey: '',
      tenantId: _tenant.text.trim(),
    );
    final ok = await BrainClient(config: probe).health();
    if (!mounted) return;
    setState(() {
      _testing = false;
      _result = ok ? 'Connected' : 'No response from ${probe.baseUrl}';
    });
  }

  Future<void> _save() async {
    widget.config
      ..baseUrl = _url.text.trim()
      ..apiKey = _key.text.trim()
      ..tenantId = _tenant.text.trim().isEmpty
          ? AppConfig.defaultTenant
          : _tenant.text.trim();
    await widget.config.save();
    widget.onSaved();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          TextField(
            controller: _url,
            keyboardType: TextInputType.url,
            autocorrect: false,
            decoration: const InputDecoration(
              labelText: 'Laptop address',
              hintText: 'http://192.168.137.1:8000',
              helperText: 'Use the laptop hotspot IP, not the venue wifi.',
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _key,
            autocorrect: false,
            decoration: const InputDecoration(
              labelText: 'API key',
              helperText: 'Leave blank if the server does not require one.',
            ),
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _tenant,
            autocorrect: false,
            decoration: const InputDecoration(labelText: 'Tenant'),
          ),
          const SizedBox(height: 24),
          FilledButton.tonal(
            onPressed: _testing ? null : _test,
            child: Text(_testing ? 'Testing…' : 'Test connection'),
          ),
          if (_result != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Text(
                _result!,
                style: TextStyle(
                  color: _result == 'Connected'
                      ? const Color(0xFF2EA97A)
                      : const Color(0xFFE5484D),
                ),
              ),
            ),
          const SizedBox(height: 24),
          FilledButton(onPressed: _save, child: const Text('Save')),
        ],
      ),
    );
  }
}
```

- [ ] **Step 2: Verify it analyses and the suite still passes**

```bash
cd mobile && flutter analyze && flutter test
```

Expected: `No issues found!` and `All tests passed!`.

- [ ] **Step 3: Commit**

```bash
git add mobile/lib/screens/settings_screen.dart
git commit -m "feat(mobile): settings with a reachability test

The test button is the most operationally valuable control in the app: when the
phone cannot reach the laptop on a venue floor it turns a blind debugging
session into one tap. It probes with an empty API key on purpose, so a
reachable server with a mistyped key still reports Connected -- separating
'wrong key' from 'wrong network', which are different problems with different
fixes."
```

---

## Task 7: The ask screen, wired end to end

**Files:**
- Create: `mobile/lib/screens/ask_screen.dart`
- Modify: `mobile/lib/main.dart` (replace the placeholder)
- Create: `mobile/android/app/src/main/res/xml/network_security_config.xml`
- Modify: `mobile/android/app/src/main/AndroidManifest.xml` (generated by `flutter create`)

**Interfaces:**
- Consumes: `AppConfig`, `BrainClient`, `Answer`, `AnswerCard`, `SettingsScreen`.
- Produces: `class AskScreen extends StatefulWidget`, `class AskScreenState extends State<AskScreen>`, and a **public** `void setQuestion(String)` seam that Tasks 8 and 9 write into. The name carries no leading underscore on purpose — in Dart that would make it library-private and unreachable from the widgets those tasks add.

**This task delivers the first working app.** Everything after it is additive.

- [ ] **Step 1: Write the network security config**

Create `mobile/android/app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Android has blocked cleartext HTTP by default since API 28. This app's entire
  connection model is plain HTTP to a LAN address, so without this it builds
  clean, installs clean, and fails every request on device with
  "CLEARTEXT communication not permitted".

  Scoped to private ranges rather than "*" so the app cannot be pointed at a
  plaintext public host.
-->
<network-security-config>
  <domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="true">192.168.0.0</domain>
    <domain includeSubdomains="true">10.0.0.0</domain>
    <domain includeSubdomains="true">172.16.0.0</domain>
    <domain includeSubdomains="true">localhost</domain>
  </domain-config>
</network-security-config>
```

- [ ] **Step 2: Wire the manifest**

In `mobile/android/app/src/main/AndroidManifest.xml`, add inside `<manifest>` above `<application>`:

```xml
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.RECORD_AUDIO"/>
    <uses-permission android:name="android.permission.CAMERA"/>
```

and add to the `<application>` tag:

```xml
        android:networkSecurityConfig="@xml/network_security_config"
```

- [ ] **Step 3: Write the ask screen**

Create `mobile/lib/screens/ask_screen.dart`:

```dart
import 'package:flutter/material.dart';

import '../api/brain_client.dart';
import '../config/app_config.dart';
import '../models/answer.dart';
import '../widgets/answer_card.dart';
import 'settings_screen.dart';

class AskScreen extends StatefulWidget {
  const AskScreen({super.key});

  @override
  State<AskScreen> createState() => AskScreenState();
}

class AskScreenState extends State<AskScreen> {
  final _controller = TextEditingController();
  AppConfig? _config;
  Answer? _answer;
  String? _error;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _boot();
  }

  Future<void> _boot() async {
    final c = await AppConfig.load();
    if (!mounted) return;
    setState(() => _config = c);
    // First launch has no address, and the app cannot do anything without one.
    if (!c.isConfigured) _openSettings();
  }

  void _openSettings() {
    final c = _config;
    if (c == null) return;
    Navigator.of(context).push(MaterialPageRoute(
      builder: (_) => SettingsScreen(config: c, onSaved: () => setState(() {})),
    ));
  }

  /// The single seam voice and camera write into. Both produce a String and
  /// nothing else, which is why neither introduces a second answer path.
  void setQuestion(String text) {
    _controller.text = text;
    _controller.selection =
        TextSelection.collapsed(offset: _controller.text.length);
  }

  Future<void> _ask() async {
    final c = _config;
    final q = _controller.text.trim();
    if (c == null) return;
    if (q.isEmpty) {
      setState(() => _error = 'Type or speak a question first.');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
      _answer = null;
    });
    try {
      final a = await BrainClient(config: c).query(q);
      if (!mounted) return;
      setState(() => _answer = a);
    } on BrainException catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Company Brain'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings),
            onPressed: _openSettings,
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: _controller,
                    minLines: 1,
                    maxLines: 3,
                    decoration: const InputDecoration(
                      hintText: 'Ask about students, fees, policy…',
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => _ask(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: _busy ? null : _ask,
                  icon: const Icon(Icons.send),
                ),
              ],
            ),
          ),
          if (_busy) const LinearProgressIndicator(),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
              child: Text(_error!,
                  style: const TextStyle(color: Color(0xFFE5484D))),
            ),
          if (_answer != null)
            Expanded(child: SingleChildScrollView(child: AnswerCard(answer: _answer!))),
        ],
      ),
    );
  }
}
```

- [ ] **Step 4: Replace the placeholder entry point**

Replace `mobile/lib/main.dart` entirely:

```dart
import 'package:flutter/material.dart';

import 'screens/ask_screen.dart';

void main() => runApp(const CompanyBrainApp());

class CompanyBrainApp extends StatelessWidget {
  const CompanyBrainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Company Brain',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark(useMaterial3: true),
      home: const AskScreen(),
    );
  }
}
```

- [ ] **Step 5: Verify, push, install, and prove it end to end**

```bash
cd mobile && flutter analyze && flutter test && cd ..
git add mobile/ && git commit -m "feat(mobile): ask screen wired end to end

First working app: type a question, get an answer with its route badge and
sources. setQuestion() is the single seam voice and camera write into later --
both produce a String and nothing else, which is why neither adds a second
answer path.

Ships the network security config that makes any of it work on a device:
Android has blocked cleartext HTTP since API 28, and every request here is
plain HTTP to a LAN address, so without it the app builds clean, installs
clean, and fails every call. Scoped to private ranges, never *."
git push origin main
gh run watch
```

Download the APK artifact, install it, then on the phone:

1. Start the backend: `powershell -File scripts\demo_up.ps1 -Lan -ApiKey demo-key-long-random`
2. Join the phone to the laptop hotspot.
3. Open the app, enter `http://<laptop-hotspot-ip>:8000` and the key, tap **Test connection**.

Expected: `Connected`. Then ask *"How many students failed at least 2 subjects?"*

Expected: a `TABULAR` badge and an answer naming 16 students.

- [ ] **Step 6: Confirm the backend is untouched**

```bash
git status --short
.venv312/Scripts/python.exe -m pytest -q
```

Expected: clean; `310 passed, 1 skipped`.

---

## Task 8: Voice input

**Files:**
- Create: `mobile/lib/services/speech_service.dart`
- Create: `mobile/lib/widgets/mic_button.dart`
- Modify: `mobile/lib/screens/ask_screen.dart`

**Interfaces:**
- Consumes: `AskScreenState.setQuestion(String)` (Task 7).
- Produces:
  - `class SpeechService` with `Future<bool> init()`, `Future<void> listen({required String localeId, required void Function(String) onResult})`, `Future<void> stop()`, `bool get isListening`.
  - `const supportedLocales = {'en_IN': 'English', 'mr_IN': 'मराठी', 'hi_IN': 'हिंदी'}`.

- [ ] **Step 1: Write the service**

Create `mobile/lib/services/speech_service.dart`:

```dart
import 'package:speech_to_text/speech_to_text.dart';

/// Locales offered by the mic control.
///
/// Multilingual voice is deliberate, not decorative: a student asking about
/// their own result in Marathi is what distinguishes this from an
/// English-language pilot, and recognition is Android's own, so it costs a
/// locale parameter and no new dependency.
const supportedLocales = <String, String>{
  'en_IN': 'English',
  'mr_IN': 'मराठी',
  'hi_IN': 'हिंदी',
};

class SpeechService {
  final SpeechToText _stt = SpeechToText();
  bool _ready = false;

  bool get isListening => _stt.isListening;

  /// Requests the microphone permission as a side effect. Called on first tap
  /// of the mic, never at launch -- a permission dialog before the user has
  /// seen the app is the most common reason a demo install gets denied.
  Future<bool> init() async {
    _ready = await _stt.initialize();
    return _ready;
  }

  Future<void> listen({
    required String localeId,
    required void Function(String) onResult,
  }) async {
    if (!_ready && !await init()) return;
    await _stt.listen(
      localeId: localeId,
      onResult: (r) => onResult(r.recognizedWords),
    );
  }

  Future<void> stop() => _stt.stop();
}
```

- [ ] **Step 2: Write the mic button**

Create `mobile/lib/widgets/mic_button.dart`:

```dart
import 'package:flutter/material.dart';

import '../services/speech_service.dart';

/// The largest control on the ask screen. That is the phone-first thesis made
/// visible rather than a styling choice.
class MicButton extends StatefulWidget {
  final void Function(String) onText;
  const MicButton({super.key, required this.onText});

  @override
  State<MicButton> createState() => _MicButtonState();
}

class _MicButtonState extends State<MicButton> {
  final _speech = SpeechService();
  String _locale = 'en_IN';
  bool _listening = false;
  String? _error;

  Future<void> _toggle() async {
    if (_listening) {
      await _speech.stop();
      setState(() => _listening = false);
      return;
    }
    final ok = await _speech.init();
    if (!ok) {
      setState(() => _error = 'Microphone unavailable — type instead.');
      return;
    }
    setState(() {
      _listening = true;
      _error = null;
    });
    await _speech.listen(localeId: _locale, onResult: widget.onText);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        GestureDetector(
          onTap: _toggle,
          child: Container(
            width: 96,
            height: 96,
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: _listening
                  ? const Color(0xFFE5484D)
                  : Theme.of(context).colorScheme.primary,
            ),
            child: Icon(_listening ? Icons.stop : Icons.mic,
                size: 44, color: Colors.white),
          ),
        ),
        const SizedBox(height: 8),
        SegmentedButton<String>(
          segments: supportedLocales.entries
              .map((e) => ButtonSegment(value: e.key, label: Text(e.value)))
              .toList(),
          selected: {_locale},
          onSelectionChanged: (s) => setState(() => _locale = s.first),
        ),
        if (_error != null)
          Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Text(_error!,
                style: const TextStyle(color: Color(0xFFE5484D), fontSize: 12)),
          ),
      ],
    );
  }
}
```

- [ ] **Step 3: Put it on the ask screen**

In `mobile/lib/screens/ask_screen.dart`, add the import:

```dart
import '../widgets/mic_button.dart';
```

and insert directly above the `Padding` that holds the text field, inside the `Column`'s `children`:

```dart
          Padding(
            padding: const EdgeInsets.only(top: 24, bottom: 8),
            child: MicButton(onText: setQuestion),
          ),
```

- [ ] **Step 4: Verify and ship**

```bash
cd mobile && flutter analyze && flutter test && cd ..
git add mobile/ && git commit -m "feat(mobile): voice input with English, Marathi and Hindi

The mic is the largest control on the screen -- the phone-first thesis made
visible, not a styling choice. Multilingual is deliberate: a student asking
about their own result in Marathi is what distinguishes this from an
English-language pilot, and recognition is Android's own so it costs a locale
parameter and no dependency. The permission is requested on first tap, never at
launch, because a dialog before the user has seen the app is the most common
reason a demo install gets denied."
git push origin main && gh run watch
```

Install the new APK and speak a question. Expected: recognised text lands in the box; submitting answers as before.

---

## Task 9: Camera OCR

**Files:**
- Create: `mobile/lib/services/ocr_service.dart`
- Modify: `mobile/lib/screens/ask_screen.dart`

**Interfaces:**
- Consumes: `AskScreenState.setQuestion(String)` (Task 7).
- Produces: `class OcrService` with `Future<String?> captureAndRecognise()` returning recognised text, `null` if the user cancels, and throwing `OcrException` when a photo contains no text.

- [ ] **Step 1: Write the service**

Create `mobile/lib/services/ocr_service.dart`:

```dart
import 'package:google_mlkit_text_recognition/google_mlkit_text_recognition.dart';
import 'package:image_picker/image_picker.dart';

class OcrException implements Exception {
  final String message;
  const OcrException(this.message);
  @override
  String toString() => message;
}

/// Camera capture plus on-device text recognition.
///
/// Recognition runs locally rather than on the server for three reasons: it
/// keeps the backend untouched, it works with no laptop at all, and on-device
/// AI is one of the three things the phone-use score names. The recognised text
/// becomes the question -- point at a printed roll number, get that student's
/// record out of the corpus that already exists. No new index, no new endpoint.
class OcrService {
  final ImagePicker _picker = ImagePicker();

  Future<String?> captureAndRecognise() async {
    final shot = await _picker.pickImage(source: ImageSource.camera);
    if (shot == null) return null; // user backed out; not an error

    final recogniser = TextRecognizer(script: TextRecognitionScript.latin);
    try {
      final result =
          await recogniser.processImage(InputImage.fromFilePath(shot.path));
      final text = result.text.trim();
      if (text.isEmpty) {
        throw const OcrException('No text found in that photo.');
      }
      // Recognised blocks arrive newline-separated; the question box is a
      // single line of intent, so collapse the whitespace.
      return text.replaceAll(RegExp(r'\s+'), ' ');
    } finally {
      await recogniser.close();
    }
  }
}
```

- [ ] **Step 2: Add the camera control to the ask screen**

In `mobile/lib/screens/ask_screen.dart`, add the import:

```dart
import '../services/ocr_service.dart';
```

add the field to `AskScreenState`:

```dart
  final _ocr = OcrService();
```

add the handler method:

```dart
  Future<void> _scan() async {
    try {
      final text = await _ocr.captureAndRecognise();
      if (text != null) setQuestion(text);
    } on OcrException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) {
        setState(() => _error = 'Camera unavailable — type instead.');
      }
    }
  }
```

and add a second `IconButton` beside the send button, inside the `Row`:

```dart
                IconButton.outlined(
                  onPressed: _busy ? null : _scan,
                  icon: const Icon(Icons.camera_alt),
                ),
                const SizedBox(width: 8),
```

- [ ] **Step 3: Verify and ship**

```bash
cd mobile && flutter analyze && flutter test && cd ..
git add mobile/ && git commit -m "feat(mobile): camera OCR as a third input method

Recognition runs on-device via ML Kit rather than on the server: it keeps the
backend untouched, it works with no laptop at all, and on-device AI is one of
the three things the phone-use score names. The recognised text becomes the
question, so pointing at a printed roll number returns that student's record
from the corpus that already exists -- no new index and no new endpoint.
Cancelling the camera returns null and is not an error; a photo with no text
is."
git push origin main && gh run watch
```

Install and photograph a printed roll number from a result sheet. Expected: the number lands in the question box; submitting returns that student's record.

- [ ] **Step 4: Confirm the backend is still untouched**

```bash
git status --short
.venv312/Scripts/python.exe -m pytest -q
cd dashboard && npm run build && cd ..
```

Expected: only `mobile/` changed; `310 passed, 1 skipped`; dashboard build succeeds.

---

## Task 10: The runbook

**Files:**
- Create: `docs/MOBILE_RUNBOOK.md`

- [ ] **Step 1: Write it**

Create `docs/MOBILE_RUNBOOK.md` covering, in this order:

1. **Getting the APK.** Push to `main`, open the Actions run, download the
   `company-brain-apk` artifact, transfer to the phone, enable install from
   unknown sources, install.
2. **Starting the backend for phone access.**
   `powershell -File scripts\demo_up.ps1 -Lan -ApiKey <long random string>`
3. **Connecting, in preference order.** Laptop hotspot first. Phone hotspot
   second. USB tethering third. **Venue wifi assumed not to work** — conference
   networks commonly isolate clients, so the phone can reach the internet but
   not the laptop. Include the command to read the laptop's hotspot IP:
   `Get-NetIPAddress -AddressFamily IPv4 | Where-Object InterfaceAlias -like '*Local Area Connection*'`
4. **First-run check.** Settings → address → **Test connection** → expect
   `Connected` before doing anything else.
5. **The demo sequence.** Typed TABULAR question, spoken question in Marathi,
   photographed roll number — one per input method, one per scoring category.
6. **What to do when it breaks.** Test connection red → wrong network, switch
   fallback. `API key rejected` → key mismatch with `demo_up.ps1`. Timeout on
   the first query only → cold model load, ask again. All three answers wrong
   in the same way → wrong tenant selected.

- [ ] **Step 2: Commit**

```bash
git add docs/MOBILE_RUNBOOK.md
git commit -m "docs: phone client runbook

Ordered by what fails first on a venue floor. Connectivity leads because venue
wifi commonly isolates clients, which would break the client/laptop design at
check-in -- so the laptop hotspot is the primary path and venue wifi is assumed
dead rather than tried and debugged live."
```

---

## Self-review notes

- **Spec coverage.** Additive constraint → verified in Tasks 1, 7, 9. Platform
  requirements → Task 7 (cleartext, permissions) and Task 1 (`minSdk`, app id,
  pinned deps). Tenant selection → Task 3 (`tenantId`, default `tenant_1`) and
  Task 6 (editable). Voice language → Task 8. `context_used` not rendered →
  Tasks 2 and 5, with a test asserting it. Error handling table → Task 4
  (network, 401, 400, timeout), Task 7 (empty query), Tasks 8 and 9
  (permissions). Connectivity fallbacks → Task 10. Testing → Tasks 2–5.
- **Deviation from the spec, recorded.** The spec described the tenant as a
  dropdown populated from `GET /tenants`. This plan makes it a text field in
  settings, defaulting to `tenant_1`. Reason: the dropdown needs a successful
  connection before it can populate, which makes first-run a circular
  dependency, and there are two tenants worth demoing. The field is strictly
  simpler and cannot fail. Task 3's interface still carries `tenantId`, so a
  dropdown remains a later change to one screen.
- **Type consistency.** `setQuestion(String)` is defined in Task 7 and consumed
  unchanged by Tasks 8 and 9. `Answer`/`Source` field names are identical
  across Tasks 2, 4 and 5. `AppConfig.defaultTenant` is used in Tasks 3, 6.
- **Verification limits.** Steps say `flutter test` locally, but Flutter is not
  installed on this machine. Where it is unavailable, the CI `Test` step is the
  verification and gives the same pass/fail — Task 1 exists to make that loop
  real before any other task needs it.
- **Ordering.** Task 7 is the first working app; Tasks 8 and 9 are additive and
  either can be dropped under time pressure without breaking anything.
