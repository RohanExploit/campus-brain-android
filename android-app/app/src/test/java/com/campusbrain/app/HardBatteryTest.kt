package com.campusbrain.app

import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.answer.AnswerComposer
import com.campusbrain.app.data.RetrievedChunk
import com.campusbrain.app.retrieval.CompoundQuestion
import com.campusbrain.app.retrieval.SqlTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four defects the hard battery found, pinned at the layers that can run
 * without a device.
 *
 * What these tests can and cannot see is worth stating, because it decides how
 * much a green run here is worth. Template SELECTION, constraint detection,
 * compound splitting and threshold scoping are all pure functions and are
 * covered exactly. The SQL those templates run is not: [TabularQueries] needs
 * a BrainDb, so every expected number below was computed against the shipped
 * bundle with sqlite3 and is recorded in the comment beside it. A test here
 * cannot tell you the number is right; it can only tell you the question was
 * routed to the query that produces it.
 */
class SqlConstraintGuardTest {

    private fun name(q: String) = SqlTemplates.match(q)?.name
    private fun resolve(q: String) = SqlTemplates.resolve(q)

    // --- defect 1: constraints dropped in silence -------------------------

    @Test fun `an SGPA threshold crossed with a fail filter is evaluated, not halved`() {
        // Observed: "35 students failed their semester examination." The truth
        // is 0, and the app never evaluated an intersection at all -- it
        // matched result_count on the word "failed" and discarded the rest.
        //
        // 0 here is structural, not incidental: 0 of the 35 FAIL rows carry an
        // SGPA, so the intersection is empty by construction. That is what
        // TabularQueries.countStudentsMatching explains in its zero branch.
        assertEquals("students_matching", name("how many students below 6 SGPA also failed"))
        assertTrue(resolve("how many students below 6 SGPA also failed")
            is SqlTemplates.Resolution.Answered)
    }

    @Test fun `a subject-count threshold crossed with an SGPA threshold is evaluated too`() {
        // Observed: a list of the 16 students with >= 2 backlogs, under a
        // heading that answered only half the question. Truth is 0.
        val q = "how many students failed more than one subject and have SGPA below 6"
        assertEquals("students_matching", name(q))
        assertTrue(resolve(q) is SqlTemplates.Resolution.Answered)
        // And the split must NOT fire on this one -- "have SGPA below 6" is a
        // constraint, not a second question.
        assertEquals(listOf(q), CompoundQuestion.split(q))
    }

    @Test fun `a constraint no template models is declared, never dropped`() {
        val r = resolve("what is the pass percentage for students with 75 percent attendance")
        assertTrue("attendance was silently discarded", r is SqlTemplates.Resolution.Partial)
        val partial = r as SqlTemplates.Resolution.Partial
        assertTrue(partial.ignored.contains(SqlTemplates.Constraint.ATTENDANCE))
        assertTrue(SqlTemplates.caveat(partial.ignored).contains("attendance"))
    }

    @Test fun `department scoping is a constraint the tables cannot honour`() {
        val r = resolve("how many students in the computer science department failed")
        assertTrue(r is SqlTemplates.Resolution.Partial)
        assertTrue((r as SqlTemplates.Resolution.Partial).ignored
            .contains(SqlTemplates.Constraint.COHORT))
    }

    @Test fun `the guard is a closed vocabulary, not a leftover-word test`() {
        // Every one of these is answered correctly today and carries words no
        // template models. A "did the template consume the whole question"
        // test would refuse all of them. Only a constraint that would change
        // the SQL is allowed to count.
        listOf(
            "is the pass rate good or bad",
            "which subject has the most failures",
            "how many students are there",
            "how many students failed two or more subjects",
            "who are the toppers",
            "what is the pass percentage",
            // "grades" here is the noun of the answer, not a filter on it.
            // A bare-word grade rule would caveat this one.
            "which subject has the most failing grades",
        ).forEach {
            assertTrue("$it must resolve cleanly, got ${resolve(it)}",
                resolve(it) is SqlTemplates.Resolution.Answered)
        }
    }

