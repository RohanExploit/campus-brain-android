package com.campusbrain.app.retrieval

import androidx.sqlite.SQLiteConnection
import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.data.RetrievedChunk
import com.campusbrain.app.data.UserCorpusDb
import com.campusbrain.app.data.query
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
class VectorSearch(private val conn: SQLiteConnection, private val declaredDim: Int) {

    /** The bundled corpus, which carries its dimension in `meta`. */
    constructor(db: BrainDb) : this(db.conn, db.embeddingDim)

    private var dim = 0
    private var ids: LongArray = LongArray(0)
    private var flat: FloatArray = FloatArray(0)
    var warmed = false
        private set

    /**
     * Drops the cache so the next [warm] re-reads. Called after a document is
     * ingested: the flat array is built once at start-up, and a new document's
     * vectors would otherwise not be searchable until the app restarted.
     */
    fun invalidate() {
        warmed = false
        ids = LongArray(0)
        flat = FloatArray(0)
    }

    fun warm() {
        if (warmed) return
        dim = declaredDim
        val idList = ArrayList<Long>()
        val vecs = ArrayList<FloatArray>()
        conn.prepare("SELECT chunk_id, vec FROM embeddings ORDER BY chunk_id").use { st ->
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
    /**
     * The user's own imported documents, searched as part of the same corpus.
     *
     * Null when nothing has been imported or the store could not be opened, in
     * which case every line below behaves exactly as it did before ingestion
     * existed. That is the design: a user document is not a second search
     * result set shown beside the real one, it is more of the same corpus,
     * competing on the same bm25 and the same cosine.
     */
    private val userArms: UserArms? = null,
) {
    /** The same three arms, pointed at the user's corpus. */
    class UserArms(
        val fts: FtsSearch,
        val like: LikeSearch,
        val vectors: VectorSearch,
        val conn: androidx.sqlite.SQLiteConnection,
    )

    data class Result(
        val chunks: List<RetrievedChunk>,
        val ftsHits: Int,
        val vecHits: Int,
        val bothCount: Int,
        val usedLikeFallback: Boolean,
        /** How many of the fused chunks came from the user's own documents. */
        val userHits: Int = 0,
    )

    fun search(queryText: String, topK: Int, perArm: Int = 20): Result {
        val usedLike = !fts.available
        val keyword = mergeKeyword(
            if (usedLike) like.search(queryText, perArm) else fts.search(queryText, perArm),
            userArms?.let {
                if (it.fts.available) it.fts.search(queryText, perArm)
                else it.like.search(queryText, perArm)
            },
            perArm,
        )

        val vectorRanked: List<Pair<Long, Double>> =
            embedder?.takeIf { it.isReady }?.let { e ->
                vectors.warm()
                val q = runCatching { e.embed(queryText) }.getOrNull()
                    ?: return@let emptyList<Pair<Long, Double>>()
                val bundled = runCatching { vectors.rank(q, perArm) }.getOrElse { emptyList() }
                val added = userArms?.let { arms ->
                    arms.vectors.warm()
                    runCatching { arms.vectors.rank(q, perArm) }.getOrElse { emptyList() }
                } ?: emptyList()
                // Cosine against L2-normalised vectors is directly comparable
                // across the two files -- same model, same normalisation -- so
                // this merge needs no rescaling. The keyword merge below does
                // not have that luxury; see mergeKeyword.
                if (added.isEmpty()) bundled
                else (bundled + added).sortedByDescending { it.second }.take(perArm)
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
            // Ids below UserCorpusDb.ID_BASE live in the bundle, ids above it
            // in the user's file. Disjoint id spaces are what let a single
            // fused ranking address two databases without a namespace tag.
            fetchChunks(db.conn, missing.filter { !UserCorpusDb.isUserChunk(it) })
                .forEach { byId[it.id] = it }
            userArms?.let { arms ->
                fetchChunks(arms.conn, missing.filter { UserCorpusDb.isUserChunk(it) })
                    .forEach { byId[it.id] = it }
            }
        }

        val ordered = fused.entries.sortedByDescending { it.value }.take(topK).mapNotNull { (id, s) ->
            byId[id]?.copy(score = s, ftsRank = ftsRankOf[id], vecRank = vecRankOf[id])
        }
        return Result(
            ordered, keyword.size, vectorRanked.size,
            ordered.count { it.foundByBoth }, usedLike,
            userHits = ordered.count { it.isUserAdded },
        )
    }

    /**
     * Interleaves the two keyword result lists.
     *
     * bm25 is corpus-relative: the same document scores differently against a
     * 493-chunk corpus and against a 12-chunk one, and the smaller corpus
     * systematically flatters its own rows because its term frequencies are
     * rarer. Sorting the two lists together by raw bm25 would therefore let a
     * newly imported three-page note outrank the Attendance Policy on the word
     * "attendance" -- not because it is a better answer, but because it is a
     * smaller haystack.
     *
     * So they are interleaved by rank instead: best-bundled, best-user,
     * second-bundled, second-user. Rank is the only thing bm25 says that
     * survives a change of corpus, and RRF downstream consumes ranks anyway,
     * so nothing is lost by converting early. A user document still wins when
     * it is genuinely the better match, because it only has to beat the
     * bundled chunk one position below it, not a score it was never on the
     * same scale as.
     */
    private fun mergeKeyword(
        bundled: List<RetrievedChunk>,
        added: List<RetrievedChunk>?,
        perArm: Int,
    ): List<RetrievedChunk> {
        if (added.isNullOrEmpty()) return bundled
        if (bundled.isEmpty()) return added.take(perArm)
        val out = ArrayList<RetrievedChunk>(perArm)
        var i = 0
        while (out.size < perArm && (i < bundled.size || i < added.size)) {
            if (i < bundled.size) out += bundled[i]
            if (out.size < perArm && i < added.size) out += added[i]
            i++
        }
        return out
    }

    private fun fetchChunks(
        conn: androidx.sqlite.SQLiteConnection,
        ids: List<Long>,
    ): List<RetrievedChunk> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return runCatching {
            conn.query(
                "SELECT id, doc_id, section, content FROM chunks WHERE id IN ($placeholders)",
                bind = { st -> ids.forEachIndexed { i, id -> st.bindLong(i + 1, id) } },
            ) {
                RetrievedChunk(it.getLong(0), it.getText(1),
                    if (it.isNull(2)) null else it.getText(2), it.getText(3), 0.0)
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Titles for provenance. Lazy, so a router that never packs anything never
     * runs the query.
     */
    private val titles by lazy { com.campusbrain.app.data.DocTitles(db) }

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
            // Relabelled here, once, rather than at each of the three places a
            // RetrievedChunk is built. Everything downstream -- the citation
            // list, the passage headings, and the "closest material" line of
            // an abstention -- reads `section`, and all three were printing
            // the signatory's name at the foot of a circular. See DocTitles.
            out.add(c.copy(section = titles.label(c.docId, c.section)))
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
