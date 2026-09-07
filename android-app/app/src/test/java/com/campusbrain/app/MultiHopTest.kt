package com.campusbrain.app

import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.answer.AnswerComposer
import com.campusbrain.app.data.RetrievedChunk
import com.campusbrain.app.retrieval.CompoundQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-hop questions: the ones whose answer is a rule in one document
 * about a situation described in another, and which [AnswerCheck.Need] had no
 * shape for.
 *
 * Every fixture is verbatim `content` from the shipped bundle, chunk id in the
 * name, exactly as [AnswerCheckTest] does it:
 *
 *   sqlite3 brain.db "SELECT content FROM chunks WHERE id = 403"
 *
 * The defect these pin is one bug with three faces. Between [Need.ELIGIBILITY]
 * -- only claimed when the question states a number -- and [Need.OTHER] --
 * no shape enforced at all -- there was nothing, so every numberless multi-hop
 * question was decided by topic overlap alone. Topic overlap cannot tell a
 * rule from the paperwork filed beside it in the same document, and all three
 * measured failures are that one confusion:
 *
 *  - "can a student with a backlog apply for the merit scholarship" returned
 *    the procedure that runs AFTER a successful application (chunk 424).
 *  - "what happens to my scholarship if I am debarred for attendance"
 *    returned one scheme's attendance relaxation -- a ruling on a question
 *    nobody asked. That question is genuinely unanswerable from this corpus,
 *    and the fix is that it says so.
 *  - "what happens if I miss it" returned "A separate procedure notice ...
 *    states ... what happens after submission", which is the nearest thing in
 *    the corpus to the WORDS of the question and the furthest from its answer.
 */
class MultiHopTest {

    private fun chunk(id: Long, doc: String, section: String?, content: String) =
        RetrievedChunk(id, doc, section, content, 0.0)

    // --- fixtures ---------------------------------------------------------

    /** chunks.id = 150 -- the rule sentence plus the Below-65% tier row. */
    private val attendancePolicyRule = chunk(
        150, "24_attendance_policy.md", "Subject: Attendance Policy, Academic Year 2026-27",
        "A  minimum  of  75%  attendance,  calculated  subject-wise,  is  required  to  be  eligible" +
            "  to  appear  for  the End-Semester examination without condonation. " +
            "The attendance tiers below apply.  \n" +
            "| Attendance Range   | Consequence   |\n" +
            "|--------------------|---------------|\n" +
            "| Below 65%          | Debarred outright from the End-Semester examination for the " +
            "term; no condonation is possible at this tier.                    |"
    )

    /** chunks.id = 151 -- the other two tier rows, in a different chunk. */
    private val attendancePolicyTiers = chunk(
        151, "24_attendance_policy.md", "Subject: Attendance Policy, Academic Year 2026-27",
        "| 65% to 74%         | Placed on the defaulter list and notified in writing to the " +
            "parent/guardian; may apply for condonation (see CONDONATION_PROCEDURE) to be " +
            "permitted to sit the End-Semester examination. |\n" +
            "| 75% and above      | No action; eligible to appear for the End-Semester " +
            "examination without condonation.        |  \n" +
            "Medical  leave  of  up  to  15  days  is  accepted  with:  Certificate  from  a  " +
            "Registered  Medical  Practitioner, submitted  within  7  days  of  resuming  " +
            "classes.  Students  on  the  defaulter  list  may  appeal  to  the Attendance " +
            "Review Committee - see the Defaulter List Procedure (KRIET/ATT/2026-27/002) and " +
            "the Condonation Procedure (KRIET/ATT/2026-27/003)."
    )

    /**
     * chunks.id = 155 -- the SAME two tier rows, in the Defaulter List
     * Procedure. Present so the dedupe in [AnswerCheck.tierConsequences] has
     * something to dedupe: a retrieval pass that returns both documents used
     * to be able to print each tier twice.
     */
    private val defaulterProcedureTiers = chunk(
        155, "25_attendance_defaulter_procedure.md",
        "Subject: Procedure for Publication of the Attendance Defaulter List",
        "The  Attendance  Coordinator  publishes  a  subject-wise  defaulter  list  on  the  " +
            "notice  board  once  every month, listing every student whose counted attendance " +
            "in any subject falls in the tiers below.  \n" +
            "| Attendance Range   | Consequence   |\n" +
            "|--------------------|---------------|\n" +
            "| Below 65%          | Debarred outright from the End-Semester examination for the " +
            "term; no condonation is possible at this tier.                    |"
    )

