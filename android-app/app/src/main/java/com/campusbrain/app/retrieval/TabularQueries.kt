package com.campusbrain.app.retrieval

import com.campusbrain.app.Grades
import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.data.StudentRow
import com.campusbrain.app.data.SubjectRow
import com.campusbrain.app.data.query

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
    private val auditList = Grades.AUDIT_GRADES.joinToString(",") { "'$it'" }

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

    /**
     * The average, over the rows that HAVE one.
     *
     * [TabularIntent] has carried an `average_sgpa` intent since the port, but
     * nothing in [SqlTemplates] ever matched it, so "what is the average SGPA"
     * fell through TABULAR to FACT and surfaced a paper about the 2010 Flash
     * Crash. Measured over the shipped bundle: 7.34 across 334 of 369 students.
     *
     * The null branch is not defensive padding. `AVG(sgpa)` over the 35 FAIL
     * rows is NULL, not 0.0, because not one of them carries a grade point --
     * and a null coerced through "%.2f" prints "0.00", which is a fabricated
     * measurement in the one place this app is meant to be exact.
     */
    fun averageSgpa(result: String? = null): TemplateResult {
        val filter = when {
            result == null -> null
            result.uppercase().startsWith("FAIL") -> "FAIL"
            else -> "PASS"
        }
        val sql = "SELECT AVG(sgpa), COUNT(sgpa), COUNT(*) FROM students" +
            (if (filter == null) "" else " WHERE result = ?")
        val row = db.conn.query(sql, { st -> filter?.let { st.bindText(1, it) } }) {
            Triple(if (it.isNull(0)) null else it.getDouble(0), it.getLong(1), it.getLong(2))
        }.firstOrNull() ?: return TemplateResult("No SGPA data available.", sql, "average_sgpa")
        val (avg, counted, total) = row
        val who = when (filter) {
            "FAIL" -> "students who failed"
            "PASS" -> "students who passed"
            else -> "students"
        }
        if (avg == null || counted == 0L) {
            return TemplateResult(
                "No SGPA is recorded for $who — all $total of those records leave it blank, " +
                    "so there is no average to report.",
                sql, "average_sgpa"
            )
        }
        val body = StringBuilder("The average SGPA is ${"%.2f".format(avg)}")
        if (counted == total) {
            body.append(", across all $total $who.")
        } else {
            body.append(", across the $counted of $total $who who have one ")
            body.append("(the other ${total - counted} have no SGPA recorded).")
        }
        return TemplateResult(body.toString(), sql, "average_sgpa")
    }

    /**
     * Pass RATE per subject, which is a different question from pass COUNT per
     * subject and has a different answer in this bundle: BTCOC502 has the most
     * failures (16) but BTAIHM503B has the worst rate (90.9%), because only 66
     * students take it against BTCOC502's 303. "Which subject has the lowest
     * pass rate" used to match the college-wide pass_percentage template and
     * answer "90.5%" -- a real figure, to a question nobody asked.
     *
     * Audit rows are excluded from the denominator, the same rule
     * [Grades.recomputeSgpa] applies. BTCOF408 and BTAIP408 are graded AU for
     * every student who takes them; a subject nobody can fail sits at 100% and
     * is noise at both ends of the ranking.
     */
    fun subjectPassRates(worstFirst: Boolean = true, limit: Int = 10): TemplateResult {
        val order = if (worstFirst) "ASC" else "DESC"
        val sql = "SELECT subject_code, COUNT(*) FILTER (WHERE grade NOT IN ($failList)) AS passed, " +
            "COUNT(*) AS took, " +
            "100.0 * COUNT(*) FILTER (WHERE grade NOT IN ($failList)) / COUNT(*) AS rate " +
            "FROM student_subjects WHERE grade IS NOT NULL AND grade NOT IN ($auditList) " +
            "GROUP BY subject_code ORDER BY rate $order, subject_code LIMIT ?"
        val rows = db.conn.query(sql, { it.bindLong(1, limit.toLong()) }) {
            SubjectRate(it.getText(0), it.getLong(1), it.getLong(2), it.getDouble(3))
        }
        if (rows.isEmpty()) {
            return TemplateResult("No subject grade data available.", sql, "subject_pass_rates")
        }
        val head = if (worstFirst) "Lowest pass rate by subject:" else "Highest pass rate by subject:"
        val body = buildString {
            append(head).append('\n')
            rows.forEach {
                append("- ${it.code}: ${"%.1f".format(it.rate)}% (${it.passed} of ${it.took} passed)\n")
            }
        }
        return TemplateResult(body.trimEnd(), sql, "subject_pass_rates")
    }

    private data class SubjectRate(val code: String, val passed: Long, val took: Long, val rate: Double)

    /**
     * How the cohort did, in four figures.
     *
     * This exists because "how is the college doing overall this semester" was
     * answered "I don't have enough information to answer that" while the
     * records held 334 passes out of 369. The GLOBAL route was doing what it is
     * built to do -- retrieve widely across documents -- and there is no
     * document to retrieve: no circular in this bundle summarises the semester.
     * The summary is a query, not a passage.
     *
     * Assembled from the existing templates rather than from new SQL, so the
     * numbers here cannot drift away from the numbers the same app gives when
     * the questions are asked one at a time. That is the whole point of the
     * deterministic badge: "the pass rate is 90.5%" and "overall, 90.5%" must
     * be the same 90.5%, computed once.
     *
     * The weakest subject is included because it is the only part of "how are
     * we doing" the headline rate hides -- and it is the RATE, not the count.
     * BTCOC502 has the most failures (16) and a 94.7% pass rate; BTAIHM503B has
     * 6 and 90.9%. Reporting the count here would name the wrong subject as the
     * cohort's weak spot.
     */
    fun semesterOverview(): TemplateResult {
        val pass = passPercentage()
        val avg = averageSgpa()
        val worst = db.conn.query(
            "SELECT subject_code, COUNT(*) FILTER (WHERE grade NOT IN ($failList)) AS passed, " +
                "COUNT(*) AS took, " +
                "100.0 * COUNT(*) FILTER (WHERE grade NOT IN ($failList)) / COUNT(*) AS rate " +
                "FROM student_subjects WHERE grade IS NOT NULL AND grade NOT IN ($auditList) " +
                "GROUP BY subject_code ORDER BY rate ASC, subject_code LIMIT 1"
        ) { SubjectRate(it.getText(0), it.getLong(1), it.getLong(2), it.getDouble(3)) }
            .firstOrNull()

        val body = StringBuilder()
        body.append(pass.answer)
        body.append(' ').append(avg.answer)
        worst?.let {
            body.append(" The weakest subject by pass rate is ").append(it.code)
                .append(" at ").append("%.1f".format(it.rate)).append("% (")
                .append(it.took - it.passed).append(" of ").append(it.took)
                .append(" failed it).")
        }
        return TemplateResult(
            body.toString(),
            pass.debugSql + " ; " + avg.debugSql + " ; subjectPassRates(worstFirst=true, limit=1)",
            "semester_overview",
        )
    }

    /**
     * One counter with optional predicates, rather than a new template per
     * combination of them. Every constraint the caller passes is applied --
     * which is the entire point: the failure this replaces was a template
     * answering the half of the question it happened to recognise.
     *
     * The zero case gets an explanation because in this bundle zero is
     * structural rather than incidental. No student with a FAIL result carries
     * an SGPA (0 of 35), so any question intersecting a failure with an SGPA
     * threshold is empty by construction. A bare "0" is arithmetically right
     * and leaves the student believing the cohort is clean.
     */
    fun countStudentsMatching(
        sgpaBelow: Double? = null,
        sgpaAtLeast: Double? = null,
        minFailedSubjects: Int? = null,
        result: String? = null,
    ): TemplateResult {
        val conds = ArrayList<String>()
        val described = ArrayList<String>()
        sgpaBelow?.let {
            conds += "s.sgpa IS NOT NULL AND s.sgpa < ?"
            described += "an SGPA below ${trimNum(it)}"
        }
        sgpaAtLeast?.let {
            conds += "s.sgpa IS NOT NULL AND s.sgpa >= ?"
            described += "an SGPA of ${trimNum(it)} or above"
        }
        val status = result?.let { if (it.uppercase().startsWith("FAIL")) "FAIL" else "PASS" }
        status?.let {
            conds += "s.result = '$it'"
            described += if (it == "FAIL") "a FAIL result" else "a PASS result"
        }
        minFailedSubjects?.let { n ->
            // Interpolated, not bound: n is an Int this object parsed itself
            // out of a digit run, never user text reaching SQL.
            conds += "(SELECT COUNT(DISTINCT ss.subject_code) FROM student_subjects ss " +
                "WHERE ss.roll_no = s.roll_no AND ss.grade IN ($failList)) >= $n"
            described += "at least $n failed subject" + (if (n == 1) "" else "s")
        }
        if (conds.isEmpty()) return studentCount()

        val sql = "SELECT COUNT(*) FROM students s WHERE " + conds.joinToString(" AND ")
        val n = db.conn.query(sql, { st ->
            var i = 1
            sgpaBelow?.let { st.bindDouble(i++, it) }
            sgpaAtLeast?.let { st.bindDouble(i++, it) }
        }) { it.getLong(0) }.first()

        val phrase = described.joinToString(" and ")
        if (n > 0) return TemplateResult("$n students have $phrase.", sql, "students_matching")

        val note = emptyIntersectionNote(
            usedSgpa = sgpaBelow != null || sgpaAtLeast != null,
            failSide = status == "FAIL" || minFailedSubjects != null,
        )
        return TemplateResult(
            "No students have $phrase." + (note?.let { " $it" } ?: ""), sql, "students_matching"
        )
    }

    /**
     * Why an intersection came back empty, when the reason is a hole in the
     * records rather than a fact about the cohort. Queried, not assumed: if a
     * future bundle does record SGPA for failing students, this note stops
     * appearing on its own instead of becoming a lie in a string constant.
     */
    private fun emptyIntersectionNote(usedSgpa: Boolean, failSide: Boolean): String? {
        if (!usedSgpa || !failSide) return null
        val row = db.conn.query(
            "SELECT COUNT(*), COUNT(sgpa) FROM students WHERE result = 'FAIL'"
        ) { it.getLong(0) to it.getLong(1) }.firstOrNull() ?: return null
        val (fails, withSgpa) = row
        if (fails == 0L || withSgpa > 0L) return null
        return "That is a gap in the records rather than a clean cohort: not one of the $fails " +
            "students with a FAIL result has an SGPA recorded, so no student can satisfy both " +
            "conditions at once."
    }

    /**
     * The clean sheets, counted both ways the records allow.
     *
     * "How many students have no backlogs" has two defensible readings -- no FF
     * in any subject, and an overall result of PASS -- and the honest answer
     * names which one produced the number rather than picking one silently.
     * Both are computed here, in one statement, so the sentence can say whether
     * they agree instead of asserting that they do. Measured on the shipped
     * bundle they do agree, at 334 of 369; that is a fact about this export and
     * not a guarantee, which is exactly why it is queried and not assumed.
     *
     * NOT EXISTS, not `COUNT(FF) = 0` through a join: a student with no
     * `student_subjects` rows at all would vanish from a join and be counted as
     * neither clean nor failing.
     */
    fun studentsWithoutBacklogs(): TemplateResult {
        val sql = "SELECT (SELECT COUNT(*) FROM students s WHERE NOT EXISTS (" +
            "SELECT 1 FROM student_subjects ss WHERE ss.roll_no = s.roll_no " +
            "AND ss.grade IN ($failList))), " +
            "(SELECT COUNT(*) FROM students WHERE result = 'PASS'), " +
            "(SELECT COUNT(*) FROM students)"
        val row = db.conn.query(sql) { Triple(it.getLong(0), it.getLong(1), it.getLong(2)) }
            .firstOrNull()
            ?: return TemplateResult("No result data available.", sql, "no_backlogs")
        val (noFf, passed, total) = row
        val body = if (noFf == passed) {
            "$noFf of $total students have no backlogs — no FF grade in any subject. " +
                "That is also the number whose overall result is PASS, so both readings of " +
                "the question give the same $noFf. The other ${total - noFf} each carry at " +
                "least one FF."
        } else {
            "Counted as no FF grade in any subject: $noFf of $total students. Counted instead " +
                "as an overall result of PASS: $passed of $total. The two readings do not " +
                "agree in these records, so both are given rather than one being chosen for you."
        }
        return TemplateResult(body, sql, "no_backlogs")
    }

    /**
     * Whether anyone missed the examination.
     *
     * Asked on hardware as "which students did not appear for the exam", this
     * matched no template, fell through to document retrieval, and answered with
     * the placement cell's registration rule -- confidently, and about an
     * entirely different kind of registering. The records answer it: three
     * columns are the only trace they keep of a student not sitting a paper, and
     * all three are empty.
     *
     * Phrased as a negative existential ("no student is marked as...") rather
     * than as the affirmative universal ("all 369 appeared"). Attendance at the
     * examination is not a field in this schema, so the affirmative would be an
     * inference wearing the deterministic badge; what the tables can actually
     * support is that nothing in them records an absence. The columns checked
     * are named in the answer for the same reason -- a student who knows the
     * basis can tell when it is the wrong basis.
     */
    fun examAttendance(): TemplateResult {
        val sql = "SELECT COUNT(*), COUNT(*) FILTER (WHERE seat_cancelled = 1), " +
            "COUNT(*) FILTER (WHERE is_supply = 1), " +
            "COUNT(*) FILTER (WHERE NOT EXISTS (SELECT 1 FROM student_subjects ss " +
            "WHERE ss.roll_no = students.roll_no AND ss.grade IS NOT NULL)) FROM students"
        val row = db.conn.query(sql) {
            listOf(it.getLong(0), it.getLong(1), it.getLong(2), it.getLong(3))
        }.firstOrNull()
            ?: return TemplateResult("No result data available.", sql, "exam_attendance")
        val (total, cancelled, supply, ungraded) = row
        val basis = "Seat cancellations, supplementary candidature and a missing grade are the " +
            "only trace these records keep of a student not sitting the regular examination — " +
            "attendance at the exam itself is not a column here."
        if (cancelled == 0L && supply == 0L && ungraded == 0L) {
            return TemplateResult(
                "No student in these records is marked as having missed the examination. " +
                    "All $total have a result recorded, none has a cancelled seat, none is " +
                    "marked as a supplementary candidate, and every one of them carries a " +
                    "grade in at least one subject. $basis",
                sql, "exam_attendance"
            )
        }
        val parts = ArrayList<String>()
        if (cancelled > 0L) parts += "$cancelled had a cancelled seat"
        if (supply > 0L) parts += "$supply are marked as supplementary candidates"
        if (ungraded > 0L) parts += "$ungraded carry no grade in any subject"
        return TemplateResult(
            "Of $total students, ${parts.joinToString("; ")}. $basis",
            sql, "exam_attendance"
        )
    }

    /**
     * The students at one exact SGPA.
     *
     * "List students who scored 10 SGPA" abstained, while "how many students
     * scored above 9.0 SGPA" answered the same true zero exactly. The
     * difference was a comparator, not a fact.
     *
     * The empty answer branches on the maximum because one sentence cannot
     * serve both misses. Above the top of the scale, naming the real maximum
     * IS the answer -- nobody reached 10, the best is 8.82. Inside the range,
     * quoting a maximum that is higher than the number asked about reads as a
     * contradiction, so the miss is reported as what it is: no row holds that
     * exact value, to the two decimals these records keep.
     */
    fun studentsWithSgpa(value: Double): TemplateResult {
        val sql = "SELECT roll_no, name, sgpa FROM students " +
            "WHERE sgpa IS NOT NULL AND ROUND(sgpa, 2) = ROUND(?, 2) ORDER BY sgpa DESC, roll_no LIMIT 100"
        val rows = db.conn.query(sql, { it.bindDouble(1, value) }) {
            Triple(it.getText(0), if (it.isNull(1)) null else it.getText(1), it.getDouble(2))
        }
        val t = trimNum(value)
        if (rows.isNotEmpty()) {
            val body = buildString {
                append("${rows.size} student${if (rows.size == 1) "" else "s"} scored exactly $t SGPA:\n")
                rows.forEach { (roll, name, sgpa) ->
                    append("- ${label(name, roll)}: SGPA ${"%.2f".format(sgpa)}\n")
                }
            }
            return TemplateResult(body.trimEnd(), sql, "students_with_sgpa")
        }
        val max = db.conn.query("SELECT MAX(sgpa) FROM students") {
            if (it.isNull(0)) null else it.getDouble(0)
        }.firstOrNull()
        val body = when {
            max == null -> "No SGPA is recorded for any student, so nobody can be listed at $t."
            value > max -> "No student has an SGPA of $t. The highest SGPA in these records is " +
                "${"%.2f".format(max)}."
            else -> "No student has an SGPA of exactly $t. SGPA is recorded here to two decimal " +
                "places, so an exact value often matches nobody — ask for above or below $t if " +
                "you meant a range."
        }
        return TemplateResult(body, sql, "students_with_sgpa")
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