    @Test fun `the single-constraint control is untouched`() {
        // "how many students have SGPA below 6" answers 25 through the intent
        // cascade, not through a template. The guard sits on the template path
        // only, so it structurally cannot reach this.
        assertEquals(SqlTemplates.Resolution.None, resolve("how many students have SGPA below 6"))
    }

    @Test fun `constraint detection ignores semester and year`() {
        // The bundle is one semester of one examination, so "this semester" is
        // not a narrowing. Treating it as one would caveat a fully answered
        // question.
        assertTrue(resolve("which students failed this semester") is SqlTemplates.Resolution.Answered ||
            resolve("which students failed this semester") == SqlTemplates.Resolution.None)
        assertFalse(SqlTemplates.constraintsIn("which students failed this semester")
            .contains(SqlTemplates.Constraint.COHORT))
    }

    @Test fun `one comparator is not read as two different thresholds`() {
        // The composite branch put an SGPA comparator and a subject-count
        // comparator in the same sentence, and the unanchored fail-count
        // patterns read the SGPA one as well: "students with sgpa more than 8
        // who failed" asked for SGPA >= 8 AND at least 9 failed subjects, from
        // one "more than 8" counted twice. Both numbers real, the question
        // unanswered, and no residual left for the guard to notice.
        val q = "students with sgpa more than 8 who failed"
        assertEquals("students_matching", name(q))
        assertTrue(SqlTemplates.constraintsIn(q).contains(SqlTemplates.Constraint.SGPA_THRESHOLD))
        assertFalse("the SGPA comparator was counted as a subject count",
            SqlTemplates.constraintsIn(q).contains(SqlTemplates.Constraint.FAIL_COUNT))
        // "more than" has to work in both word orders, or the threshold goes
        // undetected and the fail-count pattern claims the number unopposed.
        assertTrue(SqlTemplates.constraintsIn("how many students with more than 8 sgpa failed")
            .contains(SqlTemplates.Constraint.SGPA_THRESHOLD))
    }

    @Test fun `subject-count thresholds still parse where a noun anchors them`() {
        // Anchoring must not cost the three phrasings already paid for.
        assertEquals("students_failed_at_least", name("students with more than 2 backlogs"))
        assertEquals("students_failed_at_least", name("students who failed at least 3 subjects"))
        assertEquals("students_failed_at_least", name("how many students failed two or more subjects"))
        listOf(
            "students with more than 2 backlogs",
            "students who failed at least 3 subjects",
            "how many students failed two or more subjects",
        ).forEach {
            assertTrue(it, SqlTemplates.constraintsIn(it).contains(SqlTemplates.Constraint.FAIL_COUNT))
        }
    }

    @Test fun `the intent cascade declares its dropped constraints too`() {
        // "how many students below 6 SGPA are in the hostel" matches no
        // template at all, falls to TabularIntent.below_sgpa, and lists every
        // one of the 25 without ever mentioning the hostel. Same defect as
        // the template path, reached through the other door.
        val q = "how many students below 6 SGPA are in the hostel"
        assertEquals(SqlTemplates.Resolution.None, resolve(q))
        val ignored = SqlTemplates.unmodelled(SqlTemplates.INTENT_TEMPLATES["below_sgpa"], q)
        assertTrue(ignored.contains(SqlTemplates.Constraint.HOSTEL))
        // The control the guard must never touch: one constraint, modelled.
        assertTrue(SqlTemplates.unmodelled(
            SqlTemplates.INTENT_TEMPLATES["below_sgpa"], "how many students have SGPA below 6"
        ).isEmpty())
        // A single-student lookup narrows by nothing and opts out entirely.
        assertTrue(SqlTemplates.unmodelled(
            SqlTemplates.INTENT_TEMPLATES["name_search"], "marksheet of Rohan Gaikwad"
        ).isEmpty())
    }