    /**
     * chunks.id = 403 -- the second matrix table, five cells per row. This is
     * the row that answers the backlog question, and the reason
     * [AnswerCheck.schemeConditions] exists: [AnswerCheck.parseBands] cannot
     * read it, its PIPE_ROW being anchored to two cells.
     */
    private val applyMatrix = chunk(
        403, "svc_01_scholarship_eligibility_matrix.md",
        "Konkan Ratna Institute of Engineering and Technology",
        "| KRIET Alumni Merit Grant                   | Previous two semester marksheets; " +
            "No-backlog declaration                                              | " +
            "1 September 2026 to 20 September 2026 | Mrs. Deepali Ghorpade | Credited to the " +
            "tuition-fee ledger before the following semester's fee due date |\n" +
            "| Sports and Cultural Excellence Scholarship | Selection or participation " +
            "certificate from the state or national association; No-backlog declaration | " +
            "1 September 2026 to 30 September 2026 | Mr. Ganesh Thorve     | Within 60 days of " +
            "application window closure                                    |  \n" +
            "A separate procedure notice for each scheme above states its own notice number, " +
            "the exact steps to apply, and what happens after submission."
    )

    /**
     * chunks.id = 424 -- the sentence that used to win the backlog question.
     * It is about the same scheme and it answers a different question.
     */
    private val alumniGrantProcedure = chunk(
        424, "svc_05_scholarship_procedure_kriet_alumni_merit_grant.md", "Steps to apply",
        "After  submission: The  Scholarship  SPOC  verifies  the  CGPA  and  no-backlog  " +
            "declaration against examination records within 10 working days; the grant is " +
            "credited directly to the tuition-fee ledger before the following semester's fee " +
            "due date, so no separate disbursement step is needed"
    )

    /**
     * chunks.id = 404 -- the note that used to be offered as the answer to
     * "what happens to my scholarship if I am debarred". It is a true sentence
     * about a different question.
     */
    private val relaxationNote = chunk(
        404, "svc_01_scholarship_eligibility_matrix.md",
        "Konkan Ratna Institute of Engineering and Technology",
        "A separate procedure notice for each scheme above states its own notice number, the " +
            "exact steps to apply, and what happens after submission.  \n" +
            "Note: the Rajarshi Shahu Maharaj Merit Scholarship and the Rajarshi Shahu Maharaj " +
            "Freeship for EBC are  two  distinct  schemes  with  two  distinct  income  " +
            "ceilings;  read  the  Eligible  Category  and  Income Ceiling  columns  carefully " +
            " rather  than  the  scheme  name  alone.  The  Sports  and  Cultural  Excellence " +
            "Scholarship uses a minimum attendance of 70%, lower than the institute's general " +
            "75% minimum in the Attendance  Policy  -  that  relaxation  applies  only  to  " +
            "this  scheme  and  to  the  sports/cultural  benefits described in a separate notice."
    )

    /** chunks.id = 52 -- the late fee, which the Rs. split made unreachable. */
    private val feeNotice = chunk(
        52, "07_notice_fee_payment.md", "Subject: Odd-Term 2026-27 Tuition, Hostel and Bus Fee Payment",
        "| Zone                         | Fee per Semester   |\n" +
            "|------------------------------|--------------------|\n" +
            "| Ratnagiri city (up to 10 km) | Rs. 6,000          |  \n" +
            "A late fee of Rs. 50 per day applies after the due date, capped at Rs. 2,000. " +
            "Payment may be made through:"
    )

    /** chunks.id = 407 -- the post-matric rule, decapitated by the same split. */
    private val postMatricRule = chunk(
        407, "svc_02_scholarship_procedure_post-matric_scholarship_for_sc_st_students.md",
        "Subject: How to Apply: Post-Matric Scholarship for SC/ST Students",
        "Post-Matric Scholarship for SC/ST Students is open to SC, ST with family income up to " +
            "Rs. 2,50,000 per  annum,  a  minimum  attendance  of  75%  and  a  minimum  CGPA " +
            " of  No  minimum.  The  application window  is  1  September  2026  to  31  " +
            "October  2026."
    )

    // --- 1. the classifier -------------------------------------------------

