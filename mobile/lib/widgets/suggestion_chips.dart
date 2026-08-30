import 'package:flutter/material.dart';

/// The demo questions verified to return correct answers from the on-device
/// corpus. Shown as tap-to-ask chips so nobody has to watch someone type on
/// stage.
const List<String> demoSuggestions = [
  'How many students failed at least 2 subjects',
  'What percentage of students passed',
  'Top 10 students by SGPA',
  'How many students scored above 8 SGPA',
  'Which subject has the most failures',
];

/// A wrap of tap-to-ask suggestion chips. Tapping one calls [onSelected]
/// with the chip's text; the caller is responsible for populating the input
/// and submitting.
class SuggestionChips extends StatelessWidget {
  final ValueChanged<String> onSelected;
  final bool enabled;

  const SuggestionChips({
    super.key,
    required this.onSelected,
    this.enabled = true,
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final suggestion in demoSuggestions)
          ActionChip(
            label: Text(suggestion),
            onPressed: enabled ? () => onSelected(suggestion) : null,
            avatar: const Icon(Icons.bolt, size: 16),
          ),
      ],
    );
  }
}
