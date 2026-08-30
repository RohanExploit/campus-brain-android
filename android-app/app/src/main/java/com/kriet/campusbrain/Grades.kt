package com.kriet.campusbrain

/**
 * Port of models/grades.py. The ONLY place in this app that knows grade
 * semantics.
 *
 * That file's own docstring records why: a duplicated, wrong copy of this
 * mapping previously misclassified 'AB' (a pass, 8.5) as a failure across
 * ingestion, retrieval and audit. Adding a second copy on the phone would
 * reintroduce exactly that. If the scale changes, change it there and re-port
 * here -- GradesTest pins the values.
 */
object Grades {

    /** Base grade points on the 0-10 scale, from the legend printed on the PDFs. */
    val GRADE_POINTS: Map<String, Double> = mapOf(
        "EX" to 10.0,  // excellent
        "AA" to 9.0,
        "AB" to 8.5,   // 80.01-85.00 -- a PASS, not an absence
        "BB" to 8.0,
        "BC" to 7.5,
        "CC" to 7.0,
        "CD" to 6.5,
        "DD" to 6.0,
        "DE" to 5.5,
        "EE" to 5.0,
        "FF" to 0.0,   // fail, 0.00-39.99
    )

    /** The only academic failing grade. */
    val FAIL_GRADES: Set<String> = setOf("FF")

    /** Audit subjects: 0 points, excluded from the SGPA credit denominator. */
    val AUDIT_GRADES: Set<String> = setOf("AU")

    fun isFail(grade: String?): Boolean = grade != null && grade in FAIL_GRADES

    fun isAudit(grade: String?): Boolean = grade != null && grade in AUDIT_GRADES

    /**
     * SGPA recomputed from subject rows.
     *
     * `student_subjects.grade_point` is ALREADY `base_point * credit` (see the
     * grades.py docstring: AB rows carry 17.0 at 2 credits and 34.0 at 4), so
     * this must not multiply by credit again. Audit rows are excluded from both
     * numerator and denominator.
     *
     * Returned for display beside the stored SGPA, never instead of it.
     */
    fun recomputeSgpa(rows: List<Pair<String?, Pair<Int, Double>>>): Double? {
        var points = 0.0
        var credits = 0
        for ((grade, cp) in rows) {
            val (credit, gradePoint) = cp
            if (isAudit(grade)) continue
            points += gradePoint
            credits += credit
        }
        return if (credits == 0) null else points / credits
    }
}
