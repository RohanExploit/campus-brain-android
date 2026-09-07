package com.campusbrain.app

import androidx.sqlite.SQLiteConnection
import com.campusbrain.app.data.Provenance
import com.campusbrain.app.data.UserCorpusDb
import com.campusbrain.app.data.query
import com.campusbrain.app.data.sync.CorpusApi
import com.campusbrain.app.data.sync.CorpusSync
import com.campusbrain.app.data.sync.CorpusSyncStore
import com.campusbrain.app.data.sync.CorpusUpload
import com.campusbrain.app.data.sync.HexBytes
import com.campusbrain.app.data.sync.SyncWatermark
import com.campusbrain.app.jvm.JdbcSQLiteDriver
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * The shared corpus, end to end, against a fake PostgREST that behaves like
 * the migration: one monotonic revision sequence, a trigger that stamps it on
 * every insert and update, and a read filter that hides a publish in flight.
 *
 * Every test here is about a way a sync could quietly corrupt the corpus
 * rather than fail: a document spliced from two revisions, a chunk written
 * twice, a watermark that skips a notice nobody will ever be offered again, a
 * withdrawn notice that goes on answering. None of those raise anything, none
 * of them show on a screen, and all of them are permanent on the one device
 * they happen to.
 *
 * The store underneath is the real [UserCorpusDb] over a real SQLite
 * connection -- see `CorpusStoreTest`, which checks that foundation directly
 * before anything here leans on it.
 */
class CorpusSyncTest {

    // --- the watermark arithmetic, pure -----------------------------------

    @Test fun `the watermark stops at the first document that did not apply`() {
        val results = listOf(
            SyncWatermark.Applied(4, true),
            SyncWatermark.Applied(7, false),
            SyncWatermark.Applied(9, true),
        )
        // NOT 9. Revision 7 is still uploading; taking the highest that
        // happened to work would make the next sync ask for `> 9` and revision
        // 7 would never be offered to this device again.
        assertEquals(4L, SyncWatermark.advance(0L, results))
    }

    @Test fun `a clean batch advances to its last revision`() {
        assertEquals(
            9L,
            SyncWatermark.advance(
                0L,
                listOf(SyncWatermark.Applied(4, true), SyncWatermark.Applied(9, true)),
            ),
        )
    }

    @Test fun `nothing to apply leaves the watermark alone`() {
        assertEquals(12L, SyncWatermark.advance(12L, emptyList()))
        assertEquals(12L, SyncWatermark.advance(12L, listOf(SyncWatermark.Applied(13, false))))
    }

    @Test fun `results out of order are a programming error, not a guess`() {
        assertTrue(
            runCatching {
                SyncWatermark.advance(
                    0L,
                    listOf(SyncWatermark.Applied(9, true), SyncWatermark.Applied(4, true)),
                )
            }.isFailure
        )
    }

    // --- the wire format --------------------------------------------------

    @Test fun `an embedding survives the wire unchanged`() {
        val vector = FloatArray(384) { (it - 192) / 400f }
        val bytes = UserCorpusDb.encodeVector(vector)
        assertEquals(1536, bytes.size)
        val wire = HexBytes.encode(bytes)
        assertTrue(wire.startsWith("\\x"))
        assertEquals(1536 * 2 + 2, wire.length)
        assertTrue(bytes.contentEquals(HexBytes.decode(wire)))
    }

    @Test fun `an unreadable embedding is no embedding, not an empty one`() {
        // An empty ByteArray would be written into embeddings.vec and read
        // back by VectorSearch as a vector of no dimensions, which does not
        // fail loudly -- it ranks the document as noise.
        assertNull(HexBytes.decode(null))
        assertNull(HexBytes.decode(""))
        assertNull(HexBytes.decode("\\xzz"))
        assertNull(HexBytes.decode("\\x0"))
        assertNull(HexBytes.decode("0f1e"))
    }

    // --- a first sync -----------------------------------------------------

