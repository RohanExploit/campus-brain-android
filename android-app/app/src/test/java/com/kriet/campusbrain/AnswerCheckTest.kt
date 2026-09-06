package com.kriet.campusbrain

import com.kriet.campusbrain.answer.AnswerCheck
import com.kriet.campusbrain.answer.AnswerComposer
import com.kriet.campusbrain.answer.PremiseCheck
import com.kriet.campusbrain.data.RetrievedChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answering decision, pinned against the real text of brain.db.
 *
 * Every fixture below is a verbatim `content` value copied out of the shipped
 * bundle, double spaces and all -- the PDF extraction left them and they are
 * exactly the kind of detail a hand-written fixture would tidy away and then
 * fail to catch. Chunk ids are kept in the names so a disagreement can be
 * checked against the database directly:
 *
 *   sqlite3 brain.db "SELECT content FROM chunks WHERE id = 150"
 *
 * These run on the JVM in milliseconds. That matters more than usual here:
 * [AnswerComposer.compose] is on the path for every FACT, LOCAL and GLOBAL
 * answer in the app, so a change that fixes three probes and silently
 * converts a dozen passing ones into abstentions would look like progress in
 * the instrumented battery's headline number.
 */
class AnswerCheckTest {

    // --- fixtures ---------------------------------------------------------

    private fun chunk(id: Long, doc: String, section: String?, content: String) =
        RetrievedChunk(id, doc, section, content, 0.0)

    /** chunks.id = 150 -- the rule sentence plus the first tier row. */
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
     * chunks.id = 153 -- the signature block of the same document. This is the
     * adversarial one: it contains the word "attendance" and a number, ranks
     * ahead of the rule on some phrasings, and answers nothing.
     */
    private val attendanceSignature = chunk(
        153, "24_attendance_policy.md", "Mrs. Pallavi Chitnis",
        "Assistant Professor Attendance Coordinator  \nDate: 15 July 2026"
    )

    /** chunks.id = 159 -- the condonation band, stated as prose. */
    private val condonation = chunk(
        159, "26_attendance_condonation.md", "Subject: Attendance Condonation Application Procedure",
        "A student with  subject-wise  attendance  between  65%  and  74%  may  apply  for  " +
            "condonation. Condonation can raise counted attendance by at most 10 percentage " +
            "points and must be supported by documentation."
    )

    /** chunks.id = 394 -- the CSV column documentation the app used to narrate. */
    private val csvColumnDocs = chunk(
        394, "student.md", "these grades are related with the course subject, Math or Portuguese:",
        "31 G1 - first period grade (numeric: from 0 to 20) 31 G2 - second period grade " +
            "(numeric: from 0 to 20) 32 G3 - final grade (numeric: from 0 to 20, output target)  \n" +
            "Additional note: there are several (382) students that belong to both datasets ."
    )

    /** chunks.id = 9 (bonafide notice) -- an ordinary FACT answer, as a control. */
    private val bonafideNotice = chunk(
        9, "09_notice_bonafide_certificate.md", "Subject: Bonafide Certificate",
        "The bonafide certificate is issued by the Student Section within 3 working days of a " +
            "complete application. Applications are accepted at the counter between 10:00 and " +
            "13:00 on working days."
    )

    // --- defect 2: abstaining while holding the answer --------------------

    @Test fun `answers how much attendance even when a signature block ranks first`() {
        // The exact observed failure: the right documents were retrieved, the
        // top chunk was the policy's signature block, and the app printed
        // "I don't have enough information to answer that."
        val out = AnswerComposer.compose(
            "how much attendance do I need",
            listOf(attendanceSignature, attendancePolicyRule, condonation),
        )
        assertFalse("abstained while holding the answer: ${out.reason}", out.abstained)
        assertTrue("answer must state the 75% rule, got: ${out.lead}", out.lead.contains("75%"))
        // And it must cite the chunk it actually quoted, not the one that
        // happened to rank first.
        assertEquals("Subject: Attendance Policy, Academic Year 2026-27", out.passages.first().heading)
    }

    @Test fun `much and many are filler, not content words`() {
        // "much" surviving the stopword filter is the whole of that abstention:
        // it became one of two content words the Attendance Policy had to
        // contain, and the policy could only ever supply "attendance".
        assertEquals(listOf("attendance"), AnswerCheck.contentTerms("how much attendance do I need"))
        assertEquals(listOf("students", "failed"), AnswerCheck.contentTerms("how many students failed"))
    }

