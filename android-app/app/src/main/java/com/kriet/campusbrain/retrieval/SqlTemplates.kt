package com.kriet.campusbrain.retrieval

import com.kriet.campusbrain.Grades

/**
 * Port of `match_template` in retrieval/sql_templates.py.
 *
 * Rule-for-rule and in the same order. The ordering is load-bearing -- e.g. the
 * pass/fail percentage branch must be checked before the generic fail-count
 * branch, which would otherwise swallow "what is the fail percentage" -- so
 * resist tidying it into something more symmetrical.
 *
 * [match] is the matcher. [resolve] is what callers should use, because
 * matching is only half the decision: a template that matches on part of a
 * question and silently ignores the rest answers a DIFFERENT question, with
 * real data and a TABULAR badge on it. Measured on the hard battery:
 *
 *   "how many students below 6 SGPA also failed"  -> "35 students failed."
 *   "which subject has the lowest pass rate"      -> "Pass percentage: 90.5%."
 *
 * Both numbers are correct. Neither answers what was asked, and a wrong number
 * wearing the deterministic badge is worse than an abstention, because the
 * badge is the app's promise that no model made the figure up.
 */
object SqlTemplates {

    /** What matched, so the UI trace can name it the way the dashboard does. */
    data class Match(val name: String, val run: (TabularQueries) -> TabularQueries.TemplateResult)

    /**
     * The closed vocabulary of things a question can narrow by.
     *
     * Enumerated on purpose, rather than measured as "words the template did
     * not consume". A leftover-token test refuses "is the pass rate good or
     * bad" and "which subject has the most failures" -- both of which are
     * answered correctly today -- because ordinary English carries words no
     * template will ever model. Only a constraint that would change the SQL
     * counts.
     */
    enum class Constraint(val phrase: String) {
        SGPA_THRESHOLD("an SGPA threshold"),
        SGPA_RANKING("an SGPA ranking"),
        FAIL_COUNT("a threshold on the number of subjects failed"),
        RESULT_FAIL("a fail or backlog filter"),
        RESULT_PASS("a pass filter"),
        RATE("a percentage or rate"),
        SUBJECT("a per-subject breakdown"),
        SUPPLEMENTARY("supplementary-examination status"),
        AVERAGE("an average"),
        ATTENDANCE("attendance"),
        SCHOLARSHIP("scholarship eligibility"),
        PLACEMENT("placement status"),
        HOSTEL("hostel status"),
        FEES("fees"),
        COHORT("a department, branch or division filter"),
        LETTER_GRADE("a specific letter grade"),
    }

