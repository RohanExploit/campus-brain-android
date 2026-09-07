package com.campusbrain.app

import com.campusbrain.app.data.Provenance
import com.campusbrain.app.data.UserCorpusDb
import androidx.sqlite.execSQL
import com.campusbrain.app.data.query
import com.campusbrain.app.jvm.JdbcSQLiteDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UserCorpusDb] itself, over a real SQLite connection.
 *
 * ## Why this file exists at all
 *
 * Before it, nothing in `app/src/test/` had ever executed `UserCorpusDb.write`.
 * `IngestTest` covers the chunker and `encodeVector`; `DeleteAccountTest`
 * hand-copies the table DDL. So the `BEGIN IMMEDIATE` this class depends on
 * for "a whole document or none of it" had never run under [JdbcSQLiteDriver],
 * and `sqlite-jdbc` defaults to `autoCommit = true` -- which is exactly the
 * kind of thing that makes a transaction test pass while testing nothing.
 * Every idempotency and resumability claim in `CorpusSyncTest` is built on
 * this, so it is checked here first and directly.
 *
 * The external-content FTS5 companion insert is asserted through an actual
 * `MATCH`, not by counting rows in `chunks_fts`: a missing companion insert
 * leaves the document stored and permanently unfindable by keyword, and a row
 * count would not notice.
 */
class CorpusStoreTest {

    // --- the harness itself -----------------------------------------------

    @Test fun `a document written through the real store is searchable`() = withStore { store ->
        val n = store.write(doc("timetable.md", listOf("Classes begin at nine in the morning.")))
        assertEquals(1, n)

        val hits = store.conn.query(
            "SELECT c.doc_id FROM chunks_fts f JOIN chunks c ON c.id = f.rowid " +
                "WHERE chunks_fts MATCH 'morning'"
        ) { it.getText(0) }
        assertEquals(listOf("timetable.md"), hits)
    }

    @Test fun `a write that fails halfway leaves no half of it`() = withStore { store ->
        // A landmine on the id the SECOND chunk will be given: `embeddings`
        // has chunk_id as its primary key, so that chunk's vector insert
        // raises after the first chunk and its FTS row are already in.
        //
        // This is the assertion the rest of the sync work stands on. If
        // BEGIN IMMEDIATE is not really in force under the JVM driver -- and
        // sqlite-jdbc defaults to autoCommit -- the first chunk survives and
        // the document is half-written, which UserCorpusDb's own doc comment
        // calls worse than a rejected import: silently missing content the
        // user believes is searchable.
        store.conn.execSQL(
            "INSERT INTO embeddings(chunk_id, vec) " +
                "VALUES (${UserCorpusDb.ID_BASE + 1}, x'00')"
        )
        val vector = FloatArray(384) { 0.05f }
        val two = UserCorpusDb.PendingDocument(
            docId = "half.md",
            title = "Half",
            sourceUri = null,
            addedAtUtc = "2026-09-07T00:00:00Z",
            chunks = listOf(
                UserCorpusDb.PendingChunk(null, "the first half", vector),
                UserCorpusDb.PendingChunk(null, "the second half", vector),
            ),
        )
        assertTrue("the landmine did not go off", runCatching { store.write(two) }.isFailure)

        assertEquals(0, store.conn.query(
            "SELECT COUNT(*) FROM chunks WHERE doc_id = 'half.md'"
        ) { it.getLong(0) }.first().toInt())
        assertTrue(store.documents().isEmpty())
        val hits = store.conn.query(
            "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'half'"
        ) { it.getLong(0) }
        assertTrue("half a document is still searchable: $hits", hits.isEmpty())

        // And the connection is usable afterwards: a rollback that left the
        // transaction open would break every write that followed.
        assertEquals(1, store.write(doc("after.md", listOf("Written after the failure."))))
    }

    // --- the id bands -----------------------------------------------------

