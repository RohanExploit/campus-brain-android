package com.campusbrain.app.jvm

import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.embed.MiniLmEmbedder
import com.campusbrain.app.retrieval.FtsSearch
import com.campusbrain.app.retrieval.GraphTraverse
import com.campusbrain.app.retrieval.HybridSearch
import com.campusbrain.app.retrieval.LikeSearch
import com.campusbrain.app.retrieval.QueryEmbedder
import com.campusbrain.app.retrieval.QueryRouter
import com.campusbrain.app.retrieval.RoutePrototypes
import com.campusbrain.app.retrieval.TabularQueries
import com.campusbrain.app.retrieval.VectorSearch
import java.io.File

/**
 * The whole production retrieval stack, wired up in a desktop JVM.
 *
 * This is the point of the exercise. Until now the only end-to-end batteries
 * lived in `app/src/androidTest/` and needed a physical handset, so every agent
 * working on retrieval hand-transcribed the pipeline into Python to check
 * itself -- and a reimplementation can agree with itself while disagreeing with
 * the app. Everything below is the app's own code: [BrainDb], [FtsSearch],
 * [VectorSearch], [HybridSearch], [TabularQueries], [GraphTraverse],
 * [RoutePrototypes], [QueryRouter], and through the router `SqlTemplates`,
 * `AnswerComposer` and `AnswerCheck`.
 *
 * Two things are deliberately not the app:
 *
 *  - the SQLite driver is [JdbcSQLiteDriver], because `sqlite-bundled` has no
 *    JVM native. It is a thin shim over the same C SQLite with FTS5 on;
 *  - `cloud` is null, so the cloud fallback never fires. `QueryRouter` already
 *    documents null as the supported "no Context wired in" state and treats it
 *    exactly like a cloud call that failed, which is also what the app does in
 *    airplane mode -- the mode the product is sold on.
 *
 * `userArms` is null too: the bundled corpus alone, matching a fresh install.
 *
 * Built once per JVM. Opening the ONNX session costs a few seconds and the
 * corpus is read-only, so every battery class shares one.
 */
object JvmCorpus {

    /** True when the ONNX MiniLM loaded, i.e. the vector arm and the prototype
     *  router are live. False means the battery is keyword-only, and every test
     *  that reports a result has to say so. */
    val vectorArm: Boolean get() = pipeline.embedder != null

    /** The real ONNX MiniLM, or null when the 86MB asset is absent (it is
     *  gitignored, so a fresh clone has no model and the battery degrades to
     *  keyword-only rather than failing). */
    val embedder: QueryEmbedder? get() = pipeline.embedder

    /** True when `app/src/main/assets/minilm/model.onnx` exists at all, which
     *  separates "this machine has no model" from "the model would not load". */
    val modelAssetPresent: Boolean by lazy { locate("minilm/model.onnx") != null }

    val db: BrainDb get() = pipeline.db
    val router: QueryRouter get() = pipeline.router
    val tabular: TabularQueries get() = pipeline.tabular
    val fts: FtsSearch get() = pipeline.fts
    val hybrid: HybridSearch get() = pipeline.hybrid

    /** Why the vector arm is absent, for a test that wants to say so out loud. */
    val embedderFailure: String? get() = pipeline.embedderFailure

    class Pipeline(
        val db: BrainDb,
        val fts: FtsSearch,
        val hybrid: HybridSearch,
        val tabular: TabularQueries,
        val router: QueryRouter,
        val embedder: QueryEmbedder?,
        val embedderFailure: String?,
    )

    private val pipeline: Pipeline by lazy { build() }

    private fun build(): Pipeline {
        // A copy, not the committed asset. brain.db is in git; a driver that
        // decided to write a -wal beside it would dirty the working tree.
        val source = requireNotNull(locate("brain.db")) {
            "brain.db not found; looked relative to ${File(".").absolutePath}"
        }
        val work = File.createTempFile("jvm-brain", ".db").apply { deleteOnExit() }
        source.copyTo(work, overwrite = true)

        val db = BrainDb.openWith(JdbcSQLiteDriver(), work.absolutePath, "jvm test copy")

        var failure: String? = null
        val embedder: QueryEmbedder? = run {
            val model = locate("minilm/model.onnx")
            val vocab = locate("minilm/vocab.txt")
            if (model == null || vocab == null) {
                failure = "assets absent: model=$model vocab=$vocab"
                return@run null
            }
            runCatching { MiniLmEmbedder.createFrom(model, vocab) }
                .onFailure { failure = "${it.javaClass.name}: ${it.message}" }
                .getOrNull()
        }

        val fts = FtsSearch(db)
        val like = LikeSearch(db)
        val vectors = VectorSearch(db)
        val hybrid = HybridSearch(db, fts, like, vectors, embedder, userArms = null)
        val graph = GraphTraverse(db).also { it.warm() }
        val tabular = TabularQueries(db)
        val prototypes = if (embedder?.isReady == true) RoutePrototypes.create(embedder) else null
        if (embedder?.isReady == true) vectors.warm()
        // cloud = null: see the class comment.
        val router = QueryRouter(db, hybrid, tabular, graph, prototypes)

        println(
            "JvmCorpus ready: fts=${fts.available} vectors=${embedder?.isReady == true} " +
                "prototypes=${prototypes != null} edges=${graph.edgeCount} " +
                "vecs=${vectors.size}" + (failure?.let { " embedderFailure=$it" } ?: "")
        )
        return Pipeline(db, fts, hybrid, tabular, router, embedder, failure)
    }

    /**
     * Gradle runs unit tests with the module directory as the working dir, but
     * that has moved between AGP versions and differs again from an IDE. Same
     * ladder [com.campusbrain.app.WordPieceTokenizerTest] uses, and for the
     * same reason: resolving none of them there produced ten green-looking
     * skips guarding nothing.
     */
    fun locate(assetPath: String): File? = listOf(
        "src/main/assets/$assetPath",
        "app/src/main/assets/$assetPath",
        "android-app/app/src/main/assets/$assetPath",
        "../app/src/main/assets/$assetPath",
    ).map(::File).firstOrNull { it.exists() && it.length() > 0 }

    /** Suffix for a test name or message, so a keyword-only run can never be
     *  mistaken for full coverage in a log someone reads six months from now. */
    val armLabel: String
        get() = if (vectorArm) "fts5+vector" else "KEYWORD-ONLY (no ONNX embedder)"
}
