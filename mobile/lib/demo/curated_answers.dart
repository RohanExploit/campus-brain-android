// Curated answer layer for known fee-related question shapes.
//
// Every figure below is transcribed verbatim from the named source
// document -- nothing here is invented, rounded, or "improved". This layer
// exists because a small on-device model asked to read a number out of a
// markdown table gets it wrong often enough to matter (the same reasoning
// behind retrieval/sql_templates.py's deterministic SQL answers on the
// Python backend). A curated hit is always shown labelled as a curated
// answer and cited to its source document and section -- never presented
// as freshly generated prose.
library;

/// One pre-written answer for a known question shape.
class CuratedAnswer {
  /// The answer text, verbatim from the source document.
  final String answer;

  /// The source document this answer was transcribed from.
  final String sourceDoc;

  /// The section within [sourceDoc] the figures came from.
  final String sourceSection;

  /// Lowercase trigger phrases. A question matches this entry if every
  /// word of any one phrase appears in the (lowercased) question, on word
  /// boundaries.
  final List<String> matchAny;

  const CuratedAnswer({
    required this.answer,
    required this.sourceDoc,
    required this.sourceSection,
    required this.matchAny,
  });
}

const String _iceastiTable = '''
ICEASTI 2026 registration fees:
- Diploma / UG / PG Student / Research Scholar: Rs 1500/-
- Academic and Corporate: Rs 2000/-
- International Author: 80 USD
- Conference Attendee: Rs 1000/- or 40 USD''';

const String _icetisTable = '''
ICETIS 2026 registration fees (includes breakfast and lunch):
- Research Scholars / PG Students / Faculty: 3000 INR (Indian) / 35 USD (Foreign)
- UG Students: 2500 INR (Indian) / 30 USD (Foreign)
- Academic Researchers / Industry Persons: 3500 INR (Indian) / 40 USD (Foreign)
- Participants (Non-Authors): 1000 INR (Indian) / 12 USD (Foreign)''';

