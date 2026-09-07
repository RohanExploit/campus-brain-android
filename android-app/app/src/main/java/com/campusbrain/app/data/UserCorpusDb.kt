package com.campusbrain.app.data

import android.content.Context
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A second, writable corpus holding only what the user added. Never
 * `brain.db`.
 *
 * This is the whole safety argument for document injection, so it is worth
 * stating plainly rather than leaving it implicit in the file layout.
 *
 *  - `brain.db` is opened with `PRAGMA query_only = ON` (see [BrainDb.openAt])
 *    and stays that way. No ingestion path can corrupt the bundled corpus,
 *    because no ingestion path can write to it. That is a stronger guarantee
 *    than "we use a transaction".
 *  - `BrainDb.open` re-copies the asset whenever `built_at_utc` changes, which
 *    is exactly what an app update does. Anything written into `brain.db`
 *    would be silently destroyed by the next release. This file is not
 *    re-copied and not stamped, so **a user's documents survive every app
 *    update untouched, with no merge step to get wrong.**
 *  - If this file is missing, corrupt, or on a device whose storage rejects
 *    it, [openOrCreate] returns null and every caller degrades to the bundled
 *    corpus alone. A broken user database must never take the college's
 *    documents down with it.
 *
 * The schema is a copy of the bundle's, down to the FTS5 tokenizer, because
 * the two are searched as one corpus and a different analyzer would give the
 * user's own documents systematically different bm25 scores.
 */
