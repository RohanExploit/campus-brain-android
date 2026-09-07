package com.campusbrain.app.data.sync

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.campusbrain.app.data.query

/**
 * The two pieces of sync bookkeeping that live on the device: how far this
 * phone has got, and the chunks of a document it has downloaded but not yet
 * made searchable.
 *
 * Takes a raw [SQLiteConnection] and never a `Context`, following
 * `data/auth/EntitlementStore` for the same reason: what is being tested here
 * is whether an interrupted sync can duplicate a row or splice two revisions
 * of a document together, and those are not properties to first observe in the
 * field.
 *
 * ## Why staging exists at all
 *
 * A document becomes searchable **atomically or not at all** -- see
 * [CorpusSync] for that argument. But "atomically" and "resumably" pull in
 * opposite directions: the simplest atomic implementation holds the whole
 * document in memory and throws it away on interruption, so a phone on a bad
 * connection can spend an afternoon re-downloading the same fifty chunks and
 * never finish one.
 *
 * Staging resolves that. Chunks land here as they arrive, keyed on
 * `(remote_doc_id, ordinal)` so a re-send is a no-op rather than a duplicate,
 * and nothing in this table is visible to retrieval -- `chunks`, `chunks_fts`
 * and `embeddings` are untouched until the whole set is present. The document
 * appears complete or does not appear.
 *
 * ## The revision on a staged row is not decoration
 *
 * Interrupted at chunk 30 of 50 at revision 5, and the registrar republishes a
 * corrected version as revision 9: a resume that fetched ordinals 31-50 from
 * revision 9 would promote a document spliced out of two versions, with no
 * duplicate rows, no error, and nothing that could later notice. Every staged
 * row therefore carries the revision it was fetched for and the whole staged
 * set is discarded the moment that stops matching.
 *
 * ## Keyed on the REMOTE doc id
 *
 * Never on the local one. The two can differ -- a student who imported a file
 * whose name collides with a published document keeps their own, and the
 * synced copy gets a suffixed local id -- and keying staging on the local id
 * would make that document re-download itself as a second copy on every run.
 */
class CorpusSyncStore(private val conn: SQLiteConnection) {

    /**
     * Creates the two tables. Idempotent, and safe on a connection that
     * already holds the corpus schema.
     *
     * `PRAGMA user_version` is left alone for the reason
     * `EntitlementStore.ensureSchema` gives: `UserCorpusDb` owns that number
     * and runs the migration ladder for it, and bumping it from here would
     * either be undone by that ladder or make it skip a step it needed.
     */
    fun ensureSchema(): Boolean = runCatching {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS corpus_sync_state (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "watermark INTEGER NOT NULL, synced_at_ms INTEGER NOT NULL, " +
                "stalled_revision INTEGER, stalled_runs INTEGER NOT NULL DEFAULT 0)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS corpus_sync_staging (" +
                "remote_doc_id TEXT NOT NULL, ordinal INTEGER NOT NULL, " +
                "revision INTEGER NOT NULL, section TEXT, content TEXT NOT NULL, vec BLOB, " +
                "PRIMARY KEY (remote_doc_id, ordinal))"
        )
        true
    }.getOrDefault(false)

    // --- the watermark ----------------------------------------------------

    /**
     * The highest revision this device has fully applied.
     *
     * [SyncWatermark.NEVER] when there is no row, and also when the read
     * fails. Both directions are safe and the second one is chosen rather than
     * accidental: a device that cannot read its watermark re-pulls the whole
     * corpus, and every apply is guarded by a revision comparison against the
     * document already on the phone, so a full re-pull produces exactly the
     * rows that are already there. The opposite default -- guessing high --
     * would skip documents.
     */
    fun watermark(): Long = runCatching {
        conn.query("SELECT watermark FROM corpus_sync_state WHERE id = 1") { it.getLong(0) }
            .firstOrNull() ?: SyncWatermark.NEVER
    }.getOrDefault(SyncWatermark.NEVER)

