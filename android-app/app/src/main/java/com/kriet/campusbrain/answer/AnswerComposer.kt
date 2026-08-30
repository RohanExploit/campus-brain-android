package com.kriet.campusbrain.answer

import com.kriet.campusbrain.data.RetrievedChunk

/**
 * Turns retrieved chunks into something that reads as an answer.
 *
 * There is no generative model in this path and that is a choice, not a gap.
 * The alternatives measured for a phone are a ~550MB Gemma at 2-5s to first
 * token or a 1.9GB one at 6-15s; on stage, an instant cited extract beats a
 * spinner. What this does instead is pick the sentence with the most query
 * overlap as the answer, and keep the passage it came from one tap away.
 *
 * The lead is deliberately NOT concatenated in front of the passage. Doing that
 * printed the same sentence twice on screen, because the lead is by
 * construction a sentence out of the passage.
 *
 * The abstention string is byte-identical to `kAbstentionSentence` in the
 * Flutter app's prompt_builder.dart, so both clients say the same thing when
 * they know nothing.
 */
object AnswerComposer {

    const val ABSTENTION = "I don't have enough information to answer that."

    data class Passage(val heading: String, val body: String)

    data class Composed(
        /** The short answer shown in the bubble. */
        val lead: String,
        /** Source passages, collapsed behind a toggle. */
        val passages: List<Passage>,
        val abstained: Boolean,
    ) {
        /** Flat rendering, for logs and for callers that want one string. */
        val text: String
            get() = if (abstained) lead else buildString {
                append(lead)
                passages.forEach { append("\n\n").append(it.heading).append('\n').append(it.body) }
            }
    }

    private val STOPWORDS = setOf(
        "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
        "is", "are", "was", "were", "the", "a", "an", "of", "for", "to", "in",
        "on", "at", "and", "or", "do", "does", "did", "can", "i", "me", "my",
        "we", "you", "it", "this", "that", "there", "be", "been", "have", "has",
        "need", "should", "would", "about",
    )

    fun compose(query: String, chunks: List<RetrievedChunk>, prefix: String? = null): Composed {
        if (chunks.isEmpty()) return Composed(ABSTENTION, emptyList(), abstained = true)

        val terms = contentTerms(query)
        val top = chunks.first()

        // Abstain when the best chunk shares almost nothing with the question.
        // A low bar deliberately: a wrong confident answer costs far more than
        // one "I don't know" on a question the corpus could have half-answered.
        //
        // Abstaining is NOT the same as answering nothing. Inventing an answer
        // here would be a fabrication about fee deadlines or student records --
        // the one failure that would discredit every correct answer beside it.
        // So the claim is withheld, and the nearest material is offered instead,
        // explicitly labelled as not being an answer. The user still gets
        // somewhere to go; the system still does not assert what it cannot
        // support.
        val overlap = terms.count { t -> top.content.lowercase().contains(t) }
        if (terms.isNotEmpty() && overlap < minOf(2, terms.size)) {
            val nearest = chunks.take(3)
                .map { it.section?.substringAfterLast(" > ") ?: it.docId }
                .distinct()
            val lead = if (nearest.isEmpty()) ABSTENTION else
                ABSTENTION + "\n\nThe closest material in the corpus is " +
                    nearest.joinToString(", ") + " — none of it addresses the question directly."
            return Composed(
                lead,
                chunks.take(2).map { Passage(it.section ?: it.docId, it.content.trim()) },
                abstained = true,
            )
        }

        val lead = bestSentence(top.content, terms)
            ?: top.content.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(300)
            ?: top.content.take(300)

        val passages = buildList {
            if (!prefix.isNullOrBlank()) {
                add(Passage("Related connections", prefix.lineSequence().take(12).joinToString("\n")))
            }
            add(Passage(top.section ?: top.docId, top.content.trim()))
            chunks.drop(1).take(2).forEach {
                add(Passage(it.section ?: it.docId, it.content.trim()))
            }
        }
        return Composed(lead.trim(), passages, abstained = false)
    }

    private fun contentTerms(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .distinct()

    /** The sentence in [text] sharing the most query terms, if any does. */
    private fun bestSentence(text: String, terms: List<String>): String? {
        if (terms.isEmpty()) return null
        val sentences = text.split(Regex("(?<=[.!?])\\s+|\\n"))
            .map { it.trim() }
            // Table rows are not sentences. A line that is mostly pipes reads as
            // noise when lifted out of its table and put on its own.
            .filter { it.length in 25..400 && it.count { c -> c == '|' } < 3 }
        if (sentences.isEmpty()) return null
        var best: String? = null
        var bestScore = 0
        for (s in sentences) {
            val lower = s.lowercase()
            val score = terms.count { lower.contains(it) }
            if (score > bestScore) { bestScore = score; best = s }
        }
        return if (bestScore >= 2) best else null
    }
}
