import 'dart:io';

import 'package:flutter_gemma/flutter_gemma.dart';
import 'package:flutter_gemma_litertlm/flutter_gemma_litertlm.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

/// Thrown by [LlmService.initialize] when the on-device model file cannot
/// be found anywhere it looks. The message is meant to be read directly on
/// a phone screen (at a venue, with no laptop debugger attached), so it
/// states the expected directory and the exact `adb push` command to fix
/// it -- never a stack trace, and never a silent fallback to a canned
/// reply.
class ModelMissingException implements Exception {
  final String message;

  ModelMissingException(this.message);

  @override
  String toString() => message;
}

/// Wraps `flutter_gemma` 1.6.5 (core) + `flutter_gemma_litertlm` 1.5.3
/// (the LiteRT-LM `.litertlm` inference engine) to run the on-device
/// language model.
///
/// The model file is never bundled (it is ~1.9 GB) -- it reaches the phone
/// by `adb push`, exactly like [BrainDb]'s database. [initialize] resolves
/// its path the same way [BrainDb.open] resolves the database path: the
/// app's external files directory first, then the app documents directory.
/// It is then registered with `flutter_gemma` via a [FileSource] install,
/// which references the file in place rather than copying it.
class LlmService {
  /// The exact filename `flutter_gemma` and the `adb push` runbook agree
  /// on. Defined once here so nothing else in the app hardcodes it.
  static const String modelFileName = 'gemma-4-E2B-it-gpu.litertlm';

  /// Where `adb push gemma-4-E2B-it-gpu.litertlm <path>` should put the
  /// file, per the documented runbook. Used only when the external
  /// storage directory itself is unavailable (e.g. not running on
  /// Android), so there is still a concrete path to show the user.
  static const String _externalPushPathHint =
      '/sdcard/Android/data/com.companybrain.company_brain/files/$modelFileName';

  InferenceModel? _model;
  InferenceChat? _chat;
  bool _engineRegistered = false;

  static Future<Directory?> _externalStorageDir() async {
    try {
      return await getExternalStorageDirectory();
    } catch (_) {
      // Not on Android, or the platform channel isn't available (e.g. in
      // a plain `flutter test` run with no plugin bindings).
      return null;
    }
  }

  /// Pure helper (no I/O): builds the exact "model missing" message shown
  /// on the setup screen, given the directory the model was expected in.
  /// Kept separate from any I/O so it can be unit-tested without a device
  /// or the `flutter_gemma` platform channel.
  static String buildMissingMessage(String expectedPath) {
    return 'On-device model not found.\n'
        'Expected it at:\n'
        '  $expectedPath\n'
        'Push it from your laptop with:\n'
        '  adb push $modelFileName $expectedPath';
  }

  /// True if the model file is present at either location [initialize]
  /// would look in, without registering it with `flutter_gemma` or
  /// loading it. Useful for a setup screen that wants to check before the
  /// (slow) real initialization.
  static Future<bool> isModelPresent() async {
    final externalDir = await _externalStorageDir();
    if (externalDir != null) {
      final path = p.join(externalDir.path, modelFileName);
      if (await File(path).exists()) {
        return true;
      }
    }

    final docsDir = await getApplicationDocumentsDirectory();
    final docsPath = p.join(docsDir.path, modelFileName);
    return File(docsPath).exists();
  }

  /// Registers the LiteRT-LM engine (once per process), installs the
  /// on-device model by referencing its pushed file in place, and opens a
  /// chat session ready for [generateStream]. Throws
  /// [ModelMissingException] if the file cannot be found -- never falls
  /// back to a canned reply.
  Future<void> initialize() async {
    final externalDir = await _externalStorageDir();
    String? foundPath;

    if (externalDir != null) {
      final path = p.join(externalDir.path, modelFileName);
      if (await File(path).exists()) {
        foundPath = path;
      }
    }

    if (foundPath == null) {
      final docsDir = await getApplicationDocumentsDirectory();
      final docsPath = p.join(docsDir.path, modelFileName);
      if (await File(docsPath).exists()) {
        foundPath = docsPath;
      }
    }

    if (foundPath == null) {
      final expectedPath = externalDir != null
          ? p.join(externalDir.path, modelFileName)
          : _externalPushPathHint;
      throw ModelMissingException(buildMissingMessage(expectedPath));
    }

    if (!_engineRegistered) {
      await FlutterGemma.initialize(inferenceEngines: const [LiteRtLmEngine()]);
      _engineRegistered = true;
    }

    // Always (re-)install rather than pre-checking isModelInstalled: the
    // API's own modelId is documented as "filename without extension" on
    // InferenceInstallation, but isModelInstalled's parameter is
    // undocumented beyond its name, so guessing the exact key it expects
    // risks a check that silently never matches. install() itself is
    // idempotent-cheap here -- FileSource "references the pushed file in
    // place, no copying" (per the package docs), so re-running it each
    // launch just re-registers the same on-disk file as the active model.
    await FlutterGemma.installModel(
      modelType: ModelType.gemma4,
      fileType: ModelFileType.litertlm,
    ).fromFile(foundPath).install();

    _model = await FlutterGemma.getActiveModel(
      maxTokens: 2048,
      preferredBackend: PreferredBackend.gpu,
    );
    _chat = await _model!.createChat();
  }

  /// Streams incremental text tokens for [prompt] on the already-open chat
  /// session. Must be called after [initialize]. Non-text response types
  /// (function calls, thinking blocks) are dropped -- this app has no
  /// function-calling tools and Gemma 4's thinking output is not surfaced
  /// in the UI.
  Stream<String> generateStream(String prompt) async* {
    final chat = _chat;
    if (chat == null) {
      throw StateError(
        'LlmService.initialize() must complete before generateStream() is called.',
      );
    }

    await chat.addQueryChunk(Message.text(text: prompt, isUser: true));

    await for (final response in chat.generateChatResponseAsync()) {
      if (response is TextResponse) {
        yield response.token;
      }
    }
  }

  Future<void> dispose() async {
    await _model?.close();
    _model = null;
    _chat = null;
  }
}
