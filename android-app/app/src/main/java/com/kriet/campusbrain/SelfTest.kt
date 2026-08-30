package com.kriet.campusbrain

import android.util.Log
import com.kriet.campusbrain.data.BrainDb
import com.kriet.campusbrain.data.TAG
import com.kriet.campusbrain.data.query
import com.kriet.campusbrain.retrieval.QueryEmbedder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.system.measureTimeMillis

data class Check(val name: String, val ok: Boolean, val detail: String)

/**
 * Green/red checklist over the actual bundle on the actual handset.
 *
 * Exists because the failures that matter here are silent ones: FTS5 missing,
 * vectors decoded with the wrong dtype, percentages that stop summing to 100.
 * Each of those still returns plausible-looking results.
 */
object SelfTest {

    fun run(db: BrainDb): List<Check> {
        val checks = mutableListOf<Check>()

        checks += Check("bundle opened", true, "${db.source}\n${db.path}")

        checks += runCatching {
            val m = db.meta
            Check(
                "meta readable", true,
                "tenant=${m["tenant_id"]}  chunks=${m["chunk_count"]}  " +
                    "docs=${m["document_count"] ?: "-"}  students=${m["student_count"]}\n" +
                    "built=${m["built_at_utc"]}"
            )
        }.getOrElse { Check("meta readable", false, it.toString()) }

        // The one that decides whether this app can exist as designed. Platform
        // SQLite on this device has no fts5 module; this proves the bundled one
        // does.
        checks += runCatching {
            var hits = 0
            var topBm25 = 0.0
            db.conn.prepare(
                "SELECT rowid, bm25(chunks_fts) FROM chunks_fts " +
                    "WHERE chunks_fts MATCH ? ORDER BY bm25(chunks_fts) LIMIT 5"
            ).use { st ->
                st.bindText(1, "attendance OR scholarship")
                while (st.step()) {
                    if (hits == 0) topBm25 = st.getDouble(1)
                    hits++
                }
            }
            Check("FTS5 MATCH + bm25", hits > 0, "$hits hits, top bm25 ${"%.2f".format(topBm25)}")
        }.getOrElse { Check("FTS5 MATCH + bm25", false, it.message ?: it.toString()) }

        // Catches a bundle rebuilt with a different dtype or dimension: every
        // stored vector must be dim*4 bytes and unit length.
        checks += runCatching {
            val dim = db.embeddingDim
            var n = 0
            var worst = 0.0
            var badLen = 0
            db.conn.prepare("SELECT vec FROM embeddings").use { st ->
                while (st.step()) {
                    val blob = st.getBlob(0)
                    if (blob.size != dim * 4) { badLen++; continue }
                    val fb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    var sum = 0.0
                    for (i in 0 until dim) { val v = fb.get(i); sum += v.toDouble() * v }
                    worst = maxOf(worst, abs(Math.sqrt(sum) - 1.0))
                    n++
                }
            }
            Check(
                "embeddings decode (${dim}d f32 LE, unit norm)",
                badLen == 0 && n > 0 && worst < 1e-4,
                "$n vectors, $badLen wrong length, max |norm-1| = ${"%.2e".format(worst)}"
            )
        }.getOrElse { Check("embeddings decode", false, it.toString()) }

        checks += runCatching {
            var ms: Long
            var scanned = 0
            val dim = db.embeddingDim
            val probe = FloatArray(dim) { 0.05f }
            var best = -2.0f
            ms = measureTimeMillis {
                db.conn.prepare("SELECT chunk_id, vec FROM embeddings").use { st ->
                    while (st.step()) {
                        val fb = ByteBuffer.wrap(st.getBlob(1))
                            .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                        var dot = 0f
                        for (i in 0 until dim) dot += fb.get(i) * probe[i]
                        if (dot > best) best = dot
                        scanned++
                    }
                }
            }
            Check("brute-force vector scan", ms < 500, "$scanned vectors in ${ms}ms")
        }.getOrElse { Check("brute-force vector scan", false, it.toString()) }

        // The shape that broke before: filtering before aggregating made fail%
        // always 100. Two FILTER clauses over one shared denominator must sum
        // to exactly 100.
        checks += runCatching {
            val row = db.conn.query(
                "SELECT COUNT(*) FILTER (WHERE result='PASS'), " +
                    "COUNT(*) FILTER (WHERE result='FAIL'), COUNT(*) FROM students"
            ) { Triple(it.getLong(0), it.getLong(1), it.getLong(2)) }.first()
            val (pass, fail, total) = row
            val sums = total > 0 && pass + fail == total
            Check(
                "pass% + fail% == 100", sums,
                "pass=$pass fail=$fail total=$total  " +
                    "(${"%.2f".format(100.0 * pass / total)}% / ${"%.2f".format(100.0 * fail / total)}%)"
            )
        }.getOrElse { Check("pass% + fail% == 100", false, it.toString()) }

        checks += runCatching {
            val grades = db.conn.query(
                "SELECT DISTINCT grade FROM student_subjects WHERE grade IS NOT NULL ORDER BY grade"
            ) { it.getText(0) }
            val unknown = grades.filter { it !in Grades.GRADE_POINTS && it !in Grades.AUDIT_GRADES }
            Check(
                "all grades known to Grades.kt", unknown.isEmpty(),
                "${grades.size} distinct: ${grades.joinToString(",")}" +
                    if (unknown.isEmpty()) "" else "  UNKNOWN: $unknown"
            )
        }.getOrElse { Check("all grades known to Grades.kt", false, it.toString()) }

        checks += runCatching {
            val n = if (db.hasDocumentsTable) {
                db.conn.query("SELECT COUNT(*) FROM documents") { it.getLong(0) }.first()
            } else -1L
            val distinct = db.conn.query(
                "SELECT COUNT(DISTINCT doc_id) FROM chunks") { it.getLong(0) }.first()
            Check(
                "documents table matches chunks",
                !db.hasDocumentsTable || n == distinct,
                if (db.hasDocumentsTable) "$n documents, $distinct distinct doc_id in chunks"
                else "absent (older bundle) -- app synthesises from chunks; $distinct doc_id"
            )
        }.getOrElse { Check("documents table matches chunks", false, it.toString()) }

        checks += graphAndTail(db)

        checks.forEach { Log.i(TAG, "[${if (it.ok) "PASS" else "FAIL"}] ${it.name} :: ${it.detail}") }
        return checks
    }

