import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Thrown by [CloudService.complete] whenever the cloud fallback cannot
/// produce a real answer -- a network failure, a non-200 response, or a
/// response with an empty/missing content field. The caller must treat
/// this the same as "no answer available" and fall through to the
/// retrieval-only path; it must never be swallowed into a fabricated
/// answer.
class CloudUnavailableException implements Exception {
  final String message;

  CloudUnavailableException(this.message);

  @override
  String toString() => message;
}

/// Runtime configuration for the cloud fallback, read from a JSON file
/// dropped next to the on-device corpus. Never committed, never
/// hardcoded -- see [CloudConfig.load].
class CloudConfig {
  final String apiKey;
  final String model;

  const CloudConfig({required this.apiKey, required this.model});

  /// Reads `config.json` from wherever the on-device corpus lives:
  /// external files directory first, then app documents directory --
  /// exactly the lookup [BrainDb.open] performs (see
  /// `lib/local/brain_db.dart`), so the same `adb push` destination that
  /// carries `brain.db` also carries this file.
  ///
  /// Returns null when the file is absent or malformed. Absence is the
  /// normal, expected state on a device that hasn't been given a cloud
  /// key -- it is not an error and must not throw.
  static Future<CloudConfig?> load() async {
    for (final dir in await _candidateDirs()) {
      final file = File(p.join(dir.path, _fileName));
      try {
        if (!await file.exists()) continue;
        final raw = await file.readAsString();
        final config = fromJson(raw);
        if (config != null) return config;
      } catch (_) {
        // Malformed file, unreadable, or a race with something else
        // writing it -- treat exactly like "absent".
        continue;
      }
    }
    return null;
  }

  static const String _fileName = 'config.json';

  static Future<List<Directory>> _candidateDirs() async {
    final dirs = <Directory>[];
    try {
      final external = await getExternalStorageDirectory();
      if (external != null) dirs.add(external);
    } catch (_) {
      // Not on Android, or the platform channel isn't available (e.g. in
      // a plain `flutter test` run with no plugin bindings).
    }
    try {
      dirs.add(await getApplicationDocumentsDirectory());
    } catch (_) {
      // Same as above.
    }
    return dirs;
  }

  /// Pure parsing, split out from [load] so it's testable without any
  /// filesystem I/O. Returns null (never throws) for malformed JSON, a
  /// non-object body, or a missing/empty `groq_api_key` or `groq_model`.
  static CloudConfig? fromJson(String raw) {
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return null;
      final apiKey = decoded['groq_api_key'];
      final model = decoded['groq_model'];
      if (apiKey is! String || apiKey.isEmpty) return null;
      if (model is! String || model.isEmpty) return null;
      return CloudConfig(apiKey: apiKey, model: model);
    } catch (_) {
      return null;
    }
  }
}

/// Cloud fallback for when the on-device model has failed to initialise.
///
/// This is a fallback of last resort, not a primary path: the app's
/// central claim is that it runs entirely on-device with zero network,
/// and every answer this produces must carry a visible marker saying so
/// (see `ask_screen.dart`). [CloudService] itself only makes the request
/// and returns the raw answer text -- it does not add the marker, so it
/// stays a plain, testable HTTP wrapper.
class CloudService {
  static const String _endpoint =
      'https://api.groq.com/openai/v1/chat/completions';
  static const Duration _timeout = Duration(seconds: 8);

  final http.Client _client;

  CloudService({http.Client? client}) : _client = client ?? http.Client();

  /// True if a usable config file was found. Cheap re-read of [CloudConfig
  /// .load]; callers that will also call [complete] should prefer loading
  /// the config once themselves, but this is convenient for a quick
  /// "should I even offer this" check.
  Future<bool> isConfigured() async {
    return await CloudConfig.load() != null;
  }

  /// Sends a single non-streaming completion request to Groq and returns
  /// the model's answer, trimmed.
  ///
  /// Throws [CloudUnavailableException] -- never returns a fabricated or
  /// partial answer -- when: no config is present, the request times out
  /// or fails at the network layer, the response is not HTTP 200, or the
  /// response has no usable `choices[0].message.content`.
  Future<String> complete(String prompt) async {
    final config = await CloudConfig.load();
    if (config == null) {
      throw CloudUnavailableException(
        'Cloud fallback is not configured: no config.json found.',
      );
    }
    return completeWithConfig(prompt, config);
  }

  /// Same as [complete] but with an already-loaded [CloudConfig]. Split
  /// out so tests can exercise the HTTP/parsing behaviour without also
  /// exercising the filesystem lookup in [CloudConfig.load].
  Future<String> completeWithConfig(String prompt, CloudConfig config) async {
    http.Response response;
    try {
      response = await _client
          .post(
            Uri.parse(_endpoint),
            headers: {
              'Authorization': 'Bearer ${config.apiKey}',
              'Content-Type': 'application/json',
            },
            body: jsonEncode({
              'model': config.model,
              'messages': [
                {'role': 'user', 'content': prompt},
              ],
            }),
          )
          .timeout(_timeout);
    } on TimeoutException {
      throw CloudUnavailableException(
        'Groq request timed out after ${_timeout.inSeconds}s.',
      );
    } catch (e) {
      throw CloudUnavailableException('Groq request failed: $e');
    }

    if (response.statusCode != 200) {
      throw CloudUnavailableException(
        'Groq returned HTTP ${response.statusCode}: ${response.body}',
      );
    }

    String content;
    try {
      final decoded = jsonDecode(response.body);
      final choices = decoded['choices'];
      if (choices is! List || choices.isEmpty) {
        throw const FormatException('no choices in response');
      }
      final message = choices[0]['message'];
      if (message is! Map) {
        throw const FormatException('no message in first choice');
      }
      final rawContent = message['content'];
      if (rawContent is! String) {
        throw const FormatException('no content in message');
      }
      content = rawContent;
    } catch (e) {
      throw CloudUnavailableException(
        'Groq response did not contain usable content: $e',
      );
    }

    content = content.trim();
    if (content.isEmpty) {
      throw CloudUnavailableException('Groq returned an empty answer.');
    }
    return content;
  }

  void dispose() {
    _client.close();
  }
}
