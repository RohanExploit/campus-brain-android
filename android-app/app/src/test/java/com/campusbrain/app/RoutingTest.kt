package com.campusbrain.app

import com.campusbrain.app.data.Route
import com.campusbrain.app.retrieval.RouteRules
import com.campusbrain.app.retrieval.SqlTemplates
import com.campusbrain.app.retrieval.TabularIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Routing decides accuracy here.
 *
 * The backend's measured history is that routing, not retrieval, was the
 * bottleneck: 31 of 66 FACT questions were misrouted to TABULAR, and the
 * TABULAR->FACT fallback that fixed it moved stress FACT accuracy from 50% to
 * 94%. Each case below pins a specific decision that was paid for once.
 */
class RouteRulesTest {

    private fun route(q: String): Route? = RouteRules.classify(q)?.first

    @Test fun `bare roll number routes to TABULAR`() {
        // Without this rule the query fell through to the non-deterministic
        // classifier and the route flickered between runs.
        assertEquals(Route.TABULAR, route("Did student 23067571263053 pass?"))
        assertEquals(Route.TABULAR, route("23067571263053"))
    }

    @Test fun `explicit roll phrasing routes to TABULAR`() {
        assertEquals(Route.TABULAR, route("roll no 23067571263053"))
        assertEquals(Route.TABULAR, route("Roll Number: 2267571242025"))
    }

    @Test fun `bare word student must not route alone`() {
        // "student mentorship program" is a document question. Routing it to
        // the student tables would answer it from the wrong corpus entirely.
        assertNull(route("student mentorship program"))
        assertNull(route("student handbook"))
    }

    @Test fun `student plus record context routes to TABULAR`() {
        assertEquals(Route.TABULAR, route("show me the student record"))
        assertEquals(Route.TABULAR, route("marks of a student"))
    }

    @Test fun `bare percentage is not an aggregate keyword`() {
        // AGG_KW holds "pass percentage" / "pass rate" / "pass %", not bare
        // "percentage", so an attendance-policy question stays on the document
        // path instead of being dragged to the student tables.
        assertNull(route("What is the minimum attendance percentage?"))
        assertEquals(Route.TABULAR, route("what is the pass percentage"))
    }

    @Test fun `document attribute phrasing wins over aggregates`() {
        assertEquals(Route.FACT, route("authors of RAG-MicroSim"))
        assertEquals(Route.FACT, route("which university is KRIET affiliated with"))
        assertEquals(Route.FACT, route("what programs offered"))
    }

    @Test fun `attribute regex requires adjacency, matching the backend`() {
        // Verified against retrieval/router.py: "what programs are offered"
        // does NOT match there either. The alternation wants "programs offered"
        // adjacent, or "programs ... offers"; offers? does not match
        // "offered". Pinned so a well-meant "fix" to the Kotlin regex cannot
        // silently diverge from the Python one.
        assertNull(route("what programs are offered"))
    }

    @Test fun `aggregate keywords route to TABULAR`() {
        listOf(
            "how many students are there",
            "list all students",
            "which students failed",
            "top 3 by sgpa",
            "who are the toppers",
            "students with backlog",
        ).forEach { assertEquals(it, Route.TABULAR, route(it)) }
    }

    @Test fun `plain document questions fire no rule`() {
        listOf(
            "What documents do I need for a bonafide certificate?",
            "When does the library close?",
            "Who won the 2019 cricket world cup",
        ).forEach { assertNull(it, route(it)) }
    }

    // --- a subject ranked by difficulty is a table, not a document --------

    @Test fun `which subject students struggle with routes to TABULAR`() {
        // Observed: routed GLOBAL, retrieved the attendance condonation notice,
        // and answered "A student with subject-wise attendance between 65% and
        // 74% may apply for condonation." No document in the bundle says which
        // subject is hardest; student_subjects does.
        assertEquals(Route.TABULAR, route("which subject do students struggle with most"))
        assertEquals(Route.TABULAR, route("what is the hardest subject"))
        assertEquals(Route.TABULAR, route("which subjects are students weakest in"))
        assertEquals(Route.TABULAR, route("which subject do students do worst in"))
    }

    @Test fun `difficulty needs the noun beside it`() {
        // The guard SUPERLATIVE_SCORE already needed and for the same reason: a
        // bare difficulty word belongs to plenty of document questions, and a
        // bare "subject" to more.
        assertNull(route("what subjects are in the syllabus"))
        assertNull(route("is the admission process difficult"))
        assertNull(route("where do I find the syllabus"))
    }