    @Test fun `a consequence question is no longer decided by topic overlap alone`() {
        assertEquals(AnswerCheck.Need.CONSEQUENCE,
            AnswerCheck.parse("what happens if I miss it attendance").need)
        assertEquals(AnswerCheck.Need.CONSEQUENCE,
            AnswerCheck.parse("what happens to my scholarship if I am debarred for attendance").need)
        assertEquals(AnswerCheck.Need.CONSEQUENCE,
            AnswerCheck.parse("what are the consequences of missing the attendance requirement").need)
    }

    @Test fun `a permission question is no longer decided by topic overlap alone`() {
        assertEquals(AnswerCheck.Need.PERMISSION,
            AnswerCheck.parse("can a student with a backlog apply for the merit scholarship").need)
        assertEquals(AnswerCheck.Need.PERMISSION,
            AnswerCheck.parse("am I allowed to reappear for a backlog subject").need)
    }

    @Test fun `a modal without a verb of doing is not a permission question`() {
        // The half that keeps the cue closed. "Is the library open on Sunday"
        // opens with a modal-shaped clause and asks for opening hours; its
        // answer already lives in Need.OTHER and must stay there.
        assertEquals(AnswerCheck.Need.OTHER,
            AnswerCheck.parse("is the library open on Sunday").need)
        assertEquals(AnswerCheck.Need.OTHER,
            AnswerCheck.parse("is the hostel warden available").need)
    }

    @Test fun `the two shapes that carry a battery are claimed first`() {
        // COUNT and QUANTITY are tested ahead of the two new cues, so no
        // question that had a shape before can be stolen by one added here.
        assertEquals(AnswerCheck.Need.COUNT,
            AnswerCheck.parse("how many students can apply for the merit scholarship").need)
        assertEquals(AnswerCheck.Need.QUANTITY,
            AnswerCheck.parse("what is the minimum attendance").need)
        assertEquals(AnswerCheck.Need.ELIGIBILITY,
            AnswerCheck.parse("can I write the exam with 60% attendance").need)
    }

    @Test fun `the predicate of a consequence question is filler, not a topic`() {
        // "Happens" is in 4 of 493 chunks, all of them scholarship procedure
        // notices -- rare enough to clear the two-term floor on the one
        // document that cannot answer, and empty enough to mean nothing when
        // it does. "Consequence" is a COLUMN HEADING in both attendance
        // tables, which is how a question about backlogs was answered with
        // the attendance tiers.
        assertEquals(listOf("miss", "attendance"),
            AnswerCheck.contentTerms("what happens if I miss it attendance"))
        assertEquals(listOf("backlog"),
            AnswerCheck.contentTerms("what are the consequences of a backlog"))
    }

    // --- 2. the consequence shape gate -------------------------------------

    @Test fun `a sentence about procedure notices existing is not a consequence`() {
        // The measured second half of "what is the minimum attendance and what
        // happens if I miss it". It cleared the old two-term floor on
        // "happens" and on "miss" -- matched inside subMISSion, because
        // mentions() has no word boundary.
        val out = AnswerComposer.compose(
            "what happens if I miss it attendance",
            listOf(relaxationNote, applyMatrix),
        )
        assertTrue("narrated the procedure notice: ${out.lead}", out.abstained)
        assertFalse(out.lead.contains("separate procedure notice"))
    }

    @Test fun `a consequence question that cannot be answered says what is missing`() {
        // Verified against the whole bundle: every scheme states a minimum
        // attendance, and no document anywhere states what becomes of a
        // GRANTED scholarship when the student is debarred. Abstaining is the
        // right answer, and saying why is better than the generic refusal.
        val out = AnswerComposer.compose(
            "what happens to my scholarship if I am debarred for attendance",
            listOf(relaxationNote, attendancePolicyRule, applyMatrix),
        )
        assertTrue(out.abstained)
        assertFalse("must not offer one scheme's relaxation as the answer",
            out.lead.contains("relaxation"))
        assertTrue("must say what is missing, got: ${out.lead}",
            out.lead.contains("None of it states what follows in that case"))
        assertEquals("no sentence in 3 chunks answers a CONSEQUENCE question", out.reason)
    }

    @Test fun `the consequence tail is only added for a consequence question`() {
        val out = AnswerComposer.compose(
            "who is the cricket team captain", listOf(relaxationNote, applyMatrix))
        assertTrue(out.abstained)
        assertFalse(out.lead.contains("None of it states what follows"))
    }

    // --- 3. the tier table as an answer ------------------------------------

