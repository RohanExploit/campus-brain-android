package com.kriet.campusbrain.answer

import com.kriet.campusbrain.data.RetrievedChunk

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

    fun parse(query: String): Question {
        val stated = STATED_PERCENT.find(query)?.groupValues?.get(1)?.toDoubleOrNull()
        val need = when {
            // Eligibility is only claimed when the student actually supplied a
            // number to judge. "Is the library open on Sunday" opens with a
            // modal too, and there is nothing there to compare.
            stated != null && ELIGIBILITY_CUE.containsMatchIn(query) -> Need.ELIGIBILITY
            COUNT_CUE.containsMatchIn(query) -> Need.COUNT
            QUANTITY_CUE.containsMatchIn(query) -> Need.QUANTITY
            else -> Need.OTHER
        }
        return Question(query, need, contentTerms(query), stated)
    }

    // --- finding a sentence that answers ----------------------------------

    private val SENTENCE_SPLIT = Regex("""(?<=[.!?])\s+|\n""")

    /**
     * Sentences worth quoting as a lead. Table rows are excluded: a line that
     * is mostly pipes reads as noise once lifted out of its table, and the
     * band parser in [parseBands] reads the raw text anyway, so nothing is
     * lost by keeping them out of here.
     */
    fun sentencesOf(text: String): List<String> =
        text.split(SENTENCE_SPLIT)
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
     * load-bearing. "What happens to my scholarship if I am debarred for
     * attendance" is answerable, and "debarred" lives in a pipe row that
     * [sentencesOf] deliberately drops -- a per-sentence version of this rule
     * would refuse it. What is being asked here is whether retrieval found the
     * topic, not whether one sentence restates it.
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
                val score = hits * 1000 + requirement +
                    (if (numberNearTerm(lower, q.terms)) 100 else 0) - ci
                if (score > bestScore) {
                    bestScore = score
                    best = Finding(normalise(sentence), ci, hits)
                }
            }
        }
        return best
    }

    private fun satisfiesShape(q: Question, lowerSentence: String): Boolean = when (q.need) {
        Need.COUNT -> statesCount(lowerSentence, q.terms)
        Need.QUANTITY, Need.ELIGIBILITY -> lowerSentence.any(Char::isDigit)
        Need.OTHER -> true
    }

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

    private fun statesCount(lowerSentence: String, terms: List<String>): Boolean =
        NUMBER_THEN_NOUN.findAll(lowerSentence)
            .any { m -> terms.any { t -> stemMatch(m.groupValues[1], t) } }

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
    fun applyToStated(q: Question, chunks: List<RetrievedChunk>): String? {
        if (q.need != Need.ELIGIBILITY) return null
        val stated = q.statedPercent ?: return null
        // Only judge against a rule that is about the same subject as the
        // question. Both attendance and scholarship cut-offs are percentages,
        // and both documents say "examination"; sharing a unit or a piece of
        // background vocabulary is not sharing a topic.
        //
        // The subject is approximated by the question's longest content word.
        // Crude, but it is the one signal available without corpus statistics,
        // and it is right for the case that matters: in "can I write the exam
        // with 60% attendance" the longest word is "attendance", which the
        // scholarship matrix does not contain and the attendance policy does.
        val subject = q.terms.maxByOrNull { it.length }?.length ?: return null
        val subjectTerms = q.terms.filter { it.length == subject }
        val relevant = chunks.filter { c ->
            val lower = c.content.lowercase()
            subjectTerms.any { mentions(lower, it) }
        }
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
