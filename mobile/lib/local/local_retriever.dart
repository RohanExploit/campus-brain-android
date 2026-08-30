import 'dart:math';
import 'dart:typed_data';

import 'brain_db.dart';
import 'models.dart';
import 'tabular_queries.dart';

/// Routes a natural-language query to one of the retrieval routes and
/// returns the context a downstream on-device language model should
/// answer from.
///
/// Routing is deterministic and keyword-based (no ML classifier on
/// device):
///   - a 10-15 digit token in the query -> `studentByRoll`, route TABULAR
///   - an aggregate word ("how many", "count", "percentage", "percent",
///     "top", "highest", "lowest", "average", "list") combined with a
///     domain word ("pass", "fail", "student", "sgpa", "subject",
///     "backlog") -> the matching tabular query, route TABULAR
///   - otherwise -> FACT, full-text search over `chunks`
class LocalRetriever {
  final BrainDb brainDb;
  late final TabularQueries _tabular;

  static final RegExp _rollNumberPattern = RegExp(r'\b\d{10,15}\b');

  static const List<String> _aggregateWords = [
    'how many',
    'count',
    'percentage',
    'percent',
    'top',
    'highest',
    'lowest',
    'average',
    'list',
  ];

  static const List<String> _domainWords = [
    'pass',
    'fail',
    'student',
    'sgpa',
    'subject',
    'backlog',
  ];

  // FTS5 operator characters that must be stripped or escaped before a
  // user query reaches MATCH, otherwise an ordinary apostrophe, hyphen, or
  // the bare word AND/OR/NOT/NEAR throws a syntax error at runtime.
  static final RegExp _ftsOperatorChars = RegExp(r'["*:\-()^]');
  static const Set<String> _ftsKeywords = {'AND', 'OR', 'NOT', 'NEAR'};

  LocalRetriever(this.brainDb) {
    _tabular = TabularQueries(brainDb.db);
  }

  Future<RetrievalResult> retrieve(String query) async {
    final rollMatch = _rollNumberPattern.firstMatch(query);
    if (rollMatch != null) {
      final roll = rollMatch.group(0)!;
      final answer = await _tabular.studentByRoll(roll);
      return RetrievalResult(
        route: 'TABULAR',
        context: answer,
        sources: const [],
        debugSql: 'studentByRoll("$roll")',
      );
    }

    final lower = query.toLowerCase();
    final hasAggregate = _aggregateWords.any((w) => lower.contains(w));
    final hasDomain = _domainWords.any((w) => lower.contains(w));

    if (hasAggregate && hasDomain) {
      return _routeTabular(lower, query);
    }

    return _routeFact(query);
  }

  Future<RetrievalResult> _routeTabular(String lower, String original) async {
    // Small integers only (roll numbers are handled separately above and
    // never reach this branch).
    final numMatch = RegExp(r'\b\d{1,3}\b').firstMatch(original);
    final n = numMatch != null ? int.parse(numMatch.group(0)!) : null;

    String answer;
    String debugSql;

    if (lower.contains('pass') &&
        (lower.contains('percentage') || lower.contains('percent'))) {
      answer = await _tabular.passPercentage();
      debugSql = 'passPercentage()';
    } else if (lower.contains('fail') &&
        (lower.contains('percentage') || lower.contains('percent'))) {
      answer = await _tabular.failPercentage();
      debugSql = 'failPercentage()';
    } else if (lower.contains('backlog') ||
        (lower.contains('fail') &&
            (lower.contains('how many') ||
                lower.contains('count') ||
                lower.contains('list')))) {
      final threshold = n ?? 1;
      answer = await _tabular.studentsFailedAtLeast(threshold);
      debugSql = 'studentsFailedAtLeast($threshold)';
    } else if (lower.contains('subject') &&
        (lower.contains('fail') || lower.contains('backlog'))) {
      answer = await _tabular.subjectFailureCounts();
      debugSql = 'subjectFailureCounts()';
    } else if (lower.contains('top') || lower.contains('highest')) {
      final limit = n ?? 5;
      answer = await _tabular.topByScgpa(limit);
      debugSql = 'topByScgpa($limit)';
    } else {
      // Generic fallback for aggregate+domain queries that don't match a
      // more specific pattern above.
      answer = await _tabular.passPercentage();
      debugSql = 'passPercentage() [fallback]';
    }

    return RetrievalResult(
      route: 'TABULAR',
      context: answer,
      sources: const [],
      debugSql: debugSql,
    );
  }

  Future<RetrievalResult> _routeFact(String query) async {
    final chunks = await searchText(query);
    final context = _buildContext(chunks);
    return RetrievalResult(
      route: 'FACT',
      context: context,
      sources: chunks,
      debugSql: null,
    );
  }

