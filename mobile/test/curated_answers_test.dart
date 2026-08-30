// Hermetic tests for the curated fee-answer layer: no DB, no network.
import 'package:flutter_test/flutter_test.dart';

import 'package:company_brain/demo/curated_answers.dart';

void main() {
  group('curated answers', () {
    test('ambiguous "fee structure" mentions both conferences', () {
      final hit = lookup('what is the fee structure');
      expect(hit, isNotNull);
      expect(hit!.answer.toLowerCase(), contains('iceasti'));
      expect(hit.answer.toLowerCase(), contains('icetis'));
    });

    test('ICETIS registration fee returns ICETIS figures only', () {
      final hit = lookup('what is the ICETIS registration fee');
      expect(hit, isNotNull);
      expect(hit!.answer.toLowerCase(), contains('icetis'));
      expect(hit.answer, contains('3000 INR'));
      expect(hit.answer, isNot(contains('1500/-')));
      expect(hit.answer, isNot(contains('2000/-')));
    });

    test('UG student fee returns 2500 INR', () {
      final hit = lookup('how much for UG students');
      expect(hit, isNotNull);
      expect(hit!.answer, contains('2500 INR'));
    });

    test('overlength page charge returns 500', () {
      final hit = lookup('what is the overlength page charge');
      expect(hit, isNotNull);
      expect(hit!.answer, contains('500'));
    });

    test('registration deadline returns 12 March 2026', () {
      final hit = lookup('when is the registration deadline');
      expect(hit, isNotNull);
      expect(hit!.answer, contains('12 March 2026'));
    });

    test('non-fee question falls through to null', () {
      expect(lookup('how many students failed'), isNull);
    });

    test('"coffee" does not match "fee" (word boundary)', () {
      expect(lookup('coffee'), isNull);
    });

    test('every entry has a non-empty sourceDoc and sourceSection', () {
      for (final entry in curatedAnswers) {
        expect(entry.sourceDoc, isNotEmpty);
        expect(entry.sourceSection, isNotEmpty);
        expect(entry.matchAny, isNotEmpty);
        expect(entry.answer, isNotEmpty);
      }
    });
  });
}
