package com.kriet.campusbrain.data

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

    /** One chunk on its way in: text, its heading, and its vector if we have one. */
    data class PendingChunk(val section: String?, val content: String, val vec: FloatArray?)

    data class PendingDocument(
        val docId: String,
        val title: String,
        val sourceUri: String?,
        val addedAtUtc: String,
        val chunks: List<PendingChunk>,
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
        conn.execSQL("BEGIN IMMEDIATE")
        try {
            var id = nextChunkId()
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

                            if (c.vec != null) {
                                insertVec.reset()
                                insertVec.bindLong(1, id)
                                insertVec.bindBlob(2, encodeVector(c.vec))
                                insertVec.step()
                            }
                            id++
                        }
                    }
                }
            }
            conn.prepare(
                "INSERT OR REPLACE INTO documents" +
                    "(doc_id, title, category, chunk_count, preview, source_uri, added_at_utc) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?)"
            ).use { st ->
                st.bindText(1, doc.docId)
                st.bindText(2, doc.title)
                st.bindText(3, ADDED_CATEGORY)
                st.bindLong(4, doc.chunks.size.toLong())
                val preview = doc.chunks.firstOrNull()?.content?.take(200)
                if (preview == null) st.bindNull(5) else st.bindText(5, preview)
                if (doc.sourceUri == null) st.bindNull(6) else st.bindText(6, doc.sourceUri)
                st.bindText(7, doc.addedAtUtc)
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

    /** Removes a document and everything indexed from it. False if unknown. */
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
        "SELECT doc_id, title, category, chunk_count, preview FROM documents " +
            "ORDER BY added_at_utc DESC, title"
    ) {
        DocumentSummary(
            docId = it.getText(0),
            title = it.getText(1),
            category = if (it.isNull(2)) ADDED_CATEGORY else it.getText(2),
            chunkCount = it.getLong(3).toInt(),
            preview = if (it.isNull(4)) null else it.getText(4),
        )
    }

    val chunkCount: Int
        get() = conn.query("SELECT COUNT(*) FROM chunks") { it.getLong(0) }.first().toInt()

    /**
     * Ids are allocated from [ID_BASE] upward so a user chunk id can never
     * collide with a bundled one (the shipped bundle's highest is 493). The
     * two databases are fused into one ranked list by [HybridSearch] using the
     * id as the key, and disjoint id spaces are what let that happen with no
     * namespace tag and no translation layer.
     */
    private fun nextChunkId(): Long =
        conn.query("SELECT COALESCE(MAX(id), ${ID_BASE - 1}) + 1 FROM chunks") { it.getLong(0) }.first()

    fun close() = runCatching { conn.close() }

    companion object {
        const val FILE_NAME = "user_corpus.db"

        /** The category shown on the Docs tab for anything the user added. */
        const val ADDED_CATEGORY = "Added by you"

        /** Above every id the bundled corpus will plausibly ever use. */
        const val ID_BASE = 1_000_000_000L

        /** True for a chunk id that came out of this database, not the bundle. */
        fun isUserChunk(id: Long): Boolean = id >= ID_BASE

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

        /** Null when the file cannot be opened or created, which is survivable. */
        fun openOrCreate(context: Context): UserCorpusDb? = try {
            val file = File(context.filesDir, FILE_NAME)
            val conn = BundledSQLiteDriver().open(file.absolutePath)
            // Same driver as the bundle, so FTS5 and the porter tokenizer are
            // present here too. The platform's android.database.sqlite has no
            // fts5 module at all -- see BrainDb's doc comment.
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
                    "added_at_utc TEXT NOT NULL)"
            )
            conn.execSQL("PRAGMA user_version = 1")
        }
    }
}
