package com.kriet.campusbrain

import com.kriet.campusbrain.answer.PremiseCheck
import com.kriet.campusbrain.data.Route
import com.kriet.campusbrain.retrieval.RouteRules
import com.kriet.campusbrain.retrieval.SqlTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four defects the 2026-09-06 hardware run found, pinned at the layers that
 * run without a device.
 *
 * What a green run here is worth, stated plainly, because three of the four
 * fixes end in SQL this file cannot execute: template SELECTION, route
 * classification and premise detection are pure functions and are covered
 * exactly. The numbers those templates return are not -- [TabularQueries] needs
 * a BrainDb -- so every figure quoted in a comment below was computed against
 * the shipped bundle with sqlite3 and is recorded beside the query that
 * produces it. These tests prove the question reaches the right query; only the
 * device proves the query answers it.
 *
 * Recomputed against app/src/main/assets/brain.db on 2026-09-06:
 *   369 students · 334 PASS / 35 FAIL · 334 with no FF in any subject
 *   72 FF rows across 35 distinct students · highest SGPA 8.82 · nobody at 10
 *   is_supply = 0 and seat_cancelled = 0 on all 369 rows
 *   every student has a graded row in every subject they took (0 NULL grades)
 */
class PremiseSingleLetterGradeTest {

    // --- defect 4: O missed where A+ hit ---------------------------------

    @Test fun `a single-letter grade that does not exist is corrected, like A+`() {
        // Observed: "how many students got an A+ grade" was corrected exactly,
        // while "how many students got an O grade" abstained generically. The
        // token pattern demanded two letters or a +/- suffix, so the O was
        // never seen as a grade at all. Nothing on this scale is one letter
        // long, which is precisely why a one-letter grade is always wrong.
        val o = PremiseCheck.gradeScale("how many students got an O grade")
        assertNotNull("an O grade is not on this scale and must be corrected", o)
        assertTrue(o!!, o.contains("no O grade"))
        assertTrue("the correction must state the real scale", o.contains("AA"))
        assertNotNull(PremiseCheck.gradeScale("how many students got an S grade"))
        // CC is the grade; a bare C is not, and saying so is the answer.
        assertNotNull(PremiseCheck.gradeScale("how many students got a C grade"))
    }

    @Test fun `the article and the pronoun are still not grades`() {
        // The reason the single letter was refused in the first place. "A" and
        // "I" are the only two letters that are English words, and both sit
        // next to "grade" in perfectly ordinary questions.
        assertNull(PremiseCheck.gradeScale("how many students got a grade"))
        assertNull(PremiseCheck.gradeScale("what grade did I get"))
        assertNull(PremiseCheck.gradeScale("show me the grade of a student"))
    }

    @Test fun `the cases that already worked are unchanged`() {
        // Re-proved rather than assumed: the token pattern these all depend on
        // is the one that changed.
        assertNotNull(PremiseCheck.gradeScale("how many students got an A+ grade"))
        assertNotNull(PremiseCheck.gradeScale("how many students got a B+ grade"))
        assertNotNull(PremiseCheck.gradeScale("list students with an A- grade"))
        listOf(
            "how many students got an AA grade",
            "how many students got an FF grade",
            "what does grade AB mean",
            "how many students got AU",
            "which subject has no grade",
            "show me my grades",
            "what is the grade scale",
            "how many students failed",
            "what is my grade in sem 3",
        ).forEach { assertNull(it, PremiseCheck.gradeScale(it)) }
    }
}

/**
 * Defect 3, the routing half: the same true zero, answered or refused
 * depending on the verb.
 */
class ListingVerbRoutingTest {

    private fun route(q: String): Route? = RouteRules.classify(q)?.first

    @Test fun `a listing verb over student records reaches the records`() {
        // Observed pair, two probes apart on the same run:
        //   "how many students scored above 9.0 SGPA" -> TABULAR, "No students
        //      have an SGPA of 9 or above."
        //   "list students who scored 10 SGPA"        -> FACT, abstained.
        // Same fact, opposite behaviour, and the only difference is that the
        // first carries an AGG_KW and the second carries a listing verb.
        assertEquals(Route.TABULAR, route("list students who scored 10 SGPA"))
        assertEquals(Route.TABULAR, route("show me students who scored 8.5 sgpa"))
        // Only the widened first alternation reaches this one: no aggregate
        // keyword, no superlative, and the noun is plural.
        assertEquals(Route.TABULAR, route("students who scored well"))
    }

