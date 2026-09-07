package com.campusbrain.app

import com.campusbrain.app.jvm.JvmCorpus
import org.junit.Test
import java.io.File

/**
 * SCRATCH sweep for the Phase 3 cross-document composition change. Not a
 * battery, not asserted -- dumps full answer text for a broad set of real
 * questions so a before/after run can be diffed by eye. Delete before
 * shipping.
 */
class CrossDocSweepTest {

    private val questions = listOf(
        "how many students failed",
        "How many students failed?",
        "number of students who failed",
        "how many failed the exam",
        "count of failed students",
        "how many students did not pass",
        "how many students failedm",
        "how many studnets failed",
        "how many students  failed",
        "how many students failed at least two subjects",
        "how many students failed more than two subjects",
        "what is the pass percentage",
        "which subject has the most failures",
        "what is the highest SGPA",
        "how many students scored above 9.0 SGPA",
        "how many students got an A+ grade",
        "list students who scored 10 SGPA",
        "who won the 2019 cricket world cup",
        "what is the capital of France",
        "how many students failed at IIT Bombay",
        "minimum attendance to sit exams",
        "how much attendance do I need",
        "can I write the exam with 60% attendance",
        "how many students have SGPA below 6",
        "how many students below 6 SGPA also failed",
        "how many students failed more than one subject and have SGPA below 6",
        "what is the average SGPA",
        "what is the average SGPA of students who failed",
        "which subject has the lowest pass rate",
        "which subject do students struggle with most",
        "am I eligible for a scholarship if my attendance is 70 percent",
        "can a student with a backlog apply for the merit scholarship",
        "what happens to my scholarship if I am debarred for attendance",
        "how is the college doing overall this semester",
        "is the pass rate good or bad",
        "how many students have no backlogs",
        "which students did not appear for the exam",
        "how many students got an O grade",
        "how many students scored above 9.5 SGPA",
        "list students who were caught cheating",
        "how many students failed and what is the pass percentage",
        "what is the minimum attendance and what happens if I miss it",
        "what happens if my attendance is 70 percent",
        "can I hold two scholarships at once",
        "am I debarred at 60 percent attendance",
        "if I have one live backlog can I sit for the Ratnagiri Softworks drive",
        "can a student with a backlog register for a placement drive",
        "what is the minimum attendance to be eligible for a scholarship",
        "what happens if I am on the defaulter list",
        "how much can condonation raise my attendance",
        "if my attendance is 68 percent can I get condonation",
        "what happens if I am debarred from the exam",
        "can I still sit for a placement drive if I accepted an offer already",
        "what is the dress code for a placement drive",
        "am I eligible for the merit scholarship if I have a backlog",
        "can I renew a library book if I have a fine",
        "what happens if I lose my id card",
        "what is the penalty for missing a placement drive",
        "can I apply for condonation if my attendance is 60 percent",
        "what happens if my attendance is 65 percent",
        "is there a fine for a lost library book",
        "what are the eligibility criteria for the sports and cultural excellence scholarship",
        "what happens if I already have an offer and want to sit for another drive",
        "can I sit for the Konkangiri Analytics drive with 2 backlogs",
        "am I eligible for the Malvan Robotics drive with 1 backlog",
        "can I sit for the Vishalgad Power Systems drive with 2 live backlogs",
        "what is the minimum CGPA for the Ratnagiri Softworks drive",
        "can I sit for a placement drive with a backlog",
        "how many live backlogs are allowed for the Sindhu Cloud Systems drive",
        "when does the odd semester start",
        "what is the exam schedule for this semester",
        "how do I pay the hostel fee",
        "how do I get my id card reissued",
        "how do I apply for a bonafide certificate",
        "how do I register for the even semester",
        "how do I renew my bus pass",
        "what is the dress code policy",
        "how do I register for convocation",
        "when is the holiday list published",
        "what events are happening this semester",
        "who do I contact for the tech fest",
        "how do I join the NSS camp",
        "what are the library rules",
        "how do I file a grievance",
        "what is the anti-ragging policy",
        "what training programmes are available this year",
        "how do I use the incubation centre",
        "what is the placement policy one-offer rule",
        "what is the late fee for tuition payment",
    )

    @Test fun `dump full answers for the cross-document sweep`() {
        val out = StringBuilder()
        questions.forEachIndexed { i, q ->
            val r = JvmCorpus.router.answer(q)
            out.append("Q").append(i + 1).append(": ").append(q).append('\n')
            out.append("ROUTE=").append(r.route).append(" ABSTAINED=").append(r.abstained).append('\n')
            out.append("A: ").append(r.answer.replace('\n', ' ')).append('\n')
            out.append("SOURCES: ").append(r.sources.joinToString(", ") { it.docId }).append('\n')
            out.append("---\n")
        }
        val dir = File("build/reports/cross-doc-sweep").apply { mkdirs() }
        val file = File(dir, "sweep.txt")
        file.writeText(out.toString())
        println("cross-doc sweep written to ${file.absolutePath}")
    }
}
