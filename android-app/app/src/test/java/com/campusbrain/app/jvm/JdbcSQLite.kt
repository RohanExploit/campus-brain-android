package com.campusbrain.app.jvm

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

/**
 * A JVM-only implementation of the three `androidx.sqlite` interfaces, on top
 * of `org.xerial:sqlite-jdbc`.
 *
 * ## Why this exists
 *
 * `androidx.sqlite:sqlite-bundled:2.5.1` ships Android `.so` payloads only.
 * On a desktop JVM `BundledSQLiteDriver().open(...)` dies with:
 *
 *     java.lang.UnsatisfiedLinkError: no sqliteJni in java.library.path
 *
 * which is why every test that needed a real connection either lived in
 * `androidTest/` (device required) or `Assume`-skipped itself. The production
 * retrieval stack is written against the `androidx.sqlite` interfaces and not
 * against the bundled driver, so supplying a second implementation of those
 * interfaces makes the *real* `BrainDb`, `FtsSearch`, `HybridSearch`,
 * `TabularQueries`, `QueryRouter`, `AnswerComposer` and `AnswerCheck` run in an
 * ordinary unit test with no device attached.
 *
 * `sqlite-jdbc` 3.53.4.0 is compiled with FTS5 on. Verified, not assumed:
 * [JdbcSQLiteAdapterTest] runs a `chunks_fts MATCH` with `bm25()` against the
 * shipped `brain.db`, which is an external-content FTS5 table with the
 * `porter unicode61` tokenizer. Without FTS5 that query raises
 * "no such module: fts5" and the test fails rather than quietly degrading.
 *
 * ## Scope
 *
 * Test scaffolding, not a product. It implements the surface the app actually
 * calls -- `prepare`, `execSQL`, the bind/get/step/reset family -- and nothing
 * more. `testImplementation` only; nothing here is on any APK classpath.
 *
 * ## The one design decision worth stating
 *
 * A statement with no bindings is run through `Statement.execute`, not through
 * `PreparedStatement`. That is what makes `PRAGMA query_only = ON`,
 * `PRAGMA user_version = 2`, `BEGIN IMMEDIATE`, `COMMIT`, `ROLLBACK` and every
 * `CREATE TABLE` behave. All 33 `execSQL` call sites in `app/src/main` pass a
 * single, parameterless statement, and `androidx.sqlite.execSQL` is itself just
 * `prepare(sql).use { it.step() }`, so they all arrive here.
 */
class JdbcSQLiteDriver : SQLiteDriver {

    /**
     * `":memory:"` is honoured exactly as the bundled driver honours it: one
     * private in-memory database per connection.
     */
    override fun open(fileName: String): SQLiteConnection {
        // Kept explicit so a stripped-down test JVM cannot silently fall back
        // to some other registered driver.
        Class.forName("org.sqlite.JDBC")
        val url = if (fileName == ":memory:") "jdbc:sqlite::memory:" else "jdbc:sqlite:$fileName"
        return JdbcSQLiteConnection(DriverManager.getConnection(url))
    }
}

class JdbcSQLiteConnection(internal val jdbc: Connection) : SQLiteConnection {

    override fun prepare(sql: String): SQLiteStatement {
        // A closed connection has to fail here rather than at step(): the
        // stores under test (EntitlementStore, LicenseStore, FirstRunStore)
        // wrap every call in runCatching and assert that a dead connection
        // reports failure instead of throwing at the caller, and that
        // contract is only exercised if something actually throws.
        check(!jdbc.isClosed) { "connection is closed" }
        return JdbcSQLiteStatement(jdbc, sql)
    }

    override fun close() {
        jdbc.close()
    }
}

