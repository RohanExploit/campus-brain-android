// Data models shared by the on-device retrieval layer.

/// A single chunk retrieved from the local database, with the score that
/// ranked it (BM25 rank for FTS, cosine similarity for vector search, or
/// 0.0 for chunks that were not ranked, e.g. tabular results).
class RetrievedChunk {
  final int id;
  final String docId;
  final String? section;
  final String content;
  final double score;

  const RetrievedChunk({
    required this.id,
    required this.docId,
    required this.section,
    required this.content,
    required this.score,
  });

  @override
  String toString() =>
      'RetrievedChunk(id: $id, docId: $docId, section: $section, '
      'score: $score, content: ${content.length} chars)';
}

/// The result of a single retrieval call: which route answered it, the
/// assembled context text to feed a downstream language model, the chunks
/// (if any) that made up that context, and optionally the SQL used (for
/// debugging tabular routes).
class RetrievalResult {
  final String route;
  final String context;
  final List<RetrievedChunk> sources;
  final String? debugSql;

  const RetrievalResult({
    required this.route,
    required this.context,
    required this.sources,
    this.debugSql,
  });

  @override
  String toString() =>
      'RetrievalResult(route: $route, sources: ${sources.length}, '
      'context: ${context.length} chars)';
}
