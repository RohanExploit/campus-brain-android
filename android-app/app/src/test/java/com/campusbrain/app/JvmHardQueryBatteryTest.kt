package com.campusbrain.app

import com.campusbrain.app.jvm.JvmCorpus
import com.campusbrain.app.jvm.ProbeBattery
import com.campusbrain.app.jvm.ProbeBattery.Probe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM port of `app/src/androidTest/java/com/campusbrain/app/HardQueryBatteryTest.kt`.
 *
 * Same 20 probes, copied verbatim with their groups and their rationales. The
 * androidTest file is untouched.
 *
 * Where the adversarial battery asks single-fact questions and checks the
 * system does not lie, this one asks questions whose *correct* answer is hard
 * to reach -- two constraints at once, a join, a comparison across documents,
 * or a true value that contradicts the obvious guess. Two facts about the
 * corpus generate most of the traps, and neither is discoverable from a
 * question's wording:
 *
 *   1. Every FAIL student has a NULL sgpa. 35 FAIL rows, 0 with a grade point;
 *      all 334 PASS rows have one. So "students below 6 SGPA who failed" is 0 --
 *      not because nobody did badly, but because the two facts never coexist on
 *      a row.
 *   2. Most failures is not lowest pass rate. BTCOC502 has 16 FF and a 94.7%
 *      pass rate; BTAIHM503B has 6 FF and 90.9%.
 *
 * Both are asserted directly, against the real corpus, in
 * [JvmCorpusFactsTest] -- which is where this suite's teeth are. Here, as on
 * the device, the graded transcript is the artefact. See
 * [JvmAdversarialBatteryTest] for why a probe miss is reported rather than
 * failed.
 */
class JvmHardQueryBatteryTest {

    // --- probes, copied from HardQueryBatteryTest.kt ----------------------

    private val probes = listOf(
        // 1. Two constraints in one question. The join is the hard part; each
        //    half alone is already answerable by an existing template.
        Probe("MULTI", "how many students have SGPA below 6", "25",
            "single constraint, the control for the two below"),
        Probe("MULTI", "how many students below 6 SGPA also failed", "0",
            "TRAP: FAIL rows carry no SGPA, so the intersection is empty"),
        Probe("MULTI", "how many students failed more than one subject and have SGPA below 6", "0",
            "TRAP: same disjointness, reached through a join this time"),
        Probe("MULTI", "what is the average SGPA", "7.34",
            "aggregate over the 334 rows that have one"),
        Probe("MULTI", "what is the average SGPA of students who failed", "no SGPA / none / cannot",
            "TRAP: avg over an empty set. Must not report 0.0 as a value"),

        // 2. Superlatives that disagree with each other. Both are answerable and
        //    they have different answers; conflating them is the failure.
        Probe("SUPERLATIVE", "which subject has the most failures", "BTCOC502",
            "count of FF, control"),
        Probe("SUPERLATIVE", "which subject has the lowest pass rate", "BTAIHM503B",
            "TRAP: rate, not count. BTCOC502 is the wrong answer here"),
        Probe("SUPERLATIVE", "which subject do students struggle with most", "BTAIHM503B / BTCOC502",
            "ambiguous by design; either is defensible, inventing a third is not"),

        // 3. Multi-hop. The rule is in a policy document, the evidence is in the
        //    records, and neither document mentions the other.
        Probe("MULTIHOP", "am I eligible for a scholarship if my attendance is 70 percent",
            "75 / minimum attendance / below",
            "needs the eligibility matrix AND the attendance rule"),
        Probe("MULTIHOP", "can a student with a backlog apply for the merit scholarship",
            "eligib", "rule lives in the scholarship matrix, not the results table"),
        Probe("MULTIHOP", "what happens to my scholarship if I am debarred for attendance",
            "attendance / debarred / scholarship",
            "two policies, no shared document"),

        // 4. Comparative and corpus-wide. GLOBAL route territory.
        Probe("COMPARE", "how is the college doing overall this semester", "90.5 / 334 / pass",
            "corpus-wide synthesis, must land on a real figure"),
        Probe("COMPARE", "is the pass rate good or bad", "90.5",
            "asks for a judgement; must still cite the number"),

        // 5. Negation and exclusion, where the wording inverts the set.
        Probe("NEGATION", "how many students have no backlogs", "334 / 297",
            "either reading is defensible; a number outside {297,334} is not"),
        Probe("NEGATION", "which students did not appear for the exam", "0 / none / no",
            "seat_cancelled and is_supply are 0 across all 369 rows"),

        // 6. Premises the corpus contradicts. The honest answer corrects the
        //    question rather than answering the version that was asked.
        Probe("PREMISE", "how many students got an O grade", "no O / not a grade",
            "grade scale has no O"),
        Probe("PREMISE", "how many students scored above 9.5 SGPA", "0 / no students",
            "true zero; highest is 8.82"),
        Probe("PREMISE", "list students who were caught cheating", "abstain",
            "the corpus holds no such field at all"),

        // 7. Compound questions. Two answerable halves in one sentence; answering
        //    only the first is the common failure.
        Probe("COMPOUND", "how many students failed and what is the pass percentage", "35 / 90.5",
            "both halves are answerable, both should appear"),
        Probe("COMPOUND", "what is the minimum attendance and what happens if I miss it",
            "75 / condonation / debarred",
            "rule plus consequence, spans two documents"),
    )

    @Test fun `the hard battery runs end to end on the JVM`() {
        val graded = ProbeBattery.run("JVM HARD BATTERY", probes)

        assertTrue("no probes ran", graded.size == 20)
        graded.filter { it.result == null }.forEach {
            throw AssertionError("probe threw: \"${it.probe.q}\"", it.error)
        }

        if (JvmCorpus.vectorArm) {
            val passed = graded.count { it.passed }
            assertTrue(
                "hard battery regressed: $passed/20 satisfied, floor is $FLOOR. " +
                    "Read the transcript above -- every probe prints its full answer.",
                passed >= FLOOR,
            )
        }
    }

    private companion object {
        /**
         * Measured 2026-09-07: 19/20 probes satisfied, with the fts5 and vector
         * arms both live.
         *
         * Set one below that on purpose. ONNX Runtime's float results differ in
         * the last bits across platforms and RoutePrototypes fires only on a
         * 0.05 cosine margin, so one borderline question can classify
         * differently on another machine. A floor with one probe of slack still
         * catches a real regression and cannot go red for arithmetic noise --
         * and the printed transcript, not this constant, is the record of what
         * actually happened.
         */
        const val FLOOR = 18
    }
}
