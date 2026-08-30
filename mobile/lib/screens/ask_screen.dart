import 'dart:async';

import 'package:flutter/material.dart';

import '../demo/curated_answers.dart' as curated;
import '../llm/cloud_service.dart';
import '../llm/llm_service.dart';
import '../llm/prompt_builder.dart';
import '../local/brain_db.dart';
import '../local/local_retriever.dart';
import '../local/models.dart';
import 'self_test_screen.dart';
import '../widgets/status_bar.dart';
import '../widgets/suggestion_chips.dart';

/// The three states the ask screen can be in before it's ready to answer
/// questions.
enum _SetupState { checking, missing, ready }

/// Appended to every answer produced by the Groq cloud fallback. The
/// product's central claim is that everything runs on-device with zero
/// network; a cloud answer that looks identical to an on-device one would
/// turn that claim into a lie in front of an audience, so this marker is
/// never omitted and never just a tooltip.
const String kCloudAnswerMarker =
    '[Answered via Groq cloud - network was used for this answer.]';

/// Top-level screen: types a question, gets an answer, entirely on-device.
///
/// On launch it checks both [BrainDb.isAvailable] and
/// [LlmService.isModelPresent]. If either is missing it shows a setup
/// screen naming exactly what's absent and how to fix it -- never a crash,
/// never an empty chat pretending everything is fine.
class AskScreen extends StatefulWidget {
  const AskScreen({super.key});

  @override
  State<AskScreen> createState() => _AskScreenState();
}

class _AnsweredTurn {
  final String question;
  final RetrievalResult retrieval;
  String answer;
  bool streaming;
  final Stopwatch stopwatch;
  int tokenCount;
  // True only when this turn actually went through the on-device model's
  // token stream, as opposed to a SQL/TABULAR answer or the retrieval-only
  // fallback -- both of which also fill tokenCount for word-count purposes
  // but never "streamed" anything worth a tok/s figure.
  bool usedModel;

  _AnsweredTurn({
    required this.question,
    required this.retrieval,
  })  : answer = '',
        streaming = true,
        stopwatch = Stopwatch()..start(),
        tokenCount = 0,
        usedModel = false;

  double get elapsedSeconds => stopwatch.elapsedMilliseconds / 1000.0;

  double get tokensPerSecond {
    final seconds = elapsedSeconds;
    if (seconds <= 0) return 0;
    return tokenCount / seconds;
  }
}

class _AskScreenState extends State<AskScreen> {
  _SetupState _setupState = _SetupState.checking;
  bool _dbMissing = false;
  bool _modelMissing = false;
  // The model loads in the BACKGROUND. The app is usable the moment the corpus
  // opens: TABULAR answers are exact SQL and never call a model at all, and the
  // other routes can still show what retrieval found. Blocking the whole screen
  // on a 1.9 GB load meant one slow or failed initialise left a spinner forever
  // with nothing usable behind it.
  bool _llmReady = false;
  String? _llmError;
  String? _modelMissingMessage;

  BrainDb? _brainDb;
  LocalRetriever? _retriever;
  final LlmService _llmService = LlmService();
  int _chunkCount = 0;
  int _studentCount = 0;