    @Test fun `a named grade is a constraint, the word grade alone is not`() {
        assertTrue(SqlTemplates.constraintsIn("pass percentage for students with an AA grade")
            .contains(SqlTemplates.Constraint.LETTER_GRADE))
        assertFalse(SqlTemplates.constraintsIn("which subject has the most failing grades")
            .contains(SqlTemplates.Constraint.LETTER_GRADE))
    }

    // --- defect 3: no average template ------------------------------------

    @Test fun `the average reaches SQL instead of a Flash Crash paper`() {
        // Observed: abstained, and offered "Methodology, Empirical Anchoring:
        // Microstructural Reconstruction of the 2010 Flash Crash" as the
        // nearest material. TabularIntent has had an average_sgpa kind since
        // the port with nothing routing to it.
        //
        // sqlite3: SELECT AVG(sgpa), COUNT(sgpa), COUNT(*) FROM students
        //          -> 7.343592814371247 | 334 | 369
        assertEquals("average_sgpa", name("what is the average SGPA"))
        assertEquals("average_sgpa", name("average sgpa of all students"))
    }

    @Test fun `the average over failing students goes to the same template`() {
        // sqlite3: ... WHERE result='FAIL' -> NULL | 0 | 35. NULL, not 0.0.
        // The template's null branch says there is no average rather than
        // formatting a null into "0.00".
        assertEquals("average_sgpa", name("what is the average SGPA of students who failed"))
    }

    // --- defect 1, the superlative case: rate is not count -----------------

    @Test fun `lowest pass rate is a per-subject ranking, not the college figure`() {
        // Observed: "Pass percentage: 90.5% (334 of 369 students passed)" --
        // a real figure answering a different question.
        //
        // sqlite3, audit rows excluded:
        //   BTAIHM503B 60/66 = 90.9%  (worst rate)
        //   BTCOC502  287/303 = 94.7% (most failures, 16)
        // Two different subjects, which is exactly why the two probes exist.
        assertEquals("subject_pass_rates", name("which subject has the lowest pass rate"))
        assertEquals("subject_pass_rates", name("which subject has the highest failure rate"))
        assertEquals("subject_failure_counts", name("which subject has the most failures"))
        // The college-wide question is untouched.
        assertEquals("pass_percentage", name("what is the pass percentage"))
        assertEquals("fail_percentage", name("what is the fail percentage"))
    }
}

/**
 * Defect 4: a question that is two questions.
 *
 * The interesting cases are the ones that must NOT split. Splitting a
 * conjunction of constraints produces two numbers and neither is the
 * intersection asked for -- the same failure the constraint guard exists to
 * stop, arrived at from the opposite direction.
 */
class CompoundQuestionTest {

    @Test fun `two questions joined by and are two questions`() {
        assertEquals(
            listOf("how many students failed", "what is the pass percentage"),
            CompoundQuestion.split("how many students failed and what is the pass percentage")
        )
        assertEquals(
            listOf("what is the minimum attendance", "what happens if I miss it"),
            CompoundQuestion.split("what is the minimum attendance and what happens if I miss it")
        )
    }

    @Test fun `a conjunction of constraints is one question`() {
        listOf(
            "how many students failed more than one subject and have SGPA below 6",
            "list all students and their sgpa",
            "which students failed and have backlogs",
            "students with more than 2 backlogs and low attendance",
        ).forEach {
            assertEquals("must not split: $it", listOf(it), CompoundQuestion.split(it))
        }
    }

    @Test fun `an ordinary question is returned unchanged`() {
        listOf(
            "what is the pass percentage",
            "how much attendance do I need",
            "am I eligible for a scholarship if my attendance is 70 percent",
        ).forEach { assertEquals(listOf(it), CompoundQuestion.split(it)) }
    }

    @Test fun `a half whose subject is a pronoun borrows it from the first half`() {
        // "what happens if I miss it" retrieves nothing useful on its own --
        // "it" is the whole subject and it was left in the other half.
        val carried = CompoundQuestion.carryOver(
            "what happens if I miss it", "what is the minimum attendance"
        )
        assertTrue(carried.contains("attendance"))
        // A half that names its own subject is left exactly as written.
        assertEquals(
            "what is the pass percentage",
            CompoundQuestion.carryOver("what is the pass percentage", "how many students failed")
        )
    }
}

