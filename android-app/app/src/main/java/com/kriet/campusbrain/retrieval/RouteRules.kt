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
        if (FACT_ATTR.containsMatchIn(q)) return Route.FACT to "rule: document-attribute phrasing"
        return null
    }

    val STUDENT_PHRASES = listOf("score of", "result for", "search for")

    val ROLL_NUMBER =
        Regex("\\broll\\s*(no\\.?|number)?\\s*[:#]?\\s*\\d{4,}\\b", RegexOption.IGNORE_CASE)
    val BARE_ROLL = Regex("\\b\\d{10,}\\b")

    /**
     * The bare words "student" and "roll" are ambiguous -- they appear in
     * ordinary document questions such as "student mentorship program" -- so
     * they only count when paired with record/lookup context.
     */
    val STUDENT_RECORD = Regex(
        "\\bstudent\\b.*\\b(record|marks|score|result|grade|sgpa|cgpa|roll|pass(?:ed)?)\\b" +
            "|\\b(record|marks|score|result|grade|sgpa|cgpa|pass(?:ed)?)\\b.*\\bstudent\\b",
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
