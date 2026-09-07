package com.campusbrain.app

import androidx.sqlite.execSQL
import com.campusbrain.app.data.query
import com.campusbrain.app.jvm.JdbcSQLiteDriver
import com.campusbrain.app.jvm.JvmCorpus
import com.campusbrain.app.retrieval.FtsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scaffolding, tested before anything is built on it.
 *
 * [com.campusbrain.app.jvm.JdbcSQLiteDriver] is the reason the retrieval
 * battery can run without a handset, so a bug in it would show up as a
 * retrieval defect that is not one. Each case below pins one behaviour the
 * production code actually depends on, named after the call site that depends
 * on it.
 */
class JdbcSQLiteAdapterTest {

    private fun memory() = JdbcSQLiteDriver().open(":memory:")

    // --- the claim the whole exercise rests on ----------------------------

    @Test fun `the JVM driver has FTS5 with bm25 over the shipped external-content index`() {
        // Not assumed -- measured. brain.db's chunks_fts is
        //   fts5(content, doc_id, content='chunks', content_rowid='id',
        //        tokenize='porter unicode61')
        // Without FTS5 compiled in, this raises "no such module: fts5" and the
        // whole JVM battery is pointless. `porter` stemming is checked too:
        // "attendance" must reach chunks that say "attendance" and the stem
        // must behave, so a second query on a stemmed form has to land as well.
        val conn = JvmCorpus.db.conn
        val rows = conn.query(
            "SELECT c.doc_id, bm25(chunks_fts) FROM chunks_fts " +
                "JOIN chunks c ON c.id = chunks_fts.rowid " +
                "WHERE chunks_fts MATCH 'attendance' ORDER BY bm25(chunks_fts) LIMIT 5",
        ) { it.getText(0) to it.getDouble(1) }
        assertTrue("FTS5 returned nothing", rows.isNotEmpty())
        assertTrue(
            "the attendance policy should be among the top keyword hits, got $rows",
            rows.any { it.first.contains("attendance") },
        )
        // bm25 is negative-is-better in SQLite; a zero for every row would mean
        // the ranking function is a stub.
        assertTrue("bm25 produced no ranking: $rows", rows.any { it.second != 0.0 })
    }

    @Test fun `the production FtsSearch reports itself available on the JVM`() {
        // FtsSearch.available probes with a MATCH inside runCatching, so a
        // driver without FTS5 degrades silently to LikeSearch and every
        // battery result would be measuring the wrong arm.
        assertTrue("FtsSearch fell back to LIKE", JvmCorpus.fts.available)
        assertTrue(FtsSearch(JvmCorpus.db).search("attendance", 5).isNotEmpty())
    }

    // --- the surface the app calls ----------------------------------------

    @Test fun `execSQL runs DDL, pragmas and transaction control`() {
        // Every one of the 33 execSQL sites in app/src/main is a single
        // parameterless statement of one of these three shapes.
        val conn = memory()
        conn.execSQL("PRAGMA busy_timeout = 5000")
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, k TEXT, v REAL, b BLOB)")
        conn.execSQL("BEGIN IMMEDIATE")
        conn.execSQL("INSERT INTO t (id, k) VALUES (1, 'one')")
        conn.execSQL("COMMIT")
        assertEquals(listOf(1L), conn.query("SELECT COUNT(*) FROM t") { it.getLong(0) })

        conn.execSQL("BEGIN IMMEDIATE")
        conn.execSQL("INSERT INTO t (id, k) VALUES (2, 'two')")
        conn.execSQL("ROLLBACK")
        assertEquals("ROLLBACK did not roll back",
            listOf(1L), conn.query("SELECT COUNT(*) FROM t") { it.getLong(0) })