/**
 * Defect 2: a threshold applied outside the scope that owns it.
 *
 * Every fixture is verbatim `content` from the shipped bundle, double spaces
 * and all, with its chunk id in the name:
 *
 *   sqlite3 brain.db "SELECT content FROM chunks WHERE id = 404"
 */
class ThresholdScopeTest {

    private fun chunk(id: Long, doc: String, section: String?, content: String) =
        RetrievedChunk(id, doc, section, content, 0.0)

    /** chunks.id = 398 -- the eligibility matrix, five cells per row. */
    private val matrix = chunk(
        398, "svc_01_scholarship_eligibility_matrix.md", "Eligibility at a glance",
        "| Rajarshi Shahu Maharaj Freeship for EBC    | Economically Backward Class (open " +
            "category, non-creamy layer)                     | Rs. 1,00,000 per annum | 75%" +
            "               | No minimum  |\n" +
            "| KRIET Alumni Merit Grant                   | All categories, merit-based" +
            "                                                       | No ceiling             " +
            "| 80%               | 8.50        |\n" +
            "| Sports and Cultural Excellence Scholarship | Students with state or national " +
            "level representation in sports or cultural events | No ceiling             | 70%" +
            "               | No minimum  |"
    )

    /** chunks.id = 404 -- the note that states the general rule and the exception together. */
    private val schemeNote = chunk(
        404, "svc_01_scholarship_eligibility_matrix.md",
        "Konkan Ratna Institute of Engineering and Technology",
        "A separate procedure notice for each scheme above states its own notice number, the " +
            "exact steps to apply, and what happens after submission.  \nNote: the Rajarshi " +
            "Shahu Maharaj Merit Scholarship and the Rajarshi Shahu Maharaj Freeship for EBC " +
            "are  two  distinct  schemes  with  two  distinct  income  ceilings;  read  the  " +
            "Eligible  Category  and  Income Ceiling  columns  carefully  rather  than  the  " +
            "scheme  name  alone.  The  Sports  and  Cultural  Excellence Scholarship uses a " +
            "minimum attendance of 70%, lower than the institute's general 75% minimum in the " +
            "Attendance  Policy  -  that  relaxation  applies  only  to  this  scheme  and  to " +
            " the  sports/cultural  benefits described in a separate notice."
    )

    /** chunks.id = 426 -- the scheme's own procedure notice, the 70% and nothing else. */
    private val sportsProcedure = chunk(
        426, "svc_06_scholarship_procedure_sports_and_cultural_excellence_scholarship.md",
        "Subject: How to Apply: Sports and Cultural Excellence Scholarship",
        "Sports  and  Cultural  Excellence  Scholarship is  open  to  Students  with  state  " +
            "or  national  level representation in sports or cultural events with family " +
            "income up to No ceiling, a minimum attendance of 70%  and  a  minimum  CGPA  of  " +
            "No  minimum.  The  application  window  is  1  September  2026  to  30 September " +
            "2026. Apply through: Internal - apply at the Sports Complex Office ."
    )

    /** chunks.id = 150 -- the attendance rule, as the regression control. */
    private val attendanceRule = chunk(
        150, "24_attendance_policy.md", "Subject: Attendance Policy, Academic Year 2026-27",
        "A  minimum  of  75%  attendance,  calculated  subject-wise,  is  required  to  be  " +
            "eligible  to  appear  for  the End-Semester examination without condonation. " +
            "The attendance tiers below apply.  \n" +
            "| Attendance Range   | Consequence   |\n" +
            "|--------------------|---------------|\n" +
            "| Below 65%          | Debarred outright from the End-Semester examination for " +
            "the term; no condonation is possible at this tier.                    |"
    )

