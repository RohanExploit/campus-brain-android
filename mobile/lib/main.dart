import 'package:flutter/material.dart';

import 'screens/ask_screen.dart';

void main() => runApp(const CompanyBrainApp());

class CompanyBrainApp extends StatelessWidget {
  const CompanyBrainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Company Brain',
      theme: ThemeData.dark(useMaterial3: true),
      home: const AskScreen(),
    );
  }
}
