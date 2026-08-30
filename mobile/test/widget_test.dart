import 'package:flutter_test/flutter_test.dart';

import 'package:company_brain/main.dart';

void main() {
  testWidgets('renders Company Brain title', (WidgetTester tester) async {
    await tester.pumpWidget(const CompanyBrainApp());

    expect(find.text('Company Brain'), findsWidgets);
  });
}