    @Test fun `a scheme's relaxation is not read as the institute rule`() {
        // The observed answer was "Yes — 70% meets the 70% minimum required."
        // That 70% is the Sports and Cultural Excellence Scholarship's, and
        // this corpus says so in the same sentence it states it. Telling a
        // student they qualify when they do not is the worst direction for
        // this failure to run in.
        val out = AnswerComposer.compose(
            "am I eligible for a scholarship if my attendance is 70 percent",
            listOf(sportsProcedure, schemeNote, matrix),
        )
        assertFalse("said yes against a scheme-only relaxation: ${out.lead}",
            out.lead.startsWith("Yes"))
        assertTrue("must judge against the institute's 75%: ${out.lead}", out.lead.contains("75"))
        assertTrue("must name the scheme the 70% belongs to: ${out.lead}",
            out.lead.contains("Sports"))
    }

    @Test fun `with only scheme-specific figures the answer is which scheme, with numbers`() {
        // Retrieval can easily return the matrix and not the note. Every
        // minimum there is a scheme's own; there is no general rule in the
        // text to fall back on, so a verdict would be picking one row.
        val out = AnswerComposer.compose(
            "am I eligible for a scholarship if my attendance is 70 percent",
            listOf(matrix),
        )
        assertFalse(out.lead.startsWith("Yes —"))
        assertTrue(out.lead, out.lead.contains("depends on the scheme"))
        assertTrue(out.lead, out.lead.contains("75%") && out.lead.contains("80%") &&
            out.lead.contains("70%"))
    }

    @Test fun `naming the scheme does license its own threshold`() {
        // The relaxation is real. It is usable the moment the student says
        // which scheme they mean -- refusing then would be the opposite error.
        val out = AnswerComposer.compose(
            "am I eligible for the sports and cultural excellence scholarship with 70% attendance",
            listOf(matrix),
        )
        assertTrue("named scheme must be judged on its own rule: ${out.lead}",
            out.lead.startsWith("Yes —"))
        assertTrue(out.lead.contains("70%"))
    }

    @Test fun `the attendance ruling that already worked is unchanged`() {
        // Regression pin. The scoping rewrite replaced the line that produced
        // this verdict, so it has to be re-proved rather than assumed.
        val out = AnswerComposer.compose(
            "can I write the exam with 60% attendance",
            listOf(attendanceRule),
        )
        assertTrue("expected a refusal, got: ${out.lead}", out.lead.startsWith("No —"))
        assertTrue(out.lead.contains("75%"))
    }

    @Test fun `thresholds carry their scope out of the matrix rows`() {
        // parseBands cannot read these: its PIPE_ROW is anchored to a two-cell
        // row and the matrix has five. Without a separate row reader the
        // "depends which scheme" answer has no numbers to offer.
        val found = AnswerCheck.requiredMinimums(matrix.content, emptyList())
        assertEquals(3, found.size)
        assertTrue(found.all { it.scope != null })
        assertEquals(
            listOf(70.0, 75.0, 80.0),
            found.map { it.value }.sorted()
        )
        assertTrue(found.first { it.value == 70.0 }.scope!!.contains("Sports"))
    }

    @Test fun `the general figure quoted inside a relaxation stays general`() {
        // Both corpus sentences state the institute figure inside the sentence
        // that relaxes it: "lower than the institute's general 75% minimum".
        // That 75 is the rule; the 70 beside it is not.
        val found = AnswerCheck.requiredMinimums(schemeNote.content, emptyList())
        assertEquals(setOf(75.0), found.filter { it.scope == null }.map { it.value }.toSet())
        assertEquals(setOf(70.0), found.filter { it.scope != null }.map { it.value }.toSet())
    }

    @Test fun `a policy stating one plain minimum still yields one general threshold`() {
        val found = AnswerCheck.requiredMinimums(attendanceRule.content, emptyList())
        assertTrue(found.isNotEmpty())
        assertTrue("the attendance policy's 75% is nobody's exception",
            found.all { it.scope == null })
        assertEquals(setOf(75.0), found.map { it.value }.toSet())
    }
}

/**
 * Two failures found on hardware after the batch above: the most obvious
 * scholarship question in the corpus abstaining, and citations naming a
 * signatory instead of a document.
 */
class ProvenanceAndTopicTest {