    @Test fun `a published document arrives, searchable and attributed`() = withDevice { d ->
        d.server.publish("notices/fee-deadline", "Fee deadline", listOf(
            "The last date for fee payment is the fifteenth of October.",
            "A late fee of five hundred rupees applies after that date.",
        ))

        val outcome = runBlocking { d.sync.sync() }
        assertEquals(1, outcome.applied)
        assertFalse(outcome.offline)
        assertEquals(d.server.headRevision, outcome.watermark)

        val doc = d.corpus.byRemoteDocId("notices/fee-deadline")
        assertNotNull(doc)
        assertEquals(2, chunkCount(d.corpus, doc!!.docId))
        assertEquals(1, matches(d.corpus, "\"late fee\"").size)

        val summary = d.corpus.documents().single()
        assertEquals(Provenance.INSTITUTION, summary.provenance)
        assertFalse("a registrar's circular must not read as the student's own", summary.isUserAdded)
    }

    @Test fun `the same batch applied twice leaves one set of rows`() = withDevice { d ->
        d.server.publish("notices/a", "A", listOf("First.", "Second."))
        runBlocking { d.sync.sync() }
        val after = chunkCount(d.corpus, d.corpus.byRemoteDocId("notices/a")!!.docId)

        // The watermark is thrown away, which is what a reinstall of the app's
        // bookkeeping or an unreadable sync_state row looks like. The device
        // is offered the whole corpus again and must recognise it.
        assertTrue(d.state.setWatermark(SyncWatermark.NEVER, 0L))
        val second = runBlocking { d.sync.sync() }

        assertEquals(0, second.applied)
        assertEquals(1, second.unchanged)
        assertEquals(after, chunkCount(d.corpus, d.corpus.byRemoteDocId("notices/a")!!.docId))
        assertEquals(1, d.corpus.documents().size)
    }

    // --- interruption -----------------------------------------------------

    @Test fun `a sync interrupted mid-document resumes without duplicating`() = withDevice { d ->
        d.server.publish("notices/long", "Long", (1..250).map { "Paragraph number $it." })

        // Die on the second page of chunks. The first page is staged; nothing
        // is searchable; the watermark has not moved.
        d.server.failGetsMatching("corpus_chunks", after = 1)
        val first = runBlocking { d.sync.sync() }
        assertEquals(0, first.applied)
        assertEquals(1, first.deferred)
        assertEquals(SyncWatermark.NEVER, first.watermark)
        assertEquals(0, d.corpus.documents().size)
        assertTrue("half a document was left searchable", matches(d.corpus, "paragraph").isEmpty())

        d.server.clearFailures()
        val second = runBlocking { d.sync.sync() }
        assertEquals(1, second.applied)
        val docId = d.corpus.byRemoteDocId("notices/long")!!.docId
        assertEquals(250, chunkCount(d.corpus, docId))
        // The count is the assertion that matters: a resume that re-fetched
        // and re-wrote the pages it already had would give 50 + a page.
        assertEquals(250, matches(d.corpus, "paragraph").size)
    }

    @Test fun `a resume does not re-download what it already staged`() = withDevice { d ->
        d.server.publish("notices/long", "Long", (1..250).map { "Paragraph number $it." })
        d.server.failGetsMatching("corpus_chunks", after = 1)
        runBlocking { d.sync.sync() }
        val staged = d.state.stagedOrdinals("notices/long")
        assertTrue("nothing was staged, so nothing can be resumed", staged.isNotEmpty())

        assertEquals(CorpusSync.CHUNK_PAGE_SIZE, staged.size)

        d.server.clearFailures()
        d.server.chunkRowsServed = 0
        runBlocking { d.sync.sync() }
        // The whole point of staging: the resume asks for the 150 it does not
        // have and not for the 100 it does.
        assertEquals(250 - CorpusSync.CHUNK_PAGE_SIZE, d.server.chunkRowsServed)
        assertEquals(250, chunkCount(d.corpus, d.corpus.byRemoteDocId("notices/long")!!.docId))
        // And the staging table is emptied once the document is real, so it
        // does not grow into a second copy of the corpus.
        assertTrue(d.state.stagedOrdinals("notices/long").isEmpty())
    }

