package com.campusbrain.app.data.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire format of the shared corpus, and the arithmetic around it.
 *
 * Everything in this file is pure: no `Context`, no `SQLiteConnection`, no
 * socket. That is deliberate and it is the same reason
 * `data/auth/EntitlementStore` takes a raw connection -- the properties worth
 * testing here (a watermark that must not skip a document, a byte array that
 * must survive a round trip unchanged) are properties nobody should first
 * observe on a student's phone.
 */

/** One row of `corpus_documents`, as a device sees it. */
data class RemoteDocument(
    val docId: String,
    val title: String,
    val category: String?,
    val sizeBytes: Long?,
    val chunkCount: Int,
    val revision: Long,
    val publishedAt: String?,
    val withdrawnAt: String?,
) {
    /**
     * True when this row says "stop answering from this document".
     *
     * A withdrawal is a row and not an absence, because a phone that was
     * offline for the whole life of a notice cannot observe a row that is not
     * there. This is the flag it can observe.
     */
    val isWithdrawn: Boolean get() = withdrawnAt != null

    /** True when the publish that produced this revision finished. */
    val isPublished: Boolean get() = publishedAt != null
}

/** One row of `corpus_chunks`. [embedding] is null when the registrar's
 *  device had no ONNX model, which leaves the document keyword-findable. */
data class RemoteChunk(
    val ordinal: Int,
    val section: String?,
    val content: String,
    val embedding: ByteArray?,
)

/**
 * `bytea` on the wire.
 *
 * PostgREST serialises a `bytea` column with PostgreSQL's own text output,
 * which under the default `bytea_output = hex` is a backslash, an `x`, and two
 * lowercase hex digits per byte -- NOT base64. The same form is accepted on
 * the way in, so upload and download use one encoding and one decoder.
 *
 * Hex costs 3072 characters per 1536-byte vector where base64 would cost 2048.
 * The saving is real at 400 devices and is available: a view selecting
 * `encode(embedding, 'base64')` would give it. It is not taken here because
 * such a view has to be declared `security_invoker = true` to keep the RLS of
 * the table underneath it, and a view that silently runs with the owner's
 * rights instead is a cross-tenant leak. Paying 1024 characters a chunk to
 * avoid betting a tenancy boundary on a Postgres version is the right side of
 * that trade. See the migration's closing note.
 */
object HexBytes {

    private const val DIGITS = "0123456789abcdef"

    /** `\x0f1e...`, the form Postgres reads back into a `bytea`. */
    fun encode(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2 + 2).append("\\x")
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F])
        }
        return out.toString()
    }

    /**
     * Null for anything that is not a well-formed hex `bytea`.
     *
     * Null rather than an empty array or a throw: an unreadable embedding
     * means this chunk has no vector arm, which the app already degrades to
     * everywhere else. An empty array would be written into `embeddings.vec`
     * and read back by `VectorSearch` as a vector of no dimensions, which does
     * not fail loudly -- it ranks the document as noise.
     */
    fun decode(text: String?): ByteArray? {
        if (text == null) return null
        val body = when {
            text.startsWith("\\x") -> text.substring(2)
            text.startsWith("\\\\x") -> text.substring(3)
            else -> return null
        }
        if (body.length % 2 != 0) return null
        val out = ByteArray(body.length / 2)
        for (i in out.indices) {
            val hi = hex(body[i * 2])
            val lo = hex(body[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}

/**
 * How far this device has got, and the one rule that keeps it from losing a
 * document.
 */
object SyncWatermark {

    /** A device that has never synced. Every revision is above it. */
    const val NEVER = 0L

    /**
     * The new watermark after a batch: the last revision applied **before the
     * first one that was not**.
     *
     * The obvious alternative -- "the highest revision applied" -- is a silent
     * data-loss bug, and it is the reason this is a function with a test
     * rather than a `maxOf` at a call site. Suppose revision 7 cannot be
     * applied because its chunks are still uploading, and revision 9 applies
     * cleanly. Taking the highest sets the watermark to 9, the next sync asks
     * for `revision > 9`, and revision 7 is never offered again. The document
     * is missing on that one device, permanently, with nothing on screen and
     * nothing in a log. It is the same failure a timestamp watermark would
     * cause through clock skew, arriving by a different road.
     *
     * [results] must be in ascending revision order -- the order the server
     * was asked for and the order they were applied in. Anything else is a
     * programming error and the function says so rather than guessing.
     */
    fun advance(current: Long, results: List<Applied>): Long {
        var mark = current
        var previous = Long.MIN_VALUE
        for (r in results) {
            require(r.revision > previous) { "results must ascend by revision" }
            previous = r.revision
            if (!r.applied) return mark
            mark = r.revision
        }
        return mark
    }

    /** One document's fate in a batch. */
    data class Applied(val revision: Long, val applied: Boolean)
}

/** Parsing, kept apart from the socket so every branch is pinned by a test. */
object CorpusJson {

    fun documents(body: String): List<RemoteDocument>? = runCatching {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { document(array.optJSONObject(it)) }
    }.getOrNull()

    fun document(row: JSONObject?): RemoteDocument? {
        if (row == null) return null
        val docId = row.optString("doc_id").takeIf { it.isNotBlank() } ?: return null
        // A revision of zero is the column default and means the trigger did
        // not run. Refusing it keeps a row the server could not stamp from
        // being applied at a watermark that can never advance past it.
        val revision = row.optLong("revision", 0L).takeIf { it > 0L } ?: return null
        return RemoteDocument(
            docId = docId,
            title = row.optString("title").ifBlank { docId },
            category = row.optString("category").takeIf { it.isNotBlank() },
            sizeBytes = row.optLong("size_bytes", -1L).takeIf { it >= 0L },
            chunkCount = row.optInt("chunk_count", 0),
            revision = revision,
            publishedAt = row.optString("published_at").takeIf { it.isNotBlank() },
            withdrawnAt = row.optString("withdrawn_at").takeIf { it.isNotBlank() },
        )
    }

    fun chunks(body: String): List<RemoteChunk>? = runCatching {
        val array = JSONArray(body)
        (0 until array.length()).mapNotNull { i ->
            val row = array.optJSONObject(i) ?: return@mapNotNull null
            val ordinal = row.optInt("ordinal", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            val content = row.optString("content").takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            RemoteChunk(
                ordinal = ordinal,
                section = row.optString("section").takeIf { it.isNotBlank() },
                content = content,
                embedding = HexBytes.decode(
                    row.optString("embedding").takeIf { it.isNotBlank() }
                ),
            )
        }
    }.getOrNull()

    /** The revision of a single document row, for the re-read that catches a
     *  republish landing in the middle of a pull. */
    fun revisionOf(body: String): Long? = runCatching {
        val array = JSONArray(body)
        if (array.length() == 0) null
        else array.optJSONObject(0)?.optLong("revision", 0L)?.takeIf { it > 0L }
    }.getOrNull()

    /** The ordinals already on the server for one document, for a resumed
     *  upload. */
    fun ordinals(body: String): Set<Int>? = runCatching {
        val array = JSONArray(body)
        (0 until array.length())
            .mapNotNull { array.optJSONObject(it)?.optInt("ordinal", -1)?.takeIf { o -> o >= 0 } }
            .toSet()
    }.getOrNull()
}