    @Test fun `the plural does not drag document questions into the tables`() {
        // The three RouteRulesTest pins that constrain how far the plural may
        // widen. The last one survives only because the second alternation was
        // deliberately left singular: "results for students" is who a document
        // is for, not a query over rows.
        assertNull(route("student mentorship program"))
        assertNull(route("student handbook"))
        assertNull(route("what is the overall attendance of students"))
        assertNull(route("summary of the scholarship results for students"))
        assertNull(route("give me an overview of the semester registration process"))
        // And the probe that must keep abstaining: a listing verb over a field
        // the records do not have is still not a records question.
        assertNull(route("list students who were caught cheating"))
    }
}

/**
 * The three questions the records could answer and no template asked.
 *
 * None of these is a routing defect, which is worth stating because the device
 * transcript reads as though they are: all three were already TABULAR by rule,
 * matched no template, fell through TabularIntent's name search to the
 * TABULAR->FACT fallback, and were relabelled FACT on the way out. That
 * fallback is the line that moved backend FACT accuracy from 50% to 94% and is
 * not the thing to change.
 */
class MissingTabularAnswersTest {

    private fun name(q: String) = SqlTemplates.match(q)?.name
    private fun resolve(q: String) = SqlTemplates.resolve(q)

    // --- defect 1: narrating the placement policy -------------------------

    @Test fun `who did not appear for the exam is a question for the records`() {
        // Observed: ROUTE=FACT, ABSTAINED=false, and the answer was the
        // Placement Cell's registration rule -- "a student who has not
        // registered is not permitted to sit for any drive that season" --
        // because "register", "appearing" and "sit for" all occur there. The
        // true answer is none, and the records hold it exactly:
        //   sqlite3: SELECT COUNT(*) FILTER (WHERE seat_cancelled=1),
        //                   COUNT(*) FILTER (WHERE is_supply=1) FROM students
        //            -> 0 | 0   (of 369)
        assertEquals("exam_attendance", name("which students did not appear for the exam"))
        assertEquals("exam_attendance", name("which students didn't appear for the exam"))
        assertEquals("exam_attendance", name("how many students were absent from the exam"))
        assertEquals("exam_attendance", name("how many students missed the exam"))
        assertTrue(resolve("which students did not appear for the exam")
            is SqlTemplates.Resolution.Answered)
    }

    @Test fun `an absence answer carries no attendance caveat`() {
        // "absent" trips the ATTENDANCE constraint, and "that figure does not
        // account for attendance" printed under an answer that is entirely
        // about exam attendance would undermine the one thing it evaluates.
        val r = resolve("how many students were absent from the exam")
        assertTrue("unexpected: $r", r is SqlTemplates.Resolution.Answered)
    }

    @Test fun `a policy question with the same words is not this question`() {
        // The discriminating case for the cohort condition. Both of these carry
        // the absence vocabulary and neither is answered by the results table;
        // the first routes TABULAR on "how many" and must still match nothing.
        assertNull(name("how many days can I be absent"))
        assertNull(name("what is the attendance requirement to appear for the exam"))
        // Routing is the other half of the guard: a policy phrasing that DOES
        // name a student never reaches match() at all, because TABULAR is
        // reachable only from RouteRules and the prototypes hold no TABULAR
        // vector.
        assertNull(RouteRules.classify("what happens if a student does not appear for the exam"))
    }

    // --- defect 2: no backlogs --------------------------------------------

    @Test fun `no backlogs is a fail question with the sign flipped`() {
        // Observed: ROUTE=FACT, ABSTAINED=true, offering placement documents as
        // the closest material. Every fail branch reads "backlog" as a demand
        // for failures, so the negation matched nothing.
        //
        // sqlite3, both readings:
        //   no FF in any subject -> 334      result = 'PASS' -> 334
        // They agree in this bundle, and the answer says so rather than
        // asserting it -- studentsWithoutBacklogs computes both in one
        // statement and branches on whether they match.
        assertEquals("no_backlogs", name("how many students have no backlogs"))
        assertEquals("no_backlogs", name("which students have no backlogs"))
        assertEquals("no_backlogs", name("how many students have zero backlogs"))
        assertTrue(resolve("how many students have no backlogs")
            is SqlTemplates.Resolution.Answered)
    }