    @Test fun `a republish during a pull is refused rather than spliced`() = withDevice { d ->
        d.server.publish("notices/x", "X", (1..250).map { "Original paragraph $it." })
        d.server.failGetsMatching("corpus_chunks", after = 1)
        runBlocking { d.sync.sync() }
        assertTrue(d.state.stagedOrdinals("notices/x").isNotEmpty())

        // The registrar corrects the notice while this device is halfway
        // through the old one. A resume that took ordinals 31-50 from the new
        // revision would build a document out of two versions, with no
        // duplicate rows and nothing that could later notice.
        d.server.clearFailures()
        d.server.publish("notices/x", "X", (1..250).map { "Corrected paragraph $it." })

        runBlocking { d.sync.sync() }
        val docId = d.corpus.byRemoteDocId("notices/x")!!.docId
        val contents = d.corpus.conn.query(
            "SELECT content FROM chunks WHERE doc_id = ? ORDER BY id",
            bind = { it.bindText(1, docId) },
        ) { it.getText(0) }
        assertEquals(250, contents.size)
        assertTrue(
            "the document was spliced out of two revisions",
            contents.all { it.startsWith("Corrected") },
        )
    }

    @Test fun `a document still uploading is never pulled`() = withDevice { d ->
        // chunk_count is written before the first chunk goes up and
        // published_at only after the last one, so a device sees nothing.
        d.server.openDraft("notices/inflight", "In flight", chunkCount = 40)
        d.server.putChunks("notices/inflight", (1..12).map { "Half a notice, part $it." })

        val outcome = runBlocking { d.sync.sync() }
        assertEquals(0, outcome.applied)
        assertEquals(0, outcome.deferred)
        assertEquals(0, d.corpus.documents().size)
        assertEquals(SyncWatermark.NEVER, outcome.watermark)
    }

    // --- failure leaves the device answerable ------------------------------

    @Test fun `a failed sync leaves the corpus that was already there`() = withDevice { d ->
        d.server.publish("notices/first", "First", listOf("Attendance must be at least seventy five percent."))
        runBlocking { d.sync.sync() }
        val watermark = d.state.watermark()
        assertEquals(1, matches(d.corpus, "attendance").size)

        d.server.publish("notices/second", "Second", listOf("A second notice."))
        d.server.offline = true
        val outcome = runBlocking { d.sync.sync() }

        assertTrue(outcome.offline)
        assertEquals(0, outcome.applied)
        // Everything about answering is exactly as it was. This is the whole
        // product claim: a device whose sync failed answers the way it did
        // before, offline, forever.
        assertEquals(1, matches(d.corpus, "attendance").size)
        assertEquals(1, d.corpus.documents().size)
        assertEquals(watermark, d.state.watermark())
    }

    @Test fun `a device that has never synced still holds what the student imported`() =
        withDevice { d ->
            d.corpus.write(
                UserCorpusDb.PendingDocument(
                    docId = "my notes.md",
                    title = "My notes",
                    sourceUri = null,
                    addedAtUtc = "2026-09-07T00:00:00Z",
                    chunks = listOf(UserCorpusDb.PendingChunk(null, "Something I wrote down.", null)),
                )
            )
            d.server.offline = true
            val outcome = runBlocking { d.sync.sync() }
            assertTrue(outcome.offline)
            assertEquals(1, matches(d.corpus, "wrote").size)
            assertEquals(Provenance.USER, d.corpus.documents().single().provenance)
        }

    // --- withdrawal --------------------------------------------------------

    @Test fun `a withdrawn notice stops answering and moves the watermark forward`() =
        withDevice { d ->
            d.server.publish("notices/w", "W", listOf("Applications close on the tenth."))
            runBlocking { d.sync.sync() }
            val afterPublish = d.state.watermark()
            assertEquals(1, matches(d.corpus, "applications").size)

            d.server.withdraw("notices/w")
            val outcome = runBlocking { d.sync.sync() }

            assertEquals(1, outcome.withdrawn)
            assertTrue(
                "a withdrawal that does not move the revision is a tombstone nobody visits",
                d.state.watermark() > afterPublish,
            )
            assertTrue(
                "the phone is still answering from a withdrawn notice",
                matches(d.corpus, "applications").isEmpty(),
            )
            assertNull(d.corpus.byRemoteDocId("notices/w"))
            assertEquals(0, d.corpus.documents().size)
        }