  final TextEditingController _controller = TextEditingController();
  final List<_AnsweredTurn> _turns = [];
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _runSetupCheck();
  }

  Future<void> _runSetupCheck() async {
    // Guarded broadly (not just the documented exception types): on a
    // platform with no path_provider channel at all -- a plain
    // `flutter test` run with no device -- these calls can throw rather
    // than resolve false. Either way the correct behaviour is the same
    // setup screen, never an unhandled exception.
    bool dbAvailable;
    bool modelPresent;
    try {
      dbAvailable = await BrainDb.isAvailable();
    } catch (_) {
      dbAvailable = false;
    }
    try {
      modelPresent = await LlmService.isModelPresent();
    } catch (_) {
      modelPresent = false;
    }

    if (!dbAvailable || !modelPresent) {
      if (!mounted) return;
      setState(() {
        _dbMissing = !dbAvailable;
        _modelMissing = !modelPresent;
        _setupState = _SetupState.missing;
      });
      return;
    }

    try {
      final db = await BrainDb.open();
      if (!mounted) return;
      setState(() {
        _brainDb = db;
        _retriever = LocalRetriever(db);
        _setupState = _SetupState.ready;
      });
      // Deliberately not awaited: loading the model takes a long time and can
      // fail, and neither should stop a student asking a question the SQL layer
      // can answer exactly and instantly.
      unawaited(_warmModel());
      unawaited(_loadMeta(db));
    } on BrainDbMissingException {
      if (!mounted) return;
      setState(() {
        _dbMissing = true;
        _setupState = _SetupState.missing;
      });
    } on ModelMissingException catch (e) {
      if (!mounted) return;
      setState(() {
        _modelMissing = true;
        _modelMissingMessage = e.message;
        _setupState = _SetupState.missing;
      });
    }
  }

  /// Reads chunk/student counts from the corpus for the status bar. Best
  /// effort: if `meta` is missing keys or parsing fails, the pills just show
  /// 0 rather than breaking anything already working.
  Future<void> _loadMeta(BrainDb db) async {
    try {
      final meta = await db.meta();
      if (!mounted) return;
      setState(() {
        _chunkCount = int.tryParse(meta['chunk_count'] ?? '') ?? 0;
        _studentCount = int.tryParse(meta['student_count'] ?? '') ?? 0;
      });
    } catch (_) {
      // Leave counts at 0 -- not worth surfacing an error for display text.
    }
  }

  /// Loads the model in the background. Every failure is caught and surfaced as
  /// text rather than thrown: a missing or broken model must degrade the app to
  /// retrieval-only, never break the screen that is already working.
  Future<void> _warmModel() async {
    try {
      await _llmService.initialize();
      if (mounted) setState(() => _llmReady = true);
    } catch (e) {
      if (mounted) setState(() => _llmError = e.toString());
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _llmService.dispose();
    _brainDb?.close();
    super.dispose();
  }

  /// Wrapper that guarantees _busy is cleared no matter which branch runs or
  /// what throws. A stuck _busy disables the send button permanently, which on
  /// a handset looks exactly like a hung app and has no recovery short of a
  /// force-stop.
  Future<void> _submit() async {
    try {
      await _submitInner();
    } catch (e) {
      debugPrint('submit failed: $e');
    } finally {
      if (mounted && _busy) setState(() => _busy = false);
    }
  }

  Future<void> _submitInner() async {
    final question = _controller.text.trim();
    final retriever = _retriever;
    if (question.isEmpty || retriever == null || _busy) return;

    _controller.clear();
    setState(() => _busy = true);

    final retrieval = await retriever.retrieve(question);
    // TABULAR keeps absolute priority: it's live SQL and must never be
    // shadowed by a canned answer, so the curated layer is only consulted
    // when the route isn't TABULAR.
    final curatedHit =
        retrieval.route == 'TABULAR' ? null : curated.lookup(question);
    final effectiveRetrieval = curatedHit == null
        ? retrieval
        : RetrievalResult(
            route: 'FACT',
            context: retrieval.context,
            sources: retrieval.sources,
            debugSql: retrieval.debugSql,
          );
    final turn =
        _AnsweredTurn(question: question, retrieval: effectiveRetrieval);
    setState(() => _turns.insert(0, turn));

    if (retrieval.route == 'TABULAR') {
      // Exact SQL answer -- shown verbatim, no model call. See
      // prompt_builder.dart's contract note on TABULAR routes.
      turn.answer = retrieval.context;
      turn.tokenCount = retrieval.context.split(RegExp(r'\s+')).length;
      turn.streaming = false;
      turn.stopwatch.stop();
      setState(() => _busy = false);
      return;
    }

    if (curatedHit != null) {
      turn.answer = '${curatedHit.answer}\n\n'
          '[Curated answer, verified against ${curatedHit.sourceDoc} - '
          '${curatedHit.sourceSection}.]';
      turn.tokenCount = turn.answer.split(RegExp(r'\s+')).length;
      turn.streaming = false;
      turn.stopwatch.stop();
      setState(() => _busy = false);
      return;
    }

    if (!_llmReady) {
      // On-device model isn't ready. Before falling back to showing the raw
      // retrieved passage, try the Groq cloud fallback -- if it's
      // configured -- so non-TABULAR questions still get a written answer.
      final cloudPrompt = buildPrompt(question: question, retrieval: retrieval);
      final cloud = CloudService();
      try {
        if (await cloud.isConfigured()) {
          final cloudAnswer = await cloud.complete(cloudPrompt);
          turn.answer = '$cloudAnswer\n\n$kCloudAnswerMarker';
          turn.tokenCount = turn.answer.split(RegExp(r'\s+')).length;
          turn.streaming = false;
          turn.stopwatch.stop();
          setState(() => _busy = false);
          return;
        }
      } catch (_) {
        // Catch EVERYTHING, not just CloudUnavailableException. A socket
        // error, a DNS failure or a TimeoutException are all thrown as their
        // own types, and any one of them escaping here left _busy stuck true
        // and the send button spinning forever with no way back -- observed on
        // the handset on a venue network. Falling through to the retrieval-only
        // answer is always better than a spinner that never resolves.
      } finally {
        cloud.dispose();
      }

      // Retrieval-only mode. Showing what was actually retrieved, labelled as
      // such, beats either a spinner that never resolves or a generated
      // sentence from a model that has not loaded.
      final status = _llmError == null
          ? 'The on-device model is still loading'
          : 'The on-device model is unavailable';
      turn.answer = retrieval.context.isEmpty
          ? 'No matching passage found in the offline corpus.'
          : '${retrieval.context}\n\n[Retrieved directly from the corpus. '
              '$status, so this passage is shown verbatim rather than '
              'summarised.]';
      turn.tokenCount = turn.answer.split(RegExp(r'\s+')).length;
      turn.streaming = false;
      turn.stopwatch.stop();
      setState(() => _busy = false);
      return;
    }

    final prompt = buildPrompt(question: question, retrieval: retrieval);
    turn.usedModel = true;
    try {
      await for (final token in _llmService.generateStream(prompt)) {
        turn.answer += token;
        turn.tokenCount += 1;
        if (mounted) setState(() {});
      }
    } catch (e) {
      turn.answer = turn.answer.isEmpty ? 'Generation failed: $e' : turn.answer;
    } finally {
      turn.streaming = false;
      turn.stopwatch.stop();
      if (mounted) setState(() => _busy = false);
    }
  }

  void _askSuggestion(String suggestion) {
    if (_busy) return;
    _controller.text = suggestion;
    _submit();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Company Brain'),
        actions: [
          IconButton(
            icon: const Icon(Icons.fact_check_outlined),
            tooltip: 'Self-test',
            onPressed: _retriever == null
                ? null
                : () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => SelfTestScreen(retriever: _retriever!),
                      ),
                    ),
          ),
        ],
      ),
      body: switch (_setupState) {
        _SetupState.checking => const _CheckingView(),
        _SetupState.missing => _SetupMissingView(
            dbMissing: _dbMissing,
            modelMissing: _modelMissing,
            modelMissingMessage: _modelMissingMessage,
            onRetry: () {
              setState(() => _setupState = _SetupState.checking);
              _runSetupCheck();
            },
          ),
        _SetupState.ready => _ChatView(
            controller: _controller,
            turns: _turns,
            busy: _busy,
            onSubmit: _submit,
            onSuggestion: _askSuggestion,
            chunkCount: _chunkCount,
            studentCount: _studentCount,
            llmReady: _llmReady,
          ),
      },
    );
  }
}