    /**
     * False if it could not be written, and the caller must stop rather than
     * carry on.
     *
     * A single statement, no BEGIN: this store must never be the thing holding
     * a lock while a document import waits, which is the rule
     * `EntitlementStore` already follows on this same file.
     */
    fun setWatermark(revision: Long, nowMs: Long): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO corpus_sync_state(id, watermark, synced_at_ms) " +
                "VALUES (1, ?, ?)"
        ).use {
            it.bindLong(1, revision)
            it.bindLong(2, nowMs)
            it.step()
        }
        true
    }.getOrDefault(false)

    /** When the last successful apply happened, or null. For a "last updated"
     *  line; nothing decides anything on it. */
    fun syncedAtMs(): Long? = runCatching {
        conn.query("SELECT synced_at_ms FROM corpus_sync_state WHERE id = 1") { it.getLong(0) }
            .firstOrNull()
    }.getOrNull()

    // --- the stall marker -------------------------------------------------

    /**
     * How long the same document has been blocking the watermark.
     *
     * A deferral is normal and self-correcting: a publish that was in flight
     * lands, and the next pass moves on. But the watermark cannot step over
     * it -- deliberately, because stepping over is how a document is lost
     * forever -- so a document that can NEVER be applied wedges every
     * document published after it, on that device, silently.
     *
     * Reaching that state takes a server-side mishap: a `chunk_count` larger
     * than the chunks that exist, on a row that is nonetheless published. The
     * upload path cannot produce it, since a rejected chunk batch aborts
     * before `published_at` is ever set. It is accepted as
     * permanent-until-fixed-server-side rather than routed around, because
     * every way of routing around it drops a document.
     *
     * What is NOT accepted is that nobody would know. This counter is the
     * whole remedy: it names the revision that is stuck and how many passes
     * have hit it, so the refresh surface Phase 2 builds can say "sync
     * stalled" instead of showing a green tick forever. Nothing in this file
     * acts on it -- a device that is stalled still answers from everything it
     * already has, which is the point.
     */
    data class Stall(val revision: Long, val runs: Int)

    fun stall(): Stall? = runCatching {
        conn.query(
            "SELECT stalled_revision, stalled_runs FROM corpus_sync_state WHERE id = 1"
        ) { if (it.isNull(0)) null else Stall(it.getLong(0), it.getLong(1).toInt()) }
            .firstOrNull()
    }.getOrNull()

    /**
     * [revision] is the one that could not be applied, or null when the pass
     * got through everything it was offered.
     *
     * An upsert rather than an UPDATE, because the first sync a device ever
     * runs can stall before it has written a watermark -- and a stall nobody
     * recorded because there was no row yet is exactly the stall worth
     * knowing about.
     */
    fun recordStall(revision: Long?, watermark: Long, nowMs: Long) {
        runCatching {
            val previous = stall()
            val runs = when {
                revision == null -> 0
                previous?.revision == revision -> previous.runs + 1
                else -> 1
            }
            conn.prepare(
                "INSERT INTO corpus_sync_state" +
                    "(id, watermark, synced_at_ms, stalled_revision, stalled_runs) " +
                    "VALUES (1, ?, ?, ?, ?) " +
                    "ON CONFLICT(id) DO UPDATE SET " +
                    "stalled_revision = excluded.stalled_revision, " +
                    "stalled_runs = excluded.stalled_runs"
            ).use {
                it.bindLong(1, watermark)
                it.bindLong(2, nowMs)
                if (revision == null) it.bindNull(3) else it.bindLong(3, revision)
                it.bindLong(4, runs.toLong())
                it.step()
            }
        }
    }

    // --- staging ----------------------------------------------------------

    data class Staged(
        val ordinal: Int,
        val section: String?,
        val content: String,
        val vec: ByteArray?,
    )

    /**
     * The revision the staged rows for [remoteDocId] belong to, or null when
     * nothing is staged.
     */
    fun stagedRevision(remoteDocId: String): Long? = runCatching {
        conn.query(
            "SELECT revision FROM corpus_sync_staging WHERE remote_doc_id = ? LIMIT 1",
            bind = { it.bindText(1, remoteDocId) },
        ) { it.getLong(0) }.firstOrNull()
    }.getOrNull()

    /** Which ordinals are already downloaded, so a resume asks only for the
     *  rest. */
    fun stagedOrdinals(remoteDocId: String): Set<Int> = runCatching {
        conn.query(
            "SELECT ordinal FROM corpus_sync_staging WHERE remote_doc_id = ?",
            bind = { it.bindText(1, remoteDocId) },
        ) { it.getLong(0).toInt() }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Adds chunks to the staged set for one revision of one document.
     *
     * `INSERT OR REPLACE` on `(remote_doc_id, ordinal)`: re-sending a chunk
     * the device already has is a no-op, which is what makes a resume that
     * overlaps the previous run harmless. The whole batch is one transaction
     * so a torn write cannot leave a staged chunk whose bytes are half of one
     * and half of another.
     */
    fun stage(remoteDocId: String, revision: Long, chunks: List<RemoteChunk>): Boolean {
        if (chunks.isEmpty()) return true
        if (runCatching { conn.execSQL("BEGIN IMMEDIATE") }.isFailure) return false
        return try {
            conn.prepare(
                "INSERT OR REPLACE INTO corpus_sync_staging" +
                    "(remote_doc_id, ordinal, revision, section, content, vec) " +
                    "VALUES (?, ?, ?, ?, ?, ?)"
            ).use { st ->
                for (c in chunks) {
                    st.reset()
                    st.bindText(1, remoteDocId)
                    st.bindLong(2, c.ordinal.toLong())
                    st.bindLong(3, revision)
                    if (c.section == null) st.bindNull(4) else st.bindText(4, c.section)
                    st.bindText(5, c.content)
                    if (c.embedding == null) st.bindNull(6) else st.bindBlob(6, c.embedding)
                    st.step()
                }
            }
            conn.execSQL("COMMIT")
            true
        } catch (t: Throwable) {
            runCatching { conn.execSQL("ROLLBACK") }
            false
        }
    }

    /** Everything staged for [remoteDocId], in document order. */
    fun staged(remoteDocId: String): List<Staged> = runCatching {
        conn.query(
            "SELECT ordinal, section, content, vec FROM corpus_sync_staging " +
                "WHERE remote_doc_id = ? ORDER BY ordinal",
            bind = { it.bindText(1, remoteDocId) },
        ) {
            Staged(
                ordinal = it.getLong(0).toInt(),
                section = if (it.isNull(1)) null else it.getText(1),
                content = it.getText(2),
                vec = if (it.isNull(3)) null else it.getBlob(3),
            )
        }
    }.getOrDefault(emptyList())

    /** Throws nothing and reports nothing: a staged row that outlives its
     *  document is dead weight, not a correctness problem, because every apply
     *  re-checks the revision before it trusts anything staged. */
    fun discardStaging(remoteDocId: String) {
        runCatching {
            conn.prepare("DELETE FROM corpus_sync_staging WHERE remote_doc_id = ?").use {
                it.bindText(1, remoteDocId)
                it.step()
            }
        }
    }

    /**
     * Drops the staged set unless it belongs to [revision], and reports what
     * survived.
     *
     * The single place the two-revisions-spliced-together failure is
     * prevented, so it is one function with one caller rather than a
     * comparison repeated at each use.
     */
    fun stagingFor(remoteDocId: String, revision: Long): Set<Int> {
        val staged = stagedRevision(remoteDocId)
        if (staged != null && staged != revision) {
            discardStaging(remoteDocId)
            return emptySet()
        }
        return stagedOrdinals(remoteDocId)
    }
}