    @Test fun `a withdrawal for a document this device never had is still applied`() =
        withDevice { d ->
            d.server.publish("notices/gone", "Gone", listOf("Never seen here."))
            d.server.withdraw("notices/gone")
            // The device was offline for the whole life of the notice.
            val outcome = runBlocking { d.sync.sync() }
            assertEquals(1, outcome.withdrawn)
            assertEquals(d.server.headRevision, outcome.watermark)
        }

    @Test fun `a deferred document does not let a later one carry the watermark past it`() =
        withDevice { d ->
            d.server.publish("notices/one", "One", listOf("The first notice."))
            d.server.openDraft("notices/two", "Two", chunkCount = 3)
            // Published, but only one of its three chunks is up: the server's
            // own filter would hide this, so the test forces the row visible
            // to exercise the CLIENT's chunk_count guard.
            d.server.putChunks("notices/two", listOf("Only a third of it."))
            d.server.forcePublished("notices/two")
            d.server.publish("notices/three", "Three", listOf("The third notice."))

            val outcome = runBlocking { d.sync.sync() }
            assertEquals(1, outcome.applied)
            assertEquals(1, outcome.deferred)
            assertEquals(d.server.revisionOf("notices/one"), outcome.watermark)
            assertTrue(
                "the watermark jumped the incomplete document",
                outcome.watermark < d.server.revisionOf("notices/two"),
            )
            assertNull(d.corpus.byRemoteDocId("notices/two"))
            assertNull(d.corpus.byRemoteDocId("notices/three"))
        }

    @Test fun `a document that can never be applied is visible as a stall`() = withDevice { d ->
        d.server.publish("notices/one", "One", listOf("The first notice."))
        d.server.openDraft("notices/wedged", "Wedged", chunkCount = 3)
        d.server.putChunks("notices/wedged", listOf("Only a third of it."))
        d.server.forcePublished("notices/wedged")

        val first = runBlocking { d.sync.sync() }
        assertNotNull(first.stall)
        assertEquals(d.server.revisionOf("notices/wedged"), first.stall!!.revision)
        assertEquals(1, first.stall!!.runs)

        // The count is what separates "a publish was in flight when we looked"
        // from "this device has been stuck on the same document for a week".
        // Nothing here acts on it: a stalled device still answers from
        // everything it already has, which is the point.
        val second = runBlocking { d.sync.sync() }
        assertEquals(2, second.stall!!.runs)

        // And it clears itself when the document becomes applicable.
        d.server.putChunks("notices/wedged", listOf("A third.", "Another.", "The last."))
        val third = runBlocking { d.sync.sync() }
        assertNull(third.stall)
        assertEquals(1, third.applied)
    }

    @Test fun `being offline is not a stall`() = withDevice { d ->
        d.server.publish("notices/one", "One", listOf("The first notice."))
        runBlocking { d.sync.sync() }
        d.server.offline = true
        val outcome = runBlocking { d.sync.sync() }
        assertTrue(outcome.offline)
        // A week in airplane mode must not read as a broken corpus.
        assertNull(outcome.stall)
    }

    // --- provenance --------------------------------------------------------