/// Curated answers, checked in order. The list is intentionally small and
/// specific: broad or ambiguous questions get a curated answer that says so
/// plainly, rather than silently picking one conference's numbers.
// NOTE ON ORDERING: entries are checked in list order and the first match
// wins, so the specific ICEASTI/ICETIS/topic entries below are listed
// BEFORE the ambiguous general fee entry at the end of this list. A
// question like "what is the ICETIS registration fee" must resolve to the
// ICETIS-specific entry, not the ambiguous ICEASTI+ICETIS one, even though
// both entries' trigger words are present in the question.
const List<CuratedAnswer> curatedAnswers = [
  // ICEASTI fees specifically.
  CuratedAnswer(
    answer: _iceastiTable,
    sourceDoc: 'DOC-20260212-WA0018..md',
    sourceSection: 'REGISTRATION FEES',
    matchAny: [
      'iceasti fee',
      'iceasti registration',
      'iceasti fees',
    ],
  ),

  // ICETIS fees specifically.
  CuratedAnswer(
    answer: _icetisTable,
    sourceDoc: 'ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'REGISTRATION FEES',
    matchAny: [
      'icetis fee',
      'icetis registration',
      'icetis fees',
    ],
  ),

  // UG student fee (ICETIS).
  CuratedAnswer(
    answer:
        'UG Students registration fee for ICETIS 2026: 2500 INR (Indian) '
        'or 30 USD (Foreign). Includes breakfast and lunch.',
    sourceDoc: 'ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'REGISTRATION FEES',
    matchAny: [
      'ug students fee',
      'ug student fee',
      'how much for ug',
      'undergraduate students fee',
    ],
  ),

  // International / foreign delegate fee (ICETIS).
  CuratedAnswer(
    answer:
        'Foreign delegate fees for ICETIS 2026:\n'
        '- Research Scholars / PG Students / Faculty: 35 USD\n'
        '- UG Students: 30 USD\n'
        '- Academic Researchers / Industry Persons: 40 USD\n'
        '- Participants (Non-Authors): 12 USD\n\n'
        'For ICEASTI 2026, International Author fee is 80 USD.',
    sourceDoc: 'ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'REGISTRATION FEES',
    matchAny: [
      'foreign delegate fee',
      'international delegate fee',
      'foreign author fee',
      'international author fee',
    ],
  ),

  // Overlength paper charge (ICETIS).
  CuratedAnswer(
    answer:
        'Papers are limited to 6 pages. Overlength papers incur an '
        'additional charge of INR 500 per page.',
    sourceDoc: 'ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'PAPER FORMAT',
    matchAny: [
      'overlength page charge',
      'overlength paper charge',
      'extra page charge',
      'additional page fee',
    ],
  ),

  // Important dates / registration deadline (ICETIS).
  CuratedAnswer(
    answer:
        'ICETIS 2026 important dates:\n'
        '- Submission opens: 25 Jan 2026\n'
        '- Submission deadline: 20 Feb 2026\n'
        '- Acceptance notification: 28 Feb 2026\n'
        '- Camera-ready: 10 March 2026\n'
        '- Last date of registration: 12 March 2026\n'
        '- Conference: 23-24 March 2026',
    sourceDoc: 'ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'IMPORTANT DATES',
    matchAny: [
      'important dates',
      'registration deadline',
      'submission deadline',
      'when is the conference',
      'camera ready deadline',
    ],
  ),

  // College admission fee receipt.
  CuratedAnswer(
    answer:
        'College admission fee receipt (Receipt no. 1137, dated 13-Aug-2025, '
        'academic year TY CSE):\n'
        '- Registration & College Admission Form Fee: Rs 300.00\n'
        '- Development Fee: Rs 6,572.00\n'
        '- Training Charges: Rs 2,000.00\n'
        '- Total: Rs 8,872.00',
    sourceDoc: 'Rutuja fees.md',
    sourceSection: 'Receipt',
    matchAny: [
      'admission fee total',
      'admission receipt',
      'college admission fee',
      'rutuja fees',
    ],
  ),

  // Ambiguous general fee question -- the corpus has TWO different fee
  // schedules (ICEASTI and ICETIS). Presenting both, clearly labelled, is
  // the honest answer; picking one would blend two sources together. Kept
  // LAST in this list so a specific entry above (e.g. "ICETIS fee") wins
  // first when the question actually names one conference.
  CuratedAnswer(
    answer:
        'The corpus contains two different registration fee schedules, for '
        'two different conferences:\n\n$_iceastiTable\n\n$_icetisTable\n\n'
        'Ask about ICEASTI or ICETIS specifically for a single answer.',
    sourceDoc:
        'DOC-20260212-WA0018..md and ICETIS-2026_Conference_Brochure.md',
    sourceSection: 'REGISTRATION FEES',
    matchAny: [
      'fee structure',
      'registration fee',
      'what is the fee',
      'conference fee',
      'how much is the fee',
    ],
  ),
];

/// Returns the word-boundary lowercase tokens of [text].
List<String> _words(String text) {
  return text
      .toLowerCase()
      .split(RegExp(r'[^a-z0-9]+'))
      .where((w) => w.isNotEmpty)
      .toList();
}

/// Looks up a curated answer for [question], case-insensitively, using
/// word-boundary matching (so "fee" never matches inside "coffee").
///
/// A [CuratedAnswer] matches if every word of any one of its [matchAny]
/// phrases is present among the question's words. Returns null on no
/// match -- callers must then fall through to the normal retrieval path.
CuratedAnswer? lookup(String question) {
  final questionWords = _words(question).toSet();
  if (questionWords.isEmpty) return null;

  for (final entry in curatedAnswers) {
    for (final phrase in entry.matchAny) {
      final phraseWords = _words(phrase);
      if (phraseWords.isEmpty) continue;
      if (phraseWords.every(questionWords.contains)) {
        return entry;
      }
    }
  }
  return null;
}
