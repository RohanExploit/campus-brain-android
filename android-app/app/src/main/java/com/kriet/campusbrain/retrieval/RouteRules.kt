package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.data.Route

/**
 * Stage 1 of classification: the deterministic rules, with no database, no
 * embedder and no I/O.
 *
 * Split out of [QueryRouter] so it can be unit-tested on the JVM. These rules
 * are where routing accuracy is actually decided -- the backend's history
 * records that routing, not retrieval, was the accuracy bottleneck -- so they
 * need tests that run in milliseconds, not a device.
 *
 * Byte-faithful port of the pre-LLM block of `classify_query` in
 * retrieval/router.py. Order is load-bearing; keep it.
 */
object RouteRules {

    /** null means "no rule fired", which the caller resolves to FACT. */
    fun classify(query: String): Pair<Route, String>? {
        val q = query.lowercase()

        if (STUDENT_PHRASES.any { q.contains(it) }) return Route.TABULAR to "rule: student phrase"
        if (ROLL_NUMBER.containsMatchIn(q)) return Route.TABULAR to "rule: roll-number pattern"
        // A bare 10+ digit run is a roll number even without the word "roll"
        // ("Did student 23067571263053 pass?"). Roll numbers here are 10-14
        // digits and nothing else in the domain is that long.
        if (BARE_ROLL.containsMatchIn(q)) return Route.TABULAR to "rule: bare roll number"
        if (STUDENT_RECORD.containsMatchIn(q)) return Route.TABULAR to "rule: student-record phrasing"
        AGG_KW.firstOrNull { q.contains(it) }?.let {
            return Route.TABULAR to "rule: aggregate keyword \"$it\""
        }
        // "What is the highest SGPA" reached none of the above -- there is no
        // "student" token for STUDENT_RECORD and "highest" was absent from
        // AGG_KW -- so it fell through to FACT and abstained on a value one
        // MAX() away. Superlatives cannot join AGG_KW unguarded, because
        // "minimum attendance percentage" is a genuine FACT question; pair
        // them with a score word instead.
        if (SUPERLATIVE_SCORE.containsMatchIn(q)) {
            return Route.TABULAR to "rule: superlative over a score column"
        }
        // "Which subject do students struggle with most" reached no rule above
        // -- no score word for SUPERLATIVE_SCORE, no "fail" for AGG_KW -- and
        // the prototype stage sent it to GLOBAL, where it retrieved the
        // attendance condonation notice and read out the 65-74% band. Nothing
        // in 58 documents states which subject is hardest, and nothing ever
        // will: it is an argmax over per-subject grades in student_subjects,
        // which is TABULAR by definition. Routing it anywhere else is asking a
        // question of the wrong half of the corpus.
        if (SUBJECT_DIFFICULTY.containsMatchIn(q)) {
            return Route.TABULAR to "rule: subject ranked by difficulty"
        }
        // "How is the college doing overall this semester" is the same argument
        // one level up: it asks how the cohort did, and the cohort's results
        // are rows, not prose. The corpus has no state-of-the-college passage
        // to retrieve, so the vector route could only ever return the nearest
        // circular -- which is what it did.
        if (isCohortOverview(q)) return Route.TABULAR to "rule: cohort overview"
        if (FACT_ATTR.containsMatchIn(q)) return Route.FACT to "rule: document-attribute phrasing"
        return null
    }

    private val DIFFICULTY =
        """hard|harder|hardest|tough|tougher|toughest|difficult|difficulty""" +
            """|struggle|struggles|struggling|weak|weakest|worst|poorly|badly"""