    @Test fun `an import after a sync stays in the student's own band`() = withStore { store ->
        store.write(doc("circular.md", listOf("Published by the office."), Provenance.INSTITUTION))
        store.write(doc("my notes.md", listOf("Something I wrote down.")))

        val own = idsOf(store, "my notes.md")
        val theirs = idsOf(store, "circular.md")
        own.forEach {
            assertTrue("$it should be in the student's band", UserCorpusDb.isOwnChunk(it))
        }
        theirs.forEach {
            assertTrue("$it should be in the institution band", UserCorpusDb.isInstitutionChunk(it))
        }
    }

    @Test fun `a sync after an import stays in the institution band`() = withStore { store ->
        store.write(doc("my notes.md", listOf("Something I wrote down.")))
        store.write(doc("circular.md", listOf("Published by the office."), Provenance.INSTITUTION))

        idsOf(store, "my notes.md").forEach { assertTrue(UserCorpusDb.isOwnChunk(it)) }
        idsOf(store, "circular.md").forEach { assertTrue(UserCorpusDb.isInstitutionChunk(it)) }
    }

    @Test fun `the three bands are disjoint and cover everything`() {
        // The bundle's highest shipped id is 493.
        listOf(1L, 493L, UserCorpusDb.ID_BASE - 1).forEach {
            assertEquals(Provenance.BUNDLE, UserCorpusDb.provenanceOf(it))
            assertFalse(UserCorpusDb.isUserChunk(it))
        }
        listOf(UserCorpusDb.ID_BASE, UserCorpusDb.INSTITUTION_ID_BASE - 1).forEach {
            assertEquals(Provenance.USER, UserCorpusDb.provenanceOf(it))
            // Still true: isUserChunk is HybridSearch's routing question,
            // "which file holds this row", and both bands are in this file.
            assertTrue(UserCorpusDb.isUserChunk(it))
            assertTrue(UserCorpusDb.isOwnChunk(it))
        }
        listOf(UserCorpusDb.INSTITUTION_ID_BASE, UserCorpusDb.INSTITUTION_ID_BASE + 9).forEach {
            assertEquals(Provenance.INSTITUTION, UserCorpusDb.provenanceOf(it))
            assertTrue(UserCorpusDb.isUserChunk(it))
            assertFalse(UserCorpusDb.isOwnChunk(it))
        }
    }

    // --- provenance and the licence caps ----------------------------------

    @Test fun `the three sources are told apart on the document list`() = withStore { store ->
        store.write(doc("circular.md", listOf("Published by the office."), Provenance.INSTITUTION))
        store.write(doc("my notes.md", listOf("Something I wrote down.")))

        val byId = store.documents().associateBy { it.docId }
        assertEquals(Provenance.INSTITUTION, byId.getValue("circular.md").provenance)
        assertEquals(Provenance.USER, byId.getValue("my notes.md").provenance)
        assertFalse(byId.getValue("circular.md").isUserAdded)
        assertTrue(byId.getValue("my notes.md").isUserAdded)
        assertEquals(UserCorpusDb.SYNCED_CATEGORY, byId.getValue("circular.md").category)
        assertEquals(UserCorpusDb.ADDED_CATEGORY, byId.getValue("my notes.md").category)
    }

    @Test fun `a synced document does not spend the student's import allowance`() =
        withStore { store ->
            repeat(5) { i ->
                store.write(
                    doc("circular $i.md", listOf("Published by the office."), Provenance.INSTITUTION)
                        .copy(sizeBytes = 100_000L)
                )
            }
            // The whole point: an institution that publishes five documents
            // must not be able to exhaust a free device's one-document
            // allowance and have the refusal read as "your licence".
            assertEquals(0, store.importedCount())
            assertEquals(0L, store.importedBytes())

            store.write(doc("mine.md", listOf("Mine.")).copy(sizeBytes = 2_048L))
            assertEquals(1, store.importedCount())
            assertEquals(2_048L, store.importedBytes())
        }

