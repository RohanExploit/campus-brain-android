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

        // One slot, four compositions, tried in order. Each reads a rule out
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
        //  - backlogAgainstDrive: the cap the student is judged against lives
        //    in one document (a placement drive notice) and the rule that a
        //    cap above it disqualifies them lives in another (the Placement
        //    Policy). Unlike the three above it is not keyed off Need -- see
        //    its own doc comment for why -- so it only reaches this line when
        //    none of the Need-shaped compositions already answered.
        //
        // The first three are mutually exclusive by Need, so their order is
        // documentation rather than precedence. Computed as separate `val`s
        // rather than one `?:` chain so [composition] below can report which
        // one actually spoke -- collapsing them loses that the moment a
        // fourth link is not itself Need-exclusive with the first three.
        val eligible = AnswerCheck.applyToStatedEvidence(question, chunks)
        val tiered = if (eligible == null) AnswerCheck.tierConsequencesEvidence(question, chunks) else null
        val schemed = if (eligible == null && tiered == null)
            AnswerCheck.schemeConditionsEvidence(question, chunks) else null
        val backlogged = if (eligible == null && tiered == null && schemed == null)
            AnswerCheck.backlogAgainstDrive(question, chunks) else null
        val appliedComposition = eligible ?: tiered ?: schemed ?: backlogged
        val applied = appliedComposition?.text

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

        // Lead with the chunks the answer actually rests on, so the passages
        // under the bubble are the ones the claim can be checked against.
        // Before [AnswerCheck.Composition] existed this read only [finding],
        // so a composed answer -- one built by reading a rule out of one
        // retrieved chunk and a number, tier or cap out of a DIFFERENT one --
        // could cite whichever chunk merely ranked highest and leave its
        // second document out of the passage list entirely, or out of it
        // whenever that document did not also rank in the top three. A set,
        // not a list: [appliedComposition] and [finding] can point at the
        // same chunk, and a passage must not repeat.
        val leadIndices = LinkedHashSet<Int>()
        appliedComposition?.chunkIndices?.forEach { leadIndices.add(it) }
        finding?.let { leadIndices.add(it.chunkIndex) }
        val ordered = if (leadIndices.isEmpty()) chunks
        else leadIndices.map { chunks[it] } + chunks.filterIndexed { i, _ -> i !in leadIndices }

        val passages = buildList {
            if (!prefix.isNullOrBlank()) {
                add(Passage("Related connections", prefix.lineSequence().take(12).joinToString("\n")))
            }
            ordered.take(3).forEach { add(Passage(it.section ?: it.docId, it.content.trim())) }
        }

        // Which composition spoke, not just that one did. The trace is the
        // only place the four are told apart, and "applied rule to null%" is
        // what the old wording printed once a composition with no stated
        // number could reach this line.
        val composition = when {
            eligible != null -> "applied rule to ${question.statedPercent}%"
            tiered != null -> "read the tier table"
            schemed != null -> "read the eligibility matrix"
            backlogged != null -> "compared the stated backlog against the drive's cap"
            else -> "" // unreachable: applied == null whenever all four are null
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
