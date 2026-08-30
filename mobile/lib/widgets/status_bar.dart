import 'package:flutter/material.dart';

/// A thin row of small pills under the app title that make the "runs
/// entirely offline" claim visible rather than only spoken.
class StatusBar extends StatelessWidget {
  final int chunkCount;
  final int studentCount;
  final bool llmReady;

  const StatusBar({
    super.key,
    required this.chunkCount,
    required this.studentCount,
    required this.llmReady,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
      child: Wrap(
        spacing: 6,
        runSpacing: 6,
        children: [
          const _Pill(
            label: 'OFFLINE',
            color: Colors.green,
            icon: Icons.wifi_off,
          ),
          _Pill(label: '$chunkCount chunks', color: Colors.blueGrey),
          _Pill(label: '$studentCount students', color: Colors.blueGrey),
          _Pill(
            label: llmReady ? 'MODEL READY' : 'SQL MODE',
            color: llmReady ? Colors.green : Colors.amber,
            icon: llmReady ? Icons.check_circle : Icons.storage,
          ),
        ],
      ),
    );
  }
}

class _Pill extends StatelessWidget {
  final String label;
  final Color color;
  final IconData? icon;

  const _Pill({required this.label, required this.color, this.icon});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.18),
        borderRadius: BorderRadius.circular(999),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 12, color: color),
            const SizedBox(width: 4),
          ],
          Text(
            label,
            style: TextStyle(
              color: color,
              fontSize: 11,
              fontWeight: FontWeight.w600,
              letterSpacing: 0.3,
            ),
          ),
        ],
      ),
    );
  }
}
