import 'dart:io';
import 'dart:typed_data';

import 'package:flutter/services.dart' show rootBundle;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:sqflite/sqflite.dart';

/// Thrown by [BrainDb.open] when no database can be found anywhere it
/// looks. The message is meant to be shown directly on a phone screen (at
/// a venue, with no laptop debugger attached), so it states the expected
/// path and the exact `adb push` command to fix it -- not a stack trace.
class BrainDbMissingException implements Exception {
  final String message;

  BrainDbMissingException(this.message);

  @override
  String toString() => message;
}

/// Opens the on-device "Company Brain" SQLite database.
///
/// The database holds real student names, roll numbers, and results, so it
/// is never committed to this (public) repository and is never declared as
/// a Flutter asset. It reaches the phone by `adb push` instead. [open]
/// looks in three places, in order:
///
///   1. The app's external files directory (the `adb push` target --
///      writable with no root and no runtime permission, and it survives
///      app restarts).
///   2. The app documents directory (the copy target if a bundled asset
///      is ever used -- see 3).
///   3. A bundled `assets/brain.db`, if one is ever added to
///      `pubspec.yaml` (kept as a future extension point for a public or
///      anonymised corpus; not required today and not currently declared).
///
/// If none of those has a database, [open] throws
/// [BrainDbMissingException] rather than falling back to an empty
/// database -- answering from an empty corpus would be worse than saying
/// plainly that the corpus is missing.
class BrainDb {
  final Database db;

  BrainDb._(this.db);

  static const String _assetPath = 'assets/brain.db';
  static const String _dbFileName = 'brain.db';

  /// Where `adb push brain.db <path>` should put the file, per the
  /// documented runbook. Used both to look for the file and, if it's
  /// missing, to tell the user exactly what command to run.
  static const String _externalPushPathHint =
      '/sdcard/Android/data/com.companybrain.company_brain/files/$_dbFileName';

  /// Opens the database from wherever it's found (external files dir,
  /// then app documents dir, then a bundled asset if present). Throws
  /// [BrainDbMissingException] if none of those has it.
  static Future<BrainDb> open() async {
    final externalDir = await _externalStorageDir();
    if (externalDir != null) {
      final externalPath = p.join(externalDir.path, _dbFileName);
      if (await File(externalPath).exists()) {
        return openAt(externalPath);
      }
    }

    final docsDir = await getApplicationDocumentsDirectory();
    final docsPath = p.join(docsDir.path, _dbFileName);
    if (await File(docsPath).exists()) {
      return openAt(docsPath);
    }

    final assetBytes = await _tryLoadAsset();
    if (assetBytes != null) {
      await _writeBytes(docsPath, assetBytes);
      return openAt(docsPath);
    }

    final expectedPath = externalDir != null
        ? p.join(externalDir.path, _dbFileName)
        : _externalPushPathHint;
    throw BrainDbMissingException(
      'Company Brain database not found.\n'
      'Expected it at:\n'
      '  $expectedPath\n'
      'Push it from your laptop with:\n'
      '  adb push brain.db $expectedPath',
    );
  }

  /// True if [open] would succeed right now, without actually opening
  /// (and thus without leaving a connection open). Useful for a UI that
  /// wants to show a "database missing" screen before attempting a query.
  static Future<bool> isAvailable() async {
    final externalDir = await _externalStorageDir();
    if (externalDir != null &&
        await File(p.join(externalDir.path, _dbFileName)).exists()) {
      return true;
    }

    final docsDir = await getApplicationDocumentsDirectory();
    if (await File(p.join(docsDir.path, _dbFileName)).exists()) {
      return true;
    }

    return await _tryLoadAsset() != null;
  }

  /// Opens an existing database file at [path] read-only. Used directly by
  /// tests, which build their own fixture database, and internally by
  /// [open] once it has located a real file.
  static Future<BrainDb> openAt(String path) async {
    final db = await openDatabase(path, readOnly: true);
    return BrainDb._(db);
  }

  static Future<Directory?> _externalStorageDir() async {
    try {
      return await getExternalStorageDirectory();
    } catch (_) {
      // Not on Android, or the platform channel isn't available (e.g. in
      // a plain `flutter test` run with no plugin bindings).
      return null;
    }
  }

  static Future<ByteData?> _tryLoadAsset() async {
    try {
      return await rootBundle.load(_assetPath);
    } catch (_) {
      return null;
    }
  }

  static Future<void> _writeBytes(String path, ByteData data) async {
    final file = File(path);
    final bytes = data.buffer.asUint8List(
      data.offsetInBytes,
      data.lengthInBytes,
    );
    await file.writeAsBytes(bytes, flush: true);
  }

  Future<Map<String, String>> meta() async {
    final rows = await db.query('meta');
    final result = <String, String>{};
    for (final row in rows) {
      result[row['key'] as String] = row['value'] as String;
    }
    return result;
  }

  Future<void> close() async {
    await db.close();
  }
}