    // --- how the cohort did is a query, not a passage ---------------------

    @Test fun `an overview of the cohort routes to TABULAR`() {
        // Observed: "I don't have enough information to answer that", while the
        // records held 334 passes of 369.
        assertEquals(Route.TABULAR, route("how is the college doing overall this semester"))
        assertEquals(Route.TABULAR, route("how did the students perform this semester"))
        assertEquals(Route.TABULAR, route("give me a summary of the results"))
    }

    @Test fun `an overview cue without a cohort scope fires nothing`() {
        // "how is the library doing" is not this question and no table answers
        // it. The scope word is what stops the rule swallowing the corpus.
        assertNull(route("how is the library doing these days"))
        assertNull(route("overall, is the canteen any good"))
    }

    @Test fun `an overview of one topic is not an overview of the cohort`() {
        // Cue and scope both present, and the answer is still a document. This
        // is the case that made the exclusion list necessary: a pass rate is
        // not an answer to either of these.
        assertNull(route("what is the overall attendance of students"))
        assertNull(route("give me an overview of the semester registration process"))
        assertNull(route("summary of the scholarship results for students"))
    }

    @Test fun `an explicit aggregate still beats the overview rule`() {
        // Order is load-bearing: AGG_KW is checked first, so a question that
        // names the figure it wants keeps the template that computes exactly
        // that figure rather than being answered with a summary.
        assertEquals(Route.TABULAR to "rule: aggregate keyword \"pass percentage\"",
            RouteRules.classify("what is the overall pass percentage"))
        assertEquals(Route.TABULAR to "rule: aggregate keyword \"pass rate\"",
            RouteRules.classify("is the pass rate good or bad"))
    }
}

class SqlTemplatesTest {

    private fun name(q: String) = SqlTemplates.match(q)?.name

    @Test fun `percentage templates are not swallowed by the fail-count branch`() {
        assertEquals("pass_percentage", name("what is the pass percentage"))
        assertEquals("fail_percentage", name("what is the fail percentage"))
    }

    @Test fun `worded counts match, not just digits`() {
        // Digits-only matching sent "failed two or more subjects" to text-to-SQL,
        // which answered with the count of ALL failing students (35 instead of
        // 16) -- a wrong answer to a question the database can answer exactly.
        assertEquals("students_failed_at_least", name("how many students failed two or more subjects"))
        assertEquals("students_failed_at_least", name("students who failed at least 3 subjects"))
    }

    @Test fun `more than N means at least N plus one`() {
        assertEquals("students_failed_at_least", name("students with more than 2 backlogs"))
    }

    @Test fun `subject scoped failure question does not match per-student template`() {
        assertEquals("subject_failure_counts", name("which subject has the most failures"))
    }

    @Test fun `toppers and bottom rankings`() {
        assertEquals("toppers_by_sgpa", name("top 3 by sgpa"))
        assertEquals("toppers_by_sgpa", name("who are the toppers"))
        assertEquals("bottom_by_sgpa", name("lowest 5 sgpa"))
    }

    @Test fun `student count only when nothing else qualifies it`() {
        assertEquals("student_count", name("how many students are there"))
        assertTrue(name("how many students failed") != "student_count")
    }

    @Test fun `a plain document question matches no template`() {
        assertNull(name("What is the minimum attendance percentage?"))
        assertNull(name("when is the fee deadline"))
    }

    @Test fun `difficulty framing reaches the pass-rate ranking`() {
        // Observed answer: the attendance condonation band. Difficulty is a
        // pass rate whether or not the student says the words "pass rate".
        assertEquals("subject_pass_rates", name("which subject do students struggle with most"))
        assertEquals("subject_pass_rates", name("what is the hardest subject"))
        assertEquals("subject_pass_rates", name("which subject do students do worst in"))
    }

    @Test fun `difficulty framing does not steal the count or the explicit rate`() {
        // The three subject-scoped rankings answer three different questions
        // and this bundle gives them different answers: BTCOC502 has the most
        // failures (16) at a 94.7% rate, BTAIHM503B the worst rate (90.9%).
        assertEquals("subject_failure_counts", name("which subject has the most failures"))
        assertEquals("subject_pass_rates", name("which subject has the lowest pass rate"))
        assertEquals("subject_pass_rates", name("which subject has the highest pass rate"))
    }

