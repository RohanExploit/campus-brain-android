package com.kriet.campusbrain.retrieval

/**
 * Port of `match_template` in retrieval/sql_templates.py.
 *
 * Rule-for-rule and in the same order. The ordering is load-bearing -- e.g. the
 * pass/fail percentage branch must be checked before the generic fail-count
 * branch, which would otherwise swallow "what is the fail percentage" -- so
 * resist tidying it into something more symmetrical.
 */
object SqlTemplates {

    /** What matched, so the UI trace can name it the way the dashboard does. */
    data class Match(val name: String, val run: (TabularQueries) -> TabularQueries.TemplateResult)

    private val WORD_NUM = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
    )
    private const val NUM = "(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)"

    private fun num(tok: String): Int {
        val t = tok.trim().lowercase()
        return t.toIntOrNull() ?: WORD_NUM.getValue(t)
    }

    private val AT_LEAST_N = Regex("(?:at\\s*least|atleast|>=|minimum(?:\\s+of)?|min)\\s*$NUM", RegexOption.IGNORE_CASE)
    private val N_OR_MORE = Regex("$NUM\\s*(?:or\\s+more|\\+)\\s*subject", RegexOption.IGNORE_CASE)
    // "more than one subject" means >= 2, not >= 1.
    private val MORE_THAN_N = Regex("(?:more\\s+than|greater\\s+than|over|>)\\s*$NUM", RegexOption.IGNORE_CASE)
    private val TOP_N = Regex("top\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val BOTTOM_N = Regex("(?:bottom|lowest|worst)\\s+(\\d+)", RegexOption.IGNORE_CASE)
    // Anchored to its keyword so a semester or year number elsewhere in the
    // query is not mistaken for an SGPA threshold.
    private val SGPA_THRESHOLD = Regex(
        "(?:above|greater\\s+than|greater|at\\s*least|or\\s+more|>=|sgpa)\\D{0,12}(\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE
    )
    private val SUBJECT_CODE = Regex("\\bBT[A-Z]{2,5}\\d{3}[A-Z]?\\b", RegexOption.IGNORE_CASE)

    fun match(query: String): Match? {
        val q = query.lowercase()

        // Queries naming specific subject codes are targeted comparisons, not a
        // request for the full per-subject ranking.
        val namedSubjects = SUBJECT_CODE.containsMatchIn(query)

        // "subject" before "fail" scopes the question per-subject, so the
        // failed-most-subjects branch must not shadow subject_failure_counts.
        val subjectAsksFirst = q.contains("subject") && q.contains("fail") &&
            q.indexOf("subject") < q.indexOf("fail")

        if (!subjectAsksFirst && (q.contains("fail") || q.contains("backlog")) &&
            (q.contains("most") || q.contains("highest number") || q.contains("maximum"))
        ) return Match("students_failed_most") { it.studentsFailedMost() }

        if (q.contains("fail") || q.contains("backlog")) {
            val m = AT_LEAST_N.find(q) ?: N_OR_MORE.find(q)
            if (m != null) {
                val n = num(m.groupValues[1])
                return Match("students_failed_at_least") { it.studentsFailedAtLeast(n) }
            }
            MORE_THAN_N.find(q)?.let {
                // strictly greater than N is at least N+1
                val n = num(it.groupValues[1]) + 1
                return Match("students_failed_at_least") { t -> t.studentsFailedAtLeast(n) }
            }
            if (q.contains("multiple")) {
                return Match("students_failed_at_least") { it.studentsFailedAtLeast(2) }
            }
        }

        val pct = q.contains("percent") || q.contains("percentage") || q.contains("%") || q.contains("rate")
        if (q.contains("pass") && pct) return Match("pass_percentage") { it.passPercentage() }
        if ((q.contains("fail") || q.contains("failure")) && pct && !namedSubjects) {
            return Match("fail_percentage") { it.failPercentage() }
        }

        if (q.contains("topper") || (q.contains("top") && q.contains("sgpa")) ||
            (q.contains("highest") && q.contains("sgpa"))
        ) {
            val n = TOP_N.find(q)?.groupValues?.get(1)?.toInt()
            return Match("toppers_by_sgpa") { if (n != null) it.toppersBySgpa(n) else it.toppersBySgpa() }
        }

        if (!namedSubjects && q.contains("subject") && q.contains("fail") &&
            (q.contains("per") || q.contains("each") || q.contains("which") ||
                q.contains("wise") || q.contains("count"))
        ) return Match("subject_failure_counts") { it.subjectFailureCounts() }

        if ((q.contains("lowest") || q.contains("bottom") || q.contains("worst")) && q.contains("sgpa")) {
            val n = BOTTOM_N.find(q)?.groupValues?.get(1)?.toInt()
            return Match("bottom_by_sgpa") { if (n != null) it.bottomBySgpa(n) else it.bottomBySgpa() }
        }

        if (q.contains("sgpa") && !namedSubjects && !q.contains("subject") && !q.contains("fail") &&
            (q.contains("above") || q.contains("greater") || q.contains("or more") ||
                q.contains("at least") || q.contains(">=")) &&
            (q.contains("how many") || q.contains("number of") || q.contains("count"))
        ) {
            val t = SGPA_THRESHOLD.find(q)?.groupValues?.get(1)?.toDoubleOrNull() ?: 9.0
            return Match("count_sgpa_at_least") { it.countSgpaAtLeast(t) }
        }

        if (q.contains("supplement") &&
            (q.contains("how many") || q.contains("number of") || q.contains("count"))
        ) return Match("supplementary_count") { it.supplementaryCount() }

        if ((q.contains("how many") || q.contains("number of") || q.contains("count of")) &&
            q.contains("fail") && !namedSubjects && !q.contains("subject") &&
            !q.contains("at least") && !q.contains("atleast") &&
            !q.contains("most") && !q.contains("backlog")
        ) return Match("result_count") { it.resultCount("FAIL") }

        if (q.contains("pass") &&
            (q.contains("how many") || q.contains("number of") || q.contains("count of")) &&
            !namedSubjects && !q.contains("subject") &&
            !q.contains("percent") && !q.contains("rate") && !q.contains("%")
        ) return Match("result_count") { it.resultCount("PASS") }

        if ((q.contains("how many") || q.contains("number of") || q.contains("total")) &&
            q.contains("student") &&
            listOf("fail", "subject", "sgpa", "pass", "below", "above", "supplement",
                "review", "backlog", "topper", "most", "least").none { q.contains(it) }
        ) return Match("student_count") { it.studentCount() }

        return null
    }
}