    @Test fun `the chip phrasing that already worked still works`() {
        // Regression pin. This one passed before the rewrite because
        // "minimum" happens to appear in the policy text; it must not become
        // collateral damage of a stricter gate.
        val out = AnswerComposer.compose(
            "minimum attendance to sit exams",
            listOf(attendancePolicyRule, attendancePolicyTiers, condonation),
        )
        assertFalse(out.abstained)
        assertTrue(out.lead.contains("75%"))
    }

    @Test fun `an ordinary fact question is unaffected`() {
        val out = AnswerComposer.compose(
            "how long does a bonafide certificate take",
            listOf(bonafideNotice),
        )
        assertFalse(out.abstained)
        assertTrue(out.lead.contains("3 working days"))
    }

    @Test fun `the lead is normalised, not printed with the PDF's double spaces`() {
        val out = AnswerComposer.compose(
            "how much attendance do I need",
            listOf(attendancePolicyRule),
        )
        assertFalse("double spaces leaked into the answer bubble: ${out.lead}", out.lead.contains("  "))
    }

    // --- defect 1: narrating text that answers nothing --------------------

    @Test fun `a count question is not answered by a CSV column list`() {
        // "31 G1 - first period grade (numeric: from 0 to 20)". There are
        // digits and there is the word "grade", and the old overlap test was
        // satisfied by exactly that -- it read the answer off the top chunk
        // without ever asking whether a sentence about a spreadsheet column
        // states a count of students. In the running app this query never
        // gets this far, because PremiseCheck corrects the A+ first; this
        // pins the layer underneath it.
        val out = AnswerComposer.compose(
            "how many students got an A+ grade",
            listOf(csvColumnDocs),
        )
        assertTrue("narrated the CSV docs instead of abstaining: ${out.lead}", out.abstained)
    }

    @Test fun `a count question is answered by a sentence that states a count`() {
        val counted = chunk(
            1, "audit.md", "Results",
            "Of the 369 students on the roll, 35 students failed at least one subject this term."
        )
        val out = AnswerComposer.compose("how many students failed", listOf(counted))
        assertFalse(out.abstained)
        assertTrue(out.lead.contains("35 students failed"))
    }

    // --- defect 1: the premise itself is false ----------------------------

    @Test fun `A plus is corrected, not answered`() {
        val correction = PremiseCheck.gradeScale("how many students got an A+ grade")
        assertNotNull(correction)
        assertTrue(correction!!.contains("no A+ grade"))
        // The correction has to carry the real scale, or it is only half an
        // answer: the student still does not know what to ask for instead.
        assertTrue(correction.contains("EX, AA, AB"))
        assertTrue(correction.contains("FF"))
    }

    @Test fun `grades that exist are not corrected`() {
        assertNull(PremiseCheck.gradeScale("how many students got an AA grade"))
        assertNull(PremiseCheck.gradeScale("how many students got an FF grade"))
        assertNull(PremiseCheck.gradeScale("what does grade AB mean"))
        assertNull(PremiseCheck.gradeScale("how many students got AU"))
    }

    @Test fun `ordinary words beside the word grade are not read as grades`() {
        // Without the exemption list this announced that there is no "NO"
        // grade and no "MY" grade, which is true and useless.
        assertNull(PremiseCheck.gradeScale("which subject has no grade"))
        assertNull(PremiseCheck.gradeScale("show me my grades"))
        assertNull(PremiseCheck.gradeScale("what is the grade scale"))
        assertNull(PremiseCheck.gradeScale("how many students failed"))
        assertNull(PremiseCheck.gradeScale("what is my grade in sem 3"))
    }

    @Test fun `other invented grades are caught too, not just A plus`() {
        assertNotNull(PremiseCheck.gradeScale("how many students got a B+ grade"))
        assertNotNull(PremiseCheck.gradeScale("list students with an A- grade"))
    }

    // --- defect 3: comparing a stated number against the rule -------------

    @Test fun `60 percent is refused, not handed the condonation band`() {
        // The observed answer was "attendance between 65% and 74% may apply
        // for condonation", which reads as a yes to a student who has 60%.
        val out = AnswerComposer.compose(
            "can I write the exam with 60% attendance",
            listOf(condonation, attendancePolicyTiers, attendancePolicyRule),
        )
        assertFalse(out.abstained)
        assertTrue("expected a refusal, got: ${out.lead}", out.lead.startsWith("No —"))
        assertTrue(out.lead.contains("75%"))
        assertTrue("must name the tier the student is actually in: ${out.lead}",
            out.lead.contains("below 65%"))
    }

