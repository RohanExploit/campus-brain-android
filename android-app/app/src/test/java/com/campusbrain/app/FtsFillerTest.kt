package com.campusbrain.app

import com.campusbrain.app.jvm.JvmCorpus
import com.campusbrain.app.retrieval.FtsSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The keyword arm stopped voting for filler, and the answers that reached.
 *
 * [FtsSearch.sanitize] ORs a question's tokens together, so under bm25 every
 * term a chunk carries is a vote for it -- and "what", "is", "if", "it" are
 * carried by most of the corpus. Dropping them from the expression was
 * measured on the JVM harness over 120 questions: 22 answers changed, no route
 * changed, no answer became an abstention and no abstention became an answer.
 * The four that changed materially are pinned below, and the one measured cost
 * -- a scheme name that left a trailing enumeration -- is pinned too, in the
 * last test, rather than hidden.
 *
 * The end-to-end tests need the real corpus and the real embedder, so they
 * skip without `minilm/model.onnx` exactly as [JvmCorpusFactsTest] does. The
 * pure [FtsSearch.sanitize] tests need neither and always run.
 */
class FtsFillerTest {

    // --- the expression -----------------------------------------------------

    @Test fun `filler is dropped from the OR expression`() {
        // "if" survives: AnswerCheck's list is a list of words that say nothing
        // about the TOPIC of an answer, and it was assembled for that job. It
        // is borrowed here unchanged rather than tuned for this one, because a
        // word added to it for the sake of a keyword rank would also change
        // which sentences the answer check accepts. Measured either way:
        // with "if" left in, chunk 150 is the keyword arm's 5th hit instead of
        // its 4th, and every answer below is identical.
        assertEquals(
            "\"if\" OR \"miss\" OR \"attendance\"",
            FtsSearch.sanitize("what happens if I miss it attendance"),
        )
    }

    @Test fun `a number the student typed is a term, not filler`() {
        // The reason the list is borrowed from AnswerCheck but its tokenizer is
        // not: contentTerms drops everything of two characters or fewer, so
        // "70" would not survive it. Measured cost of dropping it, on
        // "am I eligible for a scholarship if my attendance is 70 percent":
        // the eligibility-matrix row holding the Alumni Grant's 80% threshold
        // falls out of the keyword arm's window and the answer lists one
        // scheme fewer.
        val expr = FtsSearch.sanitize("am I eligible for a scholarship if my attendance is 70 percent")
        assertTrue("70 was dropped: $expr", expr.contains("\"70\""))
        assertTrue(expr.contains("\"eligible\""))
        assertFalse("filler survived: $expr", expr.contains("\"my\""))
        assertTrue(FtsSearch.sanitize("can I write the exam with 60% attendance").contains("\"60%\""))
    }

    @Test fun `a question made only of filler still searches for something`() {
        // An empty MATCH expression is a syntax error, not an empty result, so
        // the filter must never empty the expression on its own.
        assertEquals("\"what\" OR \"is\" OR \"it\"", FtsSearch.sanitize("what is it"))
        assertEquals("", FtsSearch.sanitize("???"))
    }

    @Test fun `the operator guard still runs ahead of the filter`() {
        assertEquals("\"co\" OR \"operative\"", FtsSearch.sanitize("co-operative"))
        assertEquals("\"cats\" OR \"dogs\"", FtsSearch.sanitize("cats AND dogs"))
    }

    // --- the answers this reached -------------------------------------------

    private fun answer(q: String): String {
        Assume.assumeTrue(
            "no minilm/model.onnx on this machine; would run ${JvmCorpus.armLabel}",
            JvmCorpus.vectorArm,
        )
        return JvmCorpus.router.answer(q).answer
    }

    /** The 65-74% band, verbatim from chunk 151, and the reason for the change. */
    private val condonationTier =
        "65% to 74%: Placed on the defaulter list and notified in writing to the " +
            "parent/guardian; may apply for condonation (see CONDONATION_PROCEDURE) to be " +
            "permitted to sit the End-Semester examination."

    @Test fun `the compound attendance question now names every tier, not just the worst`() {
        // The open defect this change was measured for. The second half used to
        // answer with the Below-65% row alone -- true, but not the tier a
        // student on 70% is in -- because chunks 151 and 156, which carry the
        // other two rows, sat outside FACT_TOP_K. The probe in
        // JvmHardQueryBatteryTest passed throughout: "no condonation is
        // possible at this tier" contains the word "condonation". Hence an
        // assertion on the tier the corpus states, not on a keyword.
        val a = answer("what is the minimum attendance and what happens if I miss it")
        assertTrue("first half lost: $a", a.contains("A minimum of 75% attendance"))
        assertTrue("Below 65% tier lost: $a", a.contains("Below 65%: Debarred outright"))
        assertTrue("still only the worst tier: $a", a.contains(condonationTier))
        assertTrue(
            "the 75%-and-above tier is missing: $a",
            a.contains("75% and above: No action"),
        )
    }

    @Test fun `a student on 70 percent is told the tier they are actually in`() {
        val a = answer("what happens if my attendance is 70 percent")
        assertTrue("the applicable tier is missing: $a", a.contains(condonationTier))
    }

    @Test fun `a scholarship question is no longer answered from the library rules`() {
        // Measured before: "Renewal: A book may be renewed once for the same
        // loan period..." -- the library's renewal rule, reached because
        // "can", "I", "at", "once" outvoted "scholarships" in the OR.
        val a = answer("can I hold two scholarships at once")
        assertFalse("still the library book renewal rule: $a", a.contains("A book may be renewed"))
        assertTrue("not from the scholarship documents: $a", a.contains("Scholarship"))
    }

    @Test fun `the multi-hop scholarship answer is unchanged, to the byte`() {
        // The delicate one: it needs the eligibility matrix AND the attendance
        // rule, and it is the probe most likely to lose a clause when the
        // keyword arm is re-ranked. Pinned exactly, because a substring check
        // would not have caught the scheme name that an earlier form of this
        // change quietly dropped from the tail.
        assertEquals(
            "No — 70% is below the 75% minimum required. That is the general rule; " +
                "KRIET Alumni Merit Grant uses 80%; Sports and Cultural Excellence " +
                "Scholarship uses 70%; a scheme-specific relaxation uses 70%, which " +
                "applies only to that scheme.",
            answer("am I eligible for a scholarship if my attendance is 70 percent"),
        )
    }

    @Test fun `a scheme enumeration may lose an entry`() {
        // The one measured cost, recorded rather than argued away. The ruling
        // and the tier are unchanged and correct; the trailing list of
        // scheme-specific attendance relaxations lost "Sports and Cultural
        // Excellence Scholarship uses 70%" on this question, because the
        // Sports notice left the fused window. If a later change restores it,
        // this test should be updated, not deleted.
        val a = answer("am I debarred at 60 percent attendance")
        assertTrue("the ruling changed: $a", a.startsWith("No — 60% is below the 75% minimum"))
        assertTrue("the tier is gone: $a", a.contains("60% falls in the below 65% tier"))
        assertTrue("the relaxation is unmentioned: $a", a.contains("relaxation uses 70%"))
    }
}