    @Test fun `a synced document is found by its server id`() = withStore { store ->
        store.write(
            doc("circular.md", listOf("Published."), Provenance.INSTITUTION)
                .copy(remoteDocId = "notices/2026-fee-deadline", revision = 42L)
        )
        val found = store.byRemoteDocId("notices/2026-fee-deadline")
        assertNotNull(found)
        assertEquals("circular.md", found!!.docId)
        assertEquals(42L, found.revision)
        assertNull(store.byRemoteDocId("notices/nothing"))
    }

    @Test fun `removing a synced document takes its chunks and its index with it`() =
        withStore { store ->
            store.write(
                doc("circular.md", listOf("The deadline is the fifteenth."), Provenance.INSTITUTION)
                    .copy(remoteDocId = "notices/withdrawn")
            )
            assertTrue(store.remove("circular.md"))
            assertNull(store.byRemoteDocId("notices/withdrawn"))
            val hits = store.conn.query(
                "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'deadline'"
            ) { it.getLong(0) }
            assertTrue("a withdrawn notice still matches: $hits", hits.isEmpty())
        }

    @Test fun `a student cannot delete the institution's document`() = withStore { store ->
        store.write(
            doc("circular.md", listOf("The office says so."), Provenance.INSTITUTION)
                .copy(remoteDocId = "notices/circular", revision = 3L)
        )
        store.write(doc("mine.md", listOf("I say so.")))

        // Sync only ever asks for revision > watermark, so a synced document
        // deleted here would be gone from this phone permanently while the
        // watermark went on asserting it had been applied. The refusal lives
        // in the store rather than in whichever screen calls it, because the
        // registrar surface Phase 2 adds will list these documents too.
        assertFalse(store.removeOwn("circular.md"))
        assertNotNull(store.byRemoteDocId("notices/circular"))
        assertEquals(1, store.conn.query(
            "SELECT COUNT(*) FROM chunks WHERE doc_id = 'circular.md'"
        ) { it.getLong(0) }.first().toInt())

        assertTrue(store.removeOwn("mine.md"))
        assertFalse(store.removeOwn("nothing at all.md"))

        // The unconditional primitive still works, because sync needs it: a
        // withdrawal and a revision replacement both delete exactly the
        // document removeOwn refuses to touch.
        assertTrue(store.remove("circular.md"))
        assertNull(store.byRemoteDocId("notices/circular"))
    }

    // --- the vector blob --------------------------------------------------

    @Test fun `bytes from the wire are written without a round trip`() = withStore { store ->
        // A blob that is NOT the encoding of any float array this app would
        // produce, so a decode-and-re-encode would visibly change it.
        val wire = ByteArray(1536) { (it % 251).toByte() }
        store.write(
            UserCorpusDb.PendingDocument(
                docId = "circular.md",
                title = "Circular",
                sourceUri = null,
                addedAtUtc = "2026-09-07T00:00:00Z",
                chunks = listOf(UserCorpusDb.PendingChunk(null, "text", null, wire)),
                provenance = Provenance.INSTITUTION,
            )
        )
        val stored = store.conn.query("SELECT vec FROM embeddings") { it.getBlob(0) }.single()
        assertTrue("the server's bytes were altered on the way in", wire.contentEquals(stored))
    }

    // --- fixtures ---------------------------------------------------------

    private fun idsOf(store: UserCorpusDb, docId: String): List<Long> = store.conn.query(
        "SELECT id FROM chunks WHERE doc_id = ?",
        bind = { it.bindText(1, docId) },
    ) { it.getLong(0) }

    private fun doc(
        docId: String,
        contents: List<String>,
        provenance: Provenance = Provenance.USER,
    ) = UserCorpusDb.PendingDocument(
        docId = docId,
        title = docId.substringBeforeLast('.'),
        sourceUri = null,
        addedAtUtc = "2026-09-07T00:00:00Z",
        chunks = contents.map { UserCorpusDb.PendingChunk(null, it, null) },
        provenance = provenance,
    )

    private fun withStore(block: (UserCorpusDb) -> Unit) {
        val conn = JdbcSQLiteDriver().open(":memory:")
        try {
            block(UserCorpusDb.onConnection(conn))
        } finally {
            conn.close()
        }
    }
}
