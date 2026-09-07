package com.campusbrain.app.answer

import com.campusbrain.app.data.RetrievedChunk

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
        /**
         * True when the abstention is not "the corpus was thin here" but "the
         * corpus does not cover this subject at all" -- see
         * [AnswerCheck.unsupportedSubject].
         *
         * Carried out of this file because the caller has a decision to make
         * with it. An ordinary abstention is handed to the cloud fallback, and
         * that is right: the corpus missed a question a general model may know.
         * This one must not be. There is no grounding to send, the question is
         * about this college's own records, and a general-knowledge model has
         * no way to know what is in them -- so the fallback would answer
         * "students caught cheating" from nothing at all, which is the exact
         * confident-and-wrong failure the abstention was protecting against.
         */
        val offTopic: Boolean = false,
    ) {
        /** Flat rendering, for logs and for callers that want one string. */
        val text: String
            get() = if (abstained) lead else buildString {
                append(lead)
                passages.forEach { append("\n\n").append(it.heading).append('\n').append(it.body) }
            }
    }

    /**
     * [vocabulary] lets the off-topic check ask whether a word exists in the
     * corpus at all; see [AnswerCheck.unsupportedSubject]. Optional, and null
     * means that check is skipped entirely -- the app always supplies one, and
     * a caller that cannot gets exactly the behaviour it had before.
     */
    fun compose(
        query: String,
        chunks: List<RetrievedChunk>,
        prefix: String? = null,
        vocabulary: AnswerCheck.CorpusVocabulary? = null,
    ): Composed {
        if (chunks.isEmpty()) {
            return Composed(ABSTENTION, emptyList(), abstained = true, reason = "nothing retrieved")
        }

        val question = AnswerCheck.parse(query)

        // One slot, three compositions, tried in order. Each reads a rule out
        // of the retrieved text and states it as the answer to the question
        // that was actually asked, rather than quoting whichever sentence sits
        // nearest the question's vocabulary.
        //
        //  - applyToStated: the student stated a number, so answer the
        //    question they asked rather than reading the rule back at them.
        //    "Can I write the exam with 60% attendance" used to return the
        //    65-74% condonation band verbatim, which reads as a yes to
        //    someone who has 60%.
        //  - tierConsequences: the question asks what follows and the rule is
        //    a tier table, so the tiers are the answer.
        //  - schemeConditions: the question asks whether something is allowed
        //    and the eligibility matrix states the condition in a cell.
        //
        // They are mutually exclusive by Need, so the order is documentation
        // rather than precedence; it is written as a chain so that adding a
        // fourth is one line and cannot accidentally shadow a third.
        val applied = AnswerCheck.applyToStated(question, chunks)
            ?: AnswerCheck.tierConsequences(question, chunks)
            ?: AnswerCheck.schemeConditions(question, chunks)

        // Every chunk is searched, not just the top-ranked one. The measured
        // failure was an answering sentence sitting in chunk two while chunk
        // one -- a document's signature block -- happened to rank first, and
        // the app abstained on a question it was holding the answer to.
        val finding = AnswerCheck.bestAnswer(question, chunks, vocabulary)

        if (applied == null && finding == null) {
            // Abstaining is NOT the same as answering nothing. Inventing an
            // answer here would be a fabrication about fee deadlines or
            // student records -- the one failure that would discredit every
            // correct answer beside it. So the claim is withheld, and the
            // nearest material is offered instead, explicitly labelled as not
            // being an answer. The user still gets somewhere to go; the system
            // still does not assert what it cannot support.
            //
            // Two different abstentions, said differently on purpose. "The
            // closest material is X" invites the student to go and read X, and
            // that is the right offer when the corpus was merely thin. When the
            // corpus has no such subject at all, pointing at the three
            // nearest-ranked documents is a false lead -- they are the top of a
            // ranking that had nothing to rank. Naming the words that are
            // missing tells the student what the corpus actually lacks, which
            // is the only useful thing this app knows about the question.
            val missing = AnswerCheck.unsupportedSubject(question, chunks, vocabulary)
            if (missing.isNotEmpty()) {
                return Composed(
                    ABSTENTION + "\n\nNothing in the records mentions " +
                        missing.joinToString(" or ") + ", so there is no material here to " +
                        "answer that from.",
                    emptyList(),
                    abstained = true,
                    reason = "off topic: ${missing.joinToString(", ")} in none of ${chunks.size} chunks",
                    offTopic = true,
                )
            }
            val nearest = chunks.take(3)
                .map { it.section?.substringAfterLast(" > ") ?: it.docId }
                .distinct()
            val lead = buildString {
                append(ABSTENTION)
                if (nearest.isNotEmpty()) {
                    append("\n\nThe closest material in the corpus is ")
                    append(nearest.joinToString(", "))
                    append(" — none of it addresses the question directly.")
                }
                // A consequence question is the one shape where the generic
                // refusal is actively misleading. "None of it addresses the
                // question" reads as "the search went wrong, try again"; what
                // is true here is narrower and more useful, and it is the
                // honest answer to a real gap. Every scholarship notice states
                // a minimum attendance and not one document anywhere says what
                // becomes of a granted scholarship if the student is debarred
                // for attendance. Saying so is a better answer than any
                // sentence in the corpus, all of which would be a ruling on a
                // question nobody asked.
                if (question.need == AnswerCheck.Need.CONSEQUENCE) {
                    append(" None of it states what follows in that case, so I will not guess at one.")
                }
            }
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

        // Which composition spoke, not just that one did. The trace is the
        // only place the three are told apart, and "applied rule to null%" is
        // what the old wording printed once a composition with no stated
        // number could reach this line.
        val composition = when {
            question.statedPercent != null -> "applied rule to ${question.statedPercent}%"
            question.need == AnswerCheck.Need.CONSEQUENCE -> "read the tier table"
            else -> "read the eligibility matrix"
        }
        val reason = when {
            applied != null && finding != null ->
                "$composition (quote available from chunk ${finding.chunkIndex + 1})"
            applied != null -> composition
            else -> "chunk ${finding!!.chunkIndex + 1}/${chunks.size}, " +
                "${finding.topicHits}/${question.terms.size} topic terms, need=${question.need}"
        }
        return Composed(lead.trim(), passages, abstained = false, reason = reason)
    }
}
