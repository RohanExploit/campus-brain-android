package com.campusbrain.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.campusbrain.app.retrieval.QueryEmbedder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** What happened to a document the user tried to add. */
sealed interface IngestResult {
    /** Indexed, and answerable on the very next question. */
    data class Ok(val docId: String, val title: String, val chunks: Int) : IngestResult

    /** A format this build cannot read. [message] is finished user-facing copy. */
    data class Unsupported(val mime: String, val message: String) : IngestResult

    /** Anything else. Nothing was written; see [reason]. */
    data class Failed(val reason: String) : IngestResult

    /**
     * The import allowance for this tier is used up. Nothing was read, nothing
     * was written, and **nothing already imported was touched.**
     *
     * That last clause is the contract, not a description of the current
     * implementation. An expired licence, a lowered cap, a tier that went back
     * to FREE: none of them may hide, lock, or delete a document the
     * institution already added. `DocsFragment`'s list, [DocumentIngest.added]
     * and [DocumentIngest.remove] behave identically at every tier and in every
     * licence state. This case blocks a NEW import and does nothing else --
     * in the same spirit as [UserCorpusDb]'s own rule that a broken user
     * database must never take the college's documents down with it. A licence
     * that ran out must never take the college's documents down either.
     *
     * Adding a fourth case to this sealed interface is a deliberate compile
     * break at every exhaustive `when (result)`: a call site that silently
     * ignored a licence refusal would show the student nothing at all when
     * their import quietly did not happen.
     */
    data class LicenseRequired(
        val used: Int,
        val cap: Int,
        val tier: com.campusbrain.app.data.auth.Tier,
        /**
         * Which of the key's two caps bound. [used] and [cap] are documents
         * for [Limit.DOCUMENTS] and kilobytes for [Limit.KILOBYTES], because
         * one screen saying "1 of 1" and meaning documents while another says
         * "4096 of 4096" and means KB is the sort of thing a support call is
         * made of. Defaulted so the common case reads as three fields.
         */
        val limit: Limit = Limit.DOCUMENTS,
    ) : IngestResult {
        enum class Limit { DOCUMENTS, KILOBYTES }
    }
}

/**
 * The two cap comparisons, as pure arithmetic over four numbers.
 *
 * Split out of [DocumentIngest.ingest] rather than written inline, because
 * `ingest` needs a `Context` and a `ContentResolver` and is therefore only
 * runnable on a device -- and the boundary condition here (`used >= cap`, not
 * `>`) is precisely the sort of off-by-one that must be covered by a test that
 * cannot be skipped. Everything below is JVM-testable with no SQLite native
 * and no Robolectric.
 *
 * Both functions return null for "allowed", so the call site reads as a guard
 * rather than as a boolean whose polarity has to be remembered.
 */
object ImportAllowance {

    /**
     * `used >= cap`, not `>`.
     *
     * A cap of one means one document may exist, so the second import is the
     * one refused: at `used == 1` the allowance is spent. Written as `>=`
     * against the count BEFORE this import rather than `>` against the count
     * after it, because the count after it does not exist yet and inventing it
     * is how a cap of one ends up admitting two.
     */
    fun documentCap(
        usedDocs: Int,
        caps: com.campusbrain.app.data.auth.Licensing.Caps,
    ): IngestResult.LicenseRequired? =
        if (usedDocs >= caps.maxDocs) {
            IngestResult.LicenseRequired(usedDocs, caps.maxDocs, caps.tier)
        } else null

    /**
     * The total-KB cap, which unlike the document cap has to account for the
     * incoming file: a 40MB allowance with 39MB used still admits a 500KB
     * timetable, and refusing on `used >= cap` alone would refuse it.
     *
     * Kilobytes are rounded UP on the way in and DOWN on the way out. That is
     * asymmetric on purpose: rounding the incoming file up means a 1-byte file
     * costs 1KB rather than 0, so a cap cannot be defeated by a thousand tiny
     * files; rounding the stored total down means an institution is never
     * charged for a kilobyte the app cannot point at.
     */
    fun byteCap(
        usedBytes: Long,
        incomingBytes: Long,
        caps: com.campusbrain.app.data.auth.Licensing.Caps,
    ): IngestResult.LicenseRequired? {
        val usedKb = (usedBytes / 1024L).toInt()
        val incomingKb = ((incomingBytes + 1023L) / 1024L).toInt()
        return if (usedKb + incomingKb > caps.maxTotalKb) {
            IngestResult.LicenseRequired(
                usedKb, caps.maxTotalKb, caps.tier,
                IngestResult.LicenseRequired.Limit.KILOBYTES,
            )
        } else null
    }
}

