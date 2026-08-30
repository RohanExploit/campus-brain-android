import 'package:flutter/material.dart';

import '../demo/golden_qa.dart';
import '../local/local_retriever.dart';
import '../local/models.dart';

/// The outcome of running a single [GoldenQaCase] against the real
/// retriever. [actualAnswer] is always the live text the engine returned --
/// never the expected value -- so a broken engine can only ever show what
/// it actually produced (including nothing at all), never a fabricated
/// correct-looking answer.
class _CaseResult {
  final GoldenQaCase testCase;
  bool ran = false;
  bool passed = false;
  String actualRoute = '';
  String actualAnswer = '';
  int elapsedMs = 0;
  String? error;

  _CaseResult(this.testCase);
}

/// Built-in self-test screen: runs the fixed [goldenQaCases] suite through
/// the real, live [LocalRetriever] and shows pass/fail per case.
///
/// This screen never prints an expected answer as if it were the engine's
/// output. Every answer shown on screen came back from a real
/// `retriever.retrieve(...)` call made while this screen was on-screen. The
/// expected substring is used only inside [_evaluate] to decide the ✓/✗ --
/// it is compared against, never displayed as a result.
class SelfTestScreen extends StatefulWidget {
  final LocalRetriever retriever;

  const SelfTestScreen({super.key, required this.retriever});

  @override
  State<SelfTestScreen> createState() => _SelfTestScreenState();
}

class _SelfTestScreenState extends State<SelfTestScreen> {
  late final List<_CaseResult> _results;
  bool _running = false;
  final Set<int> _expanded = {};

  @override
  void initState() {
    super.initState();
    _results = goldenQaCases.map(_CaseResult.new).toList();
  }

  /// A case passes only when both the route and the answer content match
  /// what was actually returned live -- this is the only place the
  /// expected values from golden_qa.dart are read.
  bool _evaluate(_CaseResult r, RetrievalResult retrieval) {
    final routeMatches = retrieval.route == r.testCase.expectedRoute;
    final contentMatches = retrieval.context
        .toLowerCase()
        .contains(r.testCase.expectSubstring.toLowerCase());
    return routeMatches && contentMatches;
  }

  Future<void> _runAll() async {
    if (_running) return;
    setState(() {
      _running = true;
      _expanded.clear();
      for (final r in _results) {
        r.ran = false;
        r.passed = false;
        r.actualRoute = '';
        r.actualAnswer = '';
        r.elapsedMs = 0;
        r.error = null;
      }
    });

    for (final r in _results) {
      final stopwatch = Stopwatch()..start();
      try {
        final retrieval = await widget.retriever.retrieve(r.testCase.question);
        stopwatch.stop();
        final passed = _evaluate(r, retrieval);
        if (!mounted) return;
        setState(() {
          r.ran = true;
          r.passed = passed;
          r.actualRoute = retrieval.route;
          r.actualAnswer = retrieval.context;
          r.elapsedMs = stopwatch.elapsedMilliseconds;
        });
      } catch (e) {
        stopwatch.stop();
        if (!mounted) return;
        setState(() {
          r.ran = true;
          r.passed = false;
          r.actualRoute = '';
          r.actualAnswer = '';
          r.elapsedMs = stopwatch.elapsedMilliseconds;
          r.error = e.toString();
        });
      }
    }

    if (mounted) setState(() => _running = false);
  }

  int get _passedCount => _results.where((r) => r.ran && r.passed).length;
  int get _ranCount => _results.where((r) => r.ran).length;

  int get _medianMs {
    final times = _results
        .where((r) => r.ran)
        .map((r) => r.elapsedMs)
        .toList()
      ..sort();
    if (times.isEmpty) return 0;
    final mid = times.length ~/ 2;
    if (times.length.isOdd) return times[mid];
    return ((times[mid - 1] + times[mid]) / 2).round();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Self-test')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.all(16),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    _ranCount == 0
                        ? '${_results.length} cases · not run yet'
                        : '$_passedCount/$_ranCount passed · $_medianMs ms median',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                FilledButton.icon(
                  onPressed: _running ? null : _runAll,
                  icon: _running
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.play_arrow),
                  label: Text(_running ? 'Running…' : 'Run all'),
                ),
              ],
            ),
          ),
          const Divider(height: 1),
          Expanded(
            child: ListView.builder(
              itemCount: _results.length,
              itemBuilder: (context, index) => _CaseRow(
                result: _results[index],
                expanded: _expanded.contains(index),
                onToggle: () {
                  setState(() {
                    if (_expanded.contains(index)) {
                      _expanded.remove(index);
                    } else {
                      _expanded.add(index);
                    }
                  });
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _CaseRow extends StatelessWidget {
  final _CaseResult result;
  final bool expanded;
  final VoidCallback onToggle;

  const _CaseRow({
    required this.result,
    required this.expanded,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final c = result.testCase;
    final statusIcon = !result.ran
        ? const Icon(Icons.remove, color: Colors.grey)
        : result.passed
            ? const Icon(Icons.check_circle, color: Colors.green)
            : const Icon(Icons.cancel, color: Colors.red);

    final preview = result.actualAnswer.length > 120
        ? '${result.actualAnswer.substring(0, 120)}…'
        : result.actualAnswer;

    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: InkWell(
        onTap: result.ran ? onToggle : null,
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  statusIcon,
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      c.question,
                      style: Theme.of(context).textTheme.bodyMedium,
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 6),
              Text(
                'route: ${result.ran ? result.actualRoute : '—'} '
                '(expected ${c.expectedRoute}) · ${result.elapsedMs} ms',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (result.ran && result.error != null) ...[
                const SizedBox(height: 4),
                Text(
                  'error: ${result.error}',
                  style: Theme.of(context)
                      .textTheme
                      .bodySmall
                      ?.copyWith(color: Colors.red),
                ),
              ] else if (result.ran) ...[
                const SizedBox(height: 4),
                Text(
                  expanded ? result.actualAnswer : preview,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
              if (result.ran) ...[
                const SizedBox(height: 4),
                Text(
                  c.why,
                  style: Theme.of(context).textTheme.bodySmall?.copyWith(
                        fontStyle: FontStyle.italic,
                        color: Theme.of(context).colorScheme.onSurfaceVariant,
                      ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