    /**
     * Embedder fidelity: the one check nothing else can substitute for.
     *
     * Takes chunks whose vectors are already in the bundle, re-embeds their text
     * on this device, and compares. If the WordPiece tokenizer diverges from
     * HuggingFace's, or the pooling takes [CLS] instead of the masked mean, or
     * the final L2 normalise is missing, the app still produces 384d vectors and
     * still ranks results -- against a corpus embedded differently. There is no
     * exception, no empty result, nothing else in the system that notices.
     * Only this comparison does.
     */
    fun embedderChecks(db: BrainDb, embedder: QueryEmbedder?): List<Check> {
        val checks = mutableListOf<Check>()
        if (embedder == null || !embedder.isReady) {
            checks += Check("embedder available", false,
                "absent -- retrieval is FTS5-only and LOCAL/GLOBAL are unreachable")
            checks.forEach { Log.i(TAG, "[${if (it.ok) "PASS" else "FAIL"}] ${it.name} :: ${it.detail}") }
            return checks
        }

        checks += runCatching {
            val t0 = System.nanoTime()
            val v = embedder.embed("What is the minimum attendance percentage?")
            val ms = (System.nanoTime() - t0) / 1_000_000
            var sum = 0.0
            for (x in v) sum += x.toDouble() * x
            Check("query embedding shape and norm",
                v.size == db.embeddingDim && abs(Math.sqrt(sum) - 1.0) < 1e-4,
                "${v.size}d, |v| = ${"%.6f".format(Math.sqrt(sum))}, ${ms}ms")
        }.getOrElse { Check("query embedding shape and norm", false, it.toString()) }

        checks += runCatching {
            val samples = db.conn.query(
                "SELECT c.id, c.content, e.vec FROM chunks c JOIN embeddings e ON e.chunk_id = c.id " +
                    "WHERE length(c.content) BETWEEN 80 AND 900 ORDER BY c.id LIMIT 5"
            ) { Triple(it.getLong(0), it.getText(1), it.getBlob(2)) }

            var worst = 1.0
            var n = 0
            val detail = StringBuilder()
            for ((id, content, blob) in samples) {
                val stored = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val mine = embedder.embed(content)
                var dot = 0.0
                for (i in mine.indices) dot += (mine[i] * stored.get(i)).toDouble()
                worst = minOf(worst, dot)
                n++
                detail.append("#$id=${"%.4f".format(dot)} ")
            }
            // 0.99 is deliberately strict. Anything materially below it means the
            // on-device text pipeline is not the one that built the corpus.
            Check("embedder reproduces stored vectors (cos >= 0.99)",
                n > 0 && worst >= 0.99,
                "$n chunks, worst cosine ${"%.5f".format(worst)}  $detail")
        }.getOrElse { Check("embedder reproduces stored vectors (cos >= 0.99)", false, it.toString()) }

        checks.forEach { Log.i(TAG, "[${if (it.ok) "PASS" else "FAIL"}] ${it.name} :: ${it.detail}") }
        return checks
    }

    private fun graphAndTail(db: BrainDb): List<Check> {
        val checks = mutableListOf<Check>()
        checks += runCatching {
            val edges = db.conn.query("SELECT COUNT(*) FROM graph_edges") { it.getLong(0) }.first()
            // Not a failure. The graph stages need Ollama and are not part of
            // the offline bundle build; the LOCAL route degrades to vector
            // context, exactly as the backend does on an entity-link miss.
            Check("graph edges present", true,
                if (edges > 0L) "$edges edges" else "0 -- LOCAL will use vector fallback")
        }.getOrElse { Check("graph edges present", false, it.toString()) }
        return checks
    }
}