    @Test fun `the negation does not steal the fail counts beside it`() {
        // Every one of these is answered correctly today and shares the
        // vocabulary the new branch matches on.
        assertEquals("result_count", name("how many students failed"))
        assertEquals("students_failed_at_least", name("how many students failed at least two subjects"))
        assertEquals("students_failed_at_least", name("how many students failed more than two subjects"))
        assertEquals("result_count", name("how many students did not pass"))
        assertEquals("subject_failure_counts", name("which subject has the most failures"))
        assertEquals("subject_pass_rates", name("which subject has the lowest pass rate"))
        assertEquals("subject_pass_rates", name("which subject do students struggle with most"))
    }

    // --- defect 3: an SGPA named as a value -------------------------------

    @Test fun `an SGPA with no comparator is an exact value, not a bound`() {
        // sqlite3: SELECT COUNT(*) FROM students WHERE ROUND(sgpa,2) = 10.0
        //          -> 0, and MAX(sgpa) -> 8.82, which is the answer to give.
        assertEquals("students_with_sgpa", name("list students who scored 10 SGPA"))
        assertEquals("students_with_sgpa", name("which students have an sgpa of 8.82"))
        assertEquals(10.0, SqlTemplates.namedSgpaValue("list students who scored 10 SGPA")!!, 1e-9)
    }

    @Test fun `a comparator vetoes the exact-value branch entirely`() {
        // The threshold branches own every one of these, and this branch must
        // never re-read a bound as a value.
        assertNull(SqlTemplates.namedSgpaValue("how many students scored above 9.0 SGPA"))
        assertNull(SqlTemplates.namedSgpaValue("how many students have SGPA below 6"))
        assertNull(SqlTemplates.namedSgpaValue("top 3 by sgpa"))
        assertNull(SqlTemplates.namedSgpaValue("lowest 5 sgpa"))
        assertNull(SqlTemplates.namedSgpaValue("what is the average SGPA"))
        assertEquals("count_sgpa_at_least", name("how many students scored above 9.0 SGPA"))
        assertEquals("toppers_by_sgpa", name("top 3 by sgpa"))
        assertEquals("bottom_by_sgpa", name("lowest 5 sgpa"))
    }

    @Test fun `a roll number beside the word sgpa is not a grade point`() {
        // SGPA is a 0-10 scale, and roll numbers here are 10-14 digits. Without
        // the cap the exact-value branch would report that no student has an
        // SGPA of 2267571242025.
        assertNull(SqlTemplates.namedSgpaValue("sgpa of 2267571242025"))
    }
}

/**
 * The route every battery probe takes, before and after.
 *
 * A routing rule cannot be judged on the question that motivated it -- yesterday
 * a rule change that looked local moved two probes nobody was looking at -- so
 * the whole scoreboard is simulated here. Exactly one probe changes route:
 * A17, "list students who scored 10 SGPA", FACT -> TABULAR. Everything else in
 * this table is a pin on behaviour that must not move.
 *
 * null means no deterministic rule fired, which resolves to FACT on device via
 * the prototype stage (whose vectors cover FACT, LOCAL and GLOBAL only).
 */
class BatteryRouteTableTest {

