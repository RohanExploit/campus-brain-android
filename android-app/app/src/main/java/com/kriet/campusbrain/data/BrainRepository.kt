package com.kriet.campusbrain.data

import android.content.Context
import com.kriet.campusbrain.answer.CloudAnswer
import com.kriet.campusbrain.embed.MiniLmEmbedder
import com.kriet.campusbrain.retrieval.FtsSearch
import com.kriet.campusbrain.retrieval.GraphTraverse
import com.kriet.campusbrain.retrieval.HybridSearch
import com.kriet.campusbrain.retrieval.LikeSearch
import com.kriet.campusbrain.retrieval.QueryRouter
import com.kriet.campusbrain.retrieval.RoutePrototypes
import com.kriet.campusbrain.retrieval.TabularQueries
import com.kriet.campusbrain.retrieval.VectorSearch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** [BrainRepository.state] over its lifetime: Loading, then Ready or Failed, once. */
sealed class InitState {
    object Loading : InitState()
    data class Ready(val repo: BrainRepository) : InitState()
    data class Failed(val message: String) : InitState()
}

/**
 * The wired-up on-device engine: one [BrainDb] plus every retrieval component
 * built on top of it.
 *
 * A class, not an object, so [RouterSmoke] and the UI can hold a plain
 * reference to one instance ([InitState.Ready.repo]) rather than reaching back
 * through mutable global state for every field. Construction itself lives on
 * the companion, because there is exactly one corpus per process and the app
 * has nowhere else to put "the constructor needs a Context and might fail".
 */
class BrainRepository(
    val db: BrainDb,
    val fts: FtsSearch,
    val router: QueryRouter,
    val tabular: TabularQueries,
    val docs: DocsRepository,
    val embedder: MiniLmEmbedder?,
) {
    companion object {
        private val _state = MutableStateFlow<InitState>(InitState.Loading)
        val state: StateFlow<InitState> = _state.asStateFlow()

        /**
         * Opens the corpus and wires every retrieval class on top of it.
         *
         * Idempotent once Ready: MainActivity calls this from onCreate, which
         * reruns on every configuration change, and re-opening the database and
         * the ONNX session on each rotation would leak both.
         */
        suspend fun init(context: Context) {
            if (_state.value is InitState.Ready) return
            _state.value = runCatching { build(context.applicationContext) }
                .fold(
                    onSuccess = { InitState.Ready(it) },
                    onFailure = {
                        InitState.Failed(
                            "The on-device corpus is not available.\n${it.javaClass.simpleName}: ${it.message}"
                        )
                    },
                )
        }

        private fun build(context: Context): BrainRepository {
            val db = BrainDb.open(context)
            DocsRepository.init(db)

            // Absent whenever assets/minilm/ was not bundled into this build --
            // the normal state today, since it is gitignored. The router
            // degrades to FTS5-only retrieval and rules-only routing exactly as
            // designed; nothing here needs to know which case it is.
            val embedder = MiniLmEmbedder.create(context)

            val fts = FtsSearch(db)
            val hybrid = HybridSearch(db, fts, LikeSearch(db), VectorSearch(db), embedder)
            val tabular = TabularQueries(db)
            val router = QueryRouter(
                db, hybrid, tabular, GraphTraverse(db),
                RoutePrototypes.create(embedder), CloudAnswer(context),
            )
            return BrainRepository(db, fts, router, tabular, DocsRepository, embedder)
        }
    }
}
