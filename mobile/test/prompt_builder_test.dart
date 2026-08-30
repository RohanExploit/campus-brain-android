import 'package:flutter_test/flutter_test.dart';

import 'package:company_brain/llm/prompt_builder.dart';
import 'package:company_brain/local/models.dart';

void main() {
  group('buildPrompt', () {
    test('includes the abstention instruction verbatim', () {
      const result = RetrievalResult(
        route: 'FACT',
        context: 'Photosynthesis conversion efficiency is 3-6%.',
        sources: [],
      );

      final prompt = buildPrompt(
        question: 'What is photosynthesis conversion efficiency?',
        retrieval: result,
      );

      expect(prompt, contains(kAbstentionSentence));
    });

    test('includes the retrieved context', () {
      const context = 'Covalent bonds form when atoms share electron pairs.';
      const result = RetrievalResult(
        route: 'FACT',
        context: context,
        sources: [],
      );

      final prompt = buildPrompt(question: 'What is a covalent bond?', retrieval: result);

      expect(prompt, contains(context));
    });

    test('includes the question', () {
      const question = 'What is cellular respiration?';
      const result = RetrievalResult(
        route: 'FACT',
        context: 'Cellular respiration breaks down glucose to release ATP.',
        sources: [],
      );

      final prompt = buildPrompt(question: question, retrieval: result);

      expect(prompt, contains(question));
    });

    test('throws when called with a TABULAR result, per the documented '
        'contract that TABULAR answers must never be sent to the model',
        () {
      const result = RetrievalResult(
        route: 'TABULAR',
        context: 'Pass percentage: 60.00% (3/5 students).',
        sources: [],
        debugSql: 'passPercentage()',
      );

      expect(
        () => buildPrompt(question: 'what is the pass percentage', retrieval: result),
        throwsA(isA<AssertionError>()),
      );
    });

    test('an empty-context retrieval still produces a valid prompt that '
        'instructs abstention', () {
      const result = RetrievalResult(
        route: 'FACT',
        context: '',
        sources: [],
      );

      final prompt = buildPrompt(
        question: 'What is the airspeed velocity of an unladen swallow?',
        retrieval: result,
      );

      expect(prompt, isNotEmpty);
      expect(prompt, contains(kAbstentionSentence));
      expect(prompt, contains('What is the airspeed velocity of an unladen swallow?'));
    });
  });
}
