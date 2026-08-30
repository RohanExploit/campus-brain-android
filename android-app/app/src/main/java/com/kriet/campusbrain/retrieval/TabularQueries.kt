package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.Grades
import com.kriet.campusbrain.data.BrainDb
import com.kriet.campusbrain.data.StudentRow
import com.kriet.campusbrain.data.SubjectRow
import com.kriet.campusbrain.data.query

/**
 * The deterministic half of the TABULAR route.
 *
 * Ported from retrieval/sql_templates.py, but re-expressed against the mobile
 * bundle's `students` / `student_subjects` tables. The backend queries an
 * `exam_results` view (students JOIN student_subjects, one row per subject),
 * so anything it writes as `SELECT DISTINCT roll_no, ... FROM exam_results`
 * is simply `FROM students` here -- students already holds one row per roll.
 * `is_fail` becomes `grade IN ('FF')`, sourced from [Grades] rather than
 * re-hardcoded.
 *
 * Never generated, never approximated: these are the answers the app is allowed
 * to state as fact.
 */
class TabularQueries(private val db: BrainDb) {

    private val failList = Grades.FAIL_GRADES.joinToString(",") { "'$it'" }

    data class TemplateResult(val answer: String, val debugSql: String, val template: String)

    // --- single-student lookup -------------------------------------------

    fun studentByRoll(roll: String): StudentRow? = db.conn.query(
        "SELECT roll_no, name, sgpa, estimated_sgpa, total_marks, result, " +
            "is_supply, seat_cancelled FROM students WHERE roll_no = ?",
        bind = { it.bindText(1, roll) },
    ) {
        StudentRow(
            rollNo = it.getText(0),
            name = if (it.isNull(1)) null else it.getText(1),
            sgpa = if (it.isNull(2)) null else it.getDouble(2),
            estimatedSgpa = if (it.isNull(3)) null else it.getDouble(3),
            totalMarks = if (it.isNull(4)) null else it.getLong(4),
            result = it.getText(5),
            isSupply = !it.isNull(6) && it.getLong(6) != 0L,
            seatCancelled = !it.isNull(7) && it.getLong(7) != 0L,
        )
    }.firstOrNull()

    fun subjectsFor(roll: String): List<SubjectRow> = db.conn.query(
        "SELECT subject_code, credit, grade, grade_point, raw_grade_string " +
            "FROM student_subjects WHERE roll_no = ? ORDER BY subject_code",
        bind = { it.bindText(1, roll) },
    ) {
        SubjectRow(
            subjectCode = it.getText(0),
            credit = if (it.isNull(1)) 0 else it.getLong(1).toInt(),
            grade = if (it.isNull(2)) null else it.getText(2),
            gradePoint = if (it.isNull(3)) 0.0 else it.getDouble(3),
            rawGradeString = if (it.isNull(4)) null else it.getText(4),
        )
    }

    /**
     * Name search. LIKE-matches each token independently so word order and case
     * do not matter -- the DB stores names upper-case and often surname-first,
     * which is exactly why the backend refuses to let text-to-SQL handle this
     * with an equality match.
     */
    fun studentsByName(queryText: String, limit: Int = 50): List<StudentRow> {
        val tokens = queryText.uppercase()
            .split(Regex("[^A-Z0-9]+"))
            .filter { it.length > 1 && it !in NAME_STOPWORDS }
        if (tokens.isEmpty()) return emptyList()
        val where = tokens.joinToString(" AND ") { "UPPER(name) LIKE ?" }
        return db.conn.query(
            "SELECT roll_no, name, sgpa, estimated_sgpa, total_marks, result, " +
                "is_supply, seat_cancelled FROM students WHERE $where ORDER BY name LIMIT $limit",
            bind = { st -> tokens.forEachIndexed { i, t -> st.bindText(i + 1, "%$t%") } },
        ) {
            StudentRow(
                it.getText(0),
                if (it.isNull(1)) null else it.getText(1),
                if (it.isNull(2)) null else it.getDouble(2),
                if (it.isNull(3)) null else it.getDouble(3),
                if (it.isNull(4)) null else it.getLong(4),
                it.getText(5),
                !it.isNull(6) && it.getLong(6) != 0L,
                !it.isNull(7) && it.getLong(7) != 0L,
            )
        }
    }