class JdbcSQLiteStatement(
    private val jdbc: Connection,
    private val sql: String,
) : SQLiteStatement {

    /** 1-based, matching `androidx.sqlite`'s bind indices and JDBC's. */
    private val bindings = HashMap<Int, Any?>()

    private var prepared: PreparedStatement? = null
    private var plain: Statement? = null
    private var rs: ResultSet? = null
    private var executed = false
    private var closed = false

    // --- binds ------------------------------------------------------------

    override fun bindBlob(index: Int, value: ByteArray) { bindings[index] = value }
    override fun bindDouble(index: Int, value: Double) { bindings[index] = value }
    override fun bindLong(index: Int, value: Long) { bindings[index] = value }
    override fun bindText(index: Int, value: String) { bindings[index] = value }
    override fun bindNull(index: Int) { bindings[index] = null }

    override fun clearBindings() {
        bindings.clear()
        prepared?.clearParameters()
    }

    // --- stepping ---------------------------------------------------------

    /**
     * True when a row is available.
     *
     * First call executes; later calls advance the cursor. A statement that
     * produced no result set -- DDL, DML, most pragmas -- returns false, which
     * is what `execSQL` relies on.
     */
    override fun step(): Boolean {
        check(!closed) { "statement is closed" }
        if (!executed) {
            executed = true
            val hasResults = if (bindings.isEmpty()) {
                val st = plain ?: jdbc.createStatement().also { plain = it }
                st.execute(sql)
            } else {
                val ps = prepared ?: jdbc.prepareStatement(sql).also { prepared = it }
                applyBindings(ps)
                ps.execute()
            }
            rs = if (hasResults) (prepared?.resultSet ?: plain?.resultSet) else null
        }
        val cursor = rs ?: return false
        return cursor.next()
    }

    /**
     * Back to "not yet executed", bindings intact.
     *
     * That is `androidx.sqlite`'s contract and the app depends on it:
     * `AnalyticsStore` and `UserCorpusDb` both loop `reset(); bindX(...);
     * step()` over one prepared statement, and clearing bindings here would
     * make the second iteration insert nulls.
     */
    override fun reset() {
        rs?.close()
        rs = null
        executed = false
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { rs?.close() }
        runCatching { prepared?.close() }
        runCatching { plain?.close() }
    }

    private fun applyBindings(ps: PreparedStatement) {
        ps.clearParameters()
        for ((i, v) in bindings) {
            when (v) {
                null -> ps.setNull(i, java.sql.Types.NULL)
                is ByteArray -> ps.setBytes(i, v)
                is Double -> ps.setDouble(i, v)
                is Long -> ps.setLong(i, v)
                is String -> ps.setString(i, v)
                else -> error("unsupported binding $v")
            }
        }
    }

    // --- column reads -----------------------------------------------------
    //
    // androidx.sqlite column indices are 0-based; JDBC's are 1-based.

    private fun row(): ResultSet = rs ?: error("no row: step() has not returned true")

    override fun getBlob(index: Int): ByteArray = row().getBytes(index + 1) ?: ByteArray(0)
    override fun getDouble(index: Int): Double = row().getDouble(index + 1)
    override fun getLong(index: Int): Long = row().getLong(index + 1)

    /**
     * "" rather than a smuggled null.
     *
     * `getText` is declared to return a non-null Kotlin `String`. Handing back
     * a null through it produces a NullPointerException several frames away --
     * inside AnswerComposer, typically -- which reads as a retrieval bug and is
     * not one. Every call site that cares guards with `isNull` first.
     */
    override fun getText(index: Int): String = row().getString(index + 1) ?: ""

    override fun isNull(index: Int): Boolean = row().getObject(index + 1) == null

    override fun getColumnCount(): Int = rs?.metaData?.columnCount ?: 0
    override fun getColumnName(index: Int): String = row().metaData.getColumnName(index + 1)

    override fun getColumnType(index: Int): Int = when (row().metaData.getColumnType(index + 1)) {
        java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.SMALLINT,
        java.sql.Types.TINYINT, java.sql.Types.BOOLEAN -> 1 // SQLITE_DATA_INTEGER
        java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE,
        java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> 2 // SQLITE_DATA_FLOAT
        java.sql.Types.BLOB, java.sql.Types.BINARY, java.sql.Types.VARBINARY -> 4 // SQLITE_DATA_BLOB
        java.sql.Types.NULL -> 5 // SQLITE_DATA_NULL
        else -> 3 // SQLITE_DATA_TEXT
    }
}
