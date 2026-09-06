package com.campusbrain.app

import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.answer.AnswerComposer
import com.campusbrain.app.answer.PremiseCheck
import com.campusbrain.app.data.RetrievedChunk
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
 * One group is the exception, and says so where it sits: the imported-document
 * fixtures in the last section have no chunk id, because the defect they pin is
 * about a file a user added. They are verbatim from `IngestDeviceTest.doc`
 * instead -- the same text the device test writes to disk.
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

    // --- defect: narrating an unrelated document instead of abstaining ----

    /**
     * chunks.id = 167 -- the deadline tracker's opening paragraph, verbatim.
     *
     * This is the passage the app read out when asked to list students caught
     * cheating. It clears the two-term floor on "list" and "students", both of
     * which are in a third of the corpus and in most questions, and says
     * nothing whatever about cheating.
     */
    private val deadlineTracker = chunk(
        167, "28_deadline_tracker.md", "Subject: Upcoming Deadlines - Academic Year 2026-27",
        "The consolidated list below tracks every deadline a student needs to act on during " +
            "the academic year 2026-27, with the number of days remaining as of the date of " +
            "this circular (15 June 2026) and the notice that first announced each deadline. " +
            "Students should re-check the notice board close to each date, since a subsequent " +
            "circular can revise it."
    )

    /**
     * Word presence over the shipped bundle, measured rather than invented:
     *
     *   sqlite3 brain.db "SELECT COUNT(*) FROM chunks WHERE instr(lower(content),'cheating')>0"
     *
     * gives 0. The rest, all of 493: caught 1, cheating 0, expelled 0,
     * plagiarism 0, wifi 0, password 2, cricket 1, team 1, captain 0, write 0,
     * long 13, take 11, minimum 18, sit 123, happens 4, debarred 4.
     *
     * Only the zeroes have to be listed; everything else answers true, which is
     * what the corpus would say for any ordinary word.
     *
     * NOTE: the key handed to this lambda is `searchKey(term)`, not the word as
     * typed -- a five-letter-or-longer plural arrives with its "s" already
     * dropped. Every entry below happens to survive searchKey unchanged; a
     * plural added later will not.
     */
    private val corpusWords = AnswerCheck.CorpusVocabulary { term ->
        term !in setOf("cheating", "expelled", "plagiarism", "wifi", "captain", "write")
    }

    @Test fun `a question the corpus has no field for abstains instead of narrating`() {
        // Observed: "The consolidated list below tracks every deadline a
        // student needs to act on..." with ABSTAINED=false. The students table
        // holds roll_no, name, sgpa, estimated_sgpa, total_marks, result,
        // is_supply and seat_cancelled -- no disciplinary field of any kind.
        // Measured over the shipped bundle: "cheating" is in 0 of 493 chunks
        // and "caught" in 1.
        val out = AnswerComposer.compose(
            "list students who were caught cheating",
            listOf(deadlineTracker, bonafideNotice, csvColumnDocs),
            vocabulary = corpusWords,
        )
        assertTrue("narrated an unrelated document: ${out.lead}", out.abstained)
        assertTrue("must be the off-topic abstention, not the thin one", out.offTopic)
        assertTrue("must name what is missing, got: ${out.lead}", out.lead.contains("cheating"))
        assertFalse("must not quote the document it did not answer from",
            out.lead.contains("consolidated list"))
    }

    @Test fun `the fix is the subject test, not the word cheating`() {
        // Nothing above keys on the question. Any subject the corpus does not
        // carry has to behave the same way, or the fix is a special case
        // wearing a general name.
        listOf(
            "which students were expelled for plagiarism",
            "what is the wifi password",
            "who is the cricket team captain",
        ).forEach { q ->
            val out = AnswerComposer.compose(
                q, listOf(deadlineTracker, bonafideNotice), vocabulary = corpusWords)
            assertTrue(q, out.abstained)
            assertTrue(q, out.offTopic)
        }
    }

    @Test fun `the answer's form is not its subject`() {
        // "List" says how to present the answer, not what it is about, which is
        // the same argument the stoplist already makes for "available". It was
        // one of the two words that cleared the floor on the deadline tracker.
        assertEquals(listOf("students", "caught", "cheating"),
            AnswerCheck.contentTerms("list students who were caught cheating"))
        assertEquals(listOf("toppers"), AnswerCheck.contentTerms("show me the toppers"))
    }

    @Test fun `generic campus words alone are not evidence of a topic`() {
        val q = AnswerCheck.parse("list students who were caught cheating")
        assertEquals(listOf("caught", "cheating"), AnswerCheck.subjectTerms(q.terms))
        // "students" carries no information about which question was asked.
        assertTrue(AnswerCheck.subjectTerms(listOf("students", "subject", "exam")).isEmpty())
    }

    @Test fun `one absent word is a phrasing accident, not a missing subject`() {
        // "Can I write the exam with 60% attendance" is answerable and the
        // corpus never says "write" -- it says "appear for". Refusing on a
        // single absent word would re-create the over-abstention this whole
        // file exists to remove, so the gate needs two.
        val q = AnswerCheck.parse("can I write the exam with 60% attendance")
        assertEquals(listOf("write"), AnswerCheck.subjectTerms(q.terms))
        assertTrue(
            AnswerCheck.unsupportedSubject(q, listOf(attendancePolicyRule), corpusWords).isEmpty()
        )

        val out = AnswerComposer.compose(
            "can I write the exam with 60% attendance",
            listOf(condonation, attendancePolicyRule, attendancePolicyTiers),
            vocabulary = corpusWords,
        )
        assertFalse("abstained on an answerable eligibility question", out.abstained)
        assertTrue("must rule on the 60%, got: ${out.lead}", out.lead.startsWith("No"))
    }

    @Test fun `the subject is looked for in whole chunks, not in one sentence`() {
        // "Debarred" lives in a table row, and sentencesOf drops rows on
        // purpose. A per-sentence version of this rule would refuse a question
        // the retrieval answered correctly.
        val q = AnswerCheck.parse("what happens to my scholarship if I am debarred for attendance")
        assertEquals(listOf("happens", "debarred"), AnswerCheck.subjectTerms(q.terms))
        assertTrue(
            AnswerCheck.unsupportedSubject(q, listOf(attendancePolicyRule), corpusWords).isEmpty()
        )
    }

    @Test fun `a word missing from the retrieval is not a word missing from the corpus`() {
        // The near-miss that decided the shape of this rule. "How long does a
        // bonafide certificate take" retrieves the right notice, and the notice
        // says neither "long" nor "take" -- two subject words, neither in any
        // retrieved chunk, and the question is answered correctly today. Only
        // the corpus-wide check separates it from the cheating question: "long"
        // is in 13 chunks and "take" in 11, "cheating" in none.
        val q = AnswerCheck.parse("how long does a bonafide certificate take")
        assertEquals(listOf("long", "take"), AnswerCheck.subjectTerms(q.terms))
        assertTrue(
            AnswerCheck.unsupportedSubject(q, listOf(bonafideNotice), corpusWords).isEmpty()
        )
        val out = AnswerComposer.compose(
            "how long does a bonafide certificate take",
            listOf(bonafideNotice),
            vocabulary = corpusWords,
        )
        assertFalse("abstained on an answerable question: ${out.reason}", out.abstained)
        assertTrue(out.lead.contains("3 working days"))
    }

    @Test fun `the gate does not touch the questions the last fix repaired`() {
        // The regression risk of any new abstention rule. All three of these
        // are answered today and must stay answered.
        val attendance = listOf(attendanceSignature, attendancePolicyRule, condonation)
        listOf(
            "how much attendance do I need",
            "minimum attendance to sit exams",
            "what is the minimum attendance",
        ).forEach { q ->
            val out = AnswerComposer.compose(q, attendance, vocabulary = corpusWords)
            assertFalse("$q -> ${out.reason}", out.abstained)
            assertTrue(q, out.lead.contains("75%"))
        }
    }

    // --- defect: a duration written in words ------------------------------

    /**
     * The two fixtures below are the exception to this file's "verbatim out of
     * brain.db" rule stated at the top, and they have to be: the defect is
     * about an IMPORTED document, and nothing imported has a chunk id. They are
     * verbatim from somewhere real all the same -- `IngestDeviceTest.doc`, the
     * fixture the device ingest test writes to disk and asks these very
     * questions against. Ids 900/901 are placeholders and are in no database.
     *
     * Observed on hardware, 2026-09-06:
     *
     *   route=FACT abstained=true userSources=1/6
     *   a: I don't have enough information to answer that. The closest material
     *      in the corpus is Quasar Robotics Society, ...
     *
     * Retrieval had done its job -- the right chunk was in hand and the right
     * document was named. `how long` is a QUANTITY cue, "borrowed" and
     * "equipment" clear the two-term floor, and then the shape test asked for a
     * digit against a sentence that spells its number out.
     */
    private val roboticsSociety = chunk(
        900, "quasar-robotics-society.txt", "Quasar Robotics Society",
        "The Quasar Robotics Society meets every Thursday at 5:30 pm in Laboratory 7B of the " +
            "Mechanical Engineering block. Annual membership costs 1450 rupees, payable to the " +
            "society treasurer before the last working day of August."
    )

    /** The paragraph holding the answer. Ranked below the one above on device. */
    private val roboticsEquipment = chunk(
        901, "quasar-robotics-society.txt", "Robotics Club Membership and Workshop Schedule 2026",
        "Equipment may be borrowed for a maximum of fourteen days. A member with overdue " +
            "equipment cannot borrow again until it is returned, and repeated lateness leads " +
            "to suspension for one semester."
    )

    @Test fun `a duration spelled out in words is an answer`() {
        val out = AnswerComposer.compose(
            "how long can I borrow robotics equipment",
            listOf(roboticsSociety, roboticsEquipment),
            vocabulary = corpusWords,
        )
        assertFalse("abstained on a duration written in words: ${out.reason}", out.abstained)
        assertEquals(
            "Equipment may be borrowed for a maximum of fourteen days.",
            out.lead
        )
        // Two sentences in that chunk clear the floor and now both clear the
        // shape test -- the second says "suspension for one semester". The
        // requirement cue on "maximum" is what separates them, and it is worth
        // 200 against a chunk-rank tiebreak of at most a handful.
        assertEquals(
            "Robotics Club Membership and Workshop Schedule 2026",
            out.passages.first().heading
        )
    }

    @Test fun `an ordinal names a day, not a length of time`() {
        // "The fourteenth day" and "fourteen days" are different claims, and a
        // student asking how long they may keep something is not answered by a
        // date. Word boundaries keep the bare ordinal out on their own; the
        // hyphenated compound is the one that needs saying out loud.
        val ordinal = chunk(
            902, "quasar-robotics-society.txt", "Robotics Club",
            "Robotics equipment borrowed for a project must be returned on the fourteenth day " +
                "of the term."
        )
        val compound = chunk(
            903, "quasar-robotics-society.txt", "Robotics Club",
            "Robotics equipment borrowed for a project is due back on the twenty-first of the " +
                "following month."
        )
        listOf(ordinal, compound).forEach { c ->
            val out = AnswerComposer.compose(
                "how long can I borrow robotics equipment", listOf(c), vocabulary = corpusWords)
            assertTrue("read an ordinal as a duration: ${out.lead}", out.abstained)
        }
        // And the cardinal half of that compound is not lost -- "twenty-one" is
        // still a number.
        val cardinal = chunk(
            904, "quasar-robotics-society.txt", "Robotics Club",
            "Robotics equipment may be borrowed for twenty-one days by final-year students."
        )
        val out = AnswerComposer.compose(
            "how long can I borrow robotics equipment", listOf(cardinal), vocabulary = corpusWords)
        assertFalse("lost a hyphenated cardinal: ${out.reason}", out.abstained)
        assertTrue(out.lead, out.lead.contains("twenty-one days"))
    }

    @Test fun `one of the documents is not a quantity`() {
        // "One" is the article this rule could most easily be fooled by. A
        // partitive "one of", a determiner-pronoun "each one" and a bare
        // pronoun subject "one may" are all uses that state no number.
        listOf(
            "The rules for borrowing robotics equipment are set out in one of the annexures.",
            "Each one is signed by the coordinator before robotics equipment is borrowed.",
            "One may apply to borrow robotics equipment for a project of any length.",
        ).forEach { text ->
            val out = AnswerComposer.compose(
                "how long can I borrow robotics equipment",
                listOf(chunk(905, "quasar-robotics-society.txt", "Robotics Club", text)),
                vocabulary = corpusWords,
            )
            assertTrue("read the article as a quantity: ${out.lead}", out.abstained)
        }
        // The number "one" survives all three guards.
        val out = AnswerComposer.compose(
            "how long can I borrow robotics equipment",
            listOf(chunk(906, "quasar-robotics-society.txt", "Robotics Club",
                "Robotics equipment may be borrowed for one week at a time.")),
            vocabulary = corpusWords,
        )
        assertFalse(out.reason, out.abstained)
        assertTrue(out.lead, out.lead.contains("one week"))
    }

    @Test fun `a sentence with no figure at all is still refused`() {
        // The point of the shape test has not moved: a quantity question still
        // needs a quantity. Only the spelling of one has widened.
        val out = AnswerComposer.compose(
            "how long can I borrow robotics equipment",
            listOf(chunk(907, "quasar-robotics-society.txt", "Robotics Club",
                "Robotics equipment must be returned to the society treasurer in person.")),
            vocabulary = corpusWords,
        )
        assertTrue("accepted a sentence stating no quantity: ${out.lead}", out.abstained)
    }

    @Test fun `a count in words still has to be a count of what was asked`() {
        val counted = chunk(908, "quasar-robotics-society.txt", "Quasar Robotics Society",
            "The Quasar Robotics Society has fifteen members on its rolls.")
        val out = AnswerComposer.compose(
            "how many members does the robotics society have", listOf(counted), vocabulary = corpusWords)
        assertFalse(out.reason, out.abstained)
        assertTrue(out.lead, out.lead.contains("fifteen members"))

        // The noun still has to FOLLOW the number, exactly as it must on the
        // digit path -- "35 active students" does not answer "how many students"
        // either. Widening that gap is a separate loosening and this is not the
        // change that should make it.
        val adjective = chunk(909, "quasar-robotics-society.txt", "Quasar Robotics Society",
            "The Quasar Robotics Society has fifteen active members drawn from every year.")
        val loose = AnswerComposer.compose(
            "how many members does the robotics society have", listOf(adjective), vocabulary = corpusWords)
        assertTrue("the adjacency rule moved: ${loose.lead}", loose.abstained)
    }

    @Test fun `at least one subject is not an answer to how many students failed`() {
        // The trap the existing `counted` fixture already contains: "35
        // students failed at least one subject this term" holds a bare cardinal
        // that is not the count. Isolated here so the digit cannot rescue it.
        val out = AnswerComposer.compose(
            "how many students failed",
            listOf(chunk(910, "audit.md", "Results",
                "Students who failed at least one subject must register for the supplementary " +
                    "examination.")),
            vocabulary = corpusWords,
        )
        assertTrue("counted a subject as a student: ${out.lead}", out.abstained)

        // And the digit path is untouched. Re-asserted rather than assumed,
        // because statesCount is the function that changed.
        val digits = AnswerComposer.compose(
            "how many students failed",
            listOf(chunk(911, "audit.md", "Results",
                "Of the 369 students on the roll, 35 students failed at least one subject this term.")),
            vocabulary = corpusWords,
        )
        assertFalse(digits.reason, digits.abstained)
        assertTrue(digits.lead, digits.lead.contains("35 students failed"))
    }

    @Test fun `an eligibility question still needs a figure it can compare`() {
        // The word-number rule stops short of ELIGIBILITY deliberately. That
        // path exists to judge the number the student stated, and "two
        // sessions" cannot be compared against 60% -- admitting it would only
        // widen the set of sentences quoted back to someone who asked for a
        // ruling. One fixture, two question shapes, two answers.
        val sessions = chunk(912, "quasar-robotics-society.txt", "Robotics Club",
            "Attendance at the workshop is recorded, and a member may miss at most two " +
                "sessions before losing the seat.")

        val ruling = AnswerComposer.compose(
            "am I eligible for the workshop with 60% attendance", listOf(sessions), vocabulary = corpusWords)
        assertTrue("ruled on 60% from a sentence with no comparable figure: ${ruling.lead}",
            ruling.abstained)

        val counted = AnswerComposer.compose(
            "how many sessions can I miss", listOf(sessions), vocabulary = corpusWords)
        assertFalse(counted.reason, counted.abstained)
        assertTrue(counted.lead, counted.lead.contains("two sessions"))
    }
}