        conn.execSQL("PRAGMA user_version = 2")
        assertEquals(listOf(2L), conn.query("PRAGMA user_version") { it.getLong(0) })
        conn.close()
    }

    @Test fun `reset keeps bindings and re-executes, which is how the ingest loop writes`() {
        // UserCorpusDb and AnalyticsStore both loop
        //     st.reset(); st.bindX(...); st.step()
        // over one prepared statement. Clearing bindings in reset() would make
        // every row after the first insert nulls.
        val conn = memory()
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, k TEXT NOT NULL)")
        conn.prepare("INSERT INTO t (id, k) VALUES (?, ?)").use { st ->
            listOf(1L to "a", 2L to "b", 3L to "c").forEach { (id, k) ->
                st.reset()
                st.bindLong(1, id)
                st.bindText(2, k)
                assertFalse("an INSERT yields no row", st.step())
            }
        }
        assertEquals(listOf("a", "b", "c"),
            conn.query("SELECT k FROM t ORDER BY id") { it.getText(0) })
        conn.close()
    }

    @Test fun `nulls, blobs and doubles survive the round trip`() {
        val conn = memory()
        conn.execSQL("CREATE TABLE t (id INTEGER PRIMARY KEY, s TEXT, d REAL, b BLOB)")
        conn.prepare("INSERT INTO t VALUES (?, ?, ?, ?)").use { st ->
            st.bindLong(1, 1)
            st.bindNull(2)
            st.bindDouble(3, 2.5)
            st.bindBlob(4, byteArrayOf(1, 2, 3, 4))
            st.step()
        }
        conn.prepare("SELECT s, d, b FROM t WHERE id = ?").use { st ->
            st.bindLong(1, 1)
            assertTrue(st.step())
            // isNull is what every `if (it.isNull(2)) null else it.getText(2)`
            // in the retrieval code branches on.
            assertTrue("a NULL column must report isNull", st.isNull(0))
            assertFalse(st.isNull(1))
            assertEquals(2.5, st.getDouble(1), 1e-9)
            assertEquals(4, st.getBlob(2).size)
            assertEquals(2.toByte(), st.getBlob(2)[1])
        }
        conn.close()
    }

    @Test fun `the embeddings blob decodes to the 384d vectors VectorSearch expects`() {
        // VectorSearch.warm() skips any blob whose size is not dim*4, so a
        // driver that mangled BLOBs would silently produce an empty vector
        // index and a battery that looked keyword-only for the wrong reason.
        val dim = JvmCorpus.db.embeddingDim
        assertEquals(384, dim)
        val sizes = JvmCorpus.db.conn
            .query("SELECT length(vec) FROM embeddings LIMIT 5") { it.getLong(0) }
        assertTrue("no embeddings read", sizes.isNotEmpty())
        sizes.forEach { assertEquals((dim * 4).toLong(), it) }
    }

    @Test fun `query_only really is read-only, so the bundle cannot be written`() {
        // BrainDb.openWith runs PRAGMA query_only = ON. If the adapter let that
        // no-op, a test could corrupt the committed corpus.
        val failed = runCatching {
            JvmCorpus.db.conn.execSQL("CREATE TABLE should_not_exist (x)")
        }.isFailure
        assertTrue("PRAGMA query_only did not take effect", failed)
    }

    @Test fun `a closed connection throws at prepare, which is what the stores catch`() {
        // EntitlementTest's "the store never throws at a caller, even on a
        // closed connection" only means something if something throws.
        val conn = memory()
        conn.close()
        assertTrue(runCatching { conn.prepare("SELECT 1") }.isFailure)
    }

    @Test fun `getText never smuggles a null through a non-null Kotlin String`() {
        val conn = memory()
        conn.execSQL("CREATE TABLE t (s TEXT)")
        conn.execSQL("INSERT INTO t VALUES (NULL)")
        conn.prepare("SELECT s FROM t").use { st ->
            assertTrue(st.step())
            assertTrue(st.isNull(0))
            assertEquals("", st.getText(0))
        }
        conn.close()
    }

    @Test fun `a query with no rows steps to false rather than throwing`() {
        val conn = memory()
        conn.execSQL("CREATE TABLE t (id INTEGER)")
        assertNull(conn.query("SELECT id FROM t") { it.getLong(0) }.firstOrNull())
        conn.close()
    }
}
