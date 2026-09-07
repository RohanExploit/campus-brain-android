package com.campusbrain.app.data.sync

import com.campusbrain.app.data.UserCorpusDb
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * The publish path: a registrar puts a document on every enrolled phone.
 *
 * ## Nothing here chunks or embeds anything
 *
 * The registrar imports the file on their own device through `DocumentIngest`
 * -- the pipeline every phone already runs, already tested, backed by the same
 * ONNX MiniLM the students' devices use for their questions. This class reads
 * back what that produced ([UserCorpusDb.export]) and uploads it.
 *
 * That is the entire reason there is no embedding service, no Python on the
 * server, no model hosting and no cold start. It is also the only way the
 * vectors are guaranteed comparable: cosine similarity between vectors from
 * two different models is noise wearing a ranking's clothes, and "the same
 * weights produced both sides" is a much stronger guarantee when it is true by
 * construction than when it is true by discipline. Re-implementing chunking or
 * embedding here would replace the construction with the discipline.
 *
 * ## The publish is a three-step state machine, and the order is the point
 *
 *  1. **open a draft** -- write (or reopen) the `corpus_documents` row with
 *     `published_at = NULL` and the FINAL `chunk_count`;
 *  2. **upload the chunks** -- only the ordinals not already up;
 *  3. **publish** -- set `published_at`.
 *
 * A student's device only ever pulls rows where `published_at` is set, so
 * between steps 1 and 3 the document does not exist as far as any other phone
 * is concerned. Writing `chunk_count` in step 1 rather than step 3 is what
 * lets a device that somehow sees the row anyway compare what it downloaded
 * against what the document claims to be, and refuse a partial one.
 *
 * Every step is idempotent. Interrupted after 30 of 50 chunks, the next
 * attempt asks the server which ordinals it already has and sends twenty.
 * Run twice from the start, the second run sends nothing and re-publishes.
 *
 * ## Withdrawal
 *
 * A soft delete: `withdrawn_at` is set, the chunks are deleted to free the
 * storage, and the row stays as a tombstone whose revision has moved forward.
 * A phone that was offline for the whole life of a notice cannot observe a row
 * that is not there -- so the thing it needs to observe has to be a row.
 */
