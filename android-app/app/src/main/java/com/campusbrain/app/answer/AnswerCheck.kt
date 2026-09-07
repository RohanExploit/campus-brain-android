package com.campusbrain.app.answer

import com.campusbrain.app.data.RetrievedChunk

/**
 * The step that was missing: before the app says anything, ask whether the
 * text it retrieved actually answers the question that was asked.
 *
 * Three measured failures share that one root cause, and they pull in
 * opposite directions -- which is why no single "be stricter" or "be looser"
 * knob could have fixed them:
 *
 *  - "how many students got an A+ grade" returned the column documentation of
 *    an unrelated CSV: "31 G1 - first period grade (numeric: from 0 to 20)".
 *    Nothing had asked whether a sentence about a spreadsheet column states a
 *    count of students. Under-abstention.
 *  - "how much attendance do I need" retrieved the Attendance Policy and the
 *    Condonation Procedure -- the right documents -- and then abstained.
 *    Over-abstention, from two separate causes: "much" survived the stopword
 *    filter and so became one of two content words the policy was required to
 *    contain, and only `chunks.first()` was ever read, while the answering
 *    sentence sat in the second or third chunk. The project's own metrics put
 *    18 of 23 misses down to abstention, and in 13 of those the answer was
 *    already in the retrieved context.
 *  - "can I write the exam with 60% attendance" answered with the 65-74%
 *    condonation band, which reads as a yes to a student who has 60%.
 *    Retrieval was right; nobody ever compared 60 against the rule.
 *
 * So this file does three things and nothing else. It works out what shape of
 * answer the question demands ([parse]); it searches EVERY retrieved chunk for
 * a sentence of that shape rather than only the top-ranked one
 * ([bestAnswer]); and when the student states a number it evaluates that
 * number against the rule instead of reciting the rule back ([applyToStated]).
 *
 * All of it is deterministic and offline. A model-based verifier was
 * considered and rejected, for the reason [CloudAnswer]'s `GROUNDED:` marker
 * already demonstrates: that marker prompt goes to all three tiers including
 * a ~2B on-device model that will not reliably emit it, and a missing marker
 * is read as not-grounded, so correct answers get mislabelled. A check that
 * only works when the network does is not a check the abstention decision can
 * be built on.
 */
object AnswerCheck {

    /** What shape of answer the question demands. */
    enum class Need {
        /** "how many X" -- the answer must state a count of X. */
        COUNT,

        /** "how much / what percentage / minimum X" -- the answer needs a number. */
        QUANTITY,

        /** "can I ... with 60%" -- the rule must be applied to the stated number. */
        ELIGIBILITY,

        /**
         * "what happens if I miss it" -- the answer must state what follows,
         * not that a procedure for it exists.
         *
         * Split out of [OTHER] because a consequence question is the one shape
         * this file could not tell apart from its own paperwork. Measured: the
         * second half of "what is the minimum attendance and what happens if I
         * miss it" answered "A separate procedure notice for each scheme above
         * states its own notice number, the exact steps to apply, and what
         * happens after submission" -- a sentence about procedure notices
         * existing, which is the nearest thing in the corpus to the WORDS of
         * the question and the furthest thing from its answer.
         */
        CONSEQUENCE,

        /**
         * "can a student with a backlog apply" -- the answer must state a rule
         * about who may, not narrate what an office does afterwards.
         *
         * The gap [ELIGIBILITY] left behind. That one is only claimed when the
         * student supplies a number to judge; a permission question with no
         * number fell through to [OTHER], no shape was enforced, and the
         * highest topic overlap won. Measured: the winner was "After
         * submission: The Scholarship SPOC verifies the CGPA and no-backlog
         * declaration against examination records ..." -- the procedure that
         * follows a successful application, offered to a student asking
         * whether they may make one.
         */
        PERMISSION,

        /**
         * Everything else. Topic overlap only, no shape enforced. Kept
         * deliberately large: every shape rule is a new way to refuse a
         * question the corpus could have answered, and the failure this file
         * is mostly here to fix is over-refusal.
         */
        OTHER,
    }

    /** A question, reduced to what a correct answer would have to contain. */
    data class Question(
        val raw: String,
        val need: Need,
        /** Content words, filler removed. See [STOPWORDS]. */
        val terms: List<String>,
        /**
         * The percentage the student stated, e.g. 60.0 in "with 60%
         * attendance". Read off the RAW query on purpose: [contentTerms]
         * splits on non-alphanumerics and drops anything shorter than three
         * characters, so "60%", "A+" and "10" are all destroyed before the
         * term list exists.
         */
        val statedPercent: Double?,
    )

    /** A sentence that survived the check, and where it came from. */
    data class Finding(
        val sentence: String,
        /** Index into the chunk list handed to [bestAnswer]. */
        val chunkIndex: Int,
        val topicHits: Int,
    )

    // --- question parsing -------------------------------------------------

    private val STOPWORDS = setOf(
        "what", "when", "where", "which", "who", "whom", "whose", "why", "how",
        "is", "are", "was", "were", "the", "a", "an", "of", "for", "to", "in",
        "on", "at", "and", "or", "do", "does", "did", "can", "i", "me", "my",
        "we", "you", "it", "this", "that", "there", "be", "been", "have", "has",
        "need", "should", "would", "about",
        // Added after "how much attendance do I need" abstained while holding
        // the answer. "much" is filler by any stoplist, but it survived the
        // length>2 filter, became one of the two content words the retrieved
        // policy had to contain, and the Attendance Policy could only ever
        // supply one of them. The rest below fail the same way, or are one
        // rephrasing away from doing so.
        "much", "many", "get", "gets", "got", "give", "gives", "tell", "know",
        "want", "any", "some", "with", "from", "into", "will", "shall", "could",
        "may", "might", "must", "please", "let", "also", "than", "then", "them",
        "his", "her", "our", "your", "their", "these", "those",
        // "What scholarships are available?" abstained -- on hardware, with
        // the scholarship documents retrieved and named in the refusal. Two
        // content words survived, "scholarships" and "available", and the
        // corpus contains the first and never the second, so the two-term
        // floor could not be met by any sentence in any scholarship notice.
        // "Available", "offered" and "exist" are predicates of the question,
        // not topics of the answer: a notice announces a scheme, it does not
        // announce that the scheme is available.
        "available", "offered", "offers", "offer", "exist", "exists",
        "options", "kinds", "types", "sort", "sorts",
        // The imperative that asks for the answer's FORM. "List students who
        // were caught cheating" narrated a deadline tracker, and one of the two
        // terms that cleared the floor was "list" -- matched against "The
        // consolidated list below tracks every deadline...". A verb that says
        // how to present the answer says nothing about what the answer is
        // about, which is exactly the argument the block above makes for
        // "available".
        "list", "lists", "show", "shows", "display", "find", "name", "names",
        // The predicate of a consequence question, for the same reason as
        // "available" above: it says what KIND of answer is wanted, not what
        // the answer is about. Measured, and this one costs answers twice
        // over. "Happens" occurs in exactly 4 of 493 chunks, all of them
        // scholarship procedure notices, so it is simultaneously the rarest
        // and the least informative word in "what happens if I miss it" --
        // rare enough to clear the two-term floor on the one document that
        // cannot answer, and empty enough to mean nothing when it does.
        // "Consequence" is worse still: it is a COLUMN HEADING in both
        // attendance tables, so a question about backlogs that used the word
        // was answered with the attendance tiers.
        "happen", "happens", "consequence", "consequences",
    )

