package com.kriet.campusbrain.data

import android.content.Context
import android.util.Log
import com.kriet.campusbrain.embed.MiniLmEmbedder
import com.kriet.campusbrain.retrieval.FtsSearch
import com.kriet.campusbrain.retrieval.GraphTraverse
import com.kriet.campusbrain.retrieval.HybridSearch
import com.kriet.campusbrain.retrieval.LikeSearch
import com.kriet.campusbrain.retrieval.QueryEmbedder
import com.kriet.campusbrain.retrieval.QueryRouter
import com.kriet.campusbrain.retrieval.RoutePrototypes
import com.kriet.campusbrain.retrieval.TabularQueries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed interface InitState {
    data object Loading : InitState
    data class Ready(val repo: BrainRepository) : InitState
    data class Failed(val message: String) : InitState
}

/**
 * Everything the three screens share, built once.
 *
 * No DI framework: four ViewModels do not need one, and the wiring below is
 * shorter than the module declarations would be.
 */
class BrainRepository private constructor(
    val db: BrainDb,
    val router: QueryRouter,
    val tabular: TabularQueries,
    val docs: DocsRepository,
    val fts: FtsSearch,
    val vectorReady: Boolean,
    val embedder: QueryEmbedder? = null,
    /**
     * Adding a document to the on-device corpus. Non-null even when the store
     * could not be opened -- it reports the failure through [IngestResult]
     * rather than by being absent, so the UI has one code path.
     */
    val ingest: DocumentIngest,
) {
    companion object {
        private val _state = MutableStateFlow<InitState>(InitState.Loading)
        val state: StateFlow<InitState> = _state

        /** Call on a background dispatcher. Safe to call more than once. */
        fun init(context: Context, embedder: QueryEmbedder? = null) {
            if (_state.value is InitState.Ready) return
            _state.value = try {
                val db = BrainDb.open(context)
                // Null when the 86MB model is absent or the session will not
                // start. Everything below degrades rather than fails: FTS5-only
                // retrieval, and no prototype stage, which is what the backend
                // does when its own classifier call raises.
                val embed = embedder ?: MiniLmEmbedder.create(context)
                val fts = FtsSearch(db)
                val like = LikeSearch(db)
                val vectors = com.kriet.campusbrain.retrieval.VectorSearch(db)

                // The user's own imported documents. Null is a supported
                // state, not a failure: everything below falls back to the
                // bundled corpus alone, which is exactly the app as it was
                // before ingestion existed.
                val userDb = UserCorpusDb.openOrCreate(context)
                val userArms = userDb?.let {
                    HybridSearch.UserArms(
                        fts = FtsSearch(it.conn),
                        like = LikeSearch(it.conn),
                        vectors = com.kriet.campusbrain.retrieval.VectorSearch(it.conn, db.embeddingDim),
                        conn = it.conn,
                    )
                }

                val hybrid = HybridSearch(db, fts, like, vectors, embed, userArms)
                val graph = GraphTraverse(db).also { it.warm() }
                val tabular = TabularQueries(db)
                val prototypes = if (embed?.isReady == true) RoutePrototypes.create(embed) else null
                val router = QueryRouter(db, hybrid, tabular, graph, prototypes,
                    cloud = com.kriet.campusbrain.answer.CloudAnswer(context))
                if (embed?.isReady == true) vectors.warm()

                val ingest = DocumentIngest(
                    context = context.applicationContext,
                    user = userDb,
                    embedder = embed,
                    // Doc ids already spoken for, so an imported file called
                    // "student.md" cannot shadow the bundled one and make
                    // DocsRepository.chunksOf ambiguous.
                    reservedDocIds = db.conn
                        .query("SELECT DISTINCT doc_id FROM chunks") { it.getText(0) }.toSet(),
                    // A newly written vector is invisible until the flat array
                    // is rebuilt, and the array is built once at warm-up. Not
                    // invalidating here is the difference between a document
                    // that is searchable now and one that is searchable after
                    // the next cold start.
                    onIndexChanged = { userArms?.vectors?.invalidate() },
                )

                Log.i(
                    TAG,
                    "repository ready: fts=${fts.available} vectors=${embed?.isReady == true} " +
                        "prototypes=${prototypes != null} edges=${graph.edgeCount} " +
                        "user_chunks=${userDb?.chunkCount ?: -1}"
                )
                InitState.Ready(
                    BrainRepository(db, router, tabular, DocsRepository(db, userDb), fts,
                        vectorReady = embed?.isReady == true, embedder = embed, ingest = ingest)
                )
            } catch (e: BrainDbMissingException) {
                InitState.Failed(e.message ?: "corpus missing")
            } catch (e: Throwable) {
                Log.e(TAG, "repository init failed", e)
                InitState.Failed("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}

/**
 * Document list, over both corpora.
 *
 * The shipped bundle has no `documents` table, so the list is synthesised from
 * `chunks`. What used to be synthesised was a filename and the single category
 * "Other" for all 58 documents, which put a high-frequency-trading paper at
 * the top of a campus app's Documents tab. Three things changed:
 *
 *  - the category comes from [DocCatalog], read off the `doc_id`;
 *  - the title comes from the document's own "Subject:" heading where it has
 *    one, which for the college's 43 circulars and policies it does. Measured
 *    against the bundle, that turns "Svc 09 Placement Drive Ratnagiri
 *    Softworks" into "Campus Placement Drive: Ratnagiri Softworks". The first
 *    heading was tried first and rejected: almost every document's first
 *    section is the letterhead, so it produced 43 documents all titled "Konkan
 *    Ratna Institute of Engineering and Technology";
 *  - documents the user imported are appended from [UserCorpusDb], carrying
 *    `isUserAdded = true`.
 */
class DocsRepository(
    private val db: BrainDb,
    private val user: UserCorpusDb? = null,
) {

    fun all(): List<DocumentSummary> {
        val bundled = if (db.hasDocumentsTable) fromTable() else synthesised()
        val added = user?.documents()?.map { it.copy(isUserAdded = true) } ?: emptyList()
        // Sorted here rather than in SQL because the two lists come from two
        // databases and the ordering is over the union, not over either half.
        return (bundled + added).sortedWith(
            compareBy({ DocCatalog.orderOf(it.category) }, { it.title.lowercase() })
        )
    }

    private fun fromTable(): List<DocumentSummary> = db.conn.query(
        "SELECT doc_id, title, category, chunk_count, preview FROM documents"
    ) {
        val docId = it.getText(0)
        val stored = if (it.isNull(2)) "" else it.getText(2)
        DocumentSummary(
            docId = docId,
            title = it.getText(1),
            // A future bundle may carry a real category; until it does, an
            // empty or catch-all value is worse than deriving one.
            category = if (stored.isBlank() || stored.equals("other", ignoreCase = true))
                DocCatalog.categoryOf(docId) else stored,
            chunkCount = it.getLong(3).toInt(),
            preview = if (it.isNull(4)) null else it.getText(4),
        )
    }

    private fun synthesised(): List<DocumentSummary> = db.conn.query(
        // The correlated subquery is the document's own subject line. 58 rows,
        // one indexed lookup each: measured as instant, and it is the whole
        // difference between a filename and a title.
        "SELECT c.doc_id, COUNT(*), " +
            "(SELECT s.section FROM chunks s WHERE s.doc_id = c.doc_id " +
            " AND s.section LIKE 'Subject:%' ORDER BY s.id LIMIT 1), " +
            "(SELECT s2.content FROM chunks s2 WHERE s2.doc_id = c.doc_id ORDER BY s2.id LIMIT 1) " +
            "FROM chunks c GROUP BY c.doc_id"
    ) {
        val docId = it.getText(0)
        val n = it.getLong(1).toInt()
        val subject = if (it.isNull(2)) null else it.getText(2)
        val preview = if (it.isNull(3)) null else it.getText(3).take(200)
        DocumentSummary(
            docId = docId,
            title = subjectTitle(subject) ?: DocCatalog.titleFor(docId),
            category = DocCatalog.categoryOf(docId),
            chunkCount = n,
            preview = preview,
        )
    }

    /**
     * "Subject: Attendance Policy, Academic Year 2026-27" -> the part after
     * the colon. Rejected when it is too short to be a title or long enough to
     * be a paragraph, in which case the filename is the safer choice.
     */
    private fun subjectTitle(section: String?): String? {
        if (section == null || !section.startsWith("Subject:", ignoreCase = true)) return null
        val t = section.substring("Subject:".length).trim()
        return if (t.length in 8..80) t else null
    }

    /** Chunks of one document, from whichever corpus holds it. */
    fun chunksOf(docId: String): List<RetrievedChunk> {
        val conn = if (user != null && user.exists(docId)) user.conn else db.conn
        return conn.query(
            "SELECT id, doc_id, section, content FROM chunks WHERE doc_id = ? ORDER BY id",
            bind = { it.bindText(1, docId) },
        ) {
            RetrievedChunk(it.getLong(0), it.getText(1),
                if (it.isNull(2)) null else it.getText(2), it.getText(3), 0.0)
        }
    }

    companion object {
        /**
         * Kept as the public entry point because the Documents UI calls it.
         * The derivation itself moved to [DocCatalog.titleFor], where it is
         * tested against every doc_id in the bundle.
         */
        fun titleFor(docId: String): String = DocCatalog.titleFor(docId)
    }
}