    private fun chunk(id: Long, doc: String, section: String?, content: String) =
        com.campusbrain.app.data.RetrievedChunk(id, doc, section, content, 0.0)

    /** chunks.id = 407 -- one scheme's own notice, verbatim. */
    private val postMatric = chunk(
        407, "svc_02_scholarship_procedure_post-matric_scholarship_for_sc_st_students.md",
        "Subject: How to Apply: Post-Matric Scholarship for SC/ST Students",
        "Post-Matric Scholarship for SC/ST Students is open to SC, ST with family income up " +
            "to Rs. 2,50,000 per  annum,  a  minimum  attendance  of  75%  and  a  minimum  " +
            "CGPA  of  No  minimum.  The  application window  is  1  September  2026  to  31  " +
            "October  2026.  Apply  through: National  Scholarship  Portal  scholarships.gov.in ."
    )

    @Test fun `a plural question finds a singular corpus`() {
        // Observed: "I don't have enough information to answer that. The
        // closest material in the corpus is Subject: How to Apply: Post-Matric
        // Scholarship for SC/ST Students" -- it retrieved scholarship notices
        // and declared they did not address a question about scholarships.
        //
        // Two independent causes, both here. "available" was a required
        // content word no notice contains, and "scholarships" was compared to
        // a corpus that only ever writes "Scholarship".
        assertEquals(listOf("scholarships"), AnswerCheck.contentTerms("What scholarships are available?"))
        val out = AnswerComposer.compose("What scholarships are available?", listOf(postMatric))
        assertFalse("abstained on the corpus's own subject: ${out.reason}", out.abstained)
        assertTrue(out.lead, out.lead.contains("Scholarship"))
    }

    @Test fun `plural and singular are the same word for topic scoring`() {
        // The fix is a search key, not a rewritten question: contentTerms
        // still reports what was asked, because the trace and the pinned
        // tests read it.
        assertEquals(listOf("students", "failed"), AnswerCheck.contentTerms("how many students failed"))
    }

    @Test fun `a citation names the document, not whoever signed it`() {
        // Observed: an answer about scholarships cited "Mrs. Deepali
        // Ghorpade". That is the chunk's section, and at the foot of every
        // circular the preceding heading is the signatory.
        val label = com.campusbrain.app.data.DocTitles.citation(
            "Scholarship Eligibility Matrix", "Mrs. Deepali Ghorpade"
        )
        assertEquals("Scholarship Eligibility Matrix", label)
        // The letterhead heads a chunk in nearly every college document and
        // so identifies none of them.
        assertEquals(
            "Attendance Policy, Academic Year 2026-27",
            com.campusbrain.app.data.DocTitles.citation(
                "Attendance Policy, Academic Year 2026-27",
                "Konkan Ratna Institute of Engineering and Technology"
            )
        )
    }

    @Test fun `a section that says something is kept beside the title`() {
        assertEquals(
            "Student Handbook — 3. Hostel Rules",
            com.campusbrain.app.data.DocTitles.citation("Student Handbook", "3. Hostel Rules")
        )
        // No " > " may survive: the answer UI renders a citation with
        // substringAfterLast(" > "), which would throw the document away.
        val paper = com.campusbrain.app.data.DocTitles.citation(
            "RAG-MicroSim Framework",
            "RAG-MicroSim: A Hybrid Retrieval-Augmented Generation Framework > Abstract"
        )
        assertFalse(paper.contains(" > "))
        assertTrue(paper.contains("Abstract"))
    }

    @Test fun `the Subject heading is the document's title`() {
        assertEquals(
            "Attendance Policy, Academic Year 2026-27",
            com.campusbrain.app.data.DocTitles.subjectTitle(
                "Subject: Attendance Policy, Academic Year 2026-27"
            )
        )
        // Not every heading is a subject line, and a paragraph is not a title.
        assertEquals(null, com.campusbrain.app.data.DocTitles.subjectTitle("Mrs. Pallavi Chitnis"))
        assertEquals(null, com.campusbrain.app.data.DocTitles.subjectTitle("Subject: X"))
    }
}
