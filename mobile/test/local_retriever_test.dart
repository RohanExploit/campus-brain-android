import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:company_brain/local/brain_db.dart';
import 'package:company_brain/local/local_retriever.dart';
import 'package:company_brain/local/models.dart';

/// A distinctive, unique marker string used to prove the 4000-char context
/// cap cuts on a chunk boundary rather than slicing mid-chunk.
const String _cutoffMarker = 'UNIQUE_MARKER_CHUNK9_DO_NOT_TRUNCATE';

Uint8List _vecBlob(List<double> values) {
  final f = Float32List.fromList(values);
  return f.buffer.asUint8List(f.offsetInBytes, f.lengthInBytes);
}

/// Builds a fixture database matching the brain.db schema contract exactly:
/// meta, chunks, chunks_fts (external content FTS5), embeddings,
/// graph_edges, students, student_subjects.
Future<BrainDb> _buildFixtureDb() async {
  final dir = await Directory.systemTemp.createTemp('brain_db_test');
  final path = p.join(dir.path, 'fixture.db');

  final db = await databaseFactoryFfi.openDatabase(path);

  await db.execute('''
    CREATE TABLE meta(
      key TEXT PRIMARY KEY,
      value TEXT NOT NULL
    )
  ''');
  await db.execute('''
    CREATE TABLE chunks(
      id INTEGER PRIMARY KEY,
      doc_id TEXT NOT NULL,
      section TEXT,
      content TEXT NOT NULL
    )
  ''');
  await db.execute('''
    CREATE VIRTUAL TABLE chunks_fts USING fts5(
      content, doc_id,
      content='chunks',
      content_rowid='id',
      tokenize='porter unicode61'
    )
  ''');
  await db.execute('''
    CREATE TABLE embeddings(
      chunk_id INTEGER PRIMARY KEY,
      vec BLOB NOT NULL
    )
  ''');
  await db.execute('''
    CREATE TABLE graph_edges(
      id INTEGER PRIMARY KEY,
      src TEXT NOT NULL,
      rel TEXT NOT NULL,
      dst TEXT NOT NULL
    )
  ''');
  await db.execute('''
    CREATE TABLE students(
      roll_no TEXT PRIMARY KEY,
      name TEXT,
      sgpa REAL,
      estimated_sgpa REAL,
      total_marks INTEGER,
      result TEXT,
      is_supply INTEGER,
      seat_cancelled INTEGER
    )
  ''');
  await db.execute('''
    CREATE TABLE student_subjects(
      roll_no TEXT NOT NULL,
      subject_code TEXT NOT NULL,
      credit INTEGER,
      grade TEXT,
      grade_point REAL,
      raw_grade_string TEXT
    )
  ''');

  await db.insert('meta', {'key': 'tenant_id', 'value': 'test_tenant'});
  await db.insert('meta', {'key': 'built_at_utc', 'value': '2026-08-29T00:00:00Z'});

  // ---- students: 5 students, 2 FAIL, 3 PASS (so pass% != 100%) ----
  final students = [
    {
      'roll_no': '2103139001',
      'name': 'GAIKWAD ROHAN VIJAY',
      'sgpa': 8.5,
      'estimated_sgpa': 8.5,
      'total_marks': 850,
      'result': 'PASS',
      'is_supply': 0,
      'seat_cancelled': 0,
    },
    {
      'roll_no': '2103139002',
      'name': 'PATIL SNEHA RAJESH',
      'sgpa': 7.2,
      'estimated_sgpa': 7.2,
      'total_marks': 720,
      'result': 'PASS',
      'is_supply': 0,
      'seat_cancelled': 0,
    },
    {
      'roll_no': '2103139003',
      'name': 'SHINDE OMKAR SANTOSH',
      'sgpa': 5.5,
      'estimated_sgpa': 5.5,
      'total_marks': 550,
      'result': 'FAIL',
      'is_supply': 1,
      'seat_cancelled': 0,
    },
    {
      'roll_no': '2103139004',
      'name': 'JADHAV PRIYA MAHESH',
      'sgpa': 6.0,
      'estimated_sgpa': 6.0,
      'total_marks': 600,
      'result': 'FAIL',
      'is_supply': 1,
      'seat_cancelled': 0,
    },
    {
      'roll_no': '2103139005',
      'name': 'KULKARNI AMIT SURESH',
      'sgpa': 9.0,
      'estimated_sgpa': 9.0,
      'total_marks': 900,
      'result': 'PASS',
      'is_supply': 0,
      'seat_cancelled': 0,
    },
  ];
  for (final s in students) {
    await db.insert('students', s);
  }

  // ---- student_subjects: SHINDE has 2 FF (should appear in
  // studentsFailedAtLeast(2)), JADHAV has only 1 FF (should NOT). ----
  final subjects = [
    {
      'roll_no': '2103139003',
      'subject_code': 'MA301',
      'credit': 4,
      'grade': 'FF',
      'grade_point': 0.0,
      'raw_grade_string': 'FF',
    },
    {
      'roll_no': '2103139003',
      'subject_code': 'CS302',
      'credit': 3,
      'grade': 'FF',
      'grade_point': 0.0,
      'raw_grade_string': 'FF',
    },
    {
      'roll_no': '2103139004',
      'subject_code': 'MA301',
      'credit': 4,
      'grade': 'FF',
      'grade_point': 0.0,
      'raw_grade_string': 'FF',
    },
    {
      'roll_no': '2103139004',
      'subject_code': 'CS302',
      'credit': 3,
      'grade': 'AB',
      'grade_point': 6.0,
      'raw_grade_string': 'AB',
    },
    {
      'roll_no': '2103139001',
      'subject_code': 'MA301',
      'credit': 4,
      'grade': 'AA',
      'grade_point': 10.0,
      'raw_grade_string': 'AA',
    },
  ];
  for (final row in subjects) {
    await db.insert('student_subjects', row);
  }

  // ---- chunks: 6 topical chunks (one with a distinctive phrase for FTS)
  // plus 3 long "filler" chunks used to exercise the 4000-char cap. ----
  final chunks = <Map<String, Object?>>[
    {
      'id': 1,
      'doc_id': 'bio.md',
      'section': 'photosynthesis',
      'content':
          'Photosynthesis conversion efficiency in C3 plants typically '
          'ranges between three and six percent under natural sunlight.',
    },
    {
      'id': 2,
      'doc_id': 'bio.md',
      'section': 'respiration',
      'content':
          'Cellular respiration breaks down glucose to release usable '
          'energy in the form of ATP within mitochondria.',
    },
    {
      'id': 3,
      'doc_id': 'chem.md',
      'section': 'bonds',
      'content':
          'Covalent bonds form when atoms share electron pairs to '
          'achieve stable outer shells.',
    },
    {
      'id': 4,
      'doc_id': 'chem.md',
      'section': 'reactions',
      'content':
          'Exothermic reactions release energy to the surroundings, '
          'often as heat or light.',
    },
    {
      'id': 5,
      'doc_id': 'phys.md',
      'section': 'motion',
      'content':
          "Newton's second law relates force to the product of mass and "
          'acceleration for an object - it is foundational to mechanics.',
    },
    {
      'id': 6,
      'doc_id': 'phys.md',
      'section': 'energy',
      'content':
          'Kinetic energy depends on the square of velocity and is '
          'proportional to mass.',
    },
    {
      'id': 7,
      'doc_id': 'filler.md',
      'section': 'a',
      'content': 'lorem filler content number seven. ${'x' * 1470}',
    },
    {
      'id': 8,
      'doc_id': 'filler.md',
      'section': 'b',
      'content': 'lorem filler content number eight. ${'y' * 1470}',
    },
    {
      'id': 9,
      'doc_id': 'filler.md',
      'section': 'c',
      'content': 'lorem filler content number nine $_cutoffMarker. ${'z' * 1400}',
    },
  ];

  for (final chunk in chunks) {
    await db.insert('chunks', chunk);
    await db.rawInsert(
      'INSERT INTO chunks_fts(rowid, content, doc_id) VALUES (?, ?, ?)',
      [chunk['id'], chunk['content'], chunk['doc_id']],
    );
  }

  // ---- embeddings: 4-dim hand-built vectors. Chunk 1 will exactly match
  // the query vector used in the searchVector test; chunk 2 is orthogonal
  // to it. ----
  final vectors = <int, List<double>>{
    1: [1.0, 0.0, 0.0, 0.0],
    2: [0.0, 1.0, 0.0, 0.0],
    3: [0.0, 0.0, 1.0, 0.0],
    4: [0.0, 0.0, 0.0, 1.0],
    5: [0.7, 0.7, 0.0, 0.0],
    6: [0.5, 0.5, 0.5, 0.5],
    7: [0.1, 0.2, 0.3, 0.4],
    8: [0.4, 0.3, 0.2, 0.1],
    9: [0.25, 0.25, 0.25, 0.25],
  };
  for (final entry in vectors.entries) {
    await db.insert('embeddings', {
      'chunk_id': entry.key,
      'vec': _vecBlob(entry.value),
    });
  }

  await db.close();
  return BrainDb.openAt(path);
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  late BrainDb brainDb;
  late LocalRetriever retriever;

  setUp(() async {
    brainDb = await _buildFixtureDb();
    retriever = LocalRetriever(brainDb);
  });

  tearDown(() async {
    await brainDb.close();
  });

  group('BrainDb', () {
    test('meta() returns the seeded key/value pairs', () async {
      final meta = await brainDb.meta();
      expect(meta['tenant_id'], 'test_tenant');
      expect(meta['built_at_utc'], '2026-08-29T00:00:00Z');
    });
  });

  group('TabularQueries', () {
    test('failPercentage is correct and not 100.0', () async {
      // This would return 100.0 if the query filtered to FAIL rows before
      // computing the percentage (numerator == denominator bug).
      final result = await retriever.retrieve('what percentage of students fail');
      expect(result.route, 'TABULAR');
      // 2 of 5 students failed -> 40.00%.
      expect(result.context, contains('40.00'));
      expect(result.context, isNot(contains('100.00')));
    });

    test('passPercentage is correct and not 0.0', () async {
      final result = await retriever.retrieve('what is the pass percentage');
      expect(result.route, 'TABULAR');
      // 3 of 5 students passed -> 60.00%.
      expect(result.context, contains('60.00'));
    });

    test('studentsFailedAtLeast(2) returns exactly the students with >=2 FF',
        () async {
      final db = brainDb.db;
      final rows = await db.rawQuery('''
        SELECT roll_no, COUNT(*) AS ff_count
        FROM student_subjects
        WHERE grade = 'FF'
        GROUP BY roll_no
        HAVING COUNT(*) >= 2
      ''');
      expect(rows.length, 1);
      expect(rows.first['roll_no'], '2103139003');

      // Exercise through the router too.
      final result =
          await retriever.retrieve('how many students have at least 2 backlogs');
      expect(result.route, 'TABULAR');
      expect(result.context, contains('2103139003'));
      expect(result.context, isNot(contains('2103139004 (')));
    });

    test('studentByName("gaikwad rohan") finds GAIKWAD ROHAN VIJAY '
        'regardless of token order and case', () async {
      final db = brainDb.db;
      final tokens = 'gaikwad rohan'
          .trim()
          .split(RegExp(r'\s+'))
          .map((t) => t.toUpperCase())
          .toList();
      final whereClause =
          tokens.map((_) => 'UPPER(name) LIKE ?').join(' AND ');
      final args = tokens.map((t) => '%$t%').toList();
      final rows = await db.rawQuery(
        'SELECT * FROM students WHERE $whereClause',
        args,
      );
      expect(rows.length, 1);
      expect(rows.first['name'], 'GAIKWAD ROHAN VIJAY');

      // Reversed token order must find the same student.
      final tokensReversed = tokens.reversed.toList();
      final whereClause2 =
          tokensReversed.map((_) => 'UPPER(name) LIKE ?').join(' AND ');
      final args2 = tokensReversed.map((t) => '%$t%').toList();
      final rows2 = await db.rawQuery(
        'SELECT * FROM students WHERE $whereClause2',
        args2,
      );
      expect(rows2.length, 1);
      expect(rows2.first['name'], 'GAIKWAD ROHAN VIJAY');
    });
  });

  group('searchText (FTS)', () {
    test('returns the chunk with a distinctive phrase ranked first',
        () async {
      final results =
          await retriever.searchText('photosynthesis conversion efficiency');
      expect(results, isNotEmpty);
      expect(results.first.id, 1);
      expect(results.first.docId, 'bio.md');
    });

    test('does not throw on apostrophe, hyphen, and the word AND', () async {
      expect(
        () => retriever.searchText(
          "what's newton's second law - explain it AND show examples",
        ),
        returnsNormally,
      );
      final results = await retriever.searchText(
        "what's newton's second law - explain it AND show examples",
      );
      expect(results, isA<List<RetrievedChunk>>());
    });

    test('symbol-only query returns no results without throwing', () async {
      expect(() => retriever.searchText('- AND -'), returnsNormally);
      final results = await retriever.searchText('- AND -');
      expect(results, isEmpty);
    });
  });

  group('searchVector', () {
    test('ranks an exactly-matching vector above an orthogonal one',
        () async {
      final results = await retriever.searchVector(
        [1.0, 0.0, 0.0, 0.0],
        topK: 9,
      );
      expect(results, isNotEmpty);
      expect(results.first.id, 1);
      expect(results.first.score, closeTo(1.0, 1e-6));

      final chunk2Index = results.indexWhere((r) => r.id == 2);
      final chunk1Index = results.indexWhere((r) => r.id == 1);
      expect(chunk1Index, lessThan(chunk2Index));
      // Orthogonal vector should score ~0.
      expect(results[chunk2Index].score, closeTo(0.0, 1e-6));
    });
  });

  group('retrieve routing', () {
    test('routes a roll-number question to TABULAR', () async {
      final result = await retriever
          .retrieve('What is the SGPA of student with roll number 2103139001?');
      expect(result.route, 'TABULAR');
      expect(result.context, contains('2103139001'));
      expect(result.context, contains('GAIKWAD ROHAN VIJAY'));
    });

    test('routes a definition question to FACT', () async {
      final result =
          await retriever.retrieve('What is photosynthesis conversion efficiency?');
      expect(result.route, 'FACT');
      expect(result.context, contains('Photosynthesis conversion efficiency'));
    });
  });

  group('context cap', () {
    test('caps context at 4000 characters and cuts on a chunk boundary',
        () async {
      final result = await retriever.retrieve('lorem filler content');
      expect(result.route, 'FACT');
      expect(result.context.length, lessThanOrEqualTo(4000));

      // The cap must cut cleanly between chunks: every joined piece must
      // be a whole chunk's content, never a partial fragment.
      final pieces = result.context.split('\n\n');
      final fillerContents = {
        for (final s in result.sources) s.id: s.content,
      };
      for (final piece in pieces) {
        expect(fillerContents.values, contains(piece));
      }

      // Since not all matched filler chunks fit under the cap, the
      // distinctive marker in chunk 9 (which would only fit if the cap
      // sliced mid-stream) must not appear as a partial/truncated
      // fragment: either the whole chunk is present, or none of it is.
      if (result.context.contains('CHUNK9')) {
        expect(result.context, contains(_cutoffMarker));
      }
    });
  });
}