    @Test fun `what happens if I miss it is answered by the tier the corpus states`() {
        val out = AnswerComposer.compose(
            "what happens if I miss it attendance",
            listOf(relaxationNote, applyMatrix, attendancePolicyRule),
        )
        assertFalse("abstained while holding the tier: ${out.reason}", out.abstained)
        assertEquals(
            "Below 65%: Debarred outright from the End-Semester examination for the term; " +
                "no condonation is possible at this tier.",
            out.lead,
        )
    }

    @Test fun `every tier retrieved is named, in order, verbatim`() {
        val out = AnswerComposer.compose(
            "what happens if I miss it attendance",
            listOf(attendancePolicyRule, attendancePolicyTiers),
        )
        assertFalse(out.abstained)
        assertEquals(
            "Below 65%: Debarred outright from the End-Semester examination for the term; " +
                "no condonation is possible at this tier. " +
                "65% to 74%: Placed on the defaulter list and notified in writing to the " +
                "parent/guardian; may apply for condonation (see CONDONATION_PROCEDURE) to be " +
                "permitted to sit the End-Semester examination. " +
                "75% and above: No action; eligible to appear for the End-Semester examination " +
                "without condonation.",
            out.lead,
        )
    }

    @Test fun `the same table in two documents is not read out twice`() {
        // The Attendance Policy and the Defaulter List Procedure carry an
        // identical tier table, and retrieval returns both on this question.
        val q = AnswerCheck.parse("what happens if I miss it attendance")
        val out = AnswerCheck.tierConsequences(
            q, listOf(attendancePolicyRule, defaulterProcedureTiers))
        assertNotNull(out)
        assertEquals(1, Regex("Debarred outright").findAll(out!!).count())
    }

    @Test fun `no tier table means no invented ladder`() {
        val q = AnswerCheck.parse("what happens if I miss it attendance")
        assertNull(AnswerCheck.tierConsequences(q, listOf(relaxationNote, applyMatrix)))
    }

    @Test fun `only a consequence question reads the tier table`() {
        val q = AnswerCheck.parse("what is the minimum attendance")
        assertEquals(AnswerCheck.Need.QUANTITY, q.need)
        assertNull(AnswerCheck.tierConsequences(q, listOf(attendancePolicyRule)))
    }

    // --- 4. the eligibility matrix as an answer ----------------------------

    @Test fun `the backlog question is answered from the matrix, not from the procedure`() {
        val out = AnswerComposer.compose(
            "can a student with a backlog apply for the merit scholarship",
            listOf(alumniGrantProcedure, applyMatrix, relaxationNote),
        )
        assertFalse(out.abstained)
        assertEquals(
            "The records answer that as a requirement rather than a yes or no: " +
                "KRIET Alumni Merit Grant requires Previous two semester marksheets; " +
                "No-backlog declaration.",
            out.lead,
        )
        assertFalse("must not narrate what the office does afterwards",
            out.lead.contains("Scholarship SPOC verifies"))
    }

    @Test fun `the scheme the question names is the scheme that answers`() {
        // Both rows in the matrix require a no-backlog declaration. "Merit"
        // identifies one of them; reciting both would be answering a question
        // about one scheme with a list.
        val q = AnswerCheck.parse("can a student with a backlog apply for the merit scholarship")
        val out = AnswerCheck.schemeConditions(q, listOf(applyMatrix))
        assertNotNull(out)
        assertTrue(out!!.contains("KRIET Alumni Merit Grant"))
        assertFalse(out.contains("Sports and Cultural"))
    }

    @Test fun `a scheme the question does not name is not guessed at`() {
        val q = AnswerCheck.parse("can a student with a backlog apply for a scholarship")
        assertEquals(AnswerCheck.Need.PERMISSION, q.need)
        assertNull(AnswerCheck.schemeConditions(q, listOf(applyMatrix)))
    }

    @Test fun `a generic word is not a condition`() {
        // "What happens if I miss the fee deadline" matched a Disbursement
        // Timeline cell on the word "fee" and answered that the Alumni Merit
        // Grant requires being credited to the tuition-fee ledger. Restricting
        // this composition to PERMISSION is half the fix; the other half is
        // that the question must name the scheme.
        val q = AnswerCheck.parse("what happens if I miss the fee deadline")
        assertEquals(AnswerCheck.Need.CONSEQUENCE, q.need)
        assertNull(AnswerCheck.schemeConditions(q, listOf(applyMatrix)))
    }

    // --- 5. the permission tie-break ---------------------------------------

