package com.campusbrain.app.data.sync

import android.util.Log
import com.campusbrain.app.data.Provenance
import com.campusbrain.app.data.UserCorpusDb

/**
 * Pulls the institution's published documents onto this device.
 *
 * ## The rule that outranks this whole file
 *
 * **Retrieval never gates on sync.** Nothing here is reachable from
 * `QueryRouter`; [sync] is called at a lifecycle boundary or from an explicit
 * refresh and from nowhere else. A device that has never synced, or whose sync
 * failed halfway, or which has been in airplane mode since it was unboxed,
 * answers exactly as it did before -- from `brain.db` and from whatever is
 * already in `user_corpus.db`. Every failure path below degrades to that state
 * silently. There is no outcome of this function that can make the phone
 * answer worse than it did before it ran.
 *
 * ## Atomic per document, not incremental
 *
 * A document becomes searchable when **all** of its chunks are on the device,
 * in one `BEGIN IMMEDIATE` through [UserCorpusDb.write], or it does not appear
 * at all. It is never partly visible.
 *
 * The alternative -- write chunks as they arrive -- looks friendlier and is
 * much worse. A document that answers from half its content is not a partial
 * answer, it is a confident wrong one: the fee deadline arrives without the
 * late-fee clause, the attendance rule without its exemption, and neither the
 * student nor anything in the app can tell that the other half exists. A
 * document that is not there yet is visibly not there. `UserCorpusDb.write`
 * already wraps a whole document in one transaction for exactly this reason
 * and this file does not undo it.
 *
 * Resumability is bought separately, by [CorpusSyncStore]'s staging table, so
 * that atomic visibility does not cost a phone on a bad connection an
 * afternoon of re-downloading. Chunks accumulate there, invisible to
 * retrieval, until the set is complete.
 *
 * ## What happens when a sync is interrupted
 *
 * Interrupted at chunk 30 of 50:
 *
 *  1. thirty rows sit in `corpus_sync_staging`, tagged with the revision they
 *     were fetched for. Nothing is in `chunks`, `chunks_fts` or `embeddings`,
 *     so nothing about retrieval has changed;
 *  2. the watermark has not moved, because it only moves after a document is
 *     committed;
 *  3. the next run asks the server for the same revision, sees thirty ordinals
 *     already staged, and fetches twenty. If the registrar republished in
 *     between, the staged revision no longer matches and all thirty are
 *     discarded rather than spliced onto chunks from a different version;
 *  4. the document is written once, in one transaction, and the watermark
 *     moves past it.
 *
 * Run the same batch twice and the second run finds each document already on
 * the device at that revision and does nothing. One set of rows either way.
 *
 * ## Two different torn reads, two different guards
 *
 *  - **A pull landing in the middle of an upload** is prevented by
 *    `published_at`, which the registrar sets only after the last chunk is up,
 *    and by `chunk_count`, which is written before the first one. The client
 *    checks both: it asks only for rows that are published or withdrawn, and
 *    it refuses to promote a document whose staged count does not equal its
 *    declared `chunk_count`.
 *  - **A republish landing in the middle of a pull** is caught by re-reading
 *    the document's revision after the chunks are down. The chunk count would
 *    not notice a corrected version with the same number of chunks.
 */
