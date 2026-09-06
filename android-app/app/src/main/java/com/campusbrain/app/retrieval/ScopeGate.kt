package com.campusbrain.app.retrieval

/**
 * Refuses student-record questions that are about somebody else's institution.
 *
 * The tabular store holds one college's students and nothing else, but the
 * templates in [SqlTemplates] match on keywords alone. "How many students
 * failed at IIT Bombay" therefore matched `result_count` and answered **35**
 * — this college's figure, presented as IIT Bombay's, with a TABULAR badge and
 * no hedge. An adversarial battery caught it; it is the most dangerous class of
 * error the app can make, because the answer looks exactly like a right one.
 *
 * The gate deliberately applies only to the TABULAR route. A FACT question that
 * mentions Oxford is perfectly answerable — the corpus contains research papers
 * that cite other institutions — but a *student record* question about another
 * institution is unanswerable by construction, not merely unanswered.
 */
object ScopeGate {

    /**
     * This corpus is KRIET, affiliated to Dr. Babasaheb Ambedkar Technological
     * University. Counted in the shipped bundle: KRIET 136 mentions, DBATU 65.
     * Naming either is naming *us*, not a foreign institution.
     */
    private val OWN = listOf(
        "kriet", "dbatu",
        "babasaheb ambedkar technological university",
        "konkan ratna",
    )

    /**
     * Institutions distinctive enough that a bare mention settles the question.
     * Word-boundary matched, so "mit" cannot fire inside "admit" or "submit" —
     * a plain `contains` here would refuse half the admissions questions in the
     * corpus.
     */
    private val FOREIGN = Regex(
        "\\b(iit|nit|iiit|iim|aiims|bits|vit|nift|nlu|jnu|coep|vjti|spit|mit|" +
            "harvard|stanford|oxford|cambridge|berkeley|caltech)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A named institution in the general case: some capitalised words followed
     * by an institution noun. Runs against the raw query, not the lowercased
     * one, because capitalisation is the only signal that "Pune University" is
     * a name while "the university" is not.
     */
    private val NAMED_INSTITUTION = Regex(
        "\\b([A-Z][A-Za-z.&]*(?:\\s+[A-Z][A-Za-z.&]*){0,4})\\s+" +
            "(University|College|Institute|Vidyapeeth|Polytechnic|Academy|School)\\b",
    )

    /** What was named, or null when the question is about this college. */
    fun foreignInstitution(rawQuery: String): String? {
        val q = rawQuery.lowercase()
        if (OWN.any { q.contains(it) }) return null

        FOREIGN.find(rawQuery)?.let { return it.value.uppercase() }

        NAMED_INSTITUTION.find(rawQuery)?.let { m ->
            val name = m.value.trim()
            // "Technological University" on its own is how the corpus refers to
            // the parent body; only a *qualified* name is foreign.
            if (OWN.any { name.lowercase().contains(it) }) return null
            if (m.groupValues[1].split(Regex("\\s+")).all { it.length <= 3 }) return null
            return name
        }
        return null
    }

    /**
     * The refusal text. Names the institution back so the student can see the
     * app understood the question and is declining on scope, not failing to
     * parse — an unexplained "I don't know" reads as breakage.
     */
    fun refusal(institution: String): String =
        "This app only holds records for this college, so it cannot answer questions " +
            "about students at $institution."
}
