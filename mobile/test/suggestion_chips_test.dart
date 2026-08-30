import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:company_brain/widgets/suggestion_chips.dart';

void main() {
  testWidgets('renders all demo suggestions as chips', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SuggestionChips(onSelected: (_) {}),
        ),
      ),
    );

    for (final suggestion in demoSuggestions) {
      expect(find.text(suggestion), findsOneWidget);
    }
  });

  testWidgets('tapping a chip calls onSelected with its text', (tester) async {
    String? tapped;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SuggestionChips(onSelected: (value) => tapped = value),
        ),
      ),
    );

    await tester.tap(find.text(demoSuggestions.first));
    await tester.pump();

    expect(tapped, demoSuggestions.first);
  });

  testWidgets('disabled chips do not call onSelected', (tester) async {
    var called = false;
    await tester.pumpWidget(
      MaterialApp(
        home: Scaffold(
          body: SuggestionChips(onSelected: (_) => called = true, enabled: false),
        ),
      ),
    );

    await tester.tap(find.text(demoSuggestions.first), warnIfMissed: false);
    await tester.pump();

    expect(called, isFalse);
  });
}