    /**
     * A term as it is searched for, which is not always as it was typed.
     *
     * Students ask in the plural and notices are written in the singular:
     * "what scholarships are available" against a corpus that says
     * "Scholarship" in every heading it has. A plain `contains` scored zero
     * topic hits on documents that are entirely about the topic. Dropping one
     * trailing "s" from a word of five letters or more turns the term into a
     * prefix of both forms, which is all containment needs.
     *
     * Deliberately not applied inside [contentTerms]: the term list is also
     * what the trace reports and what pinned tests read, and a stemmer there
     * would rewrite the question rather than widen the search.
     */
    private fun searchKey(term: String): String =
        if (term.length >= 5 && term.endsWith("s") && !term.endsWith("ss")) term.dropLast(1) else term

    private fun mentions(lowerText: String, term: String): Boolean =
        lowerText.contains(searchKey(term))

    /** Content words of [query], filler and duplicates removed. */
    fun contentTerms(query: String): List<String> =
        query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 && it !in STOPWORDS }
            .distinct()

    private val STATED_PERCENT =
        Regex("""(\d{1,3}(?:\.\d+)?)\s*(?:%|percent\b|per\s*cent\b)""", RegexOption.IGNORE_CASE)

    private val COUNT_CUE =
        Regex("""\bhow\s+many\b|\bnumber\s+of\b|\bcount\s+of\b""", RegexOption.IGNORE_CASE)

    private val QUANTITY_CUE = Regex(
        """\bhow\s+much\b|\bhow\s+long\b|\bhow\s+often\b|\bwhat\s+percentage\b""" +
            // "minimum attendance", "required marks", "cut-off percentage": a
            // threshold word next to a measurable thing. Not a bare superlative
            // -- "highest SGPA" is TABULAR and never reaches this file.
            """|\b(?:minimum|maximum|required|cut[\s-]?off)\b[^.?!]{0,30}""" +
            """\b(?:attendance|percentage|marks?|scores?|cgpa|sgpa|fees?|credits?|days?|hours?)\b""",
        RegexOption.IGNORE_CASE
    )

    private val ELIGIBILITY_CUE = Regex(
        """^\s*(?:can|could|am|is|are|will|do|does|should|may)\b""" +
            """|\beligible\b|\ballowed\b|\bpermitted\b|\bqualif|\bdebarred\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * "What happens if ...", and nothing looser.
     *
     * A closed cue on purpose. The temptation is to read any "if" clause as a
     * consequence question, and that would swallow "can I apply if I have a
     * backlog", which wants a rule and not an outcome. Every alternative below
     * names the outcome explicitly.
     */
    private val CONSEQUENCE_CUE = Regex(
        """\bwhat\s+happens\b|\bwhat\s+will\s+happen\b|\bwhat\s+would\s+happen\b""" +
            """|\bwhat\s+(?:is|are)\s+the\s+(?:consequence|penalty|penalties|result)\b""" +
            """|\bconsequences?\s+(?:of|for|if)\b""" +
            """|\bwhat\s+(?:do|does)\s+i\s+do\s+if\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A modal of permission. Necessary but not sufficient -- see
     * [PERMISSION_ACT].
     */
    private val PERMISSION_MODAL = Regex(
        """^\s*(?:can|could|may|am\s+i|is\s+it\s+possible)\b|\bam\s+i\s+allowed\b""" +
            """|\bis\s+(?:a|an|the)?\s*\w+\s+allowed\b|\beligible\s+to\b|\bpermitted\s+to\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A verb of DOING, which is the half that makes a modal a request for
     * permission rather than a request for a fact.
     *
     * "Is the library open on Sunday" opens with a modal-shaped clause and asks
     * for opening hours; "can a student with a backlog apply" asks whether an
     * action is allowed. Requiring both halves is what keeps the first in
     * [Need.OTHER], where its answer already lives, and it is the same
     * two-condition construction [ELIGIBILITY_CUE] uses with its number.
     */
    private val PERMISSION_ACT = Regex(
        """\b(?:apply|applying|register|registering|enrol|enroll|enrolling|appear|""" +
            """sit|write|take|submit|submitting|claim|claiming|avail|availing|""" +
            """reapply|re-?appear|attend|attending|join|joining|borrow|borrowing|""" +
            """get|receive|request|requesting|renew|renewing|opt)\b""",
        RegexOption.IGNORE_CASE
    )

    fun parse(query: String): Question {
        val stated = STATED_PERCENT.find(query)?.groupValues?.get(1)?.toDoubleOrNull()
        val need = when {
            // Eligibility is only claimed when the student actually supplied a
            // number to judge. "Is the library open on Sunday" opens with a
            // modal too, and there is nothing there to compare.
            stated != null && ELIGIBILITY_CUE.containsMatchIn(query) -> Need.ELIGIBILITY
            COUNT_CUE.containsMatchIn(query) -> Need.COUNT
            QUANTITY_CUE.containsMatchIn(query) -> Need.QUANTITY
            // Last, and after the two cues that carry a battery. A question is
            // only a consequence or permission question if no earlier shape
            // claimed it, so nothing that was answered before can be stolen by
            // the two rules added here; measured over both battery files and
            // the JVM fixtures, 8 of 64 questions change Need and all 8 are
            // questions no shape was enforced on at all.
            CONSEQUENCE_CUE.containsMatchIn(query) -> Need.CONSEQUENCE
            PERMISSION_MODAL.containsMatchIn(query) &&
                PERMISSION_ACT.containsMatchIn(query) -> Need.PERMISSION
            else -> Need.OTHER
        }
        return Question(query, need, contentTerms(query), stated)
    }

    // --- finding a sentence that answers ----------------------------------

    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+|\n""")

    /**
     * Full stops that end an abbreviation, not a sentence.
     *
     * A measured defect, and the expensive kind: it made an answer
     * UNREACHABLE rather than merely mis-ranked, so no amount of tuning
     * downstream could have recovered it. "A late fee of Rs. 50 per day
     * applies after the due date, capped at Rs. 2,000." splits after "Rs."
     * into "A late fee of Rs." -- 17 characters, below the 25-character floor,
     * discarded -- and a remainder that no longer contains the words "late
     * fee". Asked what the late fee is, the app could not see the sentence
     * that says. The same split decapitated "who is eligible for the
     * post-matric scholarship", which used to stop dead at "... family income
     * up to Rs."
     *
     * The list is closed and measured, not guessed: these are the six tokens
     * that actually precede a mid-sentence full stop anywhere in the 248
     * campus chunks -- Dr. 89, No. 62, Rs. 51, Prof. 49, Mrs. 24, Mr. 14 --
     * plus "Ms." as the obvious sibling of the last two. Nothing else in the
     * corpus fires at all, and every entry that does not fire is a rule whose
     * failure modes nobody has looked at.
     */
    private val ABBREVIATIONS =
        setOf("rs", "no", "mr", "mrs", "ms", "dr", "prof")

    /**
     * Read by hand rather than with an anchored regex: Java's `$` also matches
     * before a final line terminator, and this is asked about a buffer whose
     * last character is the whole question.
     */
    private fun endsWithAbbreviation(s: CharSequence): Boolean {
        if (s.isEmpty() || s[s.length - 1] != '.') return false
        val word = s.subSequence(0, s.length - 1).takeLastWhile { it.isLetter() }
        return word.isNotEmpty() && word.toString().lowercase() in ABBREVIATIONS
    }

    /**
     * [text] cut at sentence ends, with the abbreviation guard applied.
     *
     * Written as an explicit merge pass rather than as a lookbehind in
     * [SENTENCE_SPLIT] for two reasons. First, scope: this is the only
     * consumer that needs the guard. The other reader of [SENTENCE_SPLIT] is
     * the sentence loop in [requiredMinimums], where merging would change
     * which scope a threshold is recorded under -- "Rajarshi Shahu Maharaj
     * Merit Scholarship is open to ... with family income up to Rs. 8,00,000
     * per annum, a minimum attendance of 75%" is two sentences today, and the
     * 75% is correctly recorded as the institute's general figure precisely
     * because the scheme name is in the other half. Second, honesty: a split
     * that never breaks a line is easier to reason about than a lookbehind,
     * and a break whose separator contains a newline is always taken, so a
     * table row cannot be glued to the row below it.
     */
    private fun sentencePieces(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var pos = 0
        for (m in SENTENCE_SPLIT.findAll(text)) {
            cur.append(text, pos, m.range.first)
            pos = m.range.last + 1
            if (!m.value.contains('\n') && endsWithAbbreviation(cur)) {
                cur.append(' ')
                continue
            }
            out.add(cur.toString())
            cur.setLength(0)
        }
        cur.append(text, pos, text.length)
        out.add(cur.toString())
        return out
    }

    /**
     * Sentences worth quoting as a lead. Table rows are excluded: a line that
     * is mostly pipes reads as noise once lifted out of its table, and the
     * band parser in [parseBands] reads the raw text anyway, so nothing is
     * lost by keeping them out of here.
     */
    fun sentencesOf(text: String): List<String> =
        sentencePieces(text)
            .map { it.trim() }
            .filter { s -> s.length in 25..400 && s.count { it == '|' } < 3 }

    /**
     * The words that make this question THIS question, rather than any other
     * question about college.
     *
     * "list students who were caught cheating" and "how many students failed"
     * share "students" and nothing else; the first is about cheating and the
     * second about failing, and it is the second word of each pair that decides
     * which passages could possibly answer it. [TopicGate.isDomainVocabulary]
     * owns the judgement of which is which.
     */
    fun subjectTerms(terms: List<String>): List<String> =
        terms.filter { !TopicGate.isDomainVocabulary(searchKey(it)) }

    /**
     * Whether a word occurs anywhere in the corpus, as opposed to anywhere in
     * what retrieval happened to return. Supplied by the caller because this
     * file has no database and is kept that way; null means the question
     * cannot be asked, and every rule below then resolves toward answering.
     * See `CorpusWords`.
     */
    fun interface CorpusVocabulary {
        fun occursInCorpus(term: String): Boolean
    }

    /**
     * The question's own subject words, when the corpus has never heard of them
     * and retrieval found none of them either. Empty means the normal sentence
     * search should decide.
     *
     * This is the missing precondition behind two measured failures. The floor
     * in [bestAnswer] is "two content words", and it counts every word as equal
     * evidence, so a sentence scores two on "list" and "students" -- words that
     * are in a third of the corpus and in most questions -- while contributing
     * nothing about cheating. Measured against the shipped bundle: "list
     * students who were caught cheating" retrieved the deadline tracker and
     * read out "The consolidated list below tracks every deadline a student
     * needs to act on...", labelled as an answer. The corpus has no
     * disciplinary field at all; "cheating" occurs in 0 of 493 chunks and
     * "caught" in 1.
     *
     * Checked over whole CHUNKS, not over the candidate sentence, and that is
     * load-bearing. A word can be everywhere in the retrieved material and
     * still not be in any sentence [bestAnswer] is allowed to quote: the
     * attendance tiers are a table, and [sentencesOf] drops rows of three
     * pipes or more. What is being asked here is whether retrieval found the
     * topic, not whether one sentence restates it.
     *
     * An earlier version of this comment offered "debarred" as the example and
     * said it "lives in a pipe row that [sentencesOf] deliberately drops". The
     * conclusion is right and that reason was wrong, so it is corrected rather
     * than repeated: [SENTENCE_SPLIT] breaks after the full stop that ends the
     * consequence cell, so the row arrives as `| Below 65% | Debarred outright
     * ... at this tier.` -- two pipes, under the three-pipe bar, kept. The
     * word is refused a quote by the two-term floor in [bestAnswer], not by
     * this filter.
     *
     * Two subject words are required before it may refuse. One word absent from
     * the corpus is usually a phrasing accident -- "can I WRITE the exam with
     * 60% attendance" has exactly one, and "write" is genuinely in 0 of 493
     * chunks because the policy says "appear for" -- and refusing on that alone
     * would re-create the over-abstention this file was written to remove.
     *
     * [vocabulary] is the third condition and the one that keeps the rule
     * honest. Missing from ten retrieved chunks is weak evidence: "how long
     * does a bonafide certificate take" retrieves the right notice, which says
     * neither "long" nor "take", and an absence-from-retrieval rule alone would
     * abstain on it. Missing from the whole corpus is strong evidence, and the
     * two words that made this question fail are exactly that -- "cheating" 0
     * of 493, against "long" 13 and "take" 11. A null vocabulary means the
     * question could not be asked, and then this refuses nothing.
     */
    fun unsupportedSubject(
        q: Question,
        chunks: List<RetrievedChunk>,
        vocabulary: CorpusVocabulary? = null,
    ): List<String> {
        if (vocabulary == null) return emptyList()
        val subject = subjectTerms(q.terms)
        if (subject.size < 2) return emptyList()
        val anywhere = chunks.any { c ->
            val lower = c.content.lowercase()
            subject.any { mentions(lower, it) }
        }
        if (anywhere) return emptyList()
        val unknown = subject.filter { !vocabulary.occursInCorpus(searchKey(it)) }
        // Every subject word is ordinary corpus vocabulary that this particular
        // retrieval pass happened to miss. That is a retrieval problem, and
        // refusing to answer is not the fix for it.
        if (unknown.isEmpty()) return emptyList()
        return unknown
    }

    /**
     * The best sentence in [chunks] that both talks about the question and has
     * the shape the question demands, or null if there is none.
     *
     * The search space is every chunk, which is the change that matters. The
     * old code read `chunks.first()` and nothing else, so a signature block
     * ("Assistant Professor Attendance Coordinator / Date: 15 July 2026")
     * ranking first was enough to make the app abstain on a question the
     * second chunk answered outright.
     */
    fun bestAnswer(
        q: Question,
        chunks: List<RetrievedChunk>,
        vocabulary: CorpusVocabulary? = null,
    ): Finding? {
        if (q.terms.isEmpty()) return null
        // Nothing retrieved is about what was asked, and the corpus has never
        // heard of what was asked, so no sentence in it can be the answer
        // however many generic words it happens to share.
        if (unsupportedSubject(q, chunks, vocabulary).isNotEmpty()) return null
        // The old bar, unchanged: one term is enough for a one-term question,
        // two otherwise. Widening the search space is already a large loosening
        // and this is not the place to add a second one.
        val required = minOf(2, q.terms.size)
        var best: Finding? = null
        var bestScore = Int.MIN_VALUE
        chunks.forEachIndexed { ci, chunk ->
            for (sentence in sentencesOf(chunk.content)) {
                val lower = sentence.lowercase()
                val hits = q.terms.count { mentions(lower, it) }
                if (hits < required) continue
                if (!satisfiesShape(q, lower)) continue
                // Topic coverage dominates. Then, for a question asking what
                // the requirement IS, a sentence that states a requirement
                // beats one that merely mentions a number in the same
                // territory -- "a minimum of 75% attendance is required" over
                // "attendance between 65% and 74% may apply for condonation",
                // which are adjacent in every attendance retrieval. Then a
                // number sitting next to a topic word, which is what separates
                // the policy from the same document's "Date: 15 July 2026"
                // signature block. Retrieval rank breaks whatever is left.
                val requirement =
                    if (q.need == Need.QUANTITY && REQUIREMENT_CUE.containsMatchIn(lower)) 200 else 0
                val score = hits * 1000 + requirement + permissionBonus(q, lower) +
                    (if (numberNearTerm(lower, q.terms)) 100 else 0) - ci
                if (score > bestScore) {
                    bestScore = score
                    best = Finding(normalise(sentence), ci, hits)
                }
            }
        }
        return best
    }

    /**
     * Text that states an OUTCOME. The shape a [Need.CONSEQUENCE] question
     * demands, and the reason it is a hard gate rather than a preference.
     *
     * The measured failure is not that the right sentence ranked second. It is
     * that the only sentence in the whole corpus containing the word "happens"
     * -- "A separate procedure notice for each scheme above states its own
     * notice number, the exact steps to apply, and what happens after
     * submission" -- is a sentence about procedure notices existing. Topic
     * overlap ranks it first and can never rank it anywhere else, because it
     * is the nearest thing in the corpus to the WORDS of the question and the
     * furthest thing from its answer. Nothing short of refusing it works.
     *
     * The first six alternatives are the corpus's own vocabulary, counted:
     * "debarred" 5, "not permitted" 10, "no condonation" 2, "placed on the
     * defaulter list" 2, "no action" 2, "may apply for condonation" 4, "late
     * fee" 1, "penalt-" 4, "fine of" 2, "returned on the portal" 2,
     * "withdrawn" 1. The rest are ordinary English ways of stating a
     * consequence, present for imported documents; each occurs 0 times in the
     * bundle, so each is measurably free of any effect on the batteries.
     */
    private val CONSEQUENCE_TEXT = Regex(
        """\bdebarred\b|\bnot\s+permitted\b|\bno\s+condonation\b""" +
            """|\bplaced\s+on\s+the\s+defaulter\s+list\b|\bno\s+action\b""" +
            """|\bmay\s+apply\s+for\s+condonation\b|\blate\s+fee\b|\bpenalt""" +
            """|\bfine\s+of\b|\breturned\s+on\s+the\s+portal\b|\bwithdrawn\b""" +
            """|\b(?:will|shall|may|can)\s+not\s+be\b|\bnot\s+be\s+allowed\b""" +
            """|\bforfeit|\bcancelled\b|\brejected\b|\bineligible\b|\bsuspend""" +
            """|\bbarred\s+from\b|\bdisqualif|\bexpelled\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Text that states a RULE about who may do a thing, as opposed to text
     * that narrates what an office does afterwards.
     *
     * Used as a preference in [permissionBonus] and NOT as a gate, which is a
     * deliberate departure from how [CONSEQUENCE_TEXT] is used. Measured: as a
     * gate this turned "can I apply for a scholarship" from an answer into an
     * abstention -- the scheme notices state their rule in a sentence that does
     * not contain the word "apply", so nothing cleared the two-term floor AND
     * the shape at once, and the only sentence that cleared the floor ("Apply
     * through: National Scholarship Portal") is not rule-shaped. Refusing a
     * question the corpus answers, to protect against a question it answers
     * badly, is the trade this whole file exists to refuse. As a preference it
     * can only change which sentence wins, never whether one does.
     *
     * Note the absence of "declaration". It was in the first draft, and it
     * matches the exact sentence probe 1 has to lose: "The Scholarship SPOC
     * verifies the CGPA and no-backlog DECLARATION against examination
     * records". A word that appears in the paperwork is not a mark of a rule.
     */
    private val RULE_TEXT = Regex(
        """\beligible\b|\beligibility\b|\bis\s+open\s+to\b|\bopen\s+to\b|\bmay\s+apply\b""" +
            """|\bmust\b|\bis\s+required\b|\bare\s+required\b|\brequires\b|\brequired\b""" +
            """|\bminimum\b|\bno\s+minimum\b|\bnot\s+permitted\b|\bpermitted\b|\ballowed\b""" +
            """|\bshall\b|\bcannot\b|\bmay\s+not\b|\bqualif|\bceiling\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * The tie-break for a [Need.PERMISSION] question, mirroring the QUANTITY
     * bonus above it: first prefer a sentence that states a rule, then prefer
     * one that carries every distinctive word of the question rather than the
     * generic half of it. Both are worth less than one extra topic hit, so
     * neither can promote a sentence that is about something else.
     */
    private fun permissionBonus(q: Question, lowerSentence: String): Int {
        if (q.need != Need.PERMISSION) return 0
        var bonus = 0
        if (RULE_TEXT.containsMatchIn(lowerSentence)) bonus += 400
        val distinctive = subjectTerms(q.terms)
        if (distinctive.isNotEmpty() && distinctive.all { mentions(lowerSentence, it) }) bonus += 200
        return bonus
    }

    private fun satisfiesShape(q: Question, lowerSentence: String): Boolean = when (q.need) {
        Need.COUNT -> statesCount(lowerSentence, q.terms)
        // A quantity may be spelled. See the "numbers written as words"
        // section below for the defect this clause exists to fix.
        Need.QUANTITY -> lowerSentence.any(Char::isDigit) || statesWordQuantity(lowerSentence)
        // ELIGIBILITY deliberately stays digits-only. The question there is
        // always "can I ... with N%", and the work is done by [applyToStated],
        // which compares N against a threshold it parsed out of the text. A
        // figure written in words cannot be compared against 60.0, so admitting
        // one here would only widen the pool of sentences that get quoted back
        // at a student who asked for a ruling -- a loosening with no verdict
        // behind it, on the one path where a wrong answer reads as permission.
        Need.ELIGIBILITY -> lowerSentence.any(Char::isDigit)
        // The only shape rule in this file that ADDS abstentions, and the
        // justification is that the alternative is not silence but a
        // confident irrelevance. See [CONSEQUENCE_TEXT].
        Need.CONSEQUENCE -> CONSEQUENCE_TEXT.containsMatchIn(lowerSentence)
        // No shape enforced. The rule PERMISSION carries is a preference in
        // [permissionBonus], not a filter -- see [RULE_TEXT] for the measured
        // reason a filter was wrong here and right one line above.
        Need.PERMISSION -> true
        Need.OTHER -> true
    }

    // --- numbers written as words -----------------------------------------

    /**
     * "Equipment may be borrowed for a maximum of fourteen days."
     *
     * Observed on hardware: "how long can I borrow robotics equipment" against
     * an imported document containing exactly that sentence, and the app
     * abstained -- while naming the right material as the closest it had. Every
     * step before this one was right. `how long` is a [Need.QUANTITY] cue, the
     * sentence clears the two-term floor on "borrowed" and "equipment", and
     * then [satisfiesShape] asked for a digit and there is no digit in it. The
     * bundled corpus writes figures as numerals, so nothing had ever tested the
     * assumption; an imported document written in ordinary prose does not.
     *
     * Scope is the whole difficulty here. A word-number is much easier to hit
     * by accident than a numeral -- "one" is an article in all but name, and
     * English has no spelling of "35" that turns up in a sentence about
     * something else. So this recognises a deliberately small vocabulary, and
     * every rule below is about NOT counting a word that happens to be in it.
     */
    private const val CARDINAL_WORDS =
        """zero|one|two|three|four|five|six|seven|eight|nine|ten|""" +
            """eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|""" +
            """twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand"""

    /**
     * Ordinals are listed so they can be REFUSED, not matched.
     *
     * "The fourteenth day of the loan" is a different claim from "fourteen
     * days", and a student asking how long they may keep something is not
     * answered by a sentence that names a date. Word boundaries handle the
     * bare forms for free -- `\bfourteen\b` does not match inside "fourteenth"
     * -- but they do not handle the hyphenated compound: "twenty-first" would
     * otherwise be read as the cardinal "twenty" with a suffix the regex never
     * looked at. Hence the lookahead in [WORD_CARDINAL].
     *
     * Two of this file's own test fixtures are the standing proof that the
     * refusal works: the CSV column list says "first period grade" and
     * "second period grade", and the deadline tracker says "first announced".
     */
    private const val ORDINAL_WORDS =
        """zeroth|first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth|""" +
            """eleventh|twelfth|thirteenth|fourteenth|fifteenth|sixteenth|seventeenth|""" +
            """eighteenth|nineteenth|twentieth|thirtieth|fortieth|fiftieth|sixtieth|""" +
            """seventieth|eightieth|ninetieth|hundredth|thousandth"""

    /**
     * One spelled number, hyphenated compounds included ("twenty-one",
     * "forty-five"), and never the cardinal half of an ordinal compound.
     */
    private val WORD_CARDINAL = Regex(
        """\b(?:$CARDINAL_WORDS)(?:-(?:$CARDINAL_WORDS))*\b(?!-(?:$ORDINAL_WORDS)\b)"""
    )

    /**
     * "One" is the only cardinal that is also an article, a determiner and a
     * pronoun, and it is common enough that letting it through unguarded would
     * turn most of the corpus into a quantity claim. Three uses are refused:
     *
     *  - partitive: "one of the documents", "one of the four labs";
     *  - determiner-pronoun: "the one", "no one", "each one", "which one";
     *  - bare pronoun subject: "one may apply for condonation", "if one has".
     *
     * What survives is "one" with a noun after it -- "borrowed for one week",
     * "only one student failed" -- which is the use that states a number.
     *
     * Applies to the bare word only. "Twenty-one" is not any of these things.
     */
    private val DETERMINER_BEFORE_ONE =
        setOf("no", "the", "any", "each", "every", "this", "that", "which", "some")

    /**
     * A word that can only follow the PRONOUN "one", never the number. "One of"
     * is partitive; the modals and auxiliaries are what a bare pronoun subject
     * takes ("one may apply", "one has to"). A number takes a noun instead.
     */
    private val VERB_AFTER_ONE = setOf(
        "of", "another", "may", "might", "must", "can", "could", "should", "will",
        "would", "is", "are", "was", "were", "has", "have", "had",
    )

    /**
     * Is the cardinal at [range] of [text] being used as a number at all?
     *
     * Takes the range rather than a MatchResult because the two callers match
     * different regexes over the same token and both need the same judgement.
     *
     * The neighbours are read by hand rather than with a lookbehind/lookahead:
     * only spaces and hyphens may separate the word from its neighbour, so a
     * determiner on the far side of a full stop ("... in the corpus. One week
     * is allowed.") is not mistaken for this sentence's grammar.
     */
    private fun isQuantityUse(text: String, range: IntRange): Boolean {
        if (text.substring(range) != "one") return true

        val before = text.substring(0, range.first)
        val gapBefore = before.takeLastWhile { it == ' ' || it == '-' }
        if (gapBefore.isNotEmpty() &&
            before.dropLast(gapBefore.length).takeLastWhile { it.isLetter() } in DETERMINER_BEFORE_ONE
        ) return false

        val after = text.substring(range.last + 1)
        val gapAfter = after.takeWhile { it == ' ' || it == '-' }
        if (gapAfter.isNotEmpty() &&
            after.drop(gapAfter.length).takeWhile { it.isLetter() } in VERB_AFTER_ONE
        ) return false

        return true
    }

    /** Does [lowerSentence] state a quantity in words? See [WORD_CARDINAL]. */
    private fun statesWordQuantity(lowerSentence: String): Boolean =
        WORD_CARDINAL.findAll(lowerSentence).any { isQuantityUse(lowerSentence, it.range) }

    /**
     * A count question is answered by a sentence that states a count OF THE
     * THING ASKED ABOUT -- "35 students failed" -- not merely by one that
     * contains a digit and the word somewhere.
     *
     * That distinction is the whole of defect 1. student.md's column list puts
     * a number beside the word "grade" on every line ("31 G1 - first period
     * grade (numeric: from 0 to 20)"), and reading it out was the worst answer
     * the app produced. A number the question's own noun does not follow is
     * not a count of anything the student asked for.
     */
    private val NUMBER_THEN_NOUN =
        Regex("""\b\d[\d,]*(?:\.\d+)?\s*%?\s+(?:of\s+(?:the\s+)?)?([a-z]{3,})""")

    /**
     * The same shape with the numeral spelled out: "fifteen members",
     * "twenty-one of the students". Kept as a second regex rather than folded into
     * [NUMBER_THEN_NOUN] as an alternation so the digit path stays byte for
     * byte what it was -- that path is what every count question against the
     * bundled corpus goes through, and it is the one with a battery behind it.
     *
     * Structurally identical on purpose, including the adjacency: the noun has
     * to follow the number. "Fifteen active members" does not match, exactly as
     * "35 active students" does not, because widening the gap is a separate
     * loosening with its own failure mode and this is not the change that
     * should make it.
     *
     * Group 1 is the number and group 2 the noun, which is the other way round
     * from [NUMBER_THEN_NOUN]: the number's position is needed by
     * [isQuantityUse] to tell "one student failed" from "one of the documents".
     */
    private val WORD_NUMBER_THEN_NOUN = Regex(
        """\b((?:$CARDINAL_WORDS)(?:-(?:$CARDINAL_WORDS))*)\b(?!-(?:$ORDINAL_WORDS)\b)""" +
            """\s+(?:percent\s+|per\s+cent\s+)?(?:of\s+(?:the\s+)?)?([a-z]{3,})"""
    )

    private fun statesCount(lowerSentence: String, terms: List<String>): Boolean {
        val digitCount = NUMBER_THEN_NOUN.findAll(lowerSentence)
            .any { m -> terms.any { t -> stemMatch(m.groupValues[1], t) } }
        if (digitCount) return true
        return WORD_NUMBER_THEN_NOUN.findAll(lowerSentence).any { m ->
            val number = m.groups[1] ?: return@any false
            isQuantityUse(lowerSentence, number.range) &&
                terms.any { t -> stemMatch(m.groupValues[2], t) }
        }
    }

    /**
     * Crude singular/plural and derivation tolerance: "student"/"students" and
     * "exam"/"examination" are the same word for scoring. Four characters is
     * the floor because below it prefixes stop being evidence of anything --
     * "sem" would match "semester" and "semiconductor" alike.
     */
    private fun stemMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        return short.length >= 4 && long.startsWith(short)
    }

    /**
     * A sentence that states a rule rather than an example. "How much X do I
     * need" is asking for the requirement, and the requirement sentence and
     * some neighbouring band almost always come back together.
     */
    private val REQUIREMENT_CUE = Regex(
        """\bminimum\b|\bmaximum\b|\bat\s+least\b|\bat\s+most\b|\brequired\b""" +
            """|\bmandatory\b|\bno\s+more\s+than\b|\bnot\s+less\s+than\b""",
        RegexOption.IGNORE_CASE
    )

    /** How close a digit has to be to a topic word to count as being about it. */
    private const val NEAR_CHARS = 60

    private fun numberNearTerm(lowerSentence: String, terms: List<String>): Boolean {
        val digits = lowerSentence.indices.filter { lowerSentence[it].isDigit() }
        if (digits.isEmpty()) return false
        for (t in terms) {
            val key = searchKey(t)
            var i = lowerSentence.indexOf(key)
            while (i >= 0) {
                val at = i
                if (digits.any { d -> kotlin.math.abs(d - at) <= NEAR_CHARS }) return true
                i = lowerSentence.indexOf(key, i + 1)
            }
        }
        return false
    }

    // --- applying a rule to a number the student stated -------------------

    /**
     * One tier of a rule: a percentage range and what happens inside it.
     *
     * [highExclusive] exists because "Below 65%" and "65% to 74%" are adjacent
     * tiers and 65 belongs to exactly one of them. Storing the boundary as
     * inclusive on both sides would make the answer depend on parse order.
     */
    data class Band(
        val low: Double?,
        val high: Double?,
        val consequence: String,
        val highExclusive: Boolean = false,
    ) {
        fun contains(v: Double): Boolean =
            (low == null || v >= low) && (high == null || if (highExclusive) v < high else v <= high)

        val label: String
            get() = when {
                low == null && high != null -> "below ${num(high)}%"
                low != null && high == null -> "${num(low)}% and above"
                low != null && high != null -> "${num(low)}% to ${num(high)}%"
                else -> "any"
            }
    }

    private val PIPE_ROW = Regex("""^[ \t]*\|([^|\n]{1,80})\|([^|\n]{3,})\|?[ \t]*$""", RegexOption.MULTILINE)
    private val R_BELOW = Regex("""\bbelow\s+(\d+(?:\.\d+)?)\s*%""", RegexOption.IGNORE_CASE)
    private val R_ABOVE = Regex(
        """(\d+(?:\.\d+)?)\s*%\s*(?:and|or)\s+(?:above|more|higher|over)""",
        RegexOption.IGNORE_CASE
    )
    private val R_RANGE = Regex(
        """(\d+(?:\.\d+)?)\s*%?\s*(?:to|and|-|–)\s*(\d+(?:\.\d+)?)\s*%""",
        RegexOption.IGNORE_CASE
    )

    /** Reads the tier table out of retrieved text. Order: below, above, range. */
    fun parseBands(text: String): List<Band> {
        val bands = ArrayList<Band>()
        for (row in PIPE_ROW.findAll(text)) {
            val range = row.groupValues[1].trim()
            val consequence = normalise(row.groupValues[2]).trimEnd('|', ' ')
            // Skip the header rule ("|-----|-----|") and any empty cell pair.
            if (consequence.isBlank() || consequence.all { it == '-' }) continue
            val below = R_BELOW.find(range)
            val above = R_ABOVE.find(range)
            val within = R_RANGE.find(range)
            when {
                below != null ->
                    bands += Band(null, below.groupValues[1].toDouble(), consequence, highExclusive = true)
                above != null ->
                    bands += Band(above.groupValues[1].toDouble(), null, consequence)
                within != null ->
                    bands += Band(within.groupValues[1].toDouble(), within.groupValues[2].toDouble(), consequence)
            }
        }
        return bands
    }

    private val PROSE_MIN = Regex(
        """\bminimum\s+of\s+(\d+(?:\.\d+)?)\s*%""" +
            """|\bat\s+least\s+(\d+(?:\.\d+)?)\s*%""" +
            """|(\d+(?:\.\d+)?)\s*%[^.\n]{0,60}?\bis\s+required\b""",
        RegexOption.IGNORE_CASE
    )

    /** Words that mark a tier as the one the student wants to be in. */
    private val PERMISSIVE =
        Regex("""\beligible\b|\bno action\b|\bpermitted\b|\ballowed\b|\bqualif""", RegexOption.IGNORE_CASE)

    /**
     * The threshold a student has to reach, from prose if the corpus states it
     * ("A minimum of 75% attendance ... is required") and otherwise from an
     * open-topped tier whose consequence reads as permission ("75% and above |
     * No action; eligible to appear ...").
     *
     * The fallback matters: the two halves of the tier table live in different
     * chunks, so a retrieval pass that brings back the second half and not the
     * first would otherwise have no threshold to compare against.
     */
    fun requiredMinimum(text: String, bands: List<Band>): Double? {
        PROSE_MIN.find(text)?.let { m ->
            m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toDoubleOrNull()?.let { return it }
        }
        return bands.filter { it.high == null && it.low != null && PERMISSIVE.containsMatchIn(it.consequence) }
            .minOfOrNull { it.low!! }
    }

    // --- a threshold, and who it belongs to -------------------------------

    /**
     * A minimum, carrying the scope it was stated for. [scope] null means the
     * institute-wide rule; anything else names the scheme that owns it.
     *
     * Scope is the whole point. Asked "am I eligible for a scholarship if my
     * attendance is 70 percent", the app replied "Yes — 70% meets the 70%
     * minimum required." The 70% is real, and it belongs to exactly one scheme:
     * the Sports and Cultural Excellence Scholarship, whose own notice calls it
     * a relaxation against the institute's general 75%. [requiredMinimum]
     * returns the FIRST number it finds, so a retrieval pass that happened to
     * rank the relaxation above the general rule told a student they qualified
     * when they do not. A number without its scope is not a threshold, it is a
     * digit.
     */
    data class Threshold(val value: Double, val scope: String?)

    /** "the institute's general 75% minimum", "against the general 75% minimum". */
    private val GENERAL_MIN = Regex(
        """\bgeneral\b[^.\n]{0,24}?(\d+(?:\.\d+)?)\s*%\s*minimum""" +
            """|\bgeneral\s+minimum[^.\n]{0,24}?(\d+(?:\.\d+)?)\s*%""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Wider than [PROSE_MIN], which wants "minimum of N%" adjacent and so
     * misses "a minimum attendance of 70%" -- the exact phrasing both the
     * scholarship procedure and the sports notice use.
     */
    private val ANY_MIN = Regex(
        """\bminimum(?:\s+\w+){0,2}\s+of\s+(\d+(?:\.\d+)?)\s*%""" +
            """|\bat\s+least\s+(\d+(?:\.\d+)?)\s*%""" +
            """|(\d+(?:\.\d+)?)\s*%[^.\n]{0,60}?\bis\s+required\b""",
        RegexOption.IGNORE_CASE
    )

    /** A named scheme, read off the ORIGINAL case: these are proper nouns. */
    private val SCHEME_NAME = Regex(
        """\b([A-Z][A-Za-z'-]*(?:\s+(?:and|of|for|the)?\s*[A-Z][A-Za-z'-]*){0,5}""" +
            """\s+(?:Scholarship|Freeship|Grant))\b"""
    )

    /**
     * Phrasing that marks a figure as belonging to one scheme even when no
     * scheme name is in the same sentence. The sports notice says "a counted
     * attendance minimum of 70% applies to institute representatives, against
     * the general 75% minimum" -- no scholarship named, and the 70% is still
     * not a rule anyone else can rely on.
     */
    private val SCOPE_CUE = Regex(
        """\brelaxation\b|\bapplies\s+only\b|\bthis\s+scheme\b|\bthat\s+scheme\b""" +
            """|\binstitute\s+representatives\b|\bunder\s+this\s+scheme\b""" +
            """|\blower\s+than\s+the\s+institute'?s?\s+general\b|\bagainst\s+the\s+general\b""",
        RegexOption.IGNORE_CASE
    )

    private val SCHEME_CELL = Regex("""Scholarship|Freeship|Grant""", RegexOption.IGNORE_CASE)
    private val PURE_PERCENT = Regex("""^\s*(\d+(?:\.\d+)?)\s*%\s*$""")

    /**
     * Every minimum in [text], each tagged with the scheme that owns it.
     *
     * Three sources, because the corpus states thresholds three ways: the
     * eligibility matrix as a five-column table row (which [parseBands] cannot
     * read -- its PIPE_ROW is anchored to two cells), prose sentences, and the
     * open-topped permissive tier that [requiredMinimum] already falls back
     * on. The tier fallback is used ONLY when nothing general was found, so a
     * stated rule always beats an inferred one.
     */
    fun requiredMinimums(text: String, bands: List<Band>): List<Threshold> {
        val out = ArrayList<Threshold>()

        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("|")) continue
            val cells = t.trim('|').split("|").map { it.trim() }
            if (cells.size < 3 || !SCHEME_CELL.containsMatchIn(cells[0])) continue
            val pct = cells.drop(1).firstNotNullOfOrNull { PURE_PERCENT.find(it) } ?: continue
            out += Threshold(pct.groupValues[1].toDouble(), schemeLabel(cells[0]))
        }

        for (sentence in text.split(SENTENCE_SPLIT)) {
            if (sentence.trimStart().startsWith("|")) continue
            val generals = GENERAL_MIN.findAll(sentence)
                .mapNotNull { m -> m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toDoubleOrNull() }
                .toList()
            generals.forEach { out += Threshold(it, null) }
            val scheme = SCHEME_NAME.find(sentence)?.groupValues?.get(1)?.let { schemeLabel(it) }
            val scoped = scheme != null || SCOPE_CUE.containsMatchIn(sentence)
            for (m in ANY_MIN.findAll(sentence)) {
                val v = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toDoubleOrNull() ?: continue
                // The general figure quoted inside a relaxation sentence has
                // already been recorded, correctly, as general. Do not record
                // it a second time as scoped.
                if (v in generals) continue
                out += Threshold(v, if (scoped) (scheme ?: "a scheme-specific relaxation") else null)
            }
        }

        if (out.none { it.scope == null }) {
            bands.filter { it.high == null && it.low != null && PERMISSIVE.containsMatchIn(it.consequence) }
                .minOfOrNull { it.low!! }
                ?.let { out += Threshold(it, null) }
        }
        return out.distinct()
    }

    /**
     * One scheme, one label. The matrix row says "Sports and Cultural
     * Excellence Scholarship" and the prose note says "The Sports and Cultural
     * Excellence Scholarship"; left as two labels they survive distinct() and
     * the answer names the same exception twice.
     */
    private fun schemeLabel(raw: String): String =
        normalise(raw).removePrefix("The ").removePrefix("the ").trim()

    /** Generic words that identify no scheme on their own. */
    private val SCHEME_STOPWORDS = setOf(
        "scholarship", "scholarships", "freeship", "grant", "scheme", "the", "and",
        "for", "of", "kriet", "institute", "specific",
    )

    /** Does the question actually name the scheme [scope] belongs to? */
    private fun questionNames(scope: String, query: String): Boolean {
        val q = query.lowercase()
        val distinctive = scope.lowercase()
            .split(Regex("[^a-z]+"))
            .filter { it.length > 3 && it !in SCHEME_STOPWORDS }
        return distinctive.isNotEmpty() && distinctive.any { q.contains(it) }
    }

    /**
     * The answer to "can I ... with N%", when the corpus states a rule to
     * judge N against. Null means no verdict is available and the caller
     * should fall back to quoting a sentence.
     *
     * Only ELIGIBILITY questions reach here. A bare mention of a percentage is
     * not a request for a ruling -- the student has to have asked one -- and
     * widening this to any question carrying a number is how a scholarship
     * cut-off would end up being answered against the attendance tiers.
     */
    /**
     * The retrieved chunks that are about the same subject as the question.
     *
     * Only judge against a rule that is about the same subject as the
     * question. Both attendance and scholarship cut-offs are percentages,
     * and both documents say "examination"; sharing a unit or a piece of
     * background vocabulary is not sharing a topic.
     *
     * The subject is approximated by the question's longest content word.
     * Crude, but it is the one signal available without corpus statistics,
     * and it is right for the case that matters: in "can I write the exam
     * with 60% attendance" the longest word is "attendance", which the
     * scholarship matrix does not contain and the attendance policy does.
     *
     * Lifted out of [applyToStated] so [tierConsequences] can share it. That
     * sharing is the point rather than a convenience: three compositions that
     * disagreed about which chunks a question is about would be three
     * different answers to the same question depending on which one fired.
     */
    fun subjectRelevant(q: Question, chunks: List<RetrievedChunk>): List<RetrievedChunk> {
        val longest = q.terms.maxByOrNull { it.length }?.length ?: return emptyList()
        val subject = q.terms.filter { it.length == longest }
        return chunks.filter { c ->
            val lower = c.content.lowercase()
            subject.any { mentions(lower, it) }
        }
    }

    fun applyToStated(q: Question, chunks: List<RetrievedChunk>): String? {
        if (q.need != Need.ELIGIBILITY) return null
        val stated = q.statedPercent ?: return null
        val relevant = subjectRelevant(q, chunks)
        if (relevant.isEmpty()) return null
        val text = relevant.joinToString("\n") { it.content }
        val bands = parseBands(text)

        // Scope resolution, before any comparison. The old line here was
        // `requiredMinimum(text, bands)` -- first number wins -- and it told a
        // student with 70% attendance that they qualified for a scholarship,
        // because the first minimum retrieved happened to be one scheme's
        // relaxation rather than the institute's rule.
        val thresholds = requiredMinimums(text, bands)
        val general = thresholds.filter { it.scope == null }.map { it.value }.distinct()
        val scoped = thresholds.filter { it.scope != null }.distinct()
        val named = scoped.firstOrNull { questionNames(it.scope!!, q.raw) }

        val required: Double = when {
            // The student named a scheme: judge against that scheme's rule.
            named != null -> named.value
            general.size == 1 -> general.single()
            // Two different institute-wide minima in the retrieved text means
            // the retrieval, not the corpus, is confused. Picking one would be
            // a coin toss reported as a ruling.
            general.size > 1 -> return "I can see more than one general minimum in the records (" +
                general.sorted().joinToString(", ") { "${num(it)}%" } +
                "), so I will not rule on ${num(stated)}% — tell me which rule applies."
            // Only scheme-specific figures. The honest answer is that it
            // depends on the scheme, WITH the numbers, not a shrug.
            scoped.isNotEmpty() -> return dependsOnScheme(stated, scoped)
            else -> return null
        }

        val verdict = StringBuilder()
        if (stated >= required) {
            verdict.append("Yes — ").append(num(stated)).append("% meets the ")
                .append(num(required)).append("% minimum required.")
        } else {
            verdict.append("No — ").append(num(stated)).append("% is below the ")
                .append(num(required)).append("% minimum required.")
        }
        // Naming the tier the student is actually in is the difference between
        // a ruling and a rule. 60% is not merely "below 75": it is below the
        // condonation floor as well, which is a different conversation.
        bands.sortedBy { it.low ?: Double.NEGATIVE_INFINITY }
            .firstOrNull { it.contains(stated) }
            ?.let { verdict.append(" ").append(num(stated)).append("% falls in the ")
                .append(it.label).append(" tier: ").append(it.consequence.trimEnd('.')).append(".") }

        // A relaxation the student did not ask about is still theirs to know
        // about -- but it is named as an exception, never used as the rule.
        if (named == null) {
            val exceptions = scoped.filter { it.value != required }.distinctBy { it.scope }
            if (exceptions.isNotEmpty()) {
                verdict.append(" That is the general rule; ")
                verdict.append(exceptions.joinToString("; ") { "${it.scope} uses ${num(it.value)}%" })
                verdict.append(", which applies only to that scheme.")
            }
        }
        return verdict.toString()
    }

    /**
     * When every minimum found is scheme-specific, the answer is which schemes
     * the student's number reaches -- not a verdict against whichever one
     * retrieval ranked first.
     */
    private fun dependsOnScheme(stated: Double, scoped: List<Threshold>): String {
        val byScheme = scoped.distinctBy { it.scope }.sortedBy { it.value }
        val met = byScheme.filter { stated >= it.value }
        if (byScheme.size == 1) {
            val only = byScheme.single()
            val verdict = if (stated >= only.value) "meets" else "does not meet"
            return "The only minimum I can find here is ${num(only.value)}%, and it belongs to " +
                "${only.scope} alone — it is not the institute-wide rule. " +
                "${num(stated)}% $verdict that one scheme's minimum; for any other scheme, " +
                "or for the general rule, the figure will be different."
        }
        val head = "It depends on the scheme — the minimum is not the same for all of them: " +
            byScheme.joinToString("; ") { "${it.scope} ${num(it.value)}%" } + "."
        val tail = when {
            met.isEmpty() -> " At ${num(stated)}% you meet none of them."
            met.size == byScheme.size -> " At ${num(stated)}% you meet all of them."
            else -> " At ${num(stated)}% you meet only " +
                met.joinToString(", ") { it.scope!! } + "."
        }
        return head + tail
    }

    // --- what follows, and what a scheme asks for -------------------------

    /**
     * The answer to "what happens if ...", when the rule is stated as a tier
     * table rather than as a sentence.
     *
     * [applyToStated] already reads these tiers, but only to place a number
     * the student supplied. A consequence question supplies no number and
     * wants the whole ladder: the tiers ARE the answer, and every one of them
     * is a verbatim cell from a document, so nothing here is composed in the
     * sense of being invented -- the range label names the row and the cell
     * speaks for itself.
     *
     * Deduplicated on (label, cell) because the identical tier table appears
     * in both the Attendance Policy and the Defaulter List Procedure, and a
     * retrieval pass that returns both would otherwise print each tier twice.
     */
    fun tierConsequences(q: Question, chunks: List<RetrievedChunk>): String? {
        if (q.need != Need.CONSEQUENCE) return null
        val relevant = subjectRelevant(q, chunks)
        if (relevant.isEmpty()) return null
        val bands = parseBands(relevant.joinToString("\n") { it.content })
        if (bands.isEmpty()) return null
        return bands
            .map { it.label to it.consequence }
            .distinct()
            .joinToString(" ") { (label, cell) ->
                label.replaceFirstChar { it.uppercase() } + ": " + cell.trimEnd('.') + "."
            }
    }

    /**
     * Words that identify no condition on their own, for [schemeConditions].
     *
     * The same argument the answer's-form entries in [STOPWORDS] make: "apply"
     * and "scholarship" are in the question because it is a question about
     * applying for a scholarship, and every row of the matrix is about that.
     */
    private val CONDITION_STOPWORDS = setOf(
        "student", "students", "apply", "applying", "scholarship", "scholarships",
        "scheme", "schemes", "merit", "grant", "freeship", "eligible",
        "eligibility", "college", "institute",
    )

    /**
     * The answer to "can a student with a backlog apply for X", when the
     * corpus states the condition as a cell of the eligibility matrix.
     *
     * Reads the same five-cell row [requiredMinimums] reads -- which
     * [parseBands] cannot, its PIPE_ROW being anchored to two cells -- but
     * keyed on the question's condition word instead of on a percentage.
     * Measured, this is the question that had nowhere else to go: asked
     * whether a student with a backlog may apply for the merit scholarship,
     * the highest topic overlap was "After submission: The Scholarship SPOC
     * verifies the CGPA and no-backlog declaration against examination
     * records ..." -- the procedure that follows a successful application,
     * offered to a student asking whether they may make one. The matrix says
     * plainly what that scheme requires; nobody was reading it.
     *
     * Two conditions before it will speak, and both are about not guessing.
     * The question has to name the scheme -- "merit" identifies the KRIET
     * Alumni Merit Grant among five rows -- because five schemes' document
     * lists recited at once is not an answer to a question about one. And the
     * condition word has to be the question's own, not the matrix's: keyed on
     * anything looser, "what happens if I miss the fee deadline" matched a
     * Disbursement Timeline cell on the word "fee" and answered that the
     * Alumni Merit Grant requires being credited to the tuition-fee ledger,
     * which is a sentence about nothing.
     *
     * Phrased as a requirement rather than as a yes or no on purpose. The
     * matrix says the grant requires a no-backlog declaration; whether a
     * particular student can sign one is a fact about the student that no
     * document here holds, and answering "no" would be inventing it.
     */
    fun schemeConditions(q: Question, chunks: List<RetrievedChunk>): String? {
        if (q.need != Need.PERMISSION) return null
        val condition = q.terms.filter { it !in CONDITION_STOPWORDS }
        if (condition.isEmpty()) return null

        val rows = ArrayList<Pair<String, String>>()
        for (chunk in chunks) {
            for (line in chunk.content.lineSequence()) {
                val t = line.trim()
                if (!t.startsWith("|")) continue
                val cells = t.trim('|').split("|").map { it.trim() }
                if (cells.size < 3 || !SCHEME_CELL.containsMatchIn(cells[0])) continue
                val cell = cells.drop(1).firstOrNull { c ->
                    val lower = c.lowercase()
                    condition.any { mentions(lower, it) }
                } ?: continue
                rows += schemeLabel(cells[0]) to normalise(cell)
            }
        }
        val named = rows.filter { questionNames(it.first, q.raw) }.distinct()
        if (named.isEmpty()) return null
        return "The records answer that as a requirement rather than a yes or no: " +
            named.joinToString("; ") { (scheme, cell) -> "$scheme requires ${cell.trimEnd('.')}" } +
            "."
    }

    // --- helpers ----------------------------------------------------------

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * The corpus is PDF-extracted and keeps the double spaces the layout left
     * behind ("A  minimum  of  75%  attendance"). Harmless inside a passage,
     * conspicuous in a one-line answer bubble.
     */
    fun normalise(text: String): String = WHITESPACE_RUN.replace(text, " ").trim()

}

/**
 * "75", not "75.0". Top-level rather than a member of [AnswerCheck] so the
 * nested [AnswerCheck.Band] can reach it without qualification.
 */
private fun num(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