class CorpusSync(
    private val api: CorpusApi,
    private val corpus: UserCorpusDb,
    private val state: CorpusSyncStore,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** What a sync did. Nothing here is an error a user needs to see. */
    data class Outcome(
        /** Documents written or replaced. */
        val applied: Int = 0,
        /** Documents removed because the institution withdrew them. */
        val withdrawn: Int = 0,
        /** Documents already on the device at that revision. */
        val unchanged: Int = 0,
        /** Documents left for next time: mid-upload, republished under us, or
         *  a connection that went away. Never an error state. */
        val deferred: Int = 0,
        /** Where the watermark ended up. */
        val watermark: Long = SyncWatermark.NEVER,
        /** True when the server could not be reached at all. */
        val offline: Boolean = false,
        /**
         * The revision the watermark is stuck behind, and how many passes have
         * hit it. Null when this pass got through everything it was offered.
         *
         * A deferral is normally self-correcting -- a publish in flight lands
         * and the next pass moves on. But the watermark deliberately cannot
         * step over one, because stepping over is how a document is lost, so a
         * document that can never be applied blocks every document published
         * after it on that device. That is the right failure direction and it
         * is accepted as permanent until it is fixed server-side. What is not
         * accepted is that nobody would notice, which is what this reports.
         */
        val stall: CorpusSyncStore.Stall? = null,
    )

    /**
     * One pass. Safe to call again immediately; safe to never call at all.
     *
     * Returns rather than throws in every case: a caller of this function has
     * a working offline app to fall back to and nothing useful to do with an
     * exception.
     */
    suspend fun sync(): Outcome {
        if (!state.ensureSchema()) {
            // No bookkeeping means no way to record what was applied, and
            // applying without recording is how a document gets written twice.
            // Doing nothing is correct and costs the student nothing.
            return Outcome(offline = false)
        }
        var watermark = state.watermark()
        var applied = 0
        var withdrawn = 0
        var unchanged = 0
        var deferred = 0
        var stalledAt: Long? = null

        while (true) {
            val page = fetchDocuments(watermark) ?: return Outcome(
                applied, withdrawn, unchanged, deferred, watermark,
                // Offline is not a stall: nothing was refused, nothing was
                // even offered. Leaving the marker alone means a week of
                // airplane mode does not read as a broken corpus.
                offline = true, stall = state.stall(),
            )
            if (page.isEmpty()) break

            // The page is applied in the order it was asked for -- ascending
            // by revision -- and the watermark is then computed from the run
            // of successes at the FRONT of it, never from the highest one that
            // happened to work. See SyncWatermark.advance for why that
            // distinction is a data-loss bug and not a style preference.
            val results = ArrayList<SyncWatermark.Applied>(page.size)
            var stopped = false
            for (row in page) {
                val outcome = apply(row)
                when (outcome) {
                    Applied.WRITTEN -> applied++
                    Applied.WITHDRAWN -> withdrawn++
                    Applied.UNCHANGED -> unchanged++
                    Applied.DEFERRED -> {
                        deferred++
                        stopped = true
                        stalledAt = row.revision
                    }
                }
                results += SyncWatermark.Applied(row.revision, outcome != Applied.DEFERRED)
                // Stopping at the first deferral rather than pressing on: a
                // document behind a deferred one is going to be re-fetched
                // anyway, because the watermark cannot move past the gap, and
                // spending a student's data on it twice is the wrong trade.
                if (stopped) break
            }

            val mark = SyncWatermark.advance(watermark, results)
            if (mark != watermark) {
                // A watermark that will not write is not a failure of this
                // pass -- everything applied is on the device and correct --
                // but continuing would mean applying documents whose
                // predecessors this device cannot prove it holds. Stop, and
                // let the next run re-offer them, where each is a no-op.
                if (!state.setWatermark(mark, now())) {
                    return Outcome(applied, withdrawn, unchanged, deferred, watermark)
                }
                watermark = mark
            }
            if (stopped || page.size < PAGE_SIZE) break
        }
        state.recordStall(stalledAt, watermark, now())
        return Outcome(applied, withdrawn, unchanged, deferred, watermark, stall = state.stall())
    }

    private enum class Applied { WRITTEN, WITHDRAWN, UNCHANGED, DEFERRED }

    // --- one document -----------------------------------------------------

    private suspend fun apply(row: RemoteDocument): Applied {
        val local = corpus.byRemoteDocId(row.docId)

        if (row.isWithdrawn) {
            // A withdrawal is applied even when this device never had the
            // document: there is nothing to remove, and the watermark still
            // has to move past the row or the device asks for it forever.
            state.discardStaging(row.docId)
            if (local == null) return Applied.WITHDRAWN
            // remove() reports false only when it could not do it, which must
            // not advance the watermark -- a phone that goes on answering from
            // a withdrawn notice is the exact failure the soft delete exists
            // to prevent, and quietly recording it as done would make it
            // permanent.
            return if (corpus.remove(local.docId)) Applied.WITHDRAWN else Applied.DEFERRED
        }

        // A row that is neither published nor withdrawn is a publish in
        // flight. The server's filter should already have excluded it; this is
        // the client half of the same guard, and it defers rather than fails
        // so the finished version arrives on the next pass.
        if (!row.isPublished) return Applied.DEFERRED

        if (local != null && local.revision >= row.revision) {
            // Already here, at this revision or a newer one. This is what
            // makes a repeated batch a no-op rather than a duplicate, and it
            // is also what makes a device whose watermark was lost recover by
            // re-pulling everything harmlessly.
            state.discardStaging(row.docId)
            return Applied.UNCHANGED
        }

        val have = state.stagingFor(row.docId, row.revision)
        if (!download(row, have)) return Applied.DEFERRED

        val staged = state.staged(row.docId)
        if (staged.size != row.chunkCount) {
            // Either the upload is still running -- chunk_count is written
            // before the first chunk goes up, precisely so this comparison
            // means something -- or a chunk was rejected on the way in. Both
            // are "not yet", not "broken".
            return Applied.DEFERRED
        }

        // The second torn-read guard. Re-read the revision now that the chunks
        // are down: a republish that landed while they were downloading would
        // otherwise be promoted as a document spliced from two versions, and a
        // corrected version with the same chunk count would slip past the
        // check above.
        val current = currentRevision(row.docId)
        if (current == null || current != row.revision) {
            state.discardStaging(row.docId)
            return Applied.DEFERRED
        }

        return if (promote(row, local, staged)) Applied.WRITTEN else Applied.DEFERRED
    }

    /** Fetches the ordinals not already staged. False if the connection went
     *  away; what was fetched stays staged and the next run continues. */
    private suspend fun download(row: RemoteDocument, have: Set<Int>): Boolean {
        var next = 0
        while (next < row.chunkCount) {
            if (next in have) { next++; continue }
            val response = api.get(
                "corpus_chunks?select=ordinal,section,content,embedding" +
                    "&doc_id=eq.${encode(row.docId)}" +
                    "&ordinal=gte.$next&order=ordinal.asc&limit=$CHUNK_PAGE_SIZE"
            ) ?: return false
            if (!response.ok) return false
            val chunks = CorpusJson.chunks(response.body) ?: return false
            if (chunks.isEmpty()) return true
            // Staged as they arrive, one page at a time. This is the whole
            // resumability story: a connection that dies on page four keeps
            // pages one to three.
            if (!state.stage(row.docId, row.revision, chunks)) return false
            next = chunks.maxOf { it.ordinal } + 1
        }
        return true
    }

    /**
     * Writes the document through [UserCorpusDb.write], which is the only
     * writer in the app.
     *
     * Going through it rather than around it is not tidiness. That function
     * owns the FTS5 external-content companion insert -- without which a
     * document is stored and permanently unfindable by keyword -- the vector
     * blob, the id band that decides provenance, and the transaction that
     * makes the whole thing atomic. A second writer here would be a second
     * place for all four to be got wrong.
     */
    private fun promote(
        row: RemoteDocument,
        local: UserCorpusDb.SyncedDocument?,
        staged: List<CorpusSyncStore.Staged>,
    ): Boolean {
        // A revision replaces rather than adds. `write` does INSERT OR REPLACE
        // on the documents row but a plain INSERT on chunks, so writing over
        // an existing document without removing it first would leave the old
        // chunks in the index, citable and wrong.
        //
        // A crash between the remove and the write leaves the document absent.
        // That is the safe side of the failure: the watermark has not moved,
        // so the next run rebuilds it, and in the meantime the phone abstains
        // on that document rather than answering from a stale version of it.
        val docId = local?.docId ?: corpus.uniqueDocId(row.docId)
        if (local != null && !corpus.remove(local.docId)) return false
        return runCatching {
            corpus.write(
                UserCorpusDb.PendingDocument(
                    docId = docId,
                    title = row.title,
                    sourceUri = null,
                    addedAtUtc = row.publishedAt ?: "",
                    chunks = staged.map {
                        UserCorpusDb.PendingChunk(
                            section = it.section,
                            content = it.content,
                            vec = null,
                            // The server's bytes, unexamined. See PendingChunk.
                            vecBlob = it.vec,
                        )
                    },
                    sizeBytes = row.sizeBytes,
                    provenance = Provenance.INSTITUTION,
                    remoteDocId = row.docId,
                    revision = row.revision,
                )
            )
            state.discardStaging(row.docId)
            true
        }.getOrElse {
            Log.w(TAG, "could not write a synced document: ${it.javaClass.simpleName}")
            false
        }
    }

    // --- the two reads ----------------------------------------------------

    /**
     * One page of changed documents, oldest revision first.
     *
     * Note what is absent: any mention of a tenant. Scope is decided by RLS
     * from `current_tenant_id()`, so there is no filter here for a client bug
     * to widen and no id for one to get wrong. The `or=` clause asks only for
     * rows a device can act on -- a publish that has finished, or a withdrawal
     * -- which is what keeps a document that is still uploading from being
     * pulled at all.
     */
    private suspend fun fetchDocuments(watermark: Long): List<RemoteDocument>? {
        val response = api.get(
            "corpus_documents?select=doc_id,title,category,size_bytes,chunk_count," +
                "revision,published_at,withdrawn_at" +
                "&revision=gt.$watermark" +
                "&or=(published_at.not.is.null,withdrawn_at.not.is.null)" +
                "&order=revision.asc&limit=$PAGE_SIZE"
        ) ?: return null
        if (!response.ok) return null
        return CorpusJson.documents(response.body)
    }

    private suspend fun currentRevision(docId: String): Long? {
        val response = api.get(
            "corpus_documents?select=revision&doc_id=eq.${encode(docId)}&limit=1"
        ) ?: return null
        if (!response.ok) return null
        return CorpusJson.revisionOf(response.body)
    }

    companion object {
        private const val TAG = "CorpusSync"

        /** Documents per request. Small because each one may drag fifty
         *  chunks behind it, and a page that is abandoned is re-fetched. */
        const val PAGE_SIZE = 20

        /** Chunks per request. 1536 bytes of embedding plus its text, as hex,
         *  is roughly 4KB a chunk on the wire, so this is about 400KB. */
        const val CHUNK_PAGE_SIZE = 100

        /**
         * A doc id goes into a URL, and institution doc ids are filenames --
         * spaces, ampersands and commas are all ordinary in them, and a comma
         * is how PostgREST separates the arms of a filter. Quoted and
         * percent-encoded rather than trusted.
         */
        fun encode(docId: String): String {
            val quoted = "\"" + docId.replace("\"", "\\\"") + "\""
            return java.net.URLEncoder.encode(quoted, "UTF-8")
        }
    }
}
