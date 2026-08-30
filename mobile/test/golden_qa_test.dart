import 'dart:io';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:path/path.dart' as p;
import 'package:sqflite_common_ffi/sqflite_ffi.dart';

import 'package:company_brain/demo/golden_qa.dart';
import 'package:company_brain/local/brain_db.dart';
import 'package:company_brain/local/local_retriever.dart';
import 'package:company_brain/local/models.dart';

const List<String> _validRoutes = ['TABULAR', 'FACT', 'LOCAL', 'GLOBAL'];

Uint8List _vecBlob(List<double> values) {
  final f = Float32List.fromList(values);
  return f.buffer.asUint8List(f.offsetInBytes, f.lengthInBytes);
}

/// Minimal fixture DB matching the brain.db schema contract, just enough to
/// exercise a TABULAR and a FACT route. This is not the real corpus -- CI
/// has no brain.db -- so these tests validate the self-test screen's
/// matching *logic*, not the golden numbers themselves.
Future<BrainDb> _buildFixtureDb() async {
  final dir = await Directory.systemTemp.createTemp('golden_qa_test');
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

  await db.insert('students', {
    'roll_no': '2103139001',
    'name': 'GAIKWAD ROHAN VIJAY',
    'sgpa': 8.5,
    'estimated_sgpa': 8.5,
    'total_marks': 850,
    'result': 'PASS',
    'is_supply': 0,
    'seat_cancelled': 0,
  });
  await db.insert('students', {
    'roll_no': '2103139002',
    'name': 'PATIL SNEHA RAJESH',
    'sgpa': 5.0,
    'estimated_sgpa': 5.0,
    'total_marks': 500,
    'result': 'FAIL',
    'is_supply': 1,
    'seat_cancelled': 0,
  });

  const feeContent = 'The semester fee structure totals 1500 rupees.';
  await db.insert('chunks', {
    'id': 1,
    'doc_id': 'fees.md',
    'section': 'fee structure',
    'content': feeContent,
  });
  await db.rawInsert(
    'INSERT INTO chunks_fts(rowid, content, doc_id) VALUES (?, ?, ?)',
    [1, feeContent, 'fees.md'],
  );
  await db.insert('embeddings', {'chunk_id': 1, 'vec': _vecBlob([1.0, 0.0])});

  await db.close();
  return BrainDb.openAt(path);
}

void main() {
  sqfliteFfiInit();
  databaseFactory = databaseFactoryFfi;

  group('goldenQaCases (static data)', () {
    test('is non-empty', () {
      expect(goldenQaCases, isNotEmpty);
    });

    test('every case has a non-empty question and expectations', () {
      for (final c in goldenQaCases) {
        expect(c.question.trim(), isNotEmpty);
        expect(c.expectedRoute.trim(), isNotEmpty);
        expect(c.expectSubstring.trim(), isNotEmpty);
        expect(c.why.trim(), isNotEmpty);
      }
    });

    test('questions are unique', () {
      final questions = goldenQaCases.map((c) => c.question).toList();
      expect(questions.toSet().length, questions.length);
    });

    test('every expectedRoute is one of TABULAR/FACT/LOCAL/GLOBAL', () {
      for (final c in goldenQaCases) {
        expect(_validRoutes, contains(c.expectedRoute));
      }
    });
  });

  group('self-test matching logic', () {
    late BrainDb brainDb;
    late LocalRetriever retriever;

    setUp(() async {
      brainDb = await _buildFixtureDb();
      retriever = LocalRetriever(brainDb);
    });

    tearDown(() async {
      await brainDb.close();
    });

    // Mirrors _SelfTestScreenState._evaluate: a case passes only when the
    // actual route matches the expected route AND the actual answer
    // contains the expected substring (case-insensitive).
    bool evaluate(
      RetrievalResult retrieval,
      String expectedRoute,
      String expectSubstring,
    ) {
      final routeMatches = retrieval.route == expectedRoute;
      final contentMatches =
          retrieval.context.toLowerCase().contains(expectSubstring.toLowerCase());
      return routeMatches && contentMatches;
    }

    test('a correct route + correct substring passes', () async {
      final retrieval = await retriever.retrieve('what is the fee structure');
      expect(retrieval.route, 'FACT');
      expect(evaluate(retrieval, 'FACT', '1500'), isTrue);
    });

    test('a correct route but wrong substring fails', () async {
      final retrieval = await retriever.retrieve('what is the fee structure');
      expect(retrieval.route, 'FACT');
      expect(evaluate(retrieval, 'FACT', 'not-a-real-number'), isFalse);
    });

    test('a wrong expected route fails even if content matches', () async {
      final retrieval = await retriever.retrieve('what is the fee structure');
      expect(retrieval.route, 'FACT');
      expect(evaluate(retrieval, 'TABULAR', '1500'), isFalse);
    });

    test('TABULAR route case passes when route and content match', () async {
      final retrieval =
          await retriever.retrieve('what percentage of students passed');
      expect(retrieval.route, 'TABULAR');
      // 1 of 2 students passed -> 50.00%.
      expect(evaluate(retrieval, 'TABULAR', '50.00'), isTrue);
    });
  });
}
