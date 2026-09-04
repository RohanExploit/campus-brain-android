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
                val hybrid = HybridSearch(db, fts, like, vectors, embed)
                val graph = GraphTraverse(db).also { it.warm() }
                val tabular = TabularQueries(db)
                val prototypes = if (embed?.isReady == true) RoutePrototypes.create(embed) else null
                val router = QueryRouter(db, hybrid, tabular, graph, prototypes,
                    cloud = com.kriet.campusbrain.answer.CloudAnswer(context))
                if (embed?.isReady == true) vectors.warm()
                Log.i(
                    TAG,
                    "repository ready: fts=${fts.available} vectors=${embed?.isReady == true} " +
                        "prototypes=${prototypes != null} edges=${graph.edgeCount}"
                )
                InitState.Ready(
                    BrainRepository(db, router, tabular, DocsRepository(db), fts,
                        vectorReady = embed?.isReady == true, embedder = embed)
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
 * Document list. Reads the `documents` table when present (bundle
 * user_version >= 2) and otherwise synthesises the same shape from chunks, so
 * the app keeps working against an older bundle instead of showing nothing.
 */
class DocsRepository(private val db: BrainDb) {

    fun all(): List<DocumentSummary> =
        if (db.hasDocumentsTable) fromTable() else synthesised()

    private fun fromTable(): List<DocumentSummary> = db.conn.query(
        "SELECT doc_id, title, category, chunk_count, preview FROM documents " +
            "ORDER BY category, title"
    ) {
        DocumentSummary(
            docId = it.getText(0),
            title = it.getText(1),
            category = if (it.isNull(2)) "Other" else it.getText(2),
            chunkCount = it.getLong(3).toInt(),
            preview = if (it.isNull(4)) null else it.getText(4),
        )
    }

    private fun synthesised(): List<DocumentSummary> = db.conn.query(
        "SELECT doc_id, COUNT(*), MIN(id) FROM chunks GROUP BY doc_id ORDER BY doc_id"
    ) { Triple(it.getText(0), it.getLong(1).toInt(), it.getLong(2)) }
        .map { (docId, n, _) -> DocumentSummary(docId, titleFor(docId), "Other", n, null) }

    fun chunksOf(docId: String): List<RetrievedChunk> = db.conn.query(
        "SELECT id, doc_id, section, content FROM chunks WHERE doc_id = ? ORDER BY id",
        bind = { it.bindText(1, docId) },
    ) {
        RetrievedChunk(it.getLong(0), it.getText(1),
            if (it.isNull(2)) null else it.getText(2), it.getText(3), 0.0)
    }

    companion object {
        private val PREFIX = Regex("^(svc_)?\\d+[_\\-]")

        /** Same derivation as _title_for in scripts/export_mobile_bundle.py. */
        fun titleFor(docId: String): String {
            val stem = docId.substringAfterLast('/').substringBeforeLast('.')
            val cleaned = PREFIX.replace(stem, "").replace('_', ' ').replace('-', ' ').trim()
            if (cleaned.isEmpty()) return stem
            return cleaned.split(' ').joinToString(" ") { w ->
                if (w.isEmpty()) w else w.replaceFirstChar { it.uppercase() }
            }
        }
    }
}
