/// Fixed set of "golden" question/answer pairs used by the built-in
/// self-test screen (see `screens/self_test_screen.dart`).
///
/// These are NOT canned demo answers. Every value below was verified
/// directly against the shipped corpus (brain.db) at build time, and each
/// case's [GoldenQaCase.expectSubstring] is used *only* as a comparison
/// target inside the self-test screen -- to decide pass/fail -- and is
/// never displayed as if it were the retrieval engine's own output. The
/// screen always calls the real [LocalRetriever] live and shows the actual
/// returned route and answer text; the expected values here exist solely to
/// grade that live answer.
library;

/// A single self-test case: a real question, the route the router is
/// expected to pick, and a substring the live answer must contain
/// (case-insensitive) to count as a pass.
class GoldenQaCase {
  final String question;
  final String expectedRoute;
  final String expectSubstring;

  /// Short note on what this specific case proves, e.g. that a numeric
  /// threshold is a genuine SQL parameter rather than a memorised value.
  final String why;

  const GoldenQaCase({
    required this.question,
    required this.expectedRoute,
    required this.expectSubstring,
    required this.why,
  });
}

/// The fixed self-test suite, run in order.
const List<GoldenQaCase> goldenQaCases = [
  GoldenQaCase(
    question: 'How many students failed at least 2 subjects',
    expectedRoute: 'TABULAR',
    expectSubstring: '16',
    why: 'Threshold=2 is a real SQL parameter, not a memorised answer.',
  ),
  GoldenQaCase(
    question: 'How many students failed at least 3 subjects',
    expectedRoute: 'TABULAR',
    expectSubstring: '12',
    why: 'Threshold=3 must return a different count than threshold=2.',
  ),
  GoldenQaCase(
    question: 'How many students failed at least 4 subjects',
    expectedRoute: 'TABULAR',
    expectSubstring: '7',
    why: 'Threshold=4 must return a different count than threshold=3.',
  ),
  GoldenQaCase(
    question: 'How many students failed at least 5 subjects',
    expectedRoute: 'TABULAR',
    expectSubstring: '2',
    why: 'Threshold=5 must return a different count than threshold=4.',
  ),
  GoldenQaCase(
    question: 'What percentage of students passed',
    expectedRoute: 'TABULAR',
    expectSubstring: '90.5',
    why: 'Pass percentage is computed live from the students table.',
  ),
  GoldenQaCase(
    question: 'What percentage of students failed',
    expectedRoute: 'TABULAR',
    expectSubstring: '9.4',
    why: 'Fail percentage must not equal 100 minus a wrong pass figure.',
  ),
  GoldenQaCase(
    question: 'How many students scored above 8 SGPA',
    expectedRoute: 'TABULAR',
    expectSubstring: '89',
    why: 'SGPA threshold of 8 is a real SQL parameter.',
  ),
  GoldenQaCase(
    question: 'How many students scored above 7 SGPA',
    expectedRoute: 'TABULAR',
    expectSubstring: '231',
    why: 'SGPA threshold of 7 must return a different count than 8.',
  ),
  GoldenQaCase(
    question: 'Which subject has the most failures',
    expectedRoute: 'TABULAR',
    expectSubstring: 'BTCOC502',
    why: 'Aggregation across student_subjects, not a lookup table.',
  ),
  GoldenQaCase(
    question: 'Top 10 students by SGPA',
    expectedRoute: 'TABULAR',
    expectSubstring: '8.82',
    why: 'Ranking query over the real students table.',
  ),
  GoldenQaCase(
    question: '2267571242025',
    expectedRoute: 'TABULAR',
    expectSubstring: 'HAJARE NIKHIL RAJENDRA',
    why: 'A bare roll number must route to the exact student lookup.',
  ),
  GoldenQaCase(
    question: '23063181242004',
    expectedRoute: 'TABULAR',
    expectSubstring: 'JAGTAP ANANT TANAJI',
    why: 'A different roll number must resolve to a different student.',
  ),
  GoldenQaCase(
    question: 'What is the fee structure',
    expectedRoute: 'FACT',
    expectSubstring: '1500',
    why: 'Non-tabular question must route to full-text search over chunks.',
  ),
];
