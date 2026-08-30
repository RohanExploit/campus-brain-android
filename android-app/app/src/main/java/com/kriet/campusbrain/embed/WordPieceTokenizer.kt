package com.kriet.campusbrain.embed

import java.io.InputStream
import java.text.Normalizer

/**
 * BERT-uncased WordPiece, ported to match HuggingFace's BertTokenizer for
 * all-MiniLM-L6-v2 (`do_lower_case=true`, `strip_accents=null`).
 *
 * This is the highest-risk file in the vector arm, and the risk is that it
 * fails QUIETLY. A tokenizer that is subtly wrong still returns token ids, the
 * model still returns a 384d vector, and the rankings it produces still look
 * plausible -- they are simply wrong, against a corpus embedded with the
 * correct tokenizer. Nothing downstream can detect that. The only guard is
 * SelfTest's fidelity check: embed known chunks on-device and assert cosine
 * >= 0.99 against their stored vectors.
 *
 * Notes on faithfulness to the reference implementation:
 *  - `strip_accents` is null in the config, which BertTokenizer resolves to
 *    "follow do_lower_case", i.e. accents ARE stripped here.
 *  - Chinese/CJK codepoints get surrounded by spaces before splitting; this
 *    corpus has none, but omitting it would be a silent divergence.
 *  - Control characters are dropped, and all whitespace kinds collapse to a
 *    split point, matching `_clean_text`.
 */
class WordPieceTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val unkId: Int,
    private val clsId: Int,
    private val sepId: Int,
) {
    data class Encoded(val ids: LongArray, val attentionMask: LongArray) {
        val size: Int get() = ids.size
        // Identity semantics are meaningless for this holder and equals/hashCode
        // on a data class with arrays compares references; declared explicitly so
        // no caller accidentally relies on either.
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /** Wraps in [CLS]/[SEP] and truncates the middle to fit [maxLen]. */
    fun encode(text: String, maxLen: Int = MAX_LEN): Encoded {
        val pieces = tokenize(text)
        // -2 leaves room for the two special tokens.
        val kept = if (pieces.size > maxLen - 2) pieces.subList(0, maxLen - 2) else pieces
        val ids = LongArray(kept.size + 2)
        ids[0] = clsId.toLong()
        kept.forEachIndexed { i, p -> ids[i + 1] = (vocab[p] ?: unkId).toLong() }
        ids[ids.size - 1] = sepId.toLong()
        // No padding: the exported graph has a dynamic sequence axis and the app
        // embeds one query at a time, so every position is real and the mask is
        // all ones. Kept explicit because mean pooling divides by its sum.
        return Encoded(ids, LongArray(ids.size) { 1L })
    }

    /** Text -> word pieces, without special tokens. Exposed for testing. */
    fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        for (token in basicTokenize(text)) {
            out.addAll(wordPiece(token))
        }
        return out
    }

    /** Lowercase, strip accents, split on whitespace and punctuation. */
    private fun basicTokenize(text: String): List<String> {
        val cleaned = buildString {
            for (ch in text) {
                val cp = ch.code
                if (cp == 0 || cp == 0xFFFD || isControl(ch)) continue
                if (ch.isWhitespace()) { append(' '); continue }
                if (isCjk(cp)) { append(' ').append(ch).append(' '); continue }
                append(ch)
            }
        }

        val out = ArrayList<String>()
        for (chunk in cleaned.split(' ')) {
            if (chunk.isEmpty()) continue
            val lowered = chunk.lowercase()
            val stripped = stripAccents(lowered)
            // Punctuation becomes its own token rather than being deleted --
            // BertTokenizer splits on it, so "R.500/-" is several tokens.
            var current = StringBuilder()
            for (ch in stripped) {
                if (isPunctuation(ch)) {
                    if (current.isNotEmpty()) { out.add(current.toString()); current = StringBuilder() }
                    out.add(ch.toString())
                } else {
                    current.append(ch)
                }
            }
            if (current.isNotEmpty()) out.add(current.toString())
        }
        return out
    }

    /** Greedy longest-match-first, with "##" on every continuation piece. */
    private fun wordPiece(token: String): List<String> {
        if (token.length > MAX_CHARS_PER_WORD) return listOf(UNK)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var found: String? = null
            while (start < end) {
                val sub = if (start == 0) token.substring(start, end)
                          else "##" + token.substring(start, end)
                if (vocab.containsKey(sub)) { found = sub; break }
                end--
            }
            if (found == null) {
                // One unmatched piece makes the WHOLE word [UNK], not just the
                // remainder. Getting this wrong is a classic silent divergence.
                return listOf(UNK)
            }
            pieces.add(found)
            start = end
        }
        return pieces
    }

    private fun stripAccents(s: String): String {
        val decomposed = Normalizer.normalize(s, Normalizer.Form.NFD)
        return buildString {
            for (ch in decomposed) {
                if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) append(ch)
            }
        }
    }

    private fun isControl(ch: Char): Boolean {
        if (ch == '\t' || ch == '\n' || ch == '\r') return false
        return when (Character.getType(ch).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE,
            Character.SURROGATE, Character.UNASSIGNED -> true
            else -> false
        }
    }

    private fun isPunctuation(ch: Char): Boolean {
        val cp = ch.code
        // The ASCII ranges BertTokenizer treats as punctuation regardless of
        // their Unicode category (so '$', '+', '`' and friends split too).
        if (cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126) return true
        return when (Character.getType(ch).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    private fun isCjk(cp: Int): Boolean =
        (cp in 0x4E00..0x9FFF) || (cp in 0x3400..0x4DBF) || (cp in 0x20000..0x2A6DF) ||
            (cp in 0x2A700..0x2B73F) || (cp in 0x2B740..0x2B81F) || (cp in 0x2B820..0x2CEAF) ||
            (cp in 0xF900..0xFAFF) || (cp in 0x2F800..0x2FA1F)

    companion object {
        const val MAX_LEN = 256
        private const val MAX_CHARS_PER_WORD = 100
        private const val UNK = "[UNK]"

        /** vocab.txt is one token per line; the line number is the token id. */
        fun fromVocab(stream: InputStream): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(32_768)
            stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEachIndexed { i, line -> vocab[line.trimEnd('\n', '\r')] = i }
            }
            require(vocab.isNotEmpty()) { "empty vocab.txt" }
            return WordPieceTokenizer(
                vocab,
                unkId = vocab[UNK] ?: error("vocab has no [UNK]"),
                clsId = vocab["[CLS]"] ?: error("vocab has no [CLS]"),
                sepId = vocab["[SEP]"] ?: error("vocab has no [SEP]"),
            )
        }
    }
}