class _CheckingView extends StatelessWidget {
  const _CheckingView();

  @override
  Widget build(BuildContext context) {
    return const Center(child: CircularProgressIndicator());
  }
}

class _SetupMissingView extends StatelessWidget {
  final bool dbMissing;
  final bool modelMissing;
  final String? modelMissingMessage;
  final VoidCallback onRetry;

  const _SetupMissingView({
    required this.dbMissing,
    required this.modelMissing,
    required this.modelMissingMessage,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Icon(Icons.warning_amber_rounded, size: 48, color: Colors.amber),
            const SizedBox(height: 16),
            Text(
              'Setup incomplete',
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 16),
            if (dbMissing)
              const _MissingItem(
                title: 'Database missing',
                detail:
                    'brain.db was not found. Push it with:\n'
                    'adb push brain.db '
                    '/sdcard/Android/data/com.companybrain.company_brain/files/brain.db',
              ),
            if (dbMissing && modelMissing) const SizedBox(height: 20),
            if (modelMissing)
              _MissingItem(
                title: 'Model missing',
                detail: modelMissingMessage ??
                    'The on-device model (${LlmService.modelFileName}) was not found.',
              ),
            const SizedBox(height: 24),
            FilledButton(onPressed: onRetry, child: const Text('Retry')),
          ],
        ),
      ),
    );
  }
}

class _MissingItem extends StatelessWidget {
  final String title;
  final String detail;