    /**
     * What each template actually evaluates. A constraint present in the
     * question and absent from this set is a constraint the answer ignored.
     *
     * `pass_percentage` and `fail_percentage` claim both result constraints
     * because they are two readings of one figure over one denominator, not
     * because they filter on either.
     */
    private val MODELLED: Map<String, Set<Constraint>> = mapOf(
        "students_failed_most" to setOf(Constraint.RESULT_FAIL, Constraint.FAIL_COUNT, Constraint.SUBJECT),
        "students_failed_at_least" to setOf(Constraint.RESULT_FAIL, Constraint.FAIL_COUNT, Constraint.SUBJECT),
        "result_count" to setOf(Constraint.RESULT_FAIL, Constraint.RESULT_PASS),
        "pass_percentage" to setOf(Constraint.RESULT_PASS, Constraint.RESULT_FAIL, Constraint.RATE),
        "fail_percentage" to setOf(Constraint.RESULT_FAIL, Constraint.RESULT_PASS, Constraint.RATE),
        "toppers_by_sgpa" to setOf(Constraint.SGPA_RANKING, Constraint.SGPA_THRESHOLD),
        "bottom_by_sgpa" to setOf(Constraint.SGPA_RANKING, Constraint.SGPA_THRESHOLD),
        "subject_failure_counts" to setOf(Constraint.SUBJECT, Constraint.RESULT_FAIL, Constraint.FAIL_COUNT),
        "subject_pass_rates" to setOf(
            Constraint.SUBJECT, Constraint.RESULT_PASS, Constraint.RESULT_FAIL,
            Constraint.RATE, Constraint.FAIL_COUNT,
        ),
        "count_sgpa_at_least" to setOf(Constraint.SGPA_THRESHOLD),
        "students_with_sgpa" to setOf(Constraint.SGPA_THRESHOLD, Constraint.SGPA_RANKING),
        "below_sgpa" to setOf(Constraint.SGPA_THRESHOLD),
        "supplementary_count" to setOf(Constraint.SUPPLEMENTARY),
        // Both readings of "no backlogs" are reported, so both result
        // constraints are evaluated rather than merely mentioned. FAIL_COUNT
        // is claimed because zero IS the count the question asks for.
        "no_backlogs" to setOf(
            Constraint.RESULT_FAIL, Constraint.RESULT_PASS,
            Constraint.FAIL_COUNT, Constraint.SUBJECT,
        ),
        // ATTENDANCE is claimed on purpose: "how many students were absent
        // from the exam" trips C_ATTENDANCE on the word "absent", and a
        // caveat reading "that figure does not account for attendance" under
        // an answer that is entirely about exam attendance would undermine the
        // one thing it does evaluate.
        "exam_attendance" to setOf(Constraint.ATTENDANCE, Constraint.SUPPLEMENTARY),
        "student_count" to emptySet(),
        "average_sgpa" to setOf(
            Constraint.AVERAGE, Constraint.SGPA_RANKING,
            Constraint.RESULT_FAIL, Constraint.RESULT_PASS,
        ),
        // The summary reports the pass/fail split, the rate and the average, so
        // a question that asks for the overview AND names one of them is fully
        // answered rather than partially. It does not filter, which is why no
        // threshold or cohort constraint appears here.
        "semester_overview" to setOf(
            Constraint.RESULT_PASS, Constraint.RESULT_FAIL, Constraint.RATE,
            Constraint.AVERAGE, Constraint.SUBJECT,
        ),
        "students_matching" to setOf(
            Constraint.SGPA_THRESHOLD, Constraint.RESULT_FAIL, Constraint.RESULT_PASS,
            Constraint.FAIL_COUNT, Constraint.SUBJECT,
        ),
    )

    /** The outcome of [resolve]: matched cleanly, matched partially, or not at all. */
    sealed class Resolution {
        object None : Resolution()
        data class Answered(val match: Match) : Resolution()

        /**
         * A template matched, and the question carries constraints it does not
         * evaluate. The match is still handed back rather than thrown away: on
         * this corpus a real figure plus an explicit statement of what it does
         * NOT account for is more useful to a student than either a wrong
         * number or a bare "I don't know". The caller must render [caveat]
         * alongside the answer -- that obligation is the whole contract here.
         */
        data class Partial(val match: Match, val ignored: List<Constraint>) : Resolution()
    }

    fun resolve(query: String): Resolution {
        val m = match(query) ?: return Resolution.None
        val ignored = unmodelled(m.name, query)
        return if (ignored.isEmpty()) Resolution.Answered(m) else Resolution.Partial(m, ignored)
    }

    /**
     * The constraints [templateName] does not evaluate, of those the question
     * carries. Empty for an unknown name, which is how single-student lookups
     * opt out: "marksheet of Rohan Gaikwad" narrows by nothing.
     */
    fun unmodelled(templateName: String?, query: String): List<Constraint> {
        val modelled = MODELLED[templateName] ?: return emptyList()
        return constraintsIn(query).filter { it !in modelled }
    }

    /**
     * Which entry of [MODELLED] describes each [TabularIntent] kind that runs
     * real SQL.
     *
     * The guard started life on the template path only, which left a hole: the
     * intent cascade answers "how many students below 6 SGPA are in the
     * hostel" by listing all 25 students below 6 and saying nothing about the
     * hostel. Same defect, different door. The lookup kinds are absent on
     * purpose -- they filter on a name or a roll number, not on a constraint.
     */
    val INTENT_TEMPLATES: Map<String, String> = mapOf(
        "below_sgpa" to "below_sgpa",
        "count_failures" to "subject_failure_counts",
        "average_sgpa" to "average_sgpa",
    )