  /// Full-text search over `chunks` via FTS5, ordered by BM25 relevance
  /// (best match first).
  Future<List<RetrievedChunk>> searchText(String query, {int topK = 5}) async {
    final matchQuery = _sanitizeForFts(query);
    if (matchQuery.isEmpty) {
      return const [];
    }

    final rows = await brainDb.db.rawQuery(
      '''
      SELECT c.id AS id, c.doc_id AS doc_id, c.section AS section,
             c.content AS content, bm25(chunks_fts) AS bm25_rank
      FROM chunks_fts
      JOIN chunks c ON c.id = chunks_fts.rowid
      WHERE chunks_fts MATCH ?
      ORDER BY bm25_rank ASC
      LIMIT ?
      ''',
      [matchQuery, topK],
    );

    return rows
        .map(
          (r) => RetrievedChunk(
            id: r['id'] as int,
            docId: r['doc_id'] as String,
            section: r['section'] as String?,
            content: r['content'] as String,
            // bm25 returns a "lower is better" score; negate so a higher
            // RetrievedChunk.score means a better match, consistent with
            // searchVector's cosine similarity.
            score: -((r['bm25_rank'] as num).toDouble()),
          ),
        )
        .toList();
  }

  /// Strips or escapes FTS5 operator characters (", *, :, -, (, ), ^) and
  /// drops the bare keywords AND/OR/NOT/NEAR, then quotes each remaining
  /// token so it is treated as a literal string rather than an operator.
  /// Returns an empty string if nothing usable remains, so callers can
  /// short-circuit instead of sending an empty MATCH expression (itself a
  /// syntax error).
  ///
  /// Tokens are joined with an explicit OR, not FTS5's implicit AND: a
  /// natural-language question ("What is photosynthesis conversion
  /// efficiency?") contains filler words ("what", "is") that plain
  /// content chunks won't literally contain, so requiring every token to
  /// match would return zero rows for almost every real question. BM25
  /// still ranks chunks that match more/rarer terms higher.
  static String _sanitizeForFts(String query) {
    final cleaned = query.replaceAll(_ftsOperatorChars, ' ');
    final tokens = cleaned
        .split(RegExp(r'\s+'))
        .map((t) => t.replaceAll(RegExp(r'[?!.,;]+$'), ''))
        .where((t) => t.isNotEmpty)
        .where((t) => !_ftsKeywords.contains(t.toUpperCase()));
    return tokens.map((t) => '"$t"').join(' OR ');
  }

  /// Brute-force cosine similarity search over all embeddings. 774 vectors
  /// is small enough that this is the correct design; no ANN index.
  Future<List<RetrievedChunk>> searchVector(
    List<double> queryVec, {
    int topK = 5,
  }) async {
    final rows = await brainDb.db.rawQuery('''
      SELECT c.id AS id, c.doc_id AS doc_id, c.section AS section,
             c.content AS content, e.vec AS vec
      FROM embeddings e
      JOIN chunks c ON c.id = e.chunk_id
    ''');

    final scored = <RetrievedChunk>[];
    for (final row in rows) {
      final blob = row['vec'] as Uint8List;
      // The blob may be a view into a larger buffer with a nonzero
      // offset; Float32List.view requires that offset (and length) to be
      // expressed explicitly, or it can throw depending on how the
      // underlying buffer was allocated.
      final vec = Float32List.view(
        blob.buffer,
        blob.offsetInBytes,
        blob.length ~/ 4,
      );
      final score = _similarity(queryVec, vec);
      scored.add(
        RetrievedChunk(
          id: row['id'] as int,
          docId: row['doc_id'] as String,
          section: row['section'] as String?,
          content: row['content'] as String,
          score: score,
        ),
      );
    }

    scored.sort((a, b) => b.score.compareTo(a.score));
    return scored.take(topK).toList();
  }

  /// Cosine similarity between [queryVec] and a stored embedding [stored].
  ///
  /// The exporter L2-normalises every stored vector before writing it to
  /// `embeddings.vec` (verified on the real bundle: norm == 1.0), so
  /// cosine similarity reduces to a plain dot product on the stored side.
  /// We still normalise the incoming query vector here, since a future
  /// on-device embedder is not guaranteed to hand back unit vectors.
  static double _similarity(List<double> queryVec, Float32List stored) {
    final len = queryVec.length < stored.length ? queryVec.length : stored.length;
    double queryNormSq = 0;
    for (final v in queryVec) {
      queryNormSq += v * v;
    }
    if (queryNormSq == 0) return 0.0;
    final queryNorm = sqrt(queryNormSq);

    double dot = 0;
    for (var i = 0; i < len; i++) {
      dot += (queryVec[i] / queryNorm) * stored[i];
    }
    return dot;
  }

  /// Joins chunk contents with a blank line, capped at 4000 characters so
  /// the assembled context fits a small on-device model's context window.
  /// Stops *before* adding the chunk that would push the total over the
  /// cap, so the cut always lands on a chunk boundary, never mid-chunk.
  static String _buildContext(List<RetrievedChunk> chunks) {
    const cap = 4000;
    final buffer = StringBuffer();
    for (final chunk in chunks) {
      final addition = buffer.isEmpty ? chunk.content : '\n\n${chunk.content}';
      if (buffer.length + addition.length > cap) {
        break;
      }
      buffer.write(addition);
    }
    return buffer.toString();
  }
}
