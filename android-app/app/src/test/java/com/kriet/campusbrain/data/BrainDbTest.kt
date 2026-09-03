package com.kriet.campusbrain.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.io.File

/**
 * Exercises BrainDb and the `query` extension against a real SQLite file,
 * opened with the same bundled driver the app ships -- no Context, no
 * Robolectric, because [BrainDb.openPath] is the seam that separates "open
 * this exact file" from "find the file on a device".
 *
 * The schema built here is the subset of scripts/export_mobile_bundle.py's
 * SCHEMA_SQL these tests touch; see tests/test_export_mobile_bundle.py for the
 * Python-side proof of the writer that produces the real bundle.
 *
 * androidx.sqlite:sqlite-bundled ships native SQLite per platform. Resolved
 * from an Android application module's testImplementation, that can mean the
 * Android-targeted native binary rather than one this desktop JVM can load,
 * which fails as UnsatisfiedLinkError/NoClassDefFoundError -- an environment
 * gap, not a assertion this test should make. [testDb] skips rather than
 * fails in that case, the same way WordPieceTokenizerTest skips when its
 * required assets are absent.
 */
class BrainDbTest {

    private fun tempDbPath(): String {
        val f = File.createTempFile("brain-test", ".db")
        f.deleteOnExit()
        f.delete() // BundledSQLiteDriver creates the file itself on open().
        return f.absolutePath
    }

    /** Runs a statement with no result rows and no parameters -- CREATE/INSERT literals. */
    private fun SQLiteConnection.exec(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun buildBundle(path: String, withDocuments: Boolean) {
        val conn = BundledSQLiteDriver().open(path)
        try {
            conn.exec("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            conn.exec(
                "CREATE TABLE chunks (id INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, " +
                    "section TEXT, content TEXT NOT NULL)"
            )
            conn.exec("INSERT INTO chunks (id, doc_id, section, content) VALUES (0, 'a.md', 'Intro', 'hello world')")
            conn.exec("INSERT INTO chunks (id, doc_id, section, content) VALUES (1, 'a.md', NULL, 'second chunk')")
            conn.exec("INSERT INTO chunks (id, doc_id, section, content) VALUES (2, 'b.md', 'Deploy', 'third chunk')")
            if (withDocuments) {
                conn.exec(
                    "CREATE TABLE documents (doc_id TEXT PRIMARY KEY, title TEXT NOT NULL, " +
                        "category TEXT, chunk_count INTEGER NOT NULL, first_chunk_id INTEGER, preview TEXT)"
                )
            }
            val metaRows = listOf(
                "tenant_id" to "tenant_test",
                "chunk_count" to "3",
                "embedding_dim" to "4",
                "document_count" to "2",
            )
            metaRows.forEach { (k, v) ->
                conn.prepare("INSERT INTO meta (key, value) VALUES (?, ?)").use { st ->
                    st.bindText(1, k)
                    st.bindText(2, v)
                    st.step()
                }
            }
        } finally {
            conn.close()
        }
    }

    /**
     * Builds a synthetic bundle and opens it, skipping the test if the native
     * driver can't load here. LinkageError covers UnsatisfiedLinkError (the
     * native library itself), NoClassDefFoundError (a class that failed to
     * link on an earlier test) and ExceptionInInitializerError (its cause) --
     * all the same underlying "no usable native binary for this JVM" gap.
     */
    private fun testDb(withDocuments: Boolean = true): BrainDb {
        val path = tempDbPath()
        try {
            buildBundle(path, withDocuments)
            return BrainDb.openPath(path, "test")
        } catch (e: LinkageError) {
            assumeNoException("bundled SQLite native library not loadable in this JVM", e)
            throw e
        }
    }

    @Test fun `meta reads back every key`() {
        val db = testDb()
        assertEquals("tenant_test", db.meta["tenant_id"])
        assertEquals("3", db.meta["chunk_count"])
        assertEquals(null, db.meta["not_a_key"])
    }

    @Test fun `embeddingDim reads the meta key`() {
        assertEquals(4, testDb().embeddingDim)
    }

    @Test fun `hasTable is true for a real table and false for one that does not exist`() {
        val db = testDb()
        assertTrue(db.hasTable("chunks"))
        assertFalse(db.hasTable("graph_edges"))
    }

    @Test fun `hasDocumentsTable degrades cleanly on an older bundle`() {
        assertTrue(testDb(withDocuments = true).hasDocumentsTable)

        // user_version 1: no documents table. Readers must degrade, not crash --
        // this is exactly the bundle shape DocsRepository.all() falls back for.
        assertFalse(testDb(withDocuments = false).hasDocumentsTable)
    }

    @Test fun `query binds parameters and maps every row in order`() {
        val rows = testDb().conn.query(
            "SELECT id, content FROM chunks WHERE doc_id = ? ORDER BY id",
            bind = { it.bindText(1, "a.md") },
        ) { it.getLong(0) to it.getText(1) }
        assertEquals(listOf(0L to "hello world", 1L to "second chunk"), rows)
    }

    @Test fun `query returns an empty list rather than throwing on no match`() {
        val rows = testDb().conn.query(
            "SELECT id FROM chunks WHERE doc_id = ?",
            bind = { it.bindText(1, "missing.md") },
        ) { it.getLong(0) }
        assertTrue(rows.isEmpty())
    }

    @Test fun `query with no bind reads every row`() {
        val count = testDb().conn.query("SELECT id FROM chunks") { it.getLong(0) }
        assertEquals(3, count.size)
    }
}