    @Test fun `a rule outranks the paperwork beside it in the same document`() {
        // Same document, same topic, two sentences. The bonus is worth less
        // than one extra topic hit, so it reorders and never promotes a
        // sentence that is about something else.
        val q = AnswerCheck.parse("can I apply for the post matric scholarship")
        assertEquals(AnswerCheck.Need.PERMISSION, q.need)
        val out = AnswerCheck.bestAnswer(q, listOf(alumniGrantProcedure, postMatricRule))
        assertNotNull(out)
        assertTrue(out!!.sentence.startsWith("Post-Matric Scholarship for SC/ST Students is open to"))
    }

    @Test fun `the permission preference never turns an answer into an abstention`() {
        // Measured as a hard shape gate, this exact question stopped being
        // answered: the scheme notices state their rule in a sentence that does
        // not contain the word "apply", so nothing cleared the two-term floor
        // and a rule shape at once. A preference cannot do that.
        val out = AnswerComposer.compose(
            "can I apply for the post matric scholarship", listOf(postMatricRule))
        assertFalse("abstained on a question the corpus answers: ${out.reason}", out.abstained)
    }

    // --- 6. the abbreviation guard -----------------------------------------

    @Test fun `a money figure is not a sentence boundary`() {
        val sentences = AnswerCheck.sentencesOf(feeNotice.content)
        assertTrue("the late fee sentence must survive intact: $sentences",
            sentences.any { it == "A late fee of Rs. 50 per day applies after the due date, " +
                "capped at Rs. 2,000." })
        assertFalse("the decapitated fragment must be gone",
            sentences.any { it.endsWith("A late fee of Rs.") })
    }

    @Test fun `the late fee is reachable at all`() {
        // Not mis-ranked -- unreachable. "A late fee of Rs." is 17 characters,
        // below the 25-character floor, and the remainder no longer contains
        // the words "late fee", so no ranking change could have recovered it.
        val out = AnswerComposer.compose("what is the late fee", listOf(feeNotice))
        assertFalse(out.abstained)
        assertTrue(out.lead, out.lead.contains("Rs. 50 per day"))
    }

    @Test fun `an eligibility rule is not cut off at the income figure`() {
        val out = AnswerComposer.compose(
            "who is eligible for the post-matric scholarship", listOf(postMatricRule))
        assertFalse(out.abstained)
        assertTrue("used to stop dead at 'family income up to Rs.': ${out.lead}",
            out.lead.contains("a  minimum  attendance  of  75%") ||
                out.lead.contains("a minimum attendance of 75%"))
    }

    @Test fun `a break whose separator holds a newline is always taken`() {
        // The guard must not glue a table row to the row below it. Both rows
        // here end in an abbreviation before a newline.
        val rows = "| Ratnagiri city (up to 10 km) | Rs. 6,000          |\n" +
            "| Outstation (10-25 km)        | Rs. 9,000          |"
        assertTrue(AnswerCheck.sentencesOf(rows).none { it.contains("Outstation") &&
            it.contains("Ratnagiri") })
    }

    @Test fun `the guard is scoped to sentencesOf, so scopes are unmoved`() {
        // requiredMinimums reads the unguarded split on purpose. Merged, chunk
        // 407's rule becomes one sentence, SCHEME_NAME matches it, and the 75%
        // stops being the institute's general minimum -- which is the figure
        // "am I eligible for a scholarship if my attendance is 70 percent"
        // is ruled against.
        val thresholds = AnswerCheck.requiredMinimums(postMatricRule.content, emptyList())
        assertEquals(listOf(AnswerCheck.Threshold(75.0, null)), thresholds)
    }

    // --- 7. the compound question these arrived through --------------------

    @Test fun `both halves of the attendance compound are answered`() {
        val parts = CompoundQuestion.split(
            "what is the minimum attendance and what happens if I miss it")
        assertEquals(listOf("what is the minimum attendance", "what happens if I miss it"), parts)
        val second = CompoundQuestion.carryOver(parts[1], parts[0])
        assertEquals("what happens if I miss it attendance", second)

        val first = AnswerComposer.compose(parts[0],
            listOf(attendancePolicyRule, attendancePolicyTiers))
        assertFalse(first.abstained)
        assertTrue(first.lead.contains("75%"))

        val out = AnswerComposer.compose(second,
            listOf(relaxationNote, applyMatrix, attendancePolicyRule))
        assertFalse(out.abstained)
        assertTrue(out.lead.startsWith("Below 65%:"))
    }
}