    /**
     * A subject framed as hard rather than as a topic.
     *
     * Paired with the noun on purpose, the same guard [SUPERLATIVE_SCORE]
     * needs: a bare "difficult" would claim "is the admission process
     * difficult", and a bare "subject" would claim "what subjects are in the
     * syllabus". Deliberately NOT including "most" or "lowest" -- "which
     * subject has the most failures" and "the lowest pass rate" are already
     * TABULAR through [AGG_KW], with templates that read the framing, and
     * duplicating them here would only add a second way to get it wrong.
     */
    val SUBJECT_DIFFICULTY = Regex(
        """\bsubjects?\b[^.?!]{0,40}\b(?:$DIFFICULTY)\b""" +
            """|\b(?:$DIFFICULTY)\b[^.?!]{0,40}\bsubjects?\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * "How is the college doing overall this semester" and nothing that merely
     * resembles it.
     *
     * Three conditions, all required, because each one alone over-claims. The
     * cue on its own takes "a summary of the syllabus"; the scope on its own
     * takes every second question in the corpus; and cue plus scope still takes
     * "what is the overall attendance of students", which is an attendance
     * question with a document answer and no business reaching a results table.
     * The exclusion list is the same device [SqlTemplates.QUALIFIERS] uses to
     * stop the bare roster count answering questions that narrow it.
     *
     * Reached only AFTER [AGG_KW], so a question that names the figure it wants
     * keeps the template that computes exactly that figure: "what is the
     * overall pass percentage" is pass_percentage, not a summary.
     */
    fun isCohortOverview(query: String): Boolean {
        val q = query.lowercase()
        return OVERVIEW_CUE.containsMatchIn(q) &&
            OVERVIEW_SCOPE.containsMatchIn(q) &&
            !OVERVIEW_EXCLUDED.containsMatchIn(q)
    }

    private val OVERVIEW_CUE = Regex(
        """\boverall\b|\boverview\b|\bsummary\b|\bsummaris\w*\b|\bsummariz\w*\b""" +
            """|\bhow\s+(?:is|are|has|have|did|was|were)\b[^.?!]{0,40}""" +
            """\b(?:doing|going|performing|performed|perform|fared|faring|done)\b""",
        RegexOption.IGNORE_CASE
    )

    /** Who the summary would be about. A cohort, or its results -- not a topic. */
    private val OVERVIEW_SCOPE = Regex(
        """\bcollege\b|\binstitute\b|\bcampus\b|\bbatch\b|\bcohort\b""" +
            """|\bstudents?\b|\bresults?\b|\bsemester\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Words that make the question about one thing rather than about the
     * cohort. "An overview of the semester registration process" and "the
     * overall attendance of students" both satisfy cue and scope, and neither
     * is answered by a pass rate.
     */
    private val OVERVIEW_EXCLUDED = Regex(
        """\battendance\b|\bscholarship\w*\b|\bfees?\b|\bhostel\b|\bplacement\w*\b""" +
            """|\blibrar\w*\b|\bsyllabus\b|\btimetable\b|\bregistration\b|\bcertificate\b""" +
            """|\bholiday\w*\b|\bevent\w*\b|\bnotice\b|\bcircular\b|\bpolicy\b|\bprocedure\b""",
        RegexOption.IGNORE_CASE
    )

    val STUDENT_PHRASES = listOf("score of", "result for", "search for")

    val ROLL_NUMBER =
        Regex("\\broll\\s*(no\\.?|number)?\\s*[:#]?\\s*\\d{4,}\\b", RegexOption.IGNORE_CASE)
    val BARE_ROLL = Regex("\\b\\d{10,}\\b")

    /**
     * The bare words "student" and "roll" are ambiguous -- they appear in
     * ordinary document questions such as "student mentorship program" -- so
     * they only count when paired with record/lookup context.
     *
     * The first alternation takes the plural and the inflected verb; the second
     * deliberately does not. That asymmetry is measured, not tidiness lost:
     * "list students who scored 10 SGPA" routed FACT and abstained, while "how
     * many students scored above 9.0 SGPA" -- the same true zero -- answered
     * exactly, because the second carries an [AGG_KW] and the first carries
     * only a listing verb. Widening the SECOND alternation to "students" too
     * would claim "summary of the scholarship results for students", which is a
     * document question and is pinned as one in RouteRulesTest. A record word
     * AFTER the plural noun is a records question; a plural noun trailing one is
     * usually just who the document is for.
     */
    val STUDENT_RECORD = Regex(
        "\\bstudents?\\b.*\\b(record|marks|scor(?:e|es|ed|ing)|result|grade|sgpa|cgpa|roll|pass(?:ed)?)\\b" +
            "|\\b(record|marks|score|result|grade|sgpa|cgpa|pass(?:ed)?)\\b.*\\bstudent\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * A superlative next to a score column. Deliberately NOT folded into
     * [AGG_KW]: a bare "minimum"/"highest" there would capture "minimum
     * attendance percentage", which belongs to FACT.
     */
    val SUPERLATIVE_SCORE = Regex(
        """\b(highest|lowest|maximum|minimum|max|min|best|worst|greatest)\b""" +
            """[^.?!]{0,24}\b(sgpa|cgpa|gpa|marks?|score|grade|result)\b""" +
            """|\b(sgpa|cgpa|gpa|marks?|score)\b[^.?!]{0,24}""" +
            """\b(highest|lowest|maximum|minimum|max|min|best|worst|greatest)\b""",
        RegexOption.IGNORE_CASE
    )

    /** All 20, verbatim from router.py. Note: no bare "percentage". */
    val AGG_KW = listOf(
        "how many", "how much", "list all", "list of student", "which students",
        "at least", "atleast", "or more", "average", "count of", "number of",
        "toppers", "topper", "pass percentage", "pass rate", "pass %",
        "failed", "fail", "below sgpa", "sgpa below", "most subjects", "backlog",
        "top ",
    )

    /**
     * Attribute lookups ("authors of X", "established in") are properties of one
     * named thing, not aggregates. Routed to FACT before the aggregate list can
     * claim them.
     */
    val FACT_ATTR = Regex(
        "\\bauthors?\\s+of\\b|\\bauthored\\s+by\\b|\\bwritten\\s+by\\b" +
            "|\\baffiliated\\s+with\\b|\\baffiliation\\b" +
            "|\\bestablished\\s+in\\b|\\bfounded\\s+(?:by|in)\\b" +
            "|\\blocated\\s+in\\b|\\bbased\\s+in\\b" +
            "|\\b(?:programs?|courses?)\\s+offered\\b" +
            "|\\b(?:programs?|courses?)\\b.{0,20}\\boffers?\\b" +
            "|\\boffers?\\b.{0,20}\\b(?:programs?|courses?)\\b",
        RegexOption.IGNORE_CASE
    )
}
