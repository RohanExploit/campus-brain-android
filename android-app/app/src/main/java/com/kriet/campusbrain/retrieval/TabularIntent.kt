package com.kriet.campusbrain.retrieval

/**
 * Port of retrieval/intent.py -- the cascade that runs when no deterministic
 * template matched. Same six kinds, same order, same veto lists.
 */
object TabularIntent {

    data class Intent(val kind: String, val params: Map<String, String> = emptyMap())

    /**
     * Words meaning "one student's record". `cgpa`/`sgpa`/`gpa` are here because
     * "What is the CGPA of <person>?" is a single-student lookup that carries
     * none of the other keywords.
     */
    private val LOOKUP_KW = listOf(
        "result", "results", "record", "marksheet", "marks", "grade",
        "grades", "score", "scores", "details", "cgpa", "sgpa", "gpa",
    )

    /**
     * Aggregate markers that veto the single-student reading. Without this,
     * threshold questions ("students below 6 SGPA", "how many scored above 8")
     * would be treated as somebody's name.
     */
    private val AGG_KW = listOf(
        "how many", "count", "number of", "list", "which", "average",
        "percentage", "rate", "top ", "most", " all ", "every", "each",
        "below", "under", "above", "greater", "or more", "at least",
        "atleast", "highest", "lowest", "bottom", "topper", "rank",
    )

    private val ROLL = Regex("(\\d{10,15})")
    private val BELOW_THRESHOLD = Regex("(?:below|under|sgpa)\\D{0,10}(\\d+(?:\\.\\d+)?)")
    private val ANY_DECIMAL = Regex("(\\d+\\.\\d+)")

    fun classify(query: String): Intent {
        val q = query.lowercase()

        if (q.contains("search for") || q.contains("list all") ||
            q.contains("which students") || q.contains("at least") || q.contains("atleast")
        ) {
            val simpleNameSearch = q.contains("search for") &&
                !(q.contains("fail") || q.contains("sgpa") || q.contains("subject") ||
                    q.contains("grade") || q.contains("sem"))
            return if (simpleNameSearch) Intent("name_search") else Intent("dynamic_sql")
        }

        // A personal-name query asking for a result/record/marksheet, in any
        // word order. Must use fuzzy name search, never an equality match: the
        // DB stores "SURNAME NAME MIDDLE" upper-cased.
        if (LOOKUP_KW.any { q.contains(it) } && AGG_KW.none { q.contains(it) }) {
            val roll = ROLL.find(query)?.groupValues?.get(1)
            return if (roll != null) Intent("record_by_roll", mapOf("roll" to roll))
            else Intent("name_search")
        }

        if (q.contains("average sgpa")) return Intent("average_sgpa")

        if (q.contains("fail")) {
            return if (q.contains("how many") || q.contains("count") || q.contains("number")) {
                Intent("count_failures")
            } else Intent("dynamic_sql")
        }

        if (q.contains("below") && q.contains("sgpa")) {
            // Anchor the threshold to its keyword: "semester 3 students below
            // 6 sgpa" must give 6, not 3.
            val t = BELOW_THRESHOLD.find(q)?.groupValues?.get(1)
                ?: ANY_DECIMAL.find(query)?.groupValues?.get(1)
            return Intent("below_sgpa", mapOf("threshold" to (t ?: "6.0")))
        }

        if (q.contains("record") || q.contains("roll") || q.contains("student") || q.contains("score")) {
            val roll = ROLL.find(query)?.groupValues?.get(1)
            return if (roll != null) Intent("record_by_roll", mapOf("roll" to roll))
            else Intent("name_search")
        }

        return Intent("dynamic_sql")
    }
}