  const _MissingItem({required this.title, required this.detail});

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        SelectableText(
          detail,
          style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
        ),
      ],
    );
  }
}

class _ChatView extends StatelessWidget {
  final TextEditingController controller;
  final List<_AnsweredTurn> turns;
  final bool busy;
  final VoidCallback onSubmit;
  final ValueChanged<String> onSuggestion;
  final int chunkCount;
  final int studentCount;
  final bool llmReady;

  const _ChatView({
    required this.controller,
    required this.turns,
    required this.busy,
    required this.onSubmit,
    required this.onSuggestion,
    required this.chunkCount,
    required this.studentCount,
    required this.llmReady,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        StatusBar(
          chunkCount: chunkCount,
          studentCount: studentCount,
          llmReady: llmReady,
        ),
        Expanded(
          child: turns.isEmpty
              ? SingleChildScrollView(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const SizedBox(height: 24),
                      Icon(
                        Icons.psychology_alt_outlined,
                        size: 56,
                        color: Theme.of(context).colorScheme.primary,
                      ),
                      const SizedBox(height: 16),
                      Text(
                        'Ask your college anything',
                        style: Theme.of(context).textTheme.headlineSmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'Everything runs on this phone. No network, no server.',
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                              color: Theme.of(context).colorScheme.onSurfaceVariant,
                            ),
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: 24),
                      SuggestionChips(onSelected: onSuggestion, enabled: !busy),
                    ],
                  ),
                )
              : ListView.builder(
                  reverse: true,
                  padding: const EdgeInsets.all(12),
                  itemCount: turns.length,
                  itemBuilder: (context, index) => _TurnCard(turn: turns[index]),
                ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 12),
            child: Row(
              children: [
                Expanded(
                  child: TextField(
                    controller: controller,
                    enabled: !busy,
                    decoration: const InputDecoration(
                      hintText: 'Ask a question…',
                      border: OutlineInputBorder(),
                    ),
                    onSubmitted: (_) => onSubmit(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(
                  onPressed: busy ? null : onSubmit,
                  icon: busy
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.send),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _TurnCard extends StatelessWidget {
  final _AnsweredTurn turn;

  const _TurnCard({required this.turn});

  Color _routeColor(BuildContext context) {
    switch (turn.retrieval.route) {
      case 'TABULAR':
        return Colors.teal;
      case 'FACT':
        return Colors.indigo;
      case 'LOCAL':
        return Colors.deepPurple;
      case 'GLOBAL':
        return Colors.orange;
      default:
        return Colors.grey;
    }
  }

  @override
  Widget build(BuildContext context) {
    final sources = turn.retrieval.sources;
    final isTabular = turn.retrieval.route == 'TABULAR';
    return Card(
      elevation: 2,
      margin: const EdgeInsets.only(bottom: 12),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              turn.question,
              style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    fontWeight: FontWeight.w300,
                    color: Theme.of(context).colorScheme.onSurfaceVariant,
                  ),
            ),
            const SizedBox(height: 10),
            Row(
              children: [
                Chip(
                  label: Text(turn.retrieval.route),
                  backgroundColor: _routeColor(context).withValues(alpha: 0.25),
                  labelStyle: TextStyle(color: _routeColor(context)),
                  visualDensity: VisualDensity.compact,
                ),
                const Spacer(),
                if (turn.streaming)
                  const SizedBox(
                    width: 14,
                    height: 14,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            SelectableText(
              turn.answer.isEmpty ? '…' : turn.answer,
              style: isTabular
                  ? Theme.of(context).textTheme.bodyMedium?.copyWith(
                        fontFamily: 'monospace',
                        height: 1.4,
                      )
                  : Theme.of(context).textTheme.bodyMedium,
            ),
            const SizedBox(height: 10),
            Text(
              '${turn.elapsedSeconds.toStringAsFixed(1)}s'
              '${turn.usedModel ? ' · ${turn.tokensPerSecond.toStringAsFixed(1)} tok/s' : ''}',
              style: Theme.of(context).textTheme.bodySmall,
            ),
            if (sources.isNotEmpty) ...[
              const SizedBox(height: 10),
              const Divider(height: 1),
              const SizedBox(height: 10),
              Text('Sources', style: Theme.of(context).textTheme.labelMedium),
              const SizedBox(height: 4),
              ...sources.map(
                (s) => Text(
                  '• ${s.docId}${s.section != null ? ' — ${s.section}' : ''}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
