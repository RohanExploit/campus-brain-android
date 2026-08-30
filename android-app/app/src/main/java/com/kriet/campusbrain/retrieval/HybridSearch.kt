package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.data.BrainDb
import com.kriet.campusbrain.data.RetrievedChunk
import com.kriet.campusbrain.data.query
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Vector arm: brute force over every stored embedding.
 *
 * 493 x 384 dot products is ~190k multiply-adds; an ANN index would be more
 * machinery than the problem has. Vectors are loaded once into one flat
 * FloatArray at warm-up so a query does no SQLite work at all -- reading them
 * per query measured 108ms on the demo device, which is most of a perceptible
 * pause for nothing.
 *
 * Stored vectors are already L2-normalised (verified: max |norm-1| = 1.2e-07),
 * so cosine is a plain dot product and no per-row normalisation is needed.
 */
class VectorSearch(private val db: BrainDb) {

    private var dim = 0
    private var ids: LongArray = LongArray(0)
    private var flat: FloatArray = FloatArray(0)
    var warmed = false
        private set

    fun warm() {
        if (warmed) return
        dim = db.embeddingDim
        val idList = ArrayList<Long>()
        val vecs = ArrayList<FloatArray>()
        db.conn.prepare("SELECT chunk_id, vec FROM embeddings ORDER BY chunk_id").use { st ->
            while (st.step()) {
                val blob = st.getBlob(1)
                if (blob.size != dim * 4) continue
                val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val v = FloatArray(dim)
                fb.get(v)
                idList.add(st.getLong(0))
                vecs.add(v)
            }
        }
        ids = idList.toLongArray()
        flat = FloatArray(vecs.size * dim)
        vecs.forEachIndexed { i, v -> v.copyInto(flat, i * dim) }
        warmed = true
    }

    val size: Int get() = ids.size

    /** Chunk ids ranked by cosine against [queryVec], best first. */
    fun rank(queryVec: FloatArray, topK: Int): List<Pair<Long, Double>> {
        if (!warmed || ids.isEmpty() || queryVec.size != dim) return emptyList()
        val scored = ArrayList<Pair<Long, Double>>(ids.size)
        for (i in ids.indices) {
            var dot = 0f
            val base = i * dim
            for (j in 0 until dim) dot += flat[base + j] * queryVec[j]
            scored.add(ids[i] to dot.toDouble())
        }
        scored.sortByDescending { it.second }
        return scored.take(topK)
    }
}

/**
 * Reciprocal Rank Fusion of the keyword and vector arms.
 *
 * RRF rather than a weighted score sum: bm25 is unbounded-negative and
 * corpus-dependent while cosine sits in [-1,1], so any weighted combination
 * needs a per-query normalisation that is fragile on small result sets. RRF
 * only reads ranks, has one constant, and degrades to "whatever the other arm
 * said" when one side returns nothing -- which is exactly the behaviour needed
 * while the on-device embedder is absent.
 */
class HybridSearch(
    private val db: BrainDb,
    private val fts: FtsSearch,
    private val like: LikeSearch,
    private val vectors: VectorSearch,
    private val embedder: QueryEmbedder?,
) {
    data class Result(
        val chunks: List<RetrievedChunk>,
        val ftsHits: Int,
        val vecHits: Int,
        val bothCount: Int,
        val usedLikeFallback: Boolean,
    )

    fun search(queryText: String, topK: Int, perArm: Int = 20): Result {
        val usedLike = !fts.available
        val keyword = if (usedLike) like.search(queryText, perArm) else fts.search(queryText, perArm)

        val vectorRanked: List<Pair<Long, Double>> =
            embedder?.takeIf { it.isReady }?.let { e ->
                vectors.warm()
                runCatching { vectors.rank(e.embed(queryText), perArm) }.getOrElse { emptyList() }
            } ?: emptyList()

        val ftsRankOf = keyword.mapIndexed { i, c -> c.id to i }.toMap()
        val vecRankOf = vectorRanked.mapIndexed { i, (id, _) -> id to i }.toMap()

        val fused = HashMap<Long, Double>()
        ftsRankOf.forEach { (id, r) -> fused[id] = (fused[id] ?: 0.0) + 1.0 / (RRF_K + r + 1) }
        vecRankOf.forEach { (id, r) -> fused[id] = (fused[id] ?: 0.0) + 1.0 / (RRF_K + r + 1) }

        if (fused.isEmpty()) return Result(emptyList(), 0, 0, 0, usedLike)

        val byId = keyword.associateBy { it.id }.toMutableMap()
        val missing = fused.keys.filter { it !in byId }
        if (missing.isNotEmpty()) {
            val placeholders = missing.joinToString(",") { "?" }
            db.conn.query(
                "SELECT id, doc_id, section, content FROM chunks WHERE id IN ($placeholders)",
                bind = { st -> missing.forEachIndexed { i, id -> st.bindLong(i + 1, id) } },
            ) {
                RetrievedChunk(it.getLong(0), it.getText(1),
                    if (it.isNull(2)) null else it.getText(2), it.getText(3), 0.0)
            }.forEach { byId[it.id] = it }
        }

        val ordered = fused.entries.sortedByDescending { it.value }.take(topK).mapNotNull { (id, s) ->
            byId[id]?.copy(score = s, ftsRank = ftsRankOf[id], vecRank = vecRankOf[id])
        }
        return Result(ordered, keyword.size, vectorRanked.size, ordered.count { it.foundByBoth }, usedLike)
    }

    /**
     * Pack chunks into a context budget, mirroring `_fact_context` in
     * retrieval/router.py: the best chunk is always included even if oversized,
     * then stop before the first chunk that would overflow.
     */
    fun pack(chunks: List<RetrievedChunk>, budgetChars: Int = CONTEXT_BUDGET_CHARS): List<RetrievedChunk> {
        val out = ArrayList<RetrievedChunk>()
        var budget = budgetChars
        for (c in chunks) {
            if (out.isNotEmpty() && c.content.length > budget) break
            out.add(c)
            budget -= c.content.length
        }
        return out
    }

    companion object {
        const val RRF_K = 60
        /** config.CONTEXT_BUDGET_CHARS */
        const val CONTEXT_BUDGET_CHARS = 5000
        /** config.FACT_TOP_K */
        const val FACT_TOP_K = 10
        /** config.GLOBAL_FANOUT_K */
        const val GLOBAL_FANOUT_K = 30
        /** config.LOCAL_VECTOR_K */
        const val LOCAL_VECTOR_K = 10
    }
}

/** Supplies a 384d query vector. Absent until the ONNX MiniLM is wired in. */
interface QueryEmbedder {
    val isReady: Boolean
    fun embed(text: String): FloatArray
}