    @Test fun `the difficulty branch attaches no caveat`() {
        // The guard must see the question as fully evaluated. A real ranking
        // carrying "that figure does not account for the rest of your question"
        // reads as a hedge on a number that is exactly right.
        val r = SqlTemplates.resolve("which subject do students struggle with most")
        assertTrue("unexpected: $r", r is SqlTemplates.Resolution.Answered)
    }

    @Test fun `a cohort overview matches the summary template, and only last`() {
        assertEquals("semester_overview", name("how is the college doing overall this semester"))
        // Every narrower question is a better answer to itself than a summary
        // is, so the summary may only claim what nothing else would.
        assertEquals("pass_percentage", name("what is the overall pass percentage"))
        assertEquals("average_sgpa", name("what is the overall average sgpa of students"))
        assertNull(name("what is the overall attendance of students"))
    }

    @Test fun `the overview attaches no caveat`() {
        val r = SqlTemplates.resolve("how is the college doing overall this semester")
        assertTrue("unexpected: $r", r is SqlTemplates.Resolution.Answered)
    }
}

class TabularIntentTest {

    @Test fun `roll number yields record_by_roll with the roll captured`() {
        val i = TabularIntent.classify("result of 2267571242025")
        assertEquals("record_by_roll", i.kind)
        assertEquals("2267571242025", i.params["roll"])
    }

    @Test fun `aggregate phrasing vetoes the single-student lookup branch`() {
        // The veto stops the LOOKUP_KW branch claiming this. Verified against
        // retrieval/intent.py, the cascade then falls to the final
        // record-or-student clause and still returns name_search -- so the veto
        // alone is not what protects this query.
        //
        // What protects it is the layer above: SqlTemplates matches
        // student_count first, and the intent cascade is never consulted. Both
        // halves are pinned here so neither can be "simplified" away.
        assertEquals("name_search", TabularIntent.classify("how many students have a result").kind)
        assertEquals("student_count", SqlTemplates.match("how many students have a result")?.name)
    }

    @Test fun `sgpa threshold is anchored to its keyword`() {
        // "semester 3 students below 6 sgpa" must give 6, not the 3 that appears
        // first in the sentence.
        val i = TabularIntent.classify("semester 3 students below 6 sgpa")
        assertEquals("below_sgpa", i.kind)
        assertEquals(6.0, i.params["threshold"]!!.toDouble(), 1e-9)
    }

    @Test fun `name lookup for a plain marksheet request`() {
        assertEquals("name_search", TabularIntent.classify("marksheet of Rohan Gaikwad").kind)
    }
}

/**
 * Grade semantics. Cheap, and it guards the exact bug models/grades.py
 * memorialises: a duplicated wrong copy of this mapping misclassified 'AB' (a
 * pass at 8.5) as a failure across ingestion, retrieval and audit.
 */
class GradesTest {

    @Test fun `AB is a pass, not an absence`() {
        assertEquals(false, Grades.isFail("AB"))
        assertEquals(8.5, Grades.GRADE_POINTS["AB"]!!, 1e-9)
    }

    @Test fun `FF is the only academic fail`() {
        assertEquals(true, Grades.isFail("FF"))
        listOf("EX", "AA", "AB", "BB", "BC", "CC", "CD", "DD", "DE", "EE")
            .forEach { assertEquals(it, false, Grades.isFail(it)) }
    }

    @Test fun `AU is an audit, never a fail`() {
        assertEquals(true, Grades.isAudit("AU"))
        assertEquals(false, Grades.isFail("AU"))
        assertNull(Grades.GRADE_POINTS["AU"])
    }

    @Test fun `scale matches the printed legend`() {
        assertEquals(
            mapOf(
                "EX" to 10.0, "AA" to 9.0, "AB" to 8.5, "BB" to 8.0, "BC" to 7.5,
                "CC" to 7.0, "CD" to 6.5, "DD" to 6.0, "DE" to 5.5, "EE" to 5.0,
                "FF" to 0.0,
            ),
            Grades.GRADE_POINTS
        )
    }

    @Test fun `recomputed SGPA does not multiply credits twice`() {
        // grade_point is stored as base_point * credit, so AB at 2 credits is
        // 17.0 and DE at 4 credits is 22.0. Multiplying by credit again here
        // would roughly triple the result.
        val rows = listOf<Pair<String?, Pair<Int, Double>>>(
            "AB" to (2 to 17.0),   // 8.5
            "DE" to (4 to 22.0),   // 5.5
        )
        val sgpa = Grades.recomputeSgpa(rows)!!
        assertEquals((17.0 + 22.0) / 6.0, sgpa, 1e-9)
        assertTrue("SGPA must stay on the 0-10 scale", sgpa in 0.0..10.0)
    }