    @Test fun `bundle, institution and the student's own are all distinguishable`() =
        withDevice { d ->
            d.server.publish("notices/college", "College", listOf("The college says so."))
            runBlocking { d.sync.sync() }
            d.corpus.write(
                UserCorpusDb.PendingDocument(
                    docId = "mine.md", title = "Mine", sourceUri = null,
                    addedAtUtc = "2026-09-07T00:00:00Z",
                    chunks = listOf(UserCorpusDb.PendingChunk(null, "I say so.", null)),
                )
            )

            val byProvenance = d.corpus.documents().associateBy { it.provenance }
            assertEquals("notices/college", byProvenance.getValue(Provenance.INSTITUTION).docId)
            assertEquals("mine.md", byProvenance.getValue(Provenance.USER).docId)
            // The bundle is the third source and is the absence of an id in
            // this file at all: every chunk here is >= ID_BASE, and everything
            // below it lives in brain.db.
            assertEquals(Provenance.BUNDLE, UserCorpusDb.provenanceOf(17L))

            val ids = d.corpus.conn.query("SELECT id, doc_id FROM chunks") {
                it.getLong(0) to it.getText(1)
            }
            ids.forEach { (id, docId) ->
                val expected =
                    if (docId == "mine.md") Provenance.USER else Provenance.INSTITUTION
                assertEquals("chunk $id of $docId", expected, UserCorpusDb.provenanceOf(id))
            }
        }

    // --- the publish path --------------------------------------------------

    @Test fun `a registrar's publish is what another device syncs down`() = withDevice { d ->
        // The registrar's own phone: the document is ingested locally by the
        // pipeline that already exists, with real vectors, and published from
        // there. Nothing chunks or embeds anything server-side.
        val vector = UserCorpusDb.encodeVector(FloatArray(384) { it / 1000f })
        d.corpus.write(
            UserCorpusDb.PendingDocument(
                docId = "hostel rules.docx",
                title = "Hostel rules",
                sourceUri = null,
                addedAtUtc = "2026-09-07T00:00:00Z",
                chunks = listOf(
                    UserCorpusDb.PendingChunk("Curfew", "The gate closes at ten.", null, vector),
                    UserCorpusDb.PendingChunk("Guests", "Guests must sign in.", null, vector),
                ),
                sizeBytes = 40_000L,
            )
        )
        val upload = CorpusUpload(d.server, d.corpus) { "2026-09-07T10:00:00Z" }
        val result = runBlocking { upload.publish("hostel rules.docx", "hostel/rules") }
        assertTrue("$result", result is CorpusUpload.Result.Published)

        // Now a student's device, with its own empty store.
        withDevice(d.server) { student ->
            val outcome = runBlocking { student.sync.sync() }
            assertEquals(1, outcome.applied)
            val docId = student.corpus.byRemoteDocId("hostel/rules")!!.docId
            val rows = student.corpus.conn.query(
                "SELECT c.section, c.content, e.vec FROM chunks c " +
                    "LEFT JOIN embeddings e ON e.chunk_id = c.id WHERE c.doc_id = ? ORDER BY c.id",
                bind = { it.bindText(1, docId) },
            ) { Triple(it.getText(0), it.getText(1), it.getBlob(2)) }
            assertEquals(2, rows.size)
            assertEquals("Curfew", rows[0].first)
            assertEquals("The gate closes at ten.", rows[0].second)
            // The bytes the registrar's ONNX model produced, byte for byte.
            // Anything else here and the two devices are ranking against
            // different vectors while believing they share a space.
            assertTrue(vector.contentEquals(rows[0].third))
            assertEquals(1, matches(student.corpus, "curfew OR gate").size)
        }
    }

    @Test fun `an interrupted upload sends only what is missing`() = withDevice { d ->
        d.corpus.write(
            UserCorpusDb.PendingDocument(
                docId = "long.md", title = "Long", sourceUri = null,
                addedAtUtc = "2026-09-07T00:00:00Z",
                chunks = (1..250).map { UserCorpusDb.PendingChunk(null, "Section $it.", null) },
            )
        )
        val upload = CorpusUpload(d.server, d.corpus) { "2026-09-07T10:00:00Z" }

        d.server.failPostsMatching("corpus_chunks", after = 1)
        val first = runBlocking { upload.publish("long.md", "policy/long") }
        assertTrue("$first", first is CorpusUpload.Result.Unavailable)
        val partial = d.server.chunkCountOf("policy/long")
        assertTrue(partial in 1 until 250)
        assertFalse("an unfinished upload must not be publishable", d.server.isPublished("policy/long"))

        d.server.clearFailures()
        d.server.countChunkPosts = 0
        val second = runBlocking { upload.publish("long.md", "policy/long") }
        assertTrue("$second", second is CorpusUpload.Result.Published)
        assertEquals(250, d.server.chunkCountOf("policy/long"))
        assertEquals(
            "the resume re-sent chunks the server already had",
            250 - partial, d.server.countChunkPosts,
        )
    }