    @Test fun `80 percent is allowed`() {
        val out = AnswerComposer.compose(
            "can I write the exam with 80% attendance",
            listOf(attendancePolicyRule, attendancePolicyTiers),
        )
        assertTrue("expected a yes, got: ${out.lead}", out.lead.startsWith("Yes —"))
        assertTrue(out.lead.contains("75%"))
    }

    @Test fun `70 percent lands in the condonation tier`() {
        val out = AnswerComposer.compose(
            "am I eligible for the exam with 70% attendance",
            listOf(attendancePolicyRule, attendancePolicyTiers),
        )
        assertTrue("expected a refusal with a route out, got: ${out.lead}", out.lead.startsWith("No —"))
        assertTrue(out.lead.contains("65% to 74%"))
        assertTrue(out.lead.contains("condonation"))
    }

    @Test fun `the threshold survives losing the chunk that states it in prose`() {
        // The rule sentence and the tier table live in different chunks, so a
        // retrieval pass can easily bring back one and not the other. The
        // "75% and above -> eligible" row has to be enough on its own.
        val out = AnswerComposer.compose(
            "can I write the exam with 60% attendance",
            listOf(attendancePolicyTiers),
        )
        assertTrue(out.lead.startsWith("No —"))
        assertTrue(out.lead.contains("75%"))
    }

    @Test fun `no verdict is invented when the corpus states no threshold`() {
        val out = AnswerComposer.compose(
            "can I write the exam with 60% attendance",
            listOf(attendanceSignature),
        )
        // Nothing here states a rule, so there is nothing to rule on.
        assertFalse(out.lead.startsWith("Yes"))
        assertFalse(out.lead.startsWith("No —"))
    }

    @Test fun `a percentage rule about another subject is not borrowed`() {
        // Sharing a unit is not sharing a topic: a scholarship cut-off must
        // not be used to answer an attendance question.
        val scholarship = chunk(
            396, "svc_01_scholarship_eligibility_matrix.md", "Eligibility",
            "A minimum of 60% aggregate marks in the previous examination is required to apply " +
                "for the merit scholarship under this scheme."
        )
        val out = AnswerComposer.compose(
            "can I write the exam with 50% attendance",
            listOf(scholarship),
        )
        assertFalse("borrowed a scholarship rule to rule on attendance: ${out.lead}",
            out.lead.startsWith("Yes"))
    }

    // --- the parsing pieces, directly -------------------------------------

    @Test fun `tier rows are read out of the markdown table`() {
        val bands = AnswerCheck.parseBands(
            attendancePolicyRule.content + "\n" + attendancePolicyTiers.content
        )
        assertEquals(3, bands.size)
        // 65 belongs to exactly one tier. Inclusive-on-both-sides boundaries
        // would make the answer depend on parse order.
        assertTrue(bands.first { it.label == "below 65%" }.contains(64.9))
        assertFalse(bands.first { it.label == "below 65%" }.contains(65.0))
        assertTrue(bands.first { it.label == "65% to 74%" }.contains(65.0))
        assertTrue(bands.first { it.label == "75% and above" }.contains(100.0))
    }

    @Test fun `the question's shape is read off the raw text, not the term list`() {
        // contentTerms splits on non-alphanumerics and drops anything under
        // three characters, so "60%" is gone before any term list exists.
        val q = AnswerCheck.parse("can I write the exam with 60% attendance")
        assertEquals(AnswerCheck.Need.ELIGIBILITY, q.need)
        assertEquals(60.0, q.statedPercent!!, 0.001)

        assertEquals(AnswerCheck.Need.COUNT, AnswerCheck.parse("how many students failed").need)
        assertEquals(AnswerCheck.Need.QUANTITY, AnswerCheck.parse("how much attendance do I need").need)
        assertEquals(AnswerCheck.Need.QUANTITY, AnswerCheck.parse("minimum attendance to sit exams").need)
        // No number to judge means no ruling to make, whatever the phrasing.
        assertEquals(AnswerCheck.Need.OTHER, AnswerCheck.parse("is the library open on Sunday").need)
        assertEquals(AnswerCheck.Need.OTHER,
            AnswerCheck.parse("what documents do I need for a bonafide certificate").need)
    }

    @Test fun `an empty retrieval abstains and says so`() {
        val out = AnswerComposer.compose("anything at all", emptyList())
        assertTrue(out.abstained)
        assertEquals("nothing retrieved", out.reason)
    }
}