    @Test fun `audit rows are excluded from both numerator and denominator`() {
        val withAudit = listOf<Pair<String?, Pair<Int, Double>>>(
            "AB" to (2 to 17.0),
            "AU" to (0 to 0.0),
        )
        val withoutAudit = listOf<Pair<String?, Pair<Int, Double>>>("AB" to (2 to 17.0))
        assertEquals(Grades.recomputeSgpa(withoutAudit)!!, Grades.recomputeSgpa(withAudit)!!, 1e-9)
    }

    @Test fun `no credits yields null rather than a divide by zero`() {
        assertNull(Grades.recomputeSgpa(listOf("AU" to (0 to 0.0))))
    }
}

class AnswerComposerTest {

    @Test fun `empty retrieval abstains with the shared sentence`() {
        val c = com.campusbrain.app.answer.AnswerComposer.compose("anything", emptyList())
        assertTrue(c.abstained)
        // Byte-identical to kAbstentionSentence in the Flutter prompt_builder.
        assertEquals("I don't have enough information to answer that.", c.lead)
    }

    private val libraryChunk = com.campusbrain.app.data.RetrievedChunk(
        1, "library.md", "Library Services",
        "The library holds 42,000 volumes and subscribes to twelve journals.",
        1.0
    )

    @Test fun `a question the corpus half-covers abstains and offers the nearest material`() {
        // Retrieval always returns something -- BM25 ranks even a bad match --
        // so "no results" is not what protects against a wrong answer. The
        // term-overlap floor is. What is offered instead must be framed as not
        // being an answer, never asserted as one.
        //
        // "fee" and "library" are both corpus vocabulary, so this is the thin
        // branch: the topic was found, no sentence answered. Pointing at the
        // document is a real lead here, which is why the two abstentions are
        // worded differently.
        val c = com.campusbrain.app.answer.AnswerComposer.compose(
            "what is the fee for the library", listOf(libraryChunk))
        assertTrue("must not assert an answer it cannot support", c.abstained)
        assertFalse("the topic WAS retrieved; this is not the off-topic branch", c.offTopic)
        assertTrue(c.lead.startsWith("I don't have enough information"))
        assertTrue("should point at the nearest material", c.lead.contains("Library Services"))
        assertTrue("must say plainly that it is not an answer",
            c.lead.contains("none of it addresses the question directly"))
    }

    @Test fun `a question on a subject the corpus does not carry names what is missing`() {
        // The other branch. Nothing retrieved mentions cricket, so the three
        // nearest-ranked documents are the top of a ranking that had nothing to
        // rank, and offering them as "the closest material" is a false lead.
        //
        // Measured against the shipped bundle: "won" and "cup" occur in 0 of
        // 493 chunks, "cricket" in 1, "world" in 7, "2019" in 2.
        val corpus = com.campusbrain.app.answer.AnswerCheck.CorpusVocabulary {
            it !in setOf("won", "cup")
        }
        val c = com.campusbrain.app.answer.AnswerComposer.compose(
            "who won the 2019 cricket world cup", listOf(libraryChunk), vocabulary = corpus)
        assertTrue(c.abstained)
        assertTrue("retrieval found none of the question's own words", c.offTopic)
        assertTrue(c.lead.startsWith("I don't have enough information"))
        assertTrue("must name what is missing, got: ${c.lead}",
            c.lead.contains("Nothing in the records mentions"))
        assertFalse("must not offer an unrelated document as a lead",
            c.lead.contains("Library Services"))
    }

    @Test fun `lead is not repeated inside the passages list`() {
        val chunk = com.campusbrain.app.data.RetrievedChunk(
            1, "doc.md", "Section",
            "The library closes at 8 PM on weekdays and 5 PM on Saturday. " +
                "Members must return books within fourteen days of issue.",
            1.0
        )
        val c = com.campusbrain.app.answer.AnswerComposer.compose("when does the library close", listOf(chunk))
        assertNotNull(c.lead)
        // The passage carries the full text; the lead is a sentence out of it.
        // They are separate fields precisely so the UI does not print both.
        assertEquals(1, c.passages.size)
        assertTrue(c.passages[0].body.contains(c.lead))
    }
}
