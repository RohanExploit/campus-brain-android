import 'package:sqflite/sqflite.dart';

/// Deterministic SQL answers over the `students` / `student_subjects`
/// tables. Every method returns a formatted, human-readable answer string
/// built from a single SQL query — no post-hoc filtering in Dart that
/// could silently change the denominator of a percentage.
class TabularQueries {
  final Database db;

  TabularQueries(this.db);

  /// Share of students whose `result` is not `'FAIL'`, computed over the
  /// *entire* students table (never over a subset already filtered to the
  /// status being counted — that would make numerator == denominator and
  /// the answer always exactly 100%).
  Future<String> passPercentage() async {
    final rows = await db.rawQuery('''
      SELECT 100.0 * COUNT(*) FILTER (WHERE result != 'FAIL') / NULLIF(COUNT(*), 0) AS pct,
             COUNT(*) AS total
      FROM students
    ''');
    final row = rows.first;
    final pct = (row['pct'] as num?)?.toDouble() ?? 0.0;
    final total = (row['total'] as num?)?.toInt() ?? 0;
    return 'Pass percentage: ${_fmtPct(pct)}% (out of $total students).';
  }

  /// The complement of [passPercentage], also computed over the full
  /// table.
  Future<String> failPercentage() async {
    final rows = await db.rawQuery('''
      SELECT 100.0 * COUNT(*) FILTER (WHERE result = 'FAIL') / NULLIF(COUNT(*), 0) AS pct,
             COUNT(*) AS total
      FROM students
    ''');
    final row = rows.first;
    final pct = (row['pct'] as num?)?.toDouble() ?? 0.0;
    final total = (row['total'] as num?)?.toInt() ?? 0;
    return 'Fail percentage: ${_fmtPct(pct)}% (out of $total students).';
  }

  /// Students with at least [n] subject rows whose `grade` is `'FF'`.
  /// Returns the count of matching students and, for each, their roll
  /// number and failure count, ordered descending by failure count.
  Future<String> studentsFailedAtLeast(int n) async {
    final rows = await db.rawQuery(
      '''
      SELECT ss.roll_no AS roll_no, s.name AS name, COUNT(*) AS ff_count
      FROM student_subjects ss
      LEFT JOIN students s ON s.roll_no = ss.roll_no
      WHERE ss.grade = 'FF'
      GROUP BY ss.roll_no
      HAVING COUNT(*) >= ?
      ORDER BY ff_count DESC, ss.roll_no ASC
      ''',
      [n],
    );

    if (rows.isEmpty) {
      return 'No students have $n or more FF grades.';
    }

    final lines = rows.map((r) {
      final roll = r['roll_no'];
      final name = r['name'];
      final count = r['ff_count'];
      return '$roll (${name ?? 'unknown'}): $count FF grade(s)';
    }).join('\n');

    return '${rows.length} student(s) have $n or more FF grades:\n$lines';
  }

  /// Top [limit] students by `sgpa` descending.
  Future<String> topByScgpa(int limit) async {
    final rows = await db.rawQuery(
      '''
      SELECT roll_no, name, sgpa
      FROM students
      WHERE sgpa IS NOT NULL
      ORDER BY sgpa DESC
      LIMIT ?
      ''',
      [limit],
    );

    if (rows.isEmpty) {
      return 'No students with an SGPA on record.';
    }

    final lines = rows.asMap().entries.map((e) {
      final i = e.key + 1;
      final r = e.value;
      return '$i. ${r['name']} (${r['roll_no']}) — SGPA ${r['sgpa']}';
    }).join('\n');

    return 'Top ${rows.length} students by SGPA:\n$lines';
  }

  /// Full record for a single student plus their subject rows.
  Future<String> studentByRoll(String roll) async {
    final studentRows = await db.query(
      'students',
      where: 'roll_no = ?',
      whereArgs: [roll],
    );

    if (studentRows.isEmpty) {
      return 'No student found with roll number $roll.';
    }

    final s = studentRows.first;
    final subjectRows = await db.query(
      'student_subjects',
      where: 'roll_no = ?',
      whereArgs: [roll],
    );

    final subjectLines = subjectRows
        .map(
          (r) =>
              '  ${r['subject_code']}: grade ${r['grade']}'
              ' (${r['grade_point']} pts, credit ${r['credit']})',
        )
        .join('\n');

    return 'Student ${s['roll_no']}: ${s['name']}\n'
        '  SGPA: ${s['sgpa']}\n'
        '  Estimated SGPA: ${s['estimated_sgpa']}\n'
        '  Total marks: ${s['total_marks']}\n'
        '  Result: ${s['result']}\n'
        '  Is supply: ${s['is_supply']}\n'
        '  Seat cancelled: ${s['seat_cancelled']}\n'
        'Subjects:\n$subjectLines';
  }

  /// Case-insensitive match where every whitespace-separated token in [q]
  /// appears in `name`, in any order (names are stored uppercase as
  /// "SURNAME NAME MIDDLE").
  Future<String> studentByName(String q) async {
    final tokens = q
        .trim()
        .split(RegExp(r'\s+'))
        .where((t) => t.isNotEmpty)
        .map((t) => t.toUpperCase())
        .toList();

    if (tokens.isEmpty) {
      return 'No name provided.';
    }

    final whereClause = tokens.map((_) => 'UPPER(name) LIKE ?').join(' AND ');
    final args = tokens.map((t) => '%$t%').toList();

    final rows = await db.rawQuery(
      'SELECT * FROM students WHERE $whereClause',
      args,
    );

    if (rows.isEmpty) {
      return 'No student found matching "$q".';
    }

    final lines = rows
        .map((r) => '${r['roll_no']}: ${r['name']} (SGPA ${r['sgpa']})')
        .join('\n');

    return '${rows.length} student(s) match "$q":\n$lines';
  }

  /// Per-`subject_code` count of `grade = 'FF'`, descending.
  Future<String> subjectFailureCounts() async {
    final rows = await db.rawQuery('''
      SELECT subject_code, COUNT(*) AS ff_count
      FROM student_subjects
      WHERE grade = 'FF'
      GROUP BY subject_code
      ORDER BY ff_count DESC, subject_code ASC
    ''');

    if (rows.isEmpty) {
      return 'No FF grades on record.';
    }

    final lines = rows
        .map((r) => '${r['subject_code']}: ${r['ff_count']} failure(s)')
        .join('\n');

    return 'Subject failure counts:\n$lines';
  }

  static String _fmtPct(double pct) => pct.toStringAsFixed(2);
}
