package com.kriet.campusbrain.embed

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.kriet.campusbrain.data.TAG
import com.kriet.campusbrain.retrieval.QueryEmbedder
import java.io.File
import java.nio.LongBuffer

/**
 * On-device all-MiniLM-L6-v2, supplying query vectors for the hybrid search's
 * vector arm and for the router's prototype classifier.
 *
 * Runs the exact HuggingFace graph through ONNX Runtime rather than a converted
 * approximation, because the corpus in brain.db was embedded with these weights
 * and a vector from any other model is not comparable to it. scripts/
 * export_minilm_onnx.py verifies the export reproduces sentence-transformers to
 * 1e-4 before it will write the file (measured: 6e-08).
 *
 * Every failure path here degrades to [isReady] = false rather than throwing.
 * Without an embedder the app runs FTS5-only and the router resolves everything
 * unmatched to FACT -- degraded, but exactly the backend's own behaviour when
 * its classifier call fails.
 */
class MiniLmEmbedder private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: WordPieceTokenizer,
    private val inputNames: Set<String>,
) : QueryEmbedder {

    override val isReady: Boolean = true

    /** 384d, mean-pooled over real tokens, L2-normalised. */
    override fun embed(text: String): FloatArray {
        val enc = tokenizer.encode(text)
        val shape = longArrayOf(1, enc.size.toLong())

        val ids = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.ids), shape)
        val mask = OnnxTensor.createTensor(env, LongBuffer.wrap(enc.attentionMask), shape)
        // BERT wants token_type_ids; a single sentence is all zeros.
        val types = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(enc.size)), shape)

        try {
            val feed = HashMap<String, OnnxTensor>()
            if ("input_ids" in inputNames) feed["input_ids"] = ids
            if ("attention_mask" in inputNames) feed["attention_mask"] = mask
            if ("token_type_ids" in inputNames) feed["token_type_ids"] = types

            session.run(feed).use { result ->
                @Suppress("UNCHECKED_CAST")
                val hidden = result[0].value as Array<Array<FloatArray>>   // [1][seq][384]
                return normalize(meanPool(hidden[0], enc.attentionMask))
            }
        } finally {
            ids.close(); mask.close(); types.close()
        }
    }

    /**
     * Mean over positions where the attention mask is 1.
     *
     * This is what sentence-transformers does for this model. Taking the [CLS]
     * token instead is the classic substitution: it runs, it returns 384 floats,
     * and every similarity it produces is quietly wrong.
     */
    private fun meanPool(hidden: Array<FloatArray>, mask: LongArray): FloatArray {
        val dim = hidden.firstOrNull()?.size ?: 0
        val out = FloatArray(dim)
        var counted = 0
        for (i in hidden.indices) {
            if (i >= mask.size || mask[i] == 0L) continue
            val row = hidden[i]
            for (j in 0 until dim) out[j] += row[j]
            counted++
        }
        if (counted > 0) for (j in 0 until dim) out[j] /= counted
        return out
    }

    private fun normalize(v: FloatArray): FloatArray {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = Math.sqrt(sum).toFloat()
        if (norm > 1e-12f) for (i in v.indices) v[i] /= norm
        return v
    }

    fun close() {
        runCatching { session.close() }
    }

    companion object {
        private const val ASSET_DIR = "minilm"
        private const val MODEL = "model.onnx"
        private const val VOCAB = "vocab.txt"

        /**
         * Returns null when the assets are absent or the session will not
         * initialise. The caller treats that as "no vector arm" and carries on.
         *
         * The model is copied out of assets once: ONNX Runtime memory-maps the
         * file, which it cannot do inside an APK. `noCompress += "onnx"` in
         * build.gradle.kts keeps the copy a straight byte copy.
         */
        fun create(context: Context): MiniLmEmbedder? = try {
            val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }

            // An adb-pushed model in the external dir wins, mirroring how the
            // corpus resolves, so a model can be swapped without a rebuild.
            val external = context.getExternalFilesDir(null)?.let { File(it, "$ASSET_DIR/$MODEL") }
            val modelFile = if (external != null && external.exists() && external.length() > 0) {
                external
            } else {
                File(dir, MODEL).also { if (!it.exists() || it.length() == 0L) copyAsset(context, MODEL, it) }
            }
            val vocabFile = File(dir, VOCAB).also {
                if (!it.exists() || it.length() == 0L) copyAsset(context, VOCAB, it)
            }

            val tokenizer = vocabFile.inputStream().use { WordPieceTokenizer.fromVocab(it) }
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(modelFile.absolutePath, opts)
            val names = session.inputNames.toSet()
            Log.i(TAG, "MiniLM ready (${modelFile.length() / (1024 * 1024)}MB, inputs=$names)")
            MiniLmEmbedder(env, session, tokenizer, names)
        } catch (e: Throwable) {
            // Missing asset, unsupported ABI, out of memory -- all mean the same
            // thing to the caller.
            Log.w(TAG, "MiniLM unavailable: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        private fun copyAsset(context: Context, name: String, dest: File) {
            context.assets.open("$ASSET_DIR/$name").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