class UserCorpusDb private constructor(
    val conn: SQLiteConnection,
    val path: String,
) {

    /**
     * One chunk on its way in: text, its heading, and its vector if we have
     * one.
     *
     * The vector arrives one of two ways and they are deliberately different
     * fields rather than one converted into the other.
     *
     *  - [vec] is what [DocumentIngest] produces: the ONNX embedder's own
     *    `FloatArray`, encoded here by [encodeVector].
     *  - [vecBlob] is what `data/sync/CorpusSync` produces: the exact bytes
     *    the registrar's device uploaded, carried through the server as
     *    `bytea` and written back down untouched.
     *
     * Decoding [vecBlob] into a `FloatArray` on the way in would put an IEEE
     * 754 round trip -- and Java's NaN canonicalisation -- between two halves
     * of what is supposed to be the same vector. The whole reason the server
     * stores bytes rather than `vector(384)` is that the bytes can make the
     * trip unexamined; taking them apart here would spend that guarantee for
     * nothing. [vecBlob] wins if both are set.
     */
    data class PendingChunk(
        val section: String?,
        val content: String,
        val vec: FloatArray?,
        val vecBlob: ByteArray? = null,
    )

    data class PendingDocument(
        val docId: String,
        val title: String,
        val sourceUri: String?,
        val addedAtUtc: String,
        val chunks: List<PendingChunk>,
        /**
         * The source file's size, for the licence layer's total-KB cap.
         *
         * The file's bytes, not the extracted text's: an institution buys an
         * allowance against the documents it hands the app, and a .docx that
         * is 400KB of zip and 30KB of prose is a 400KB document to the person
         * who chose it. Nullable and defaulted so nothing that predates the
         * cap has to know about it, and so a row written before this column
         * existed reads back as "unknown" rather than as zero.
         */
        val sizeBytes: Long? = null,
        /**
         * Whose document this is. [Provenance.USER] for an import,
         * [Provenance.INSTITUTION] for a synced one. [Provenance.BUNDLE] is
         * not writable here and is coerced to [Provenance.USER] rather than
         * throwing -- nothing can put a row in `brain.db`, so the value would
         * be a category error rather than a dangerous one.
         */
        val provenance: Provenance = Provenance.USER,
        /**
         * The server's `corpus_documents.doc_id` for a synced document.
         *
         * Kept because the local [docId] may differ: a student who imported a
         * file called "attendance_policy.md" before the registrar published a
         * document with that id would otherwise have their own file replaced
         * by the college's, since `doc_id` is the primary key here. The sync
         * path allocates a free local id and remembers the remote one, and
         * **every sync-side lookup keys on this column, never on [docId]** --
         * keying on the local id would make a suffixed document re-insert
         * itself as a second copy on the next run.
         */
        val remoteDocId: String? = null,
        /** The `corpus_documents.revision` this content was pulled at. */
        val revision: Long? = null,
    )

    /**
     * Writes a whole document or none of it.
     *
     * BEGIN IMMEDIATE rather than a deferred transaction: the write locks are
     * taken up front, so a failure happens before any row exists rather than
     * halfway through 50 embeddings. A half-ingested document is worse than a
     * rejected one -- it is silently missing content the user believes is
     * searchable, and nothing in the UI could tell them which half.
     */
    fun write(doc: PendingDocument): Int {
        val band = Band.of(doc.provenance)
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            var id = nextChunkId(band)
            // The band has 1,000,000,000 ids in it and a document is capped at
            // 600 chunks, so this cannot fire in practice. It is here because
            // the consequence if it ever did is not an error, it is a chunk
            // silently allocated into the next band and therefore attributed
            // to the wrong source for the rest of its life.
            check(id + doc.chunks.size <= band.ceiling) {
                "chunk id band ${band.base} is full"
            }
            conn.prepare(
                "INSERT INTO chunks(id, doc_id, section, content) VALUES (?, ?, ?, ?)"
            ).use { insertChunk ->
                conn.prepare(
                    "INSERT INTO chunks_fts(rowid, content, doc_id) VALUES (?, ?, ?)"
                ).use { insertFts ->
                    conn.prepare(
                        "INSERT INTO embeddings(chunk_id, vec) VALUES (?, ?)"
                    ).use { insertVec ->
                        for (c in doc.chunks) {
                            insertChunk.reset()
                            insertChunk.bindLong(1, id)
                            insertChunk.bindText(2, doc.docId)
                            if (c.section == null) insertChunk.bindNull(3)
                            else insertChunk.bindText(3, c.section)
                            insertChunk.bindText(4, c.content)
                            insertChunk.step()

                            // chunks_fts is an external-content table
                            // (content='chunks'), so it does NOT index a row
                            // just because chunks got one. The companion insert
                            // is mandatory; without it the document is stored
                            // and permanently unfindable by keyword.
                            insertFts.reset()
                            insertFts.bindLong(1, id)
                            insertFts.bindText(2, c.content)
                            insertFts.bindText(3, doc.docId)
                            insertFts.step()

                            // vecBlob first: bytes that came off the wire are
                            // written exactly as they arrived. See PendingChunk.
                            val blob = c.vecBlob ?: c.vec?.let { encodeVector(it) }
                            if (blob != null) {
                                insertVec.reset()
                                insertVec.bindLong(1, id)
                                insertVec.bindBlob(2, blob)
                                insertVec.step()
                            }
                            id++
                        }
                    }
                }
            }
            conn.prepare(
                "INSERT OR REPLACE INTO documents" +
                    "(doc_id, title, category, chunk_count, preview, source_uri, added_at_utc, " +
                    " size_bytes, origin, remote_doc_id, revision) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            ).use { st ->
                st.bindText(1, doc.docId)
                st.bindText(2, doc.title)
                // An institution document keeps its own category so the
                // Documents list can file it under the college's heading
                // rather than under "Added by you", which it is not.
                st.bindText(3, if (band == Band.INSTITUTION) SYNCED_CATEGORY else ADDED_CATEGORY)
                st.bindLong(4, doc.chunks.size.toLong())
                val preview = doc.chunks.firstOrNull()?.content?.take(200)
                if (preview == null) st.bindNull(5) else st.bindText(5, preview)
                if (doc.sourceUri == null) st.bindNull(6) else st.bindText(6, doc.sourceUri)
                st.bindText(7, doc.addedAtUtc)
                if (doc.sizeBytes == null) st.bindNull(8) else st.bindLong(8, doc.sizeBytes)
                st.bindText(9, band.origin)
                if (doc.remoteDocId == null) st.bindNull(10) else st.bindText(10, doc.remoteDocId)
                if (doc.revision == null) st.bindNull(11) else st.bindLong(11, doc.revision)
                st.step()
            }
            conn.execSQL("COMMIT")
            return doc.chunks.size
        } catch (t: Throwable) {
            // Rolling back is not optional and must not itself throw into the
            // caller: a failed rollback would leave the connection inside a
            // transaction and break every subsequent write.
            runCatching { conn.execSQL("ROLLBACK") }
            throw t
        }
    }

    /**
     * Removes a document the STUDENT added. False for anything else.
     *
     * The distinction is structural rather than a rule the Documents screen
     * remembers. A synced institution document is not this device's to delete:
     * sync only ever asks the server for `revision > watermark`, so a document
     * removed here is gone from this phone permanently, while the watermark
     * goes on asserting it was applied. The student would simply stop getting
     * answers from a college document, with nothing on screen and no way back
     * short of clearing the app's data.
     *
     * `DocsFragment` already gates its delete button on
     * [DocumentSummary.isUserAdded], which is now correctly false for a synced
     * document -- but the registrar surface Phase 2 adds will list these
     * documents, and a gate that lives only in a fragment is a gate the next
     * screen has to remember to build. This one it cannot get wrong.
     *
     * `data/sync/CorpusSync` uses [remove] instead, and must: applying a
     * withdrawal and replacing a revision both mean deleting exactly the
     * documents this refuses to touch.
     */
    fun removeOwn(docId: String): Boolean {
        val origin = runCatching {
            conn.query(
                "SELECT origin FROM documents WHERE doc_id = ?",
                bind = { it.bindText(1, docId) },
            ) { if (it.isNull(0)) Band.USER.origin else it.getText(0) }.firstOrNull()
        }.getOrNull() ?: return false
        if (Band.provenanceOf(origin) != Provenance.USER) return false
        return remove(docId)
    }

    /**
     * Removes a document and everything indexed from it, whoever it belongs
     * to. False if unknown.
     *
     * The unconditional primitive. Student-facing callers want [removeOwn];
     * this exists for the sync path, which has to be able to take an
     * institution document away when the institution withdraws it.
     */
    fun remove(docId: String): Boolean {
        // Opening the transaction is itself fallible (a locked database, a
        // read-only filesystem). Outside the try it would throw past a caller
        // whose contract is a Boolean.
        if (runCatching { conn.execSQL("BEGIN IMMEDIATE") }.isFailure) return false
        return try {
            // The content is read out BEFORE anything is deleted, because an
            // external-content FTS5 table keeps no copy of the text: the
            // delete command has to be handed the exact indexed values back.
            // Delete the chunks row first and the index can never be cleaned
            // up -- the terms stay, and a removed document goes on matching
            // queries while having nothing left to cite.
            val doomed = conn.query(
                "SELECT id, content, doc_id FROM chunks WHERE doc_id = ?",
                bind = { it.bindText(1, docId) },
            ) { Triple(it.getLong(0), it.getText(1), it.getText(2)) }
            if (doomed.isEmpty()) {
                conn.execSQL("ROLLBACK")
                false
            } else {
                // The VALUES form, one row at a time. An `INSERT ... SELECT`
                // carrying the same four values was measured to work and to
                // leave the index passing 'integrity-check' -- but this is the
                // form SQLite documents, it costs one loop over a handful of
                // rows, and it is the difference between a data-integrity path
                // that is specified and one that merely tested clean on a
                // version that is not the one on the phone.
                conn.prepare(
                    "INSERT INTO chunks_fts(chunks_fts, rowid, content, doc_id) " +
                        "VALUES ('delete', ?, ?, ?)"
                ).use { st ->
                    for ((id, content, doc) in doomed) {
                        st.reset()
                        st.bindLong(1, id)
                        st.bindText(2, content)
                        st.bindText(3, doc)
                        st.step()
                    }
                }
                conn.prepare("DELETE FROM embeddings WHERE chunk_id IN " +
                    "(SELECT id FROM chunks WHERE doc_id = ?)").use { it.bindText(1, docId); it.step() }
                conn.prepare("DELETE FROM chunks WHERE doc_id = ?").use { it.bindText(1, docId); it.step() }
                conn.prepare("DELETE FROM documents WHERE doc_id = ?").use { it.bindText(1, docId); it.step() }
                conn.execSQL("COMMIT")
                true
            }
        } catch (t: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            Log.w(TAG, "remove($docId) failed: ${t.javaClass.simpleName}")
            false
        }
    }

    /** A doc_id not already taken, derived from [base]. */
    fun uniqueDocId(base: String): String {
        var candidate = base
        var n = 2
        while (exists(candidate)) {
            candidate = "$base ($n)"
            n++
        }
        return candidate
    }

    fun exists(docId: String): Boolean = conn.query(
        "SELECT 1 FROM documents WHERE doc_id = ?",
        bind = { it.bindText(1, docId) },
    ) { it.getLong(0) }.isNotEmpty()

    fun documents(): List<DocumentSummary> = conn.query(
        "SELECT doc_id, title, category, chunk_count, preview, origin FROM documents " +
            "ORDER BY added_at_utc DESC, title"
    ) {
        val origin = if (it.isNull(5)) Band.USER.origin else it.getText(5)
        DocumentSummary(
            docId = it.getText(0),
            title = it.getText(1),
            category = if (it.isNull(2)) ADDED_CATEGORY else it.getText(2),
            chunkCount = it.getLong(3).toInt(),
            preview = if (it.isNull(4)) null else it.getText(4),
            // Read off the row, not decided by the caller. Both call sites
            // used to `.copy(isUserAdded = true)` over this whole list, which
            // was true when the only writer was an import and would have
            // announced every synced college document as the student's own.
            provenance = Band.provenanceOf(origin),
        )
    }

    /** A synced institution document by its server-side id, or null. */
    fun byRemoteDocId(remoteDocId: String): SyncedDocument? = conn.query(
        "SELECT doc_id, remote_doc_id, revision FROM documents WHERE remote_doc_id = ?",
        bind = { it.bindText(1, remoteDocId) },
    ) {
        SyncedDocument(
            docId = it.getText(0),
            remoteDocId = it.getText(1),
            revision = if (it.isNull(2)) 0L else it.getLong(2),
        )
    }.firstOrNull()

    /** What is on this device from the institution, and at what revision. */
    data class SyncedDocument(val docId: String, val remoteDocId: String, val revision: Long)

    // --- publishing -------------------------------------------------------

    /**
     * One chunk on its way OUT, for `data/sync/CorpusUpload`.
     *
     * [vec] is the stored blob, handed back without being decoded. The
     * registrar's device ran the same ONNX MiniLM that every other device
     * runs, so these bytes are already in the vector space the whole
     * institution shares; turning them into floats to turn them back into
     * bytes could only lose that guarantee, never add to it.
     *
     * [ordinal] is the position in `ORDER BY id`, which is the order
     * `TextChunker` produced and therefore the order the document reads in.
     * It is generated here rather than stored, because it is not a fact about
     * the chunk -- it is a fact about the sequence, and storing it would let
     * the two disagree.
     */
    data class ExportedChunk(
        val ordinal: Int,
        val section: String?,
        val content: String,
        val vec: ByteArray?,
    )

    data class ExportedDocument(
        val docId: String,
        val title: String,
        val sizeBytes: Long?,
        val chunks: List<ExportedChunk>,
    )

    /**
     * Everything needed to publish a document that has already been ingested
     * on this device, or null if it is not here.
     *
     * Reading it back out rather than intercepting it on the way in is what
     * keeps the publish path from re-implementing chunking or embedding. The
     * registrar imports a file through the pipeline every device already runs
     * and which is already tested; this returns what that pipeline produced.
     */
    fun export(docId: String): ExportedDocument? {
        val head = conn.query(
            "SELECT title, size_bytes FROM documents WHERE doc_id = ?",
            bind = { it.bindText(1, docId) },
        ) { it.getText(0) to (if (it.isNull(1)) null else it.getLong(1)) }.firstOrNull()
            ?: return null
        val chunks = conn.query(
            "SELECT c.section, c.content, e.vec FROM chunks c " +
                "LEFT JOIN embeddings e ON e.chunk_id = c.id " +
                "WHERE c.doc_id = ? ORDER BY c.id",
            bind = { it.bindText(1, docId) },
        ) { row ->
            Triple(
                if (row.isNull(0)) null else row.getText(0),
                row.getText(1),
                if (row.isNull(2)) null else row.getBlob(2),
            )
        }.mapIndexed { i, (section, content, vec) ->
            ExportedChunk(i, section, content, vec)
        }
        return ExportedDocument(docId, head.first, head.second, chunks)
    }

    val chunkCount: Int
        get() = conn.query("SELECT COUNT(*) FROM chunks") { it.getLong(0) }.first().toInt()

    /**
     * How many documents the user has imported, for the licence cap.
     *
     * A count of what is here NOW, computed on demand rather than kept as a
     * running total. Removing a document therefore frees an allowance slot,
     * which is the behaviour anyone would expect and the only one that does
     * not need a counter to stay in step with a table.
     *
     * Returns 0 rather than throwing on a broken database. That direction is
     * chosen, not accidental: a corpus that cannot be counted must not be able
     * to lock a user out of importing, because "the disk is unreadable" is not
     * a statement about anybody's licence.
     */
    fun importedCount(): Int = runCatching {
        conn.query(USER_ONLY_COUNT) { it.getLong(0) }.first().toInt()
    }.getOrDefault(0)

    /**
     * Total bytes of the imported source files, for the licence's KB cap.
     *
     * `COALESCE(size_bytes, 0)`: rows written before the column existed
     * contribute nothing. That under-counts an old install rather than
     * over-counting it, which is the right way round -- the alternative is
     * charging an institution for documents the app never measured.
     */
    fun importedBytes(): Long = runCatching {
        conn.query(USER_ONLY_BYTES) { it.getLong(0) }.first()
    }.getOrDefault(0L)

    /**
     * The next free id **inside one band**, not across the table.
     *
     * `MAX(id) + 1` over the whole table was correct while there was one band.
     * With two it is a silent misattribution bug: once any institution chunk
     * exists at 2e9, the next document the student imports would be allocated
     * an id above 2e9 and would be read as a college document by
     * [provenanceOf] for the rest of its life. Both directions are pinned in
     * `CorpusSyncTest`.
     */
    private fun nextChunkId(band: Band): Long = conn.query(
        "SELECT COALESCE(MAX(id), ?) + 1 FROM chunks WHERE id >= ? AND id < ?",
        bind = {
            it.bindLong(1, band.base - 1)
            it.bindLong(2, band.base)
            it.bindLong(3, band.ceiling)
        },
    ) { it.getLong(0) }.first()

    fun close() = runCatching { conn.close() }

    /**
     * The two id ranges inside this file, and the `documents.origin` string
     * that goes with each.
     *
     * A band is not stored on the chunk row. It is the id itself, which is why
     * [HybridSearch] can fuse three sources into one ranked list keyed on
     * nothing but a `Long` and still say afterwards where each hit came from.
     * A stored flag would be a second copy of that fact and would eventually
     * disagree with the first.
     */
    internal enum class Band(val base: Long, val ceiling: Long, val origin: String) {
        USER(UserCorpusDb.ID_BASE, UserCorpusDb.INSTITUTION_ID_BASE, "user"),
        INSTITUTION(UserCorpusDb.INSTITUTION_ID_BASE, Long.MAX_VALUE, "institution");

        companion object {
            fun of(p: Provenance): Band =
                if (p == Provenance.INSTITUTION) INSTITUTION else USER

            fun provenanceOf(origin: String): Provenance =
                if (origin == INSTITUTION.origin) Provenance.INSTITUTION else Provenance.USER
        }
    }

    companion object {
        const val FILE_NAME = "user_corpus.db"

        /**
         * 1 was the original schema; 2 added `documents.size_bytes`; 3 added
         * `documents.origin`, `remote_doc_id` and `revision` for the synced
         * institution corpus.
         */
        const val SCHEMA_VERSION = 3

        /** The category shown on the Docs tab for anything the user added. */
        const val ADDED_CATEGORY = "Added by you"

        /**
         * The category for a document the registrar published. A distinct
         * heading rather than [ADDED_CATEGORY], because these are the two
         * things a student most needs to tell apart and they arrive in the
         * same list from the same file.
         */
        const val SYNCED_CATEGORY = "From your institution"

        /** Above every id the bundled corpus will plausibly ever use. */
        const val ID_BASE = 1_000_000_000L

        /**
         * Where synced institution chunks start.
         *
         * Deliberately ABOVE the user's band rather than below it, so nothing
         * about an existing install changes: a phone that has imported
         * documents keeps every id it already allocated, keeps answering
         * identically, and needs no rowid migration. Moving rows in an
         * external-content FTS5 table means deleting and reinserting every one
         * of them by hand, and a failure halfway through that is a corpus that
         * matches queries it can no longer cite.
         */
        const val INSTITUTION_ID_BASE = 2_000_000_000L

        /**
         * True for a chunk id that came out of THIS DATABASE rather than the
         * bundle -- i.e. which file to read it from.
         *
         * This is a routing predicate and nothing else. `HybridSearch` uses it
         * to decide which connection to fetch a fused id from, and both bands
         * live here, so both must answer true. Ask [isOwnChunk] or
         * [provenanceOf] for the question about whose document it is.
         */
        fun isUserChunk(id: Long): Boolean = id >= ID_BASE

        /** True for a chunk from a document the registrar published. */
        fun isInstitutionChunk(id: Long): Boolean = id >= INSTITUTION_ID_BASE

        /** True for a chunk from a document this student imported. */
        fun isOwnChunk(id: Long): Boolean = id in ID_BASE until INSTITUTION_ID_BASE

        /** The three-way answer. See [Provenance]. */
        fun provenanceOf(id: Long): Provenance = when {
            id >= INSTITUTION_ID_BASE -> Provenance.INSTITUTION
            id >= ID_BASE -> Provenance.USER
            else -> Provenance.BUNDLE
        }

        /**
         * The licence caps count the student's OWN imports and nothing else.
         *
         * Without the filter, an institution that publishes fifty documents
         * would spend fifty of a free device's allowance and the student's
         * next import would be refused for a reason that has nothing to do
         * with them -- and the refusal would arrive as "your licence", which
         * is the worst possible wording for it. `origin IS NULL` is a row
         * written before schema 3 and is an import by definition.
         */
        private const val USER_ONLY =
            "FROM documents WHERE origin IS NULL OR origin = 'user'"
        private const val USER_ONLY_COUNT = "SELECT COUNT(*) $USER_ONLY"
        private const val USER_ONLY_BYTES = "SELECT COALESCE(SUM(size_bytes), 0) $USER_ONLY"

        /**
         * float32 little-endian, 384 values, exactly as `embeddings.vec` is
         * written by scripts/export_mobile_bundle.py -- confirmed by reading a
         * shipped row before writing one: 1536 bytes, and a measured L2 norm of
         * 1.0000000. VectorSearch reads both databases' blobs with the same
         * ByteBuffer decode, so a mismatch here would not fail loudly, it would
         * quietly rank the user's documents as noise.
         */
        fun encodeVector(v: FloatArray): ByteArray {
            val buf = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            v.forEach { buf.putFloat(it) }
            return buf.array()
        }

        /**
         * Long enough to outlast an entitlement write, which is a single
         * statement, and deliberately far short of a whole import, so a genuine
         * deadlock still surfaces as a failure rather than a frozen screen.
         */
        private const val PRAGMA_BUSY_TIMEOUT = "PRAGMA busy_timeout = 5000"

        /** Null when the file cannot be opened or created, which is survivable. */
        fun openOrCreate(context: Context): UserCorpusDb? = try {
            val file = File(context.filesDir, FILE_NAME)
            val conn = BundledSQLiteDriver().open(file.absolutePath)
            // Same driver as the bundle, so FTS5 and the porter tokenizer are
            // present here too. The platform's android.database.sqlite has no
            // fts5 module at all -- see BrainDb's doc comment.

            // This file has a second writer: data/auth/EntitlementStore, on its
            // own connection. Without a busy_timeout this connection defaults
            // to zero, so a lock held by that store -- even for the microsecond
            // of a single-statement write -- makes the BEGIN IMMEDIATE in
            // [write] fail outright, and the student is told their document
            // could not be added. The entitlement store already waits 5s for an
            // import; this is the same courtesy in the other direction, and the
            // direction that matters more, because a lost entitlement write
            // retries tomorrow while a lost import is a visible failure.
            conn.execSQL(PRAGMA_BUSY_TIMEOUT)
            createSchema(conn)
            Log.i(TAG, "user corpus ready at ${file.absolutePath}")
            UserCorpusDb(conn, file.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "user corpus unavailable: ${t.javaClass.simpleName}: ${t.message}")
            null
        }

        private fun createSchema(conn: SQLiteConnection) {
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS chunks (" +
                    "id INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, section TEXT, content TEXT NOT NULL)"
            )
            conn.execSQL("CREATE INDEX IF NOT EXISTS idx_user_chunks_doc ON chunks(doc_id)")
            // Byte-for-byte the bundle's FTS5 declaration. The tokenizer is the
            // part that must match: 'porter unicode61' stems on both sides, so
            // "attendance" in a user's file and in the college's policy are
            // scored by the same rules.
            conn.execSQL(
                "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(" +
                    "content, doc_id, content='chunks', content_rowid='id', " +
                    "tokenize='porter unicode61')"
            )
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS embeddings (" +
                    "chunk_id INTEGER PRIMARY KEY REFERENCES chunks(id), vec BLOB NOT NULL)"
            )
            // The bundle has no `documents` table (user_version 1), which is
            // why DocsRepository synthesises rows and files everything under
            // one category. This one always has it: an ingested document knows
            // its real title, when it arrived and where from, and throwing that
            // away to match an older schema would be a strange thing to do.
            conn.execSQL(
                "CREATE TABLE IF NOT EXISTS documents (" +
                    "doc_id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL, " +
                    "chunk_count INTEGER NOT NULL, preview TEXT, source_uri TEXT, " +
                    "added_at_utc TEXT NOT NULL, size_bytes INTEGER, " +
                    "origin TEXT NOT NULL DEFAULT 'user', remote_doc_id TEXT, revision INTEGER)"
            )
            migrate(conn)
            // After the ladder, so it is created over a table that certainly
            // has the column. UNIQUE and partial: two local rows may not claim
            // the same server document, and the hundreds of rows with no
            // remote id at all are simply not in the index.
            conn.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_user_documents_remote " +
                    "ON documents(remote_doc_id) WHERE remote_doc_id IS NOT NULL"
            )
        }

        /**
         * Opens the schema on a connection somebody else owns.
         *
         * The precedent is `data/auth/EntitlementStore`, which takes a raw
         * [SQLiteConnection] for the same reason: it makes every line of this
         * class runnable in a JVM unit test with no Robolectric and no device.
         * The thing being tested here is whether an interrupted sync can
         * duplicate a row or leave half a document searchable, and that is not
         * a property anyone should first observe on a student's phone.
         *
         * [openOrCreate] remains the only way the app itself opens this file.
         */
        fun onConnection(conn: SQLiteConnection, path: String = ":memory:"): UserCorpusDb {
            createSchema(conn)
            return UserCorpusDb(conn, path)
        }

        /**
         * The `PRAGMA user_version` ladder.
         *
         * This used to be one unconditional `PRAGMA user_version = 1`, which
         * was honest while there was nothing to migrate and would have been a
         * lie the moment there was: a file created by an older build has a
         * `documents` table with no `size_bytes` column, and `CREATE TABLE IF
         * NOT EXISTS` above will not add one to a table that already exists.
         *
         * The column is added rather than the table rebuilt. `ALTER TABLE ADD
         * COLUMN` is the one schema change SQLite does in place and without
         * touching a row, so an institution with two hundred imported
         * documents pays nothing for it and cannot lose them to a failed copy.
         * It is nullable for the same reason -- there is no honest value to
         * backfill, and `NULL` says "never measured" where `0` would say
         * "measured, and empty".
         *
         * Note the interaction with `data/auth/EntitlementStore` and
         * `data/auth/LicenseStore`: both create their own tables on their own
         * connections with `CREATE TABLE IF NOT EXISTS` and neither touches
         * `user_version`. This function owns it, because this class owns the
         * corpus schema.
         */
        private fun migrate(conn: SQLiteConnection) {
            val version = runCatching {
                conn.query("PRAGMA user_version") { it.getLong(0) }.first().toInt()
            }.getOrDefault(0)
            if (version >= SCHEMA_VERSION) return

            // Asked of the table rather than inferred from the version number,
            // because the version was written unconditionally by every build
            // before this one: a file could be stamped 1 and already have the
            // column (created fresh by this build's CREATE TABLE above), or be
            // stamped 1 and not have it (created by an older build). Only the
            // table knows.
            val columns = runCatching {
                conn.query("PRAGMA table_info(documents)") { it.getText(1) }
            }.getOrDefault(emptyList()).toSet()
            if ("size_bytes" !in columns) {
                conn.execSQL("ALTER TABLE documents ADD COLUMN size_bytes INTEGER")
            }
            // Schema 3. Same in-place ALTER for the same reason, and the same
            // reading off the table rather than off the version number.
            //
            // `origin` gets a DEFAULT so every row that predates the column
            // reads as 'user' -- which is not a guess, it is the only thing it
            // could have been: before this version the sole writer of this
            // table was a document the student imported. The default also
            // means `write` never has to backfill and the licence caps'
            // `origin IS NULL OR origin = 'user'` filter is belt and braces
            // rather than the load-bearing part.
            if ("origin" !in columns) {
                conn.execSQL("ALTER TABLE documents ADD COLUMN origin TEXT NOT NULL DEFAULT 'user'")
            }
            if ("remote_doc_id" !in columns) {
                conn.execSQL("ALTER TABLE documents ADD COLUMN remote_doc_id TEXT")
            }
            if ("revision" !in columns) {
                conn.execSQL("ALTER TABLE documents ADD COLUMN revision INTEGER")
            }
            conn.execSQL("PRAGMA user_version = $SCHEMA_VERSION")
        }
    }
}