    fun renderStudent(s: StudentRow, subjects: List<SubjectRow>): String = buildString {
        append("**").append(s.name ?: s.rollNo).append("** (").append(s.rollNo).append(")\n")
        append("Result: ").append(s.result)
        s.sgpa?.let { append("   SGPA: ").append("%.2f".format(it)) }
        s.totalMarks?.let { append("   Total: ").append(it) }
        append('\n')
        if (s.isSupply) append("Appeared for supplementary examination.\n")
        if (s.seatCancelled) append("Seat cancelled.\n")
        val backlogs = subjects.count { Grades.isFail(it.grade) }
        append(if (backlogs == 0) "No backlogs.\n" else "$backlogs backlog(s).\n")
        if (subjects.isNotEmpty()) {
            append("\nSubjects:\n")
            subjects.forEach {
                val tag = when {
                    Grades.isAudit(it.grade) -> " (audit)"
                    Grades.isFail(it.grade) -> " (fail)"
                    else -> ""
                }
                append("- ").append(it.subjectCode).append(": ").append(it.grade ?: "-")
                append(tag).append("  [").append(it.credit).append(" cr]\n")
            }
        }
    }

    // --- analytical templates --------------------------------------------

    fun studentsFailedAtLeast(n: Int): TemplateResult {
        val sql = "SELECT ss.roll_no, s.name, COUNT(DISTINCT ss.subject_code) AS failed_subjects " +
            "FROM student_subjects ss LEFT JOIN students s ON s.roll_no = ss.roll_no " +
            "WHERE ss.grade IN ($failList) GROUP BY ss.roll_no, s.name " +
            "HAVING COUNT(DISTINCT ss.subject_code) >= ? ORDER BY failed_subjects DESC, ss.roll_no"
        val rows = db.conn.query(sql, { it.bindLong(1, n.toLong()) }) {
            Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getLong(2))
        }
        if (rows.isEmpty()) {
            return TemplateResult("No students failed at least $n subjects.", sql, "students_failed_at_least")
        }
        val body = buildString {
            append("Found ${rows.size} students who failed at least $n subjects:\n")
            rows.forEach { (roll, name, fails) -> append("- ${label(name, roll)}: $fails subjects\n") }
        }
        return TemplateResult(body.trimEnd(), sql, "students_failed_at_least")
    }

    fun studentsFailedMost(limit: Int = 10): TemplateResult {
        val sql = "SELECT ss.roll_no, s.name, COUNT(DISTINCT ss.subject_code) AS failed_subjects " +
            "FROM student_subjects ss LEFT JOIN students s ON s.roll_no = ss.roll_no " +
            "WHERE ss.grade IN ($failList) GROUP BY ss.roll_no, s.name " +
            "ORDER BY failed_subjects DESC, ss.roll_no LIMIT ?"
        val rows = db.conn.query(sql, { it.bindLong(1, limit.toLong()) }) {
            Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getLong(2))
        }
        if (rows.isEmpty()) return TemplateResult("No failing students found.", sql, "students_failed_most")
        val body = buildString {
            append("Students who failed the most subjects (max = ${rows[0].third}):\n")
            rows.forEach { (roll, name, fails) -> append("- ${label(name, roll)}: $fails subjects\n") }
        }
        return TemplateResult(body.trimEnd(), sql, "students_failed_most")
    }

    /**
     * Two FILTER clauses over ONE shared denominator.
     *
     * The backend's docstring records why this is a template and not generated
     * SQL: text-to-SQL wrote `COUNT(*) FROM students WHERE result='FAIL'`, which
     * filters before aggregating, making the denominator equal the numerator and
     * the answer always exactly 100%.
     */
    private fun resultPercentage(status: String, template: String): TemplateResult {
        val sql = "SELECT 100.0 * COUNT(*) FILTER (WHERE result = ?) / NULLIF(COUNT(*), 0), " +
            "COUNT(*) FILTER (WHERE result = ?), COUNT(*) FROM students"
        val row = db.conn.query(sql, { it.bindText(1, status); it.bindText(2, status) }) {
            Triple(if (it.isNull(0)) null else it.getDouble(0), it.getLong(1), it.getLong(2))
        }.first()
        val (pct, n, total) = row
            ?: return TemplateResult("No result data available.", sql, template)
        if (pct == null) return TemplateResult("No result data available.", sql, template)
        val word = if (status == "FAIL") "Fail" else "Pass"
        val verb = if (status == "FAIL") "failed" else "passed"
        return TemplateResult(
            "$word percentage: ${"%.1f".format(pct)}% ($n of $total students $verb).", sql, template
        )
    }

    fun passPercentage() = resultPercentage("PASS", "pass_percentage")
    fun failPercentage() = resultPercentage("FAIL", "fail_percentage")

    fun toppersBySgpa(limit: Int = 10) = sgpaRanking(limit, desc = true)
    fun bottomBySgpa(limit: Int = 10) = sgpaRanking(limit, desc = false)

    private fun sgpaRanking(limit: Int, desc: Boolean): TemplateResult {
        val template = if (desc) "toppers_by_sgpa" else "bottom_by_sgpa"
        val sql = "SELECT roll_no, name, sgpa FROM students WHERE sgpa IS NOT NULL " +
            "ORDER BY sgpa ${if (desc) "DESC" else "ASC"}, roll_no LIMIT ?"
        val rows = db.conn.query(sql, { it.bindLong(1, limit.toLong()) }) {
            Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getDouble(2))
        }
        if (rows.isEmpty()) return TemplateResult("No SGPA data available.", sql, template)
        val head = if (desc) "Top ${rows.size} students by SGPA:" else "Lowest ${rows.size} students by SGPA:"
        val body = buildString {
            append(head).append('\n')
            rows.forEachIndexed { i, (roll, name, sgpa) ->
                append("${i + 1}. ${label(name, roll)}: SGPA ${"%.2f".format(sgpa)}\n")
            }
        }
        return TemplateResult(body.trimEnd(), sql, template)
    }

    fun subjectFailureCounts(limit: Int = 50): TemplateResult {
        val sql = "SELECT subject_code, COUNT(*) AS fails FROM student_subjects " +
            "WHERE grade IN ($failList) GROUP BY subject_code HAVING fails > 0 " +
            "ORDER BY fails DESC, subject_code LIMIT ?"
        val rows = db.conn.query(sql, { it.bindLong(1, limit.toLong()) }) {
            it.getText(0) to it.getLong(1)
        }
        if (rows.isEmpty()) return TemplateResult("No subject failures found.", sql, "subject_failure_counts")
        val body = buildString {
            append("Failures per subject:\n")
            rows.forEach { (code, fails) -> append("- $code: $fails failures\n") }
        }
        return TemplateResult(body.trimEnd(), sql, "subject_failure_counts")
    }

    fun studentCount(): TemplateResult {
        val sql = "SELECT COUNT(*) FROM students"
        val n = db.conn.query(sql) { it.getLong(0) }.first()
        return TemplateResult("There are $n students in the database.", sql, "student_count")
    }

    fun resultCount(status: String): TemplateResult {
        val s = if (status.uppercase().startsWith("FAIL")) "FAIL" else "PASS"
        val sql = "SELECT COUNT(*) FROM students WHERE result = ?"
        val n = db.conn.query(sql, { it.bindText(1, s) }) { it.getLong(0) }.first()
        val verb = if (s == "FAIL") "failed" else "passed"
        return TemplateResult("$n students $verb their semester examination.", sql, "result_count")
    }

    fun countSgpaAtLeast(threshold: Double): TemplateResult {
        val sql = "SELECT COUNT(*) FROM students WHERE sgpa IS NOT NULL AND sgpa >= ?"
        val n = db.conn.query(sql, { it.bindDouble(1, threshold) }) { it.getLong(0) }.first()
        val t = trimNum(threshold)
        return TemplateResult(
            if (n == 0L) "No students have an SGPA of $t or above."
            else "$n students have an SGPA of $t or above.",
            sql, "count_sgpa_at_least"
        )
    }

    fun listBelowSgpa(threshold: Double): TemplateResult {
        val sql = "SELECT roll_no, name, sgpa FROM students WHERE sgpa IS NOT NULL AND sgpa < ? " +
            "ORDER BY sgpa ASC, roll_no LIMIT 100"
        val rows = db.conn.query(sql, { it.bindDouble(1, threshold) }) {
            Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getDouble(2))
        }
        val t = trimNum(threshold)
        if (rows.isEmpty()) return TemplateResult("No students have an SGPA below $t.", sql, "below_sgpa")
        val body = buildString {
            append("${rows.size} students with SGPA below $t:\n")
            rows.forEach { (roll, name, sgpa) -> append("- ${label(name, roll)}: SGPA ${"%.2f".format(sgpa)}\n") }
        }
        return TemplateResult(body.trimEnd(), sql, "below_sgpa")
    }

    fun supplementaryCount(): TemplateResult {
        val sql = "SELECT COUNT(*) FROM students WHERE is_supply = 1"
        val n = db.conn.query(sql) { it.getLong(0) }.first()
        return TemplateResult(
            if (n == 0L) "No students appeared for a supplementary examination."
            else "$n students appeared for a supplementary examination.",
            sql, "supplementary_count"
        )
    }

    private fun label(name: String?, roll: String) = if (name.isNullOrBlank()) roll else "$name ($roll)"

    private fun trimNum(d: Double) = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    companion object {
        private val NAME_STOPWORDS = setOf(
            "RESULT", "RESULTS", "RECORD", "MARKSHEET", "MARKS", "GRADE", "GRADES",
            "SCORE", "SCORES", "DETAILS", "CGPA", "SGPA", "GPA", "STUDENT",
            "SEARCH", "FOR", "OF", "THE", "SHOW", "ME", "WHAT", "IS",
        )
    }
}