    @Test fun `a student's device is refused the publish path by the server`() = withDevice { d ->
        d.corpus.write(
            UserCorpusDb.PendingDocument(
                docId = "mine.md", title = "Mine", sourceUri = null,
                addedAtUtc = "2026-09-07T00:00:00Z",
                chunks = listOf(UserCorpusDb.PendingChunk(null, "Mine.", null)),
            )
        )
        // What RLS does to a member whose role is neither admin nor registrar.
        d.server.canPublish = false
        val result = runBlocking { CorpusUpload(d.server, d.corpus).publish("mine.md") }
        assertEquals(CorpusUpload.Result.Refused(403), result)
        assertEquals(0, d.server.documentCount)
    }

    @Test fun `publishing something this device does not have is not an error`() = withDevice { d ->
        assertEquals(
            CorpusUpload.Result.NotFound,
            runBlocking { CorpusUpload(d.server, d.corpus).publish("nothing.md") },
        )
    }

    // --- harness -----------------------------------------------------------

    private fun chunkCount(corpus: UserCorpusDb, docId: String): Int = corpus.conn.query(
        "SELECT COUNT(*) FROM chunks WHERE doc_id = ?",
        bind = { it.bindText(1, docId) },
    ) { it.getLong(0) }.first().toInt()

    /** Keyword hits, through the external-content FTS5 index the write path
     *  is responsible for keeping in step. */
    private fun matches(corpus: UserCorpusDb, match: String): List<Long> = corpus.conn.query(
        "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH ?",
        bind = { it.bindText(1, match) },
    ) { it.getLong(0) }

    private class Device(
        val conn: SQLiteConnection,
        val corpus: UserCorpusDb,
        val state: CorpusSyncStore,
        val sync: CorpusSync,
        val server: FakeCorpusServer,
    )

    private fun withDevice(server: FakeCorpusServer? = null, block: (Device) -> Unit) {
        val conn = JdbcSQLiteDriver().open(":memory:")
        try {
            val corpus = UserCorpusDb.onConnection(conn)
            val state = CorpusSyncStore(conn)
            state.ensureSchema()
            val api = server ?: FakeCorpusServer()
            block(Device(conn, corpus, state, CorpusSync(api, corpus, state) { 1_000L }, api))
        } finally {
            conn.close()
        }
    }

    /**
     * A PostgREST that behaves like the migration.
     *
     * Specifically: one global revision sequence, stamped on every insert and
     * every update including a withdrawal; `published_at` null while a publish
     * is in flight; and a read filter that returns only rows which are
     * published or withdrawn. Those are the three server-side properties the
     * client's correctness depends on, so they are modelled rather than
     * assumed.
     */
    private class FakeCorpusServer : CorpusApi {

        class Doc(
            var title: String,
            var category: String?,
            var sizeBytes: Long?,
            var chunkCount: Int,
            var revision: Long,
            var publishedAt: String?,
            var withdrawnAt: String?,
        )

        private val docs = LinkedHashMap<String, Doc>()
        private val chunks = HashMap<String, MutableMap<Int, Triple<String?, String, String?>>>()
        private var seq = 0L

        var offline = false
        var canPublish = true
        var chunkRowsServed = 0
        var countChunkPosts = 0
        private var failGetPath: String? = null
        private var failGetAfter = 0
        private var failPostPath: String? = null
        private var failPostAfter = 0

        val headRevision: Long get() = seq
        val documentCount: Int get() = docs.size

        fun revisionOf(docId: String): Long = docs.getValue(docId).revision
        fun isPublished(docId: String): Boolean = docs[docId]?.publishedAt != null
        fun chunkCountOf(docId: String): Int = chunks[docId]?.size ?: 0

        fun failGetsMatching(path: String, after: Int) {
            failGetPath = path; failGetAfter = after
        }