/**
 * Turns a file the user shared into part of the searchable corpus, entirely on
 * this phone.
 *
 * The pipeline is extract -> chunk -> embed -> write, and the interesting
 * decisions are at the two ends.
 *
 * At the front: only formats that cost nothing at build time. Plain text,
 * markdown, and .docx, which is a zip of XML that `java.util.zip` and
 * `javax.xml.parsers` already read (see [DocxText]). PDF is refused with a
 * message rather than supported, because the only way to support it is to add
 * PdfBox and roughly 16MB of APK, and that is a product decision, not one to
 * make quietly inside an ingestion function.
 *
 * At the back: writes go to [UserCorpusDb], never to `brain.db`. The bundled
 * corpus is opened `query_only` and is re-copied from assets whenever the app
 * ships a new one, so it is both unwritable and impermanent -- either property
 * on its own would be enough to rule it out as a place to keep a user's
 * documents. See UserCorpusDb's doc comment for what that means at upgrade
 * time (short version: nothing happens to them).
 *
 * Nothing here touches the network. The embedder is the same ONNX MiniLM the
 * app already runs for queries, so an ingested chunk and a bundled one live in
 * the same vector space and are ranked against each other honestly.
 */
class DocumentIngest internal constructor(
    private val context: Context,
    private val user: UserCorpusDb?,
    private val embedder: QueryEmbedder?,
    /** Doc ids already in the bundle, so an added file cannot shadow one. */
    private val reservedDocIds: Set<String>,
    /** Called after a successful write so the vector cache can be re-warmed. */
    private val onIndexChanged: () -> Unit,
    /**
     * What this device is licensed to import, read fresh on every attempt.
     *
     * A lambda rather than a value, so a key entered on the licence screen
     * takes effect on the very next import with nothing to invalidate; and a
     * parameter rather than a direct call to
     * [com.campusbrain.app.data.auth.Licensing], so the gate is testable on
     * the JVM without a SQLite native or an Android `Context`.
     *
     * Note the direction of the dependency. Ingestion asks the licence layer a
     * question; the licence layer knows nothing about ingestion, and neither
     * of them appears anywhere on the path from a question to an answer.
     */
    private val caps: () -> com.campusbrain.app.data.auth.Licensing.Caps =
        { com.campusbrain.app.data.auth.Licensing.caps() },
) {

    /**
     * False when the ONNX model is absent. Ingestion still works and the
     * document is still findable by keyword through FTS5 -- it simply has no
     * vector arm, exactly the degradation the whole app already has in that
     * state. Worth showing, because it is the difference between "finds the
     * words you typed" and "finds what you meant".
     */
    val embedderReady: Boolean get() = embedder?.isReady == true

    /**
     * Extract, chunk, embed and index [uri]. Suspending; switches to
     * [Dispatchers.IO] itself, so it is safe to call straight from a
     * Main-dispatcher coroutine.
     *
     * [onProgress] is invoked back on the CALLER's context, so a fragment may
     * touch views inside it without posting. `total` is 0 while the file is
     * being read and chunked -- the chunk count is not knowable until then --
     * and thereafter `done` climbs to `total` as each chunk is embedded.
     * Embedding ~50 chunks is a few seconds of phone CPU; this is a real
     * measurement of it, not a timed animation.
     */
    suspend fun ingest(
        uri: Uri,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): IngestResult {
        // Keep the caller's dispatcher, drop its Job: reporting progress must
        // not join this work to the caller's cancellation scope twice.
        val callerContext: CoroutineContext = currentCoroutineContext().minusKey(Job)
        suspend fun report(done: Int, total: Int) =
            withContext(callerContext) { onProgress(done, total) }

        val store = user ?: return IngestResult.Failed(
            "There is nowhere on this device to store an added document."
        )

        // The licence check, here and not lower down.
        //
        // Two reasons, and they point the same way. It matches this function's
        // existing cheap-to-expensive ordering -- the null-store guard above
        // costs nothing, this costs one COUNT(*), and everything below opens
        // the file -- so a refusal is instant rather than arriving after a
        // student has watched a progress bar. And it means **no bytes of the
        // student's file are read before the app knows whether it is allowed
        // to keep them**, which is a stronger statement than "we deleted it
        // afterwards".
        //
        // Nothing here consults the network, and nothing here can fail into
        // "allowed": a broken store counts 0 documents and a broken licence
        // resolves to the free allowance, so the worst case is a free import,
        // never a locked-out one.
        val allowance = caps()
        ImportAllowance.documentCap(store.importedCount(), allowance)?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                report(0, 0)
                val name = displayName(uri) ?: "document"
                val mime = context.contentResolver.getType(uri) ?: ""

                val bytes = readBytes(uri)
                    ?: return@withContext IngestResult.Failed("That file could not be opened.")
                if (bytes.isEmpty()) return@withContext IngestResult.Failed("That file is empty.")
                if (bytes.size > MAX_BYTES) return@withContext IngestResult.Failed(
                    "That file is ${bytes.size / (1024 * 1024)}MB. " +
                        "The limit is ${MAX_BYTES / (1024 * 1024)}MB so indexing stays quick."
                )

                // The second cap, and the only one that cannot be checked
                // before the file is opened: its size is not knowable without
                // reading it. Still before extraction, chunking and every one
                // of the fifty embeddings, and still before a single row is
                // written -- so a refusal here costs a read and nothing else.
                //
                // The device-independent file-size limit above is checked
                // FIRST on purpose: "that file is 9MB" is a sentence the
                // student can act on, and it should not be replaced by a
                // licence wall they cannot.
                ImportAllowance.byteCap(store.importedBytes(), bytes.size.toLong(), allowance)
                    ?.let { return@withContext it }

                val text = when (val e = extract(bytes, mime, name)) {
                    is Extracted.Text -> e.value
                    is Extracted.No -> return@withContext IngestResult.Unsupported(
                        mime.ifBlank { "unknown" }, e.message
                    )
                }
                if (text.isBlank()) return@withContext IngestResult.Failed(
                    "No readable text came out of that file."
                )

                val pieces = TextChunker.chunk(text)
                if (pieces.isEmpty()) return@withContext IngestResult.Failed(
                    "No readable text came out of that file."
                )
                if (pieces.size > MAX_CHUNKS) return@withContext IngestResult.Failed(
                    "That document splits into ${pieces.size} sections, over the " +
                        "$MAX_CHUNKS limit. Add it in parts."
                )

                report(0, pieces.size)
                val embed = embedder?.takeIf { it.isReady }
                val chunks = ArrayList<UserCorpusDb.PendingChunk>(pieces.size)
                pieces.forEachIndexed { i, piece ->
                    // Cancellation is checked per chunk rather than per
                    // document: a user who backs out of a 400-chunk file
                    // should not wait for the rest of it, and nothing has been
                    // written yet, so stopping here costs nothing.
                    currentCoroutineContext().ensureActive()
                    val vec = embed?.let { runCatching { it.embed(piece.content) }.getOrNull() }
                    chunks += UserCorpusDb.PendingChunk(piece.section, piece.content, vec)
                    report(i + 1, pieces.size)
                }

                val title = titleFor(name)
                val docId = store.uniqueDocId(freeDocId(name, store))
                val written = store.write(
                    UserCorpusDb.PendingDocument(
                        docId = docId,
                        title = title,
                        sourceUri = uri.toString(),
                        addedAtUtc = Instant.now().toString(),
                        chunks = chunks,
                        sizeBytes = bytes.size.toLong(),
                    )
                )
                onIndexChanged()
                Log.i(TAG, "ingested \"$docId\": $written chunks, vectors=${embed != null}")
                IngestResult.Ok(docId, title, written)
            } catch (c: CancellationException) {
                // Cancellation is not a failure and must not be converted into
                // one. The user backed out, or the fragment went away; the
                // per-chunk ensureActive() above throws this deliberately.
                // Swallowing it would report a failure into a scope that has
                // already gone, and would break the caller's own cancellation.
                // Nothing was written -- the transaction has not started yet.
                throw c
            } catch (t: Throwable) {
                // Log the type, never the payload: the file is the user's own
                // and may be a fee receipt or a medical certificate.
                Log.w(TAG, "ingest failed: ${t.javaClass.simpleName}")
                IngestResult.Failed("That document could not be added (${t.javaClass.simpleName}).")
            }
        }
    }

    /**
     * How much of the import allowance is spent: documents, and bytes.
     *
     * Exposed here rather than by handing the licence screen a [UserCorpusDb],
     * because this class is already the one thing that owns the store and the
     * only thing that enforces the cap. Two readers of the same numbers cannot
     * then disagree about what "used" means.
     *
     * Zeroes when there is no store. Same direction as everywhere else in this
     * file: a corpus that cannot be counted must not be able to lock anybody
     * out.
     */
    fun usage(): Pair<Int, Long> =
        (user?.importedCount() ?: 0) to (user?.importedBytes() ?: 0L)

    /** Deletes an added document and everything indexed from it. */
    fun remove(docId: String): Boolean {
        val removed = user?.remove(docId) ?: false
        if (removed) onIndexChanged()
        return removed
    }

    /**
     * Documents the user added, newest first.
     *
     * Every row is flagged [DocumentSummary.isUserAdded] here rather than
     * relying on the caller to know what it asked for. The Documents UI was
     * observed setting the flag itself with a `.copy()` and a comment
     * explaining that this function did not -- which was true, and was a
     * needless thing for a caller to have to know.
     *
     * Note this is a SUBSET of `DocsRepository.all()`, which returns both
     * corpora merged and sorted. Use one or the other, never both: adding
     * this list to that one lists every imported document twice.
     */
    fun added(): List<DocumentSummary> =
        user?.documents()?.map { it.copy(isUserAdded = true) } ?: emptyList()

    // --- extraction --------------------------------------------------------

    private sealed interface Extracted {
        data class Text(val value: String) : Extracted
        data class No(val message: String) : Extracted
    }

    private fun extract(bytes: ByteArray, mime: String, name: String): Extracted {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext in PLAIN_EXTENSIONS || mime in PLAIN_MIMES || mime.startsWith("text/") ->
                Extracted.Text(decodeText(bytes))

            ext == "docx" || mime == DOCX_MIME ->
                runCatching { Extracted.Text(DocxText.extract(ByteArrayInputStream(bytes))) }
                    .getOrElse {
                        Extracted.No(
                            "That .docx could not be read — it may be password protected, " +
                                "or saved in the older .doc format. Re-save it as .docx and try again."
                        )
                    }

            ext == "pdf" || mime == "application/pdf" -> Extracted.No(
                "PDFs aren't supported yet. Save the document as Word (.docx) or " +
                    "plain text and add that instead."
            )

            ext == "doc" -> Extracted.No(
                "The old .doc format isn't supported. Open it in Word and " +
                    "\"Save As\" .docx, then add that."
            )

            else -> Extracted.No(
                "Campus Brain can read plain text, Markdown and Word (.docx) files. " +
                    "It can't read ${if (ext.isNotBlank()) ".$ext" else "that format"} yet."
            )
        }
    }

    /**
     * UTF-8, with the byte-order mark stripped. Windows Notepad writes one and
     * it would otherwise become the first character of the first chunk, where
     * it is invisible on screen and blocks an exact match on the first word.
     */
    private fun decodeText(bytes: ByteArray): String {
        val text = String(bytes, Charsets.UTF_8)
        return if (text.startsWith("﻿")) text.substring(1) else text
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
            ?: uri.lastPathSegment
    }.getOrNull()

    /** A doc_id that collides with neither the bundle nor an earlier upload. */
    private fun freeDocId(name: String, store: UserCorpusDb): String {
        var candidate = name
        var n = 2
        while (candidate in reservedDocIds || store.exists(candidate)) {
            candidate = "${name.substringBeforeLast('.')} ($n)." +
                name.substringAfterLast('.', "txt")
            n++
        }
        return candidate
    }

    /**
     * Human title from a filename. Unlike [DocsRepository.titleFor] this does
     * NOT strip a leading number: the bundle's names are catalogue-generated
     * ("24_attendance_policy.md") and the digits are an export artefact, while
     * a user who names a file "2026 fee receipt" meant the year.
     */
    private fun titleFor(name: String): String =
        name.substringBeforeLast('.').replace('_', ' ').trim().ifBlank { name }

    companion object {
        /** Big enough for any handbook, small enough that reading it is instant. */
        private const val MAX_BYTES = 8 * 1024 * 1024

        /**
         * Roughly 600 * 50ms of on-device embedding, so half a minute at the
         * worst. Past that the honest thing is to refuse and say why, rather
         * than truncate a document the user believes is fully searchable.
         */
        private const val MAX_CHUNKS = 600

        private const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        private val PLAIN_EXTENSIONS = setOf("txt", "text", "md", "markdown", "mdown", "log", "csv")
        private val PLAIN_MIMES = setOf(
            "text/plain", "text/markdown", "text/x-markdown", "text/csv", "application/markdown",
        )
    }
}