    /** The sentence a [Resolution.Partial] must be shown with. */
    fun caveat(ignored: List<Constraint>): String =
        "That figure does not account for the rest of your question — " +
            ignored.joinToString(", ") { it.phrase } +
            ". I can only report what the templates above actually evaluate, " +
            "so treat the number as unfiltered by that."

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
    /** Raw string: the alternation is dense enough that escaped backslashes
     * become unreadable, and one wrong pair fails silently at match time. */
    private val NEGATED_PASS = Regex(
        """\b(did\s*n[o']?t|didnt|have\s*n[o']?t|havent|has\s*n[o']?t|hasnt|""" +
            """were\s*n[o']?t|werent|was\s*n[o']?t|wasnt|not)\s+(pass(?:ed)?|clear(?:ed)?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val TOP_N = Regex("top\\s+(\\d+)", RegexOption.IGNORE_CASE)
    private val BOTTOM_N = Regex("(?:bottom|lowest|worst)\\s+(\\d+)", RegexOption.IGNORE_CASE)
    // Anchored to its keyword so a semester or year number elsewhere in the
    // query is not mistaken for an SGPA threshold.
    private val SGPA_THRESHOLD = Regex(
        "(?:above|greater\\s+than|greater|at\\s*least|or\\s+more|>=|sgpa)\\D{0,12}(\\d+(?:\\.\\d+)?)",
        RegexOption.IGNORE_CASE
    )
    private val SUBJECT_CODE = Regex("\\bBT[A-Z]{2,5}\\d{3}[A-Z]?\\b", RegexOption.IGNORE_CASE)

    // --- constraint detection ---------------------------------------------
    //
    // Written as two patterns per threshold, one for each word order, because
    // students write both: "SGPA below 6" and "below 6 SGPA" are the same
    // constraint and the second one is what the hard battery used.

    private val SGPA_BELOW = Regex(
        """(?:sgpa|cgpa|gpa)\s*(?:is\s+)?(?:below|under|less\s+than|lower\s+than|<)\s*(\d+(?:\.\d+)?)""" +
            """|(?:below|under|less\s+than|lower\s+than|<)\s*(\d+(?:\.\d+)?)\s*(?:sgpa|cgpa|gpa)""",
        RegexOption.IGNORE_CASE
    )
    private val SGPA_ATLEAST = Regex(
        """(?:sgpa|cgpa|gpa)\s*(?:is\s+)?(?:above|over|greater\s+than|more\s+than|at\s*least|>=|>)\s*(\d+(?:\.\d+)?)""" +
            // "more than" belongs in BOTH orders. It was in the first only, so
            // "students with more than 8 sgpa who failed" found no SGPA
            // threshold at all, and MORE_THAN_N then claimed the 8 as a
            // subject count -- "at least 9 failed subjects", with nothing left
            // over for the guard to notice.
            """|(?:above|over|greater\s+than|more\s+than|at\s*least|>=|>)\s*(\d+(?:\.\d+)?)\s*(?:sgpa|cgpa|gpa)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Fail-count thresholds, anchored to the noun they count.
     *
     * [AT_LEAST_N] and [MORE_THAN_N] are unanchored, which is fine where they
     * are used -- inside a branch already guarded by the word "fail". It is not
     * fine in the composite branch, where an SGPA comparator sits in the same
     * sentence: "students with sgpa more than 8 who failed" gave sgpa >= 8 AND
     * at least 9 failed subjects, from one "more than 8" read twice. Both
     * numbers real, the answer nonsense, and no residual for the guard to see.
     */
    private const val COUNTED_NOUN = "(?:subject|paper|backlog|course)s?"
    private val AT_LEAST_N_SUBJECTS = Regex(
        "(?:at\\s*least|atleast|>=|minimum(?:\\s+of)?)\\s*$NUM\\s*(?:\\w+\\s+){0,2}$COUNTED_NOUN",
        RegexOption.IGNORE_CASE
    )
    private val MORE_THAN_N_SUBJECTS = Regex(
        "(?:more\\s+than|greater\\s+than|over|>)\\s*$NUM\\s*(?:\\w+\\s+){0,2}$COUNTED_NOUN",
        RegexOption.IGNORE_CASE
    )

    /** How many subjects a question demands be failed, or null. */
    private fun failedSubjectCount(q: String): Int? =
        AT_LEAST_N_SUBJECTS.find(q)?.let { num(it.groupValues[1]) }
            ?: N_OR_MORE.find(q)?.let { num(it.groupValues[1]) }
            ?: MORE_THAN_N_SUBJECTS.find(q)?.let { num(it.groupValues[1]) + 1 }
            ?: if (q.contains("fail") && q.contains("multiple")) 2 else null

    private fun firstNumber(m: MatchResult?): Double? =
        m?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toDoubleOrNull()

    private val C_FAIL = Regex("""\bfail(?:ed|s|ing|ure|ures)?\b|\bbacklogs?\b""", RegexOption.IGNORE_CASE)
    private val C_PASS = Regex("""\bpass(?:ed|es|ing)?\b|\bclear(?:ed)?\b""", RegexOption.IGNORE_CASE)
    private val C_RATE = Regex("""\bpercent(?:age)?\b|\brate\b|%""", RegexOption.IGNORE_CASE)
    private val C_AVERAGE = Regex("""\baverage\b|\bmean\b|\bavg\b""", RegexOption.IGNORE_CASE)
    private val C_RANKING = Regex(
        """\b(?:top|topper|toppers|highest|lowest|bottom|worst|best|rank)\b""",
        RegexOption.IGNORE_CASE
    )
    private val C_SUPPLEMENTARY = Regex("""\bsupplement\w*\b|\bre-?exam\w*\b|\bsupply\b""", RegexOption.IGNORE_CASE)
    private val C_ATTENDANCE = Regex("""\battendance\b|\bpresent\b|\babsent\w*\b""", RegexOption.IGNORE_CASE)
    private val C_SCHOLARSHIP = Regex("""\bscholarship\w*\b|\bfreeship\b|\bstipend\b""", RegexOption.IGNORE_CASE)
    private val C_PLACEMENT = Regex("""\bplace(?:d|ment|ments)\b|\brecruit\w*\b""", RegexOption.IGNORE_CASE)
    private val C_HOSTEL = Regex("""\bhostel\w*\b|\bwarden\b""", RegexOption.IGNORE_CASE)
    private val C_FEES = Regex("""\bfees?\b|\btuition\b""", RegexOption.IGNORE_CASE)

    /**
     * Deliberately NOT including "semester" or "year". This bundle is one
     * semester of one exam, so "which students failed this semester" is not a
     * narrowing at all, and treating it as one would attach a caveat to a
     * question that was fully answered.
     */
    private val C_COHORT = Regex(
        """\bdepartment\w*\b|\bbranch\b|\bdivision\b|\bsection\b|\bclass\b|\bcomputer\s+science\b""",
        RegexOption.IGNORE_CASE
    )
    /**
     * The bare word "grade" is not a constraint. "Which subject has the most
     * failing grades" is fully answered by subject_failure_counts, and
     * treating "grades" as unmodelled would attach a caveat to a correct
     * answer. What IS a constraint is a specific grade named beside it --
     * "the pass percentage for students with an AA grade" -- which no template
     * filters on. The scale comes from [Grades] rather than a second copy: a
     * duplicated list of these letters has already misclassified AB as a
     * failure across three subsystems once.
     */
    private val GRADE_TOKENS =
        (Grades.GRADE_POINTS.keys + Grades.AUDIT_GRADES).joinToString("|") { it.lowercase() }
    private val C_GRADE = Regex(
        """\b(?:$GRADE_TOKENS|[a-z][+\-])\s+grades?\b""" +
            """|\bgrades?\s+(?:of\s+)?(?:$GRADE_TOKENS|[a-z][+\-])\b""",
        RegexOption.IGNORE_CASE
    )

    // --- three questions the records answer and no template claimed ---------
    //
    // All three reached SqlTemplates on hardware, matched nothing, fell through
    // TabularIntent to a name search that found nobody, and were handed to the
    // TABULAR->FACT fallback -- which retrieved documents and, in the first
    // case, narrated one with total confidence. None of them is a routing
    // defect: each was already TABULAR by rule. What was missing was the query.

    /**
     * Who is on the roll with a clean sheet, in either of the two readings the
     * records support.
     *
     * "How many students have no backlogs" is a fail question with the sign
     * flipped, and every fail branch below reads the word "backlog" as a demand
     * for failures -- so it matched nothing at all and abstained while offering
     * placement documents as the closest material. Checked before those
     * branches because it shares their vocabulary and loses every tie.
     *
     * Not folded into `students_matching` with `minFailedSubjects = 0`: zero
     * failed subjects is a NOT EXISTS, not a `>= 0`, which every row satisfies.
     */
    private val NO_BACKLOG = Regex(
        """\b(?:no|zero|nil|without(?:\s+any)?|not\s+a\s+single)\s+(?:\w+\s+){0,1}""" +
            """(?:backlogs?|arrears?|ff|fails?|failures?|failed\s+subjects?)\b""" +
            """|\bbacklog[\s-]?free\b""" +
            """|\b(?:cleared|passed)\s+(?:all|every)\s+(?:\w+\s+){0,1}(?:subjects?|papers?|courses?)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Who did not sit the examination.
     *
     * "Which students did not appear for the exam" is the worst answer this app
     * has produced: it matched no template, fell to document retrieval, found
     * "register", "appearing" and "sit for" in the placement policy, and read
     * out the registration rule for placement drives as though it were the
     * answer. The records settle the question exactly -- `seat_cancelled` and
     * `is_supply` are 0 on all 369 rows -- and nothing was asking them.
     *
     * [ABOUT_STUDENTS] is the condition that keeps this off policy questions.
     * "How many days can I be absent" carries the same absence vocabulary and
     * is answered by the attendance policy, not by the results table; it names
     * no cohort, so it is not this question. The route itself is the other
     * guard: [match] runs only under TABULAR, and TABULAR is reachable only
     * from [RouteRules] -- [RoutePrototypes] holds vectors for FACT, LOCAL and
     * GLOBAL and cannot return it -- so "what happens if a student does not
     * appear for the exam" never gets this far.
     */
    private val NOT_APPEARED = Regex(
        """(?:\bnot\b|\bnever\b|n['’]t\b)[^.?!]{0,16}""" +
            """\b(?:appear\w*|attend\w*|sit|sat|writ\w*|turn\s+up|show\s+up)\b""" +
            """[^.?!]{0,24}\b(?:exams?|examination\w*|papers?|tests?)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ABSENT = Regex(
        """\babsent\w*\b|\bseat[\s-]?cancel\w*\b""" +
            """|\bmissed\s+(?:the\s+)?(?:exams?|examination\w*|papers?|tests?)\b""",
        RegexOption.IGNORE_CASE
    )

    /** A question about the cohort rather than about the person asking. */
    private val ABOUT_STUDENTS = Regex(
        """\bstudents?\b|\banyone\b|\banybody\b|\bnobody\b""", RegexOption.IGNORE_CASE
    )

    /** A ranking over subjects, which none of the three branches answers. */
    private val SUBJECT_SCOPED = Regex("""\bwhich\s+subjects?\b""", RegexOption.IGNORE_CASE)

    fun missedExam(query: String): Boolean {
        val q = query.lowercase()
        return (NOT_APPEARED.containsMatchIn(q) || ABSENT.containsMatchIn(q)) &&
            ABOUT_STUDENTS.containsMatchIn(q)
    }

    /**
     * An SGPA named as a value, not as a bound.
     *
     * "List students who scored 10 SGPA" abstained while "how many students
     * scored above 9.0 SGPA" answered the same true zero exactly, because every
     * SGPA branch in this file wants a comparator and this question has none.
     * [SGPA_COMPARATOR] is therefore the veto, not the trigger: with a
     * comparator present the question belongs to one of the threshold branches
     * above and this one must not touch it.
     *
     * Capped at the 0-10 scale so a roll number written next to the word "sgpa"
     * cannot be read as a grade point.
     */
    private val SGPA_VALUE = Regex(
        """(?:s?gpa|cgpa)\s*(?:of|is|:)?\s*(\d+(?:\.\d+)?)\b""" +
            """|\b(\d+(?:\.\d+)?)\s*(?:s?gpa|cgpa)\b""",
        RegexOption.IGNORE_CASE
    )
    private val SGPA_COMPARATOR = Regex(
        """\babove\b|\bbelow\b|\bunder\b|\bover\b|\bat\s*least\b|\batleast\b""" +
            """|\bmore\b|\bless\b|\bgreater\b|\blower\b|\bhigher\b|\bminimum\b|\bmaximum\b""" +
            """|\bhighest\b|\blowest\b|\btop\b|\bbottom\b|\bworst\b|\bbest\b|\brank\w*\b""" +
            """|\baverage\b|\bmean\b|\bbetween\b|\bexcept\b|>|<""",
        RegexOption.IGNORE_CASE
    )
    /** Asking for the students, or for how many of them there are. */
    private val LIST_OR_COUNT_CUE = Regex(
        """\blist\b|\bshow\b|\bname\b|\bnames\b|\bfind\b|\bdisplay\b|\bgive\b|\bany\b""" +
            """|\bwhich\b|\bwho\b|\bwhose\b|\bhow\s+many\b|\bcount\b|\bnumber\s+of\b""",
        RegexOption.IGNORE_CASE
    )

    /** The SGPA a question names as a value, if it names one on this scale. */
    fun namedSgpaValue(query: String): Double? {
        val q = query.lowercase()
        if (SGPA_COMPARATOR.containsMatchIn(q)) return null
        if (!LIST_OR_COUNT_CUE.containsMatchIn(q)) return null
        val v = firstNumber(SGPA_VALUE.find(q)) ?: return null
        return if (v in 0.0..10.0) v else null
    }

    /**
     * Everything in [Constraint] the question actually carries. Order is the
     * declaration order of the enum, so the caveat sentence reads the same way
     * every time.
     */
    fun constraintsIn(query: String): List<Constraint> {
        val q = query.lowercase()
        val out = ArrayList<Constraint>()
        if (SGPA_BELOW.containsMatchIn(q) || SGPA_ATLEAST.containsMatchIn(q)) out += Constraint.SGPA_THRESHOLD
        if (C_RANKING.containsMatchIn(q) && Regex("""\bs?gpa\b|\bcgpa\b""").containsMatchIn(q)) {
            out += Constraint.SGPA_RANKING
        }
        if (C_FAIL.containsMatchIn(q) && failedSubjectCount(q) != null) out += Constraint.FAIL_COUNT
        if (C_FAIL.containsMatchIn(q) || NEGATED_PASS.containsMatchIn(q)) out += Constraint.RESULT_FAIL
        if (C_PASS.containsMatchIn(q)) out += Constraint.RESULT_PASS
        if (C_RATE.containsMatchIn(q)) out += Constraint.RATE
        if (q.contains("subject") || SUBJECT_CODE.containsMatchIn(query)) out += Constraint.SUBJECT
        if (C_SUPPLEMENTARY.containsMatchIn(q)) out += Constraint.SUPPLEMENTARY
        if (C_AVERAGE.containsMatchIn(q)) out += Constraint.AVERAGE
        if (C_ATTENDANCE.containsMatchIn(q)) out += Constraint.ATTENDANCE
        if (C_SCHOLARSHIP.containsMatchIn(q)) out += Constraint.SCHOLARSHIP
        if (C_PLACEMENT.containsMatchIn(q)) out += Constraint.PLACEMENT
        if (C_HOSTEL.containsMatchIn(q)) out += Constraint.HOSTEL
        if (C_FEES.containsMatchIn(q)) out += Constraint.FEES
        if (C_COHORT.containsMatchIn(q)) out += Constraint.COHORT
        if (C_GRADE.containsMatchIn(q)) out += Constraint.LETTER_GRADE
        return out
    }


    /**
     * Words that narrow "how many students" to a subset the roster count
     * cannot answer. Presence of any of these means the bare count is the
     * wrong reply, so the query must fall through to retrieval or abstention.
     */
    private val QUALIFIERS = listOf(
        "grade", "cgpa", "mark", "attendance", "credit", "hostel", "scholarship",
        "placement", "branch", "department", "semester", "sem ", "year", "division",
        "with", "who", "having", "scored", "got ", "received", "enrolled in",
    )
    fun match(query: String): Match? {
        val q = query.lowercase()

        // Queries naming specific subject codes are targeted comparisons, not a
        // request for the full per-subject ranking.
        val namedSubjects = SUBJECT_CODE.containsMatchIn(query)

        // "subject" before "fail" scopes the question per-subject, so the
        // failed-most-subjects branch must not shadow subject_failure_counts.
        val subjectAsksFirst = q.contains("subject") && q.contains("fail") &&
            q.indexOf("subject") < q.indexOf("fail")

        // Averages first. "what is the average SGPA of students who failed"
        // contains "fail", and every fail branch below would claim it before
        // the word "average" was ever looked at. TabularIntent has had an
        // `average_sgpa` kind since the port with nothing routing to it, which
        // is why the question reached FACT and surfaced a Flash Crash paper.
        if (C_AVERAGE.containsMatchIn(q) && Regex("""\bs?gpa\b|\bcgpa\b""").containsMatchIn(q) &&
            !namedSubjects && !q.contains("subject")
        ) {
            val filter = when {
                NEGATED_PASS.containsMatchIn(q) || q.contains("fail") -> "FAIL"
                q.contains("pass") -> "PASS"
                else -> null
            }
            return Match("average_sgpa") { it.averageSgpa(filter) }
        }

        // An SGPA threshold together with a result or backlog condition is an
        // INTERSECTION, and the two branches that used to claim these answered
        // one side of it: "how many students below 6 SGPA also failed" (truth
        // 0) came back "35 students failed". Evaluated properly here, both
        // sides applied, and the empty result explained -- see
        // TabularQueries.countStudentsMatching, which reports why zero is zero.
        val sgpaBelow = firstNumber(SGPA_BELOW.find(q))
        val sgpaAtLeast = firstNumber(SGPA_ATLEAST.find(q))
        if (sgpaBelow != null || sgpaAtLeast != null) {
            val failCount = failedSubjectCount(q)
            val failed = failCount == null && C_FAIL.containsMatchIn(q)
            val passed = failCount == null && !failed && C_PASS.containsMatchIn(q)
            if (failCount != null || failed || passed) {
                return Match("students_matching") {
                    it.countStudentsMatching(
                        sgpaBelow = sgpaBelow,
                        sgpaAtLeast = sgpaAtLeast,
                        minFailedSubjects = failCount,
                        result = if (failed) "FAIL" else if (passed) "PASS" else null,
                    )
                }
            }
        }

        // Before every fail branch, because it is worded out of their
        // vocabulary and would lose each tie. See [NO_BACKLOG].
        if (NO_BACKLOG.containsMatchIn(q) && ABOUT_STUDENTS.containsMatchIn(q) &&
            !SUBJECT_SCOPED.containsMatchIn(q) && !namedSubjects
        ) return Match("no_backlogs") { it.studentsWithoutBacklogs() }

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

        // "how many students did not pass" fell through to the pass branch
        // and answered 334 -- the complement of the question asked. Normalise
        // the negated forms before anything can match on "pass".
        val negatedPass = NEGATED_PASS.containsMatchIn(q) || q.contains("unsuccessful")
        if (negatedPass && (q.contains("how many") || q.contains("number of") || q.contains("count"))) {
            return Match("result_count") { it.resultCount("FAIL") }
        }

        val pct = q.contains("percent") || q.contains("percentage") || q.contains("%") || q.contains("rate")
        // A rate scoped to subjects is a per-subject ranking, not the
        // college-wide figure. These are genuinely different answers here:
        // BTCOC502 has the most failures (16) while BTAIHM503B has the worst
        // pass rate (90.9%), because 66 students take the second and 303 the
        // first. Checked before the college-wide branch, which used to swallow
        // "which subject has the lowest pass rate" and reply "90.5%".
        if (pct && q.contains("subject") && !namedSubjects && (q.contains("pass") || q.contains("fail"))) {
            val low = q.contains("lowest") || q.contains("worst") || q.contains("least") ||
                q.contains("bottom")
            val high = q.contains("highest") || q.contains("most") || q.contains("best") ||
                q.contains("top")
            // "lowest fail rate" and "lowest pass rate" are opposite ends of
            // the same ranking, so the framing has to flip the sort.
            val failFramed = q.contains("fail") && !q.contains("pass")
            val worstFirst = when {
                !failFramed && high -> false
                failFramed && low -> false
                else -> true
            }
            return Match("subject_pass_rates") { it.subjectPassRates(worstFirst) }
        }

        // The same ranking, asked without a rate word in the question. "Which
        // subject do students struggle with most" carries no "pass", no "fail"
        // and no "rate", so every branch above declines it, and it used to fall
        // through the whole file to GLOBAL retrieval and narrate an attendance
        // notice. Difficulty is a pass rate whether or not the student says so.
        //
        // Checked AFTER the branch above so an explicit framing keeps its own
        // sort: "which subject has the highest pass rate" must not be answered
        // worst-first merely because "worst" is in the difficulty vocabulary.
        if (!namedSubjects && RouteRules.SUBJECT_DIFFICULTY.containsMatchIn(q)) {
            return Match("subject_pass_rates") { it.subjectPassRates(worstFirst = true) }
        }

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

        // A value, not a bound. Sits beside count_sgpa_at_least because they
        // are the same question with and without a comparator, and after it so
        // an explicit threshold is never re-read as an exact match.
        namedSgpaValue(q)?.let { v ->
            if (!namedSubjects && !q.contains("subject") && !C_FAIL.containsMatchIn(q)) {
                return Match("students_with_sgpa") { it.studentsWithSgpa(v) }
            }
        }

        // Before supplementary_count and student_count, both of which would
        // otherwise claim it: "how many students were absent" narrows the
        // roster by something student_count does not model, and answering
        // "there are 369 students" is the confident non-answer this branch
        // exists to replace.
        if (missedExam(q)) return Match("exam_attendance") { it.examAttendance() }

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
                "review", "backlog", "topper", "most", "least").none { q.contains(it) } &&
            // A bare roster count is right only when nothing narrows the set.
            // "How many students got an A+ grade" landed here and answered
            // "There are 369 students" -- a confident reply to a question
            // nobody asked.
            QUALIFIERS.none { q.contains(it) }
        ) return Match("student_count") { it.studentCount() }

        // Last, and last on purpose. "How is the college doing overall this
        // semester" is a real question with a real answer -- 334 of 369 passed,
        // 90.5% -- and the app said "I don't have enough information to answer
        // that", because GLOBAL retrieval went looking for a state-of-the-
        // college passage the corpus does not contain. But every narrower
        // question above is a better answer to itself than a summary is, so
        // this may only claim what nothing else would.
        if (RouteRules.isCohortOverview(q)) {
            return Match("semester_overview") { it.semesterOverview() }
        }

        return null
    }
}