        fun failPostsMatching(path: String, after: Int) {
            failPostPath = path; failPostAfter = after
        }

        fun clearFailures() {
            failGetPath = null; failPostPath = null
        }

        // --- what a registrar does, modelled directly ---------------------

        fun openDraft(docId: String, title: String, chunkCount: Int) {
            val doc = docs.getOrPut(docId) {
                Doc(title, null, null, chunkCount, 0L, null, null)
            }
            doc.title = title
            doc.chunkCount = chunkCount
            doc.publishedAt = null
            doc.withdrawnAt = null
            doc.revision = ++seq
        }

        fun putChunks(docId: String, contents: List<String>) {
            val map = chunks.getOrPut(docId) { HashMap() }
            contents.forEachIndexed { i, text -> map[i] = Triple(null, text, null) }
        }

        fun forcePublished(docId: String) {
            docs.getValue(docId).publishedAt = "2026-09-07T00:00:00Z"
            docs.getValue(docId).revision = ++seq
        }

        fun publish(docId: String, title: String, contents: List<String>) {
            openDraft(docId, title, contents.size)
            chunks[docId] = HashMap()
            putChunks(docId, contents)
            forcePublished(docId)
        }

        fun withdraw(docId: String) {
            val doc = docs.getValue(docId)
            doc.withdrawnAt = "2026-09-07T12:00:00Z"
            doc.publishedAt = null
            doc.chunkCount = 0
            doc.revision = ++seq
            chunks.remove(docId)
        }

        // --- the HTTP surface ---------------------------------------------

        override suspend fun get(path: String): CorpusApi.Response? {
            if (offline) return null
            if (failGetPath != null && path.startsWith(failGetPath!!)) {
                if (failGetAfter <= 0) return null
                failGetAfter--
            }
            return when {
                path.startsWith("corpus_documents?select=revision") ->
                    json(JSONArray().apply {
                        docs[docIdOf(path)]?.let { put(JSONObject().put("revision", it.revision)) }
                    })

                path.startsWith("corpus_documents?select=published_at") ->
                    json(JSONArray().apply {
                        docs[docIdOf(path)]?.let {
                            put(JSONObject().put("published_at", it.publishedAt ?: ""))
                        }
                    })

                path.startsWith("corpus_documents?select=doc_id") -> {
                    val above = path.substringAfter("revision=gt.").substringBefore('&').toLong()
                    val limit = path.substringAfter("limit=").toInt()
                    val rows = docs.entries
                        // The server's own filter: a publish in flight is not
                        // a row any device is offered.
                        .filter { it.value.publishedAt != null || it.value.withdrawnAt != null }
                        .filter { it.value.revision > above }
                        .sortedBy { it.value.revision }
                        .take(limit)
                    json(JSONArray().apply { rows.forEach { put(documentRow(it.key, it.value)) } })
                }

                path.startsWith("corpus_chunks?select=ordinal,") -> {
                    val docId = docIdOf(path)
                    val from = path.substringAfter("ordinal=gte.").substringBefore('&').toInt()
                    val limit = path.substringAfter("limit=").toInt()
                    val rows = (chunks[docId] ?: emptyMap()).entries
                        .filter { it.key >= from }.sortedBy { it.key }.take(limit)
                    chunkRowsServed += rows.size
                    json(JSONArray().apply {
                        rows.forEach { (ordinal, c) ->
                            put(JSONObject()
                                .put("ordinal", ordinal)
                                .put("section", c.first ?: JSONObject.NULL)
                                .put("content", c.second)
                                .put("embedding", c.third ?: JSONObject.NULL))
                        }
                    })
                }

                path.startsWith("corpus_chunks?select=ordinal&") -> {
                    val docId = docIdOf(path)
                    json(JSONArray().apply {
                        (chunks[docId] ?: emptyMap()).keys.sorted()
                            .forEach { put(JSONObject().put("ordinal", it)) }
                    })
                }

                else -> CorpusApi.Response(400, "unexpected: $path")
            }
        }

