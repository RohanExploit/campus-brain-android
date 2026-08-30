// Pure prompt-construction for the on-device language model. No I/O here --
// this file must stay unit-testable without a model, a device, or a
// database, so every dependency is passed in as a plain value.

import '../local/models.dart';

/// The exact sentence the model must produce -- and only this sentence,
/// nothing appended -- when the supplied context does not contain the
/// answer. This is the same abstention behaviour the desktop system is
/// measured on; it must not regress on the phone. Kept as a constant so the
/// UI layer and tests can compare against the identical string used in the
/// prompt.
const String kAbstentionSentence =
    "I don't have enough information to answer that.";

/// The maximum number of characters of retrieval context that will be sent
/// to the model. [LocalRetriever] already caps assembled FACT/LOCAL/GLOBAL
/// context at 4000 characters (see `_buildContext` in local_retriever.dart),
/// so this is normally a no-op -- it exists as a defensive second cap here
/// so `buildPrompt` is safe to call directly, in tests or otherwise, with
/// context that was not produced by [LocalRetriever].
const int kMaxContextChars = 4000;

/// Builds the prompt sent to the on-device model for a FACT, LOCAL, or
/// GLOBAL retrieval result.
///
/// TABULAR results must never reach this function. They come straight out
/// of SQL in [TabularQueries] -- exact, already-computed figures, not
/// something a 2B on-device model should be asked to rephrase or
/// "double-check". Routing a TABULAR result through the model can only make
/// it slower and risk the model inventing a different number. The caller
/// (the ask screen) is responsible for detecting `retrieval.route ==
/// 'TABULAR'` and returning `retrieval.context` verbatim, with no call to
/// `buildPrompt` and no call to the model at all. This function documents
/// that contract with an assertion, so a future call site that wires it
/// wrong fails loudly in debug/test builds rather than silently sending a
/// SQL answer through the model.
///
/// The prompt is deliberately short: a long system preamble costs tokens
/// and latency for nothing on a phone-sized model and context window.
String buildPrompt({
  required String question,
  required RetrievalResult retrieval,
}) {
  assert(
    retrieval.route != 'TABULAR',
    'buildPrompt must not be called for TABULAR results: they are already '
    'exact SQL answers from TabularQueries. The caller must return '
    "retrieval.context verbatim and must not invoke the model at all.",
  );

  final context = retrieval.context.length > kMaxContextChars
      ? retrieval.context.substring(0, kMaxContextChars)
      : retrieval.context;

  return 'Answer the question using only the information in the context '
      "below. If the context does not contain the answer, respond with "
      'exactly this sentence and nothing else: "$kAbstentionSentence" '
      'Never estimate or invent a figure that is not in the context.\n\n'
      'Context:\n'
      '$context\n\n'
      'Question: $question\n\n'
      'Answer:';
}
