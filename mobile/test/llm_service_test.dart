import 'package:flutter_test/flutter_test.dart';

import 'package:company_brain/llm/llm_service.dart';

void main() {
  group('LlmService', () {
    test('modelFileName is the exact pushed filename', () {
      expect(LlmService.modelFileName, 'gemma-4-E2B-it-gpu.litertlm');
    });

    test('buildMissingMessage names the expected path and adb push command', () {
      const expectedPath =
          '/sdcard/Android/data/com.companybrain.company_brain/files/'
          'gemma-4-E2B-it-gpu.litertlm';

      final message = LlmService.buildMissingMessage(expectedPath);

      expect(message, contains(expectedPath));
      expect(
        message,
        contains('adb push gemma-4-E2B-it-gpu.litertlm $expectedPath'),
      );
    });

    test('buildMissingMessage never silently degrades -- states the fix, not just the failure', () {
      final message = LlmService.buildMissingMessage('/some/path/gemma-4-E2B-it-gpu.litertlm');

      expect(message, contains('not found'));
      expect(message, contains('Push it from your laptop with'));
    });

    test('ModelMissingException.toString() surfaces the message directly', () {
      final exception = ModelMissingException('boom: something is missing');

      expect(exception.toString(), 'boom: something is missing');
    });
  });

  // isModelPresent() and initialize() are deliberately not exercised here:
  // both need path_provider's platform channel (present on a device, not in
  // a plain `flutter test` run) and initialize() also needs the
  // flutter_gemma native engine and a real 1.9 GB model file. Only the pure
  // parts -- the filename constant and the missing-model message -- are
  // hermetic, and those are covered above.
}