        override suspend fun post(
            path: String,
            body: String,
            prefer: String,
        ): CorpusApi.Response? {
            if (offline) return null
            if (!canPublish) return CorpusApi.Response(403, """{"code":"42501"}""")
            if (failPostPath != null && path.startsWith(failPostPath!!)) {
                if (failPostAfter <= 0) return null
                failPostAfter--
            }
            val rows = JSONArray(body)
            when {
                path == "corpus_documents" -> {
                    val row = rows.getJSONObject(0)
                    val docId = row.getString("doc_id")
                    docs[docId] = Doc(
                        title = row.optString("title"),
                        category = row.optString("category").takeIf { it.isNotBlank() },
                        sizeBytes = row.optLong("size_bytes", -1L).takeIf { it >= 0 },
                        chunkCount = row.optInt("chunk_count"),
                        revision = ++seq,
                        publishedAt = null,
                        withdrawnAt = null,
                    )
                }
                path == "corpus_chunks" -> {
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val map = chunks.getOrPut(row.getString("doc_id")) { HashMap() }
                        val ordinal = row.getInt("ordinal")
                        // The unique constraint. A duplicate is an error, not
                        // a silent overwrite, so a client that re-sent a chunk
                        // it should have skipped is caught rather than
                        // flattered.
                        if (map.containsKey(ordinal)) {
                            return CorpusApi.Response(409, """{"code":"23505"}""")
                        }
                        map[ordinal] = Triple(
                            row.optString("section").takeIf { it.isNotBlank() },
                            row.getString("content"),
                            row.optString("embedding").takeIf { it.isNotBlank() },
                        )
                        countChunkPosts++
                    }
                }
                else -> return CorpusApi.Response(400, "unexpected: $path")
            }
            return CorpusApi.Response(201, "")
        }

        override suspend fun patch(path: String, body: String): CorpusApi.Response? {
            if (offline) return null
            if (!canPublish) return CorpusApi.Response(403, """{"code":"42501"}""")
            val doc = docs[docIdOf(path)] ?: return CorpusApi.Response(404, "")
            val row = JSONObject(body)
            if (row.has("title")) doc.title = row.getString("title")
            if (row.has("chunk_count")) doc.chunkCount = row.getInt("chunk_count")
            if (row.has("size_bytes")) doc.sizeBytes = row.optLong("size_bytes")
            if (row.has("category")) doc.category = row.optString("category")
            if (row.has("published_at")) {
                doc.publishedAt = if (row.isNull("published_at")) null
                else row.getString("published_at")
            }
            if (row.has("withdrawn_at")) {
                doc.withdrawnAt = if (row.isNull("withdrawn_at")) null
                else row.getString("withdrawn_at")
            }
            // The trigger, which is what makes a withdrawal observable.
            doc.revision = ++seq
            return CorpusApi.Response(204, "")
        }

        override suspend fun delete(path: String): CorpusApi.Response? {
            if (offline) return null
            if (!canPublish) return CorpusApi.Response(403, """{"code":"42501"}""")
            chunks.remove(docIdOf(path))
            return CorpusApi.Response(204, "")
        }

        private fun documentRow(docId: String, doc: Doc) = JSONObject()
            .put("doc_id", docId)
            .put("title", doc.title)
            .put("category", doc.category ?: JSONObject.NULL)
            .put("size_bytes", doc.sizeBytes ?: JSONObject.NULL)
            .put("chunk_count", doc.chunkCount)
            .put("revision", doc.revision)
            .put("published_at", doc.publishedAt ?: JSONObject.NULL)
            .put("withdrawn_at", doc.withdrawnAt ?: JSONObject.NULL)

        private fun json(array: JSONArray) = CorpusApi.Response(200, array.toString())

        /** Undoes [CorpusSync.encode]: percent-decode, then unquote. */
        private fun docIdOf(path: String): String {
            val raw = path.substringAfter("doc_id=eq.").substringBefore('&')
            val decoded = URLDecoder.decode(raw, "UTF-8")
            return decoded.removeSurrounding("\"").replace("\\\"", "\"")
        }
    }
}