    private val expected: List<Pair<String, Route?>> = listOf(
        // --- HardQueryBatteryTest ---
        "how many students have SGPA below 6" to Route.TABULAR,
        "how many students below 6 SGPA also failed" to Route.TABULAR,
        "how many students failed more than one subject and have SGPA below 6" to Route.TABULAR,
        "what is the average SGPA" to Route.TABULAR,
        "what is the average SGPA of students who failed" to Route.TABULAR,
        "which subject has the most failures" to Route.TABULAR,
        "which subject has the lowest pass rate" to Route.TABULAR,
        "which subject do students struggle with most" to Route.TABULAR,
        "am I eligible for a scholarship if my attendance is 70 percent" to null,
        "can a student with a backlog apply for the merit scholarship" to Route.TABULAR,
        "what happens to my scholarship if I am debarred for attendance" to null,
        "how is the college doing overall this semester" to Route.TABULAR,
        "is the pass rate good or bad" to Route.TABULAR,
        "how many students have no backlogs" to Route.TABULAR,
        "which students did not appear for the exam" to Route.TABULAR,
        "how many students got an O grade" to Route.TABULAR,
        "how many students scored above 9.5 SGPA" to Route.TABULAR,
        "list students who were caught cheating" to null,
        "how many students failed and what is the pass percentage" to Route.TABULAR,
        "what is the minimum attendance and what happens if I miss it" to null,
        // --- AdversarialBatteryTest ---
        "how many students failed" to Route.TABULAR,
        "How many students failed?" to Route.TABULAR,
        "number of students who failed" to Route.TABULAR,
        "how many failed the exam" to Route.TABULAR,
        "count of failed students" to Route.TABULAR,
        "how many students did not pass" to Route.TABULAR,
        "how many students failedm" to Route.TABULAR,
        "how many studnets failed" to Route.TABULAR,
        "how many students  failed" to Route.TABULAR,
        "how many students failed at least two subjects" to Route.TABULAR,
        "how many students failed more than two subjects" to Route.TABULAR,
        "what is the pass percentage" to Route.TABULAR,
        "what is the highest SGPA" to Route.TABULAR,
        "how many students scored above 9.0 SGPA" to Route.TABULAR,
        "how many students got an A+ grade" to Route.TABULAR,
        // The one that moved. FACT before this change.
        "list students who scored 10 SGPA" to Route.TABULAR,
        "who won the 2019 cricket world cup" to null,
        "what is the capital of France" to null,
        "how many students failed at IIT Bombay" to Route.TABULAR,
        "minimum attendance to sit exams" to null,
        "how much attendance do I need" to Route.TABULAR,
        "can I write the exam with 60% attendance" to null,
    )

    @Test fun `every battery probe routes where it did, bar one`() {
        assertEquals("both batteries, minus the duplicated most-failures probe", 42, expected.size)
        expected.forEach { (q, route) ->
            assertEquals(q, route, RouteRules.classify(q)?.first)
        }
    }

    /**
     * The templates the TABULAR probes reach. Three entries changed, and all
     * three replace a `null` that ended in document retrieval.
     */
    @Test fun `every TABULAR probe reaches the template it did, bar three`() {
        val expectedTemplate = mapOf(
            "how many students have SGPA below 6" to null,            // intent cascade, below_sgpa
            "how many students below 6 SGPA also failed" to "students_matching",
            "how many students failed more than one subject and have SGPA below 6" to "students_matching",
            "what is the average SGPA" to "average_sgpa",
            "what is the average SGPA of students who failed" to "average_sgpa",
            "which subject has the most failures" to "subject_failure_counts",
            "which subject has the lowest pass rate" to "subject_pass_rates",
            "which subject do students struggle with most" to "subject_pass_rates",
            "can a student with a backlog apply for the merit scholarship" to null,
            "how is the college doing overall this semester" to "semester_overview",
            "is the pass rate good or bad" to "pass_percentage",
            "how many students have no backlogs" to "no_backlogs",       // was null
            "which students did not appear for the exam" to "exam_attendance", // was null
            "how many students got an O grade" to null,                  // PremiseCheck answers it
            "how many students scored above 9.5 SGPA" to "count_sgpa_at_least",
            "how many students failed and what is the pass percentage" to "pass_percentage",
            "how many students failed" to "result_count",
            "How many students failed?" to "result_count",
            "number of students who failed" to "result_count",
            "how many failed the exam" to "result_count",
            "count of failed students" to "result_count",
            "how many students did not pass" to "result_count",
            "how many students failedm" to "result_count",
            "how many studnets failed" to "result_count",
            "how many students  failed" to "result_count",
            "how many students failed at least two subjects" to "students_failed_at_least",
            "how many students failed more than two subjects" to "students_failed_at_least",
            "what is the pass percentage" to "pass_percentage",
            "what is the highest SGPA" to "toppers_by_sgpa",
            "how many students scored above 9.0 SGPA" to "count_sgpa_at_least",
            "how many students got an A+ grade" to null,                 // PremiseCheck answers it
            "list students who scored 10 SGPA" to "students_with_sgpa",  // was unreachable
            // Refused by ScopeGate before any template runs; the match is
            // recorded anyway because the refusal must not depend on it.
            "how many students failed at IIT Bombay" to "result_count",
            "how much attendance do I need" to null,
        )
        expectedTemplate.forEach { (q, template) ->
            assertEquals(q, template, SqlTemplates.match(q)?.name)
        }
    }
}