class CorpusUpload(
    private val api: CorpusApi,
    private val corpus: UserCorpusDb,
    private val now: () -> String = { Instant.now().toString() },
) {

    sealed interface Result {
        /** On every enrolled device from their next sync. */
        data class Published(val docId: String, val chunks: Int) : Result

        /** Withdrawn. Devices remove it on their next sync. */
        data class Withdrawn(val docId: String) : Result

        /** The document is not on this device. */
        data object NotFound : Result

        /**
         * The server refused the write. In practice this is RLS: this account
         * is not an `admin` or a `registrar`, and PostgREST returns 42501.
         * The role gate is on the server, not in this class -- a client-side
         * check is a courtesy to the user interface and never a control.
         */
        data class Refused(val status: Int) : Result

        /**
         * No answer, or an answer that could not be read. **Nothing is
         * decided**: the document may be fully uploaded and unpublished, which
         * the next attempt finishes rather than restarts.
         */
        data object Unavailable : Result
    }

    /**
     * Publishes a document already ingested on this device.
     *
     * [remoteDocId] defaults to the local id. They are separate parameters
     * because they are separate things: the local id is whatever
     * `DocumentIngest` had to pick to avoid colliding with something else on
     * this phone, and the institution's id is what four hundred other phones
     * will key on forever.
     */
    suspend fun publish(
        localDocId: String,
        remoteDocId: String = localDocId,
        category: String? = null,
    ): Result {
        val doc = corpus.export(localDocId) ?: return Result.NotFound

        when (val opened = openDraft(doc, remoteDocId, category)) {
            is Result.Refused, is Result.Unavailable -> return opened
            else -> Unit
        }

        val have = existingOrdinals(remoteDocId) ?: return Result.Unavailable
        val missing = doc.chunks.filter { it.ordinal !in have }
        for (batch in missing.chunked(UPLOAD_BATCH)) {
            val response = api.post("corpus_chunks", chunkRows(remoteDocId, batch))
                ?: return Result.Unavailable
            if (!response.ok) return classify(response)
        }

        val published = api.patch(
            "corpus_documents?doc_id=eq.${CorpusSync.encode(remoteDocId)}",
            JSONObject().put("published_at", now()).toString(),
        ) ?: return Result.Unavailable
        if (!published.ok) return classify(published)
        return Result.Published(remoteDocId, doc.chunks.size)
    }

    /**
     * Withdraws a published document.
     *
     * Takes the institution's id, not a local one: withdrawing is an act
     * against the shared corpus and the registrar may be doing it from a
     * device that never held the document.
     */
    suspend fun withdraw(remoteDocId: String): Result {
        val filter = "doc_id=eq.${CorpusSync.encode(remoteDocId)}"
        // The tombstone first. If the chunk delete below fails, the document
        // is already withdrawn everywhere and the leftover chunks are storage,
        // not a correctness problem. The other order would leave a published
        // document with no content -- which every device would pull, find
        // short of its chunk_count, and defer on forever.
        val marked = api.patch(
            "corpus_documents?$filter",
            JSONObject()
                .put("withdrawn_at", now())
                // Cleared so a device cannot read the row as a live document
                // on any code path; `withdrawn_at` is what its sync filter
                // matches on now.
                .put("published_at", JSONObject.NULL)
                .put("chunk_count", 0)
                .toString(),
        ) ?: return Result.Unavailable
        if (!marked.ok) return classify(marked)
        api.delete("corpus_chunks?$filter")
        return Result.Withdrawn(remoteDocId)
    }

    // --- steps ------------------------------------------------------------

    /**
     * Creates the row, or reopens an existing one as a draft.
     *
     * The chunk delete happens only when the server's row is currently
     * PUBLISHED -- that is, when this is a revision of a live document. On a
     * resumed upload the row is already a draft, and deleting there would
     * throw away the thirty chunks the previous attempt got up, turning every
     * dropped connection into a fresh start.
     */
    private suspend fun openDraft(
        doc: UserCorpusDb.ExportedDocument,
        remoteDocId: String,
        category: String?,
    ): Result {
        val filter = "doc_id=eq.${CorpusSync.encode(remoteDocId)}"
        val existing = api.get("corpus_documents?select=published_at&$filter&limit=1")
            ?: return Result.Unavailable
        if (!existing.ok) return classify(existing)

        val rows = runCatching { JSONArray(existing.body) }.getOrNull()
            ?: return Result.Unavailable
        val present = rows.length() > 0
        val livePublished = present &&
            rows.optJSONObject(0)?.optString("published_at")?.isNotBlank() == true

        val body = JSONObject()
            .put("title", doc.title)
            .put("chunk_count", doc.chunks.size)
            .put("published_at", JSONObject.NULL)
            .put("withdrawn_at", JSONObject.NULL)
        if (category != null) body.put("category", category)
        if (doc.sizeBytes != null) body.put("size_bytes", doc.sizeBytes)

        if (!present) {
            // No tenant_id in this payload, and there is no version of it that
            // has one: the column DEFAULTs to current_tenant_id() and is not
            // grantable to a client at all, so a publish cannot be aimed at
            // another institution even by a client that tried.
            val created = api.post(
                "corpus_documents",
                JSONArray().put(body.put("doc_id", remoteDocId)).toString(),
            ) ?: return Result.Unavailable
            return if (created.ok) Result.Published(remoteDocId, 0) else classify(created)
        }

        val reopened = api.patch("corpus_documents?$filter", body.toString())
            ?: return Result.Unavailable
        if (!reopened.ok) return classify(reopened)
        if (livePublished) {
            // A revision replaces the content rather than adding to it. Safe
            // to do now and not before: the row above is already a draft, so
            // no device can pull the document while it has no chunks.
            val cleared = api.delete("corpus_chunks?$filter") ?: return Result.Unavailable
            if (!cleared.ok) return classify(cleared)
        }
        return Result.Published(remoteDocId, 0)
    }

    private suspend fun existingOrdinals(remoteDocId: String): Set<Int>? {
        val response = api.get(
            "corpus_chunks?select=ordinal&doc_id=eq.${CorpusSync.encode(remoteDocId)}" +
                "&order=ordinal.asc"
        ) ?: return null
        if (!response.ok) return null
        return CorpusJson.ordinals(response.body)
    }

    companion object {

        /**
         * Chunks per request. Each carries 1536 bytes of embedding as 3074
         * characters of hex plus its text, so this is roughly 400KB of body --
         * enough that a fifty-chunk document is one round trip, small enough
         * that a dropped connection costs one batch.
         */
        const val UPLOAD_BATCH = 100

        /**
         * The rows, as PostgREST wants them.
         *
         * `tenant_id` is absent by construction and `embedding` is the local
         * blob rendered straight to `\xNN` hex -- the byte-exact form
         * PostgreSQL reads back into a `bytea`, with nothing in between that
         * could reinterpret a float.
         */
        fun chunkRows(remoteDocId: String, chunks: List<UserCorpusDb.ExportedChunk>): String {
            val array = JSONArray()
            for (c in chunks) {
                val row = JSONObject()
                    .put("doc_id", remoteDocId)
                    .put("ordinal", c.ordinal)
                    .put("content", c.content)
                row.put("section", c.section ?: JSONObject.NULL)
                row.put("embedding", c.vec?.let { HexBytes.encode(it) } ?: JSONObject.NULL)
                array.put(row)
            }
            return array.toString()
        }

        /**
         * 401 and 403 are the role gate saying no, and no amount of retrying
         * changes them; anything else might be the network and is worth
         * another attempt later. The split matters because
         * [Result.Unavailable] means "nothing was decided" and a caller may
         * safely resume from it, while [Result.Refused] means this account
         * will never be allowed to do this.
         */
        fun classify(response: CorpusApi.Response): Result =
            if (response.code == 401 || response.code == 403) Result.Refused(response.code)
            else Result.Unavailable
    }
}
