package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.data.Route

/**
 * Stage 2 of classification: cosine against one averaged exemplar vector per
 * route, standing in for the backend's Ollama classify call.
 *
 * Prototypes are generated at build time by scripts/export_route_prototypes.py
 * from the same MiniLM that embedded the corpus, so they live in the same
 * vector space as the query embedding.
 *
 * Returns null -- meaning "no opinion", which the router reads as FACT --
 * whenever the embedder is unavailable or the top two routes are within
 * [MARGIN] of each other. That mirrors the backend, where any classifier
 * failure also resolves to FACT.
 */
class RoutePrototypes(
    private val embedder: QueryEmbedder?,
    private val prototypes: Map<Route, FloatArray>,
) {
    fun classify(query: String): Pair<Route, String>? {
        val e = embedder?.takeIf { it.isReady } ?: return null
        if (prototypes.isEmpty()) return null
        val v = runCatching { e.embed(query) }.getOrNull() ?: return null
        val scored = prototypes.mapValues { (_, p) -> cosine(v, p) }
            .entries.sortedByDescending { it.value }
        if (scored.size < 2) return null
        val (top, second) = scored[0] to scored[1]
        if (top.value - second.value < MARGIN) return null
        return top.key to "cosine %.2f vs %.2f".format(top.value, second.value)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return -1.0
        var dot = 0.0
        for (i in a.indices) dot += (a[i] * b[i]).toDouble()
        return dot
    }

    companion object {
        /**
         * Required lead of the top prototype over the runner-up.
         *
         * Measured on 15 held-out questions the exemplars never saw:
         *   rules only (everything unmatched -> FACT):  5/15, LOCAL+GLOBAL 0/10
         *   margin 0.02 .. 0.05:                       12/15, LOCAL+GLOBAL 7/10,
         *                                              and 11/11 correct when it fires
         *   margin 0.08:                               11/15  (starts abstaining
         *                                              on questions it had right)
         * 0.02 and 0.05 score identically, so the larger one is taken for
         * headroom. Every question below the margin falls to FACT, so the
         * failure direction is always "did not help", never "confidently wrong".
         */
        const val MARGIN = 0.05

        val EMPTY = RoutePrototypes(null, emptyMap())

        fun create(embedder: QueryEmbedder?): RoutePrototypes =
            RoutePrototypes(embedder, RoutePrototypesData.VECTORS)
    }
}
