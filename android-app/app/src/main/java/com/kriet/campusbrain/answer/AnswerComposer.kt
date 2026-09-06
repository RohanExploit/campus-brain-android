package com.kriet.campusbrain.answer

import com.kriet.campusbrain.data.RetrievedChunk

/**
 * Turns retrieved chunks into something that reads as an answer.
 *
 * There is no generative model in this path and that is a choice, not a gap.
 * The alternatives measured for a phone are a ~550MB Gemma at 2-5s to first
 * token or a 1.9GB one at 6-15s; on stage, an instant cited extract beats a
 * spinner. What this does instead is pick the sentence that actually answers
 * the question, and keep the passage it came from one tap away.
 *
 * The judgement of what "actually answers" means lives in [AnswerCheck], not
 * here. This file is the policy -- speak, or stay silent, and with what
 * caveat; that file is the evidence. Splitting them is what made the decision
 * testable on the JVM against real chunk text instead of only on a device.
 *
 * The lead is deliberately NOT concatenated in front of the passage. Doing
 * that printed the same sentence twice on screen, because the lead is by
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
        /**
         * Why this verdict was reached, for the UI trace. The abstention rate
         * is the single largest remaining accuracy loss, so every abstention
         * needs to say which check refused and on what evidence -- otherwise
         * the next round of tuning is guesswork.
         */
        val reason: String = "",
    ) {
        /** Flat rendering, for logs and for callers that want one string. */
        val text: String
            get() = if (abstained) lead else buildString {
                append(lead)
                passages.forEach { append("\n\n").append(it.heading).append('\n').append(it.body) }
            }
    }

    fun compose(query: String, chunks: List<RetrievedChunk>, prefix: String? = null): Composed {
        if (chunks.isEmpty()) {
            return Composed(ABSTENTION, emptyList(), abstained = true, reason = "nothing retrieved")
        }

        val question = AnswerCheck.parse(query)

        // When the student states a number, answer the question they asked
        // rather than reading the rule back at them. "Can I write the exam
        // with 60% attendance" used to return the 65-74% condonation band
        // verbatim, which reads as a yes to someone who has 60%.
        val applied = AnswerCheck.applyToStated(question, chunks)

        // Every chunk is searched, not just the top-ranked one. The measured
        // failure was an answering sentence sitting in chunk two while chunk
        // one -- a document's signature block -- happened to rank first, and
        // the app abstained on a question it was holding the answer to.
        val finding = AnswerCheck.bestAnswer(question, chunks)

        if (applied == null && finding == null) {
            // Abstaining is NOT the same as answering nothing. Inventing an
            // answer here would be a fabrication about fee deadlines or
            // student records -- the one failure that would discredit every
            // correct answer beside it. So the claim is withheld, and the
            // nearest material is offered instead, explicitly labelled as not
            // being an answer. The user still gets somewhere to go; the system
            // still does not assert what it cannot support.
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
                reason = "no sentence in ${chunks.size} chunks answers a ${question.need} question",
            )
        }

        val lead = applied ?: finding!!.sentence

        // Lead with the chunk the answer came out of, so the first passage
        // under the bubble is the one the claim can be checked against. Before
        // the search widened this was always chunks[0] and the two were the
        // same thing; now they need not be.
        val ordered = finding?.let { f ->
            listOf(chunks[f.chunkIndex]) + chunks.filterIndexed { i, _ -> i != f.chunkIndex }
        } ?: chunks

        val passages = buildList {
            if (!prefix.isNullOrBlank()) {
                add(Passage("Related connections", prefix.lineSequence().take(12).joinToString("\n")))
            }
            ordered.take(3).forEach { add(Passage(it.section ?: it.docId, it.content.trim())) }
        }

        val reason = when {
            applied != null && finding != null ->
                "applied rule to ${question.statedPercent}% (quote available from chunk ${finding.chunkIndex + 1})"
            applied != null -> "applied rule to ${question.statedPercent}%"
            else -> "chunk ${finding!!.chunkIndex + 1}/${chunks.size}, " +
                "${finding.topicHits}/${question.terms.size} topic terms, need=${question.need}"
        }
        return Composed(lead.trim(), passages, abstained = false, reason = reason)
    }
}
