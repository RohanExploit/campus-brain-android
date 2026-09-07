package com.campusbrain.app

import com.campusbrain.app.jvm.JvmCorpus
import com.campusbrain.app.jvm.ProbeBattery
import com.campusbrain.app.jvm.ProbeBattery.Probe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM port of `app/src/androidTest/java/com/campusbrain/app/AdversarialBatteryTest.kt`.
 *
 * Same 23 probes, same questions, same expectations, same order and the same
 * group headings -- copied, not rewritten. The androidTest file is untouched
 * and stays the on-device scoreboard; this one runs the identical pipeline in
 * an ordinary unit test through [com.campusbrain.app.jvm.JdbcSQLiteDriver], so
 * a retrieval change can be measured without a handset.
 *
 * The pipeline really is the app's: [com.campusbrain.app.data.BrainDb] over the
 * shipped brain.db, `FtsSearch` on the real FTS5 index, `VectorSearch` on the
 * real 493 stored embeddings, the real ONNX MiniLM supplying query vectors and
 * route prototypes, and `QueryRouter` -> `SqlTemplates` / `TabularQueries` /
 * `AnswerComposer` / `AnswerCheck` on top. The two departures from the device
 * are stated in [JvmCorpus]: a JDBC-backed driver, and `cloud = null`.
 *
 * Grading, and why this does not fail the build on a probe miss: the device
 * battery asserts only that the corpus opened, and says why -- "assertions stay
 * weak on purpose; the graded transcript is the artefact". Several of these
 * probes are documented open defects (paraphrase stability was measured at 60%
 * before this file existed), so a red suite here would mean "the known bugs are
 * still there", which is not what a red suite should mean. The floor assertion
 * below is the regression guard instead: it can only be tripped by a change
 * that makes things worse.
 */
class JvmAdversarialBatteryTest {

    // --- probes, copied from AdversarialBatteryTest.kt --------------------

    private val probes = listOf(
        // 1. Paraphrase stability. One question, six ways a student might type it.
        //    CHECKPOINT.md records paraphrase stability at 60%, so this is where
        //    the system is already known to be weakest -- measure it, don't assume.
        Probe("PARAPHRASE", "how many students failed", "35", "baseline phrasing"),
        Probe("PARAPHRASE", "How many students failed?", "35", "capitalised + question mark"),
        Probe("PARAPHRASE", "number of students who failed", "35", "noun phrasing"),
        Probe("PARAPHRASE", "how many failed the exam", "35", "elided subject"),
        Probe("PARAPHRASE", "count of failed students", "35", "inverted noun phrasing"),
        Probe("PARAPHRASE", "how many students did not pass", "35", "negated predicate"),

        // 2. Brittleness. A single stray character already flipped this from a
        //    correct TABULAR answer to an unrelated chunk about a funding centre.
        Probe("TYPO", "how many students failedm", "35", "one trailing char, observed to break routing"),
        Probe("TYPO", "how many studnets failed", "35", "transposed letters in 'students'"),
        Probe("TYPO", "how many students  failed", "35", "double space"),

        // 3. Arithmetic the router must not conflate. 35 students failed overall,
        //    but only 16 failed two or more subjects. Answering 35 here is wrong
        //    and is the single most likely silent error in the whole system.
        Probe("EDGE", "how many students failed at least two subjects", "16", "must NOT be 35"),
        Probe("EDGE", "how many students failed more than two subjects", "12", "'more than 2' means >= 3"),
        Probe("EDGE", "what is the pass percentage", "90.5", "computed, not counted"),
        Probe("EDGE", "which subject has the most failures", "BTCOC502", "argmax over a join"),
        Probe("EDGE", "what is the highest SGPA", "8.82", "max over a nullable column"),

        // 4. False premises. The honest answer is zero or a correction. Inventing
        //    a plausible number here is the worst failure the system can have.
        Probe("PREMISE", "how many students scored above 9.0 SGPA", "0", "true zero, must not invent"),
        Probe("PREMISE", "how many students got an A+ grade", "no A+ / none", "grade scale has no A+"),
        Probe("PREMISE", "list students who scored 10 SGPA", "none", "nobody is above 8.82"),

        // 5. Out of scope. Must abstain rather than answer from model priors.
        Probe("ABSTAIN", "who won the 2019 cricket world cup", "abstain", "not in corpus, not campus"),
        Probe("ABSTAIN", "what is the capital of France", "abstain", "general knowledge"),
        Probe("ABSTAIN", "how many students failed at IIT Bombay", "abstain", "campus-shaped, wrong institution"),

        // 6. Retrieval questions, phrased away from the suggestion chips.
        Probe("FACT-PARA", "minimum attendance to sit exams", "75", "chip phrasing"),
        Probe("FACT-PARA", "how much attendance do I need", "75", "colloquial"),
        Probe("FACT-PARA", "can I write the exam with 60% attendance", "75 / no", "applied, needs the rule"),
    )

    @Test fun `the adversarial battery runs end to end on the JVM`() {
        val graded = ProbeBattery.run("JVM ADVERSARIAL BATTERY", probes)

        assertTrue("no probes ran", graded.size == 23)
        // Every probe must produce an answer. A thrown exception is a defect of
        // a different kind from a wrong answer and is never acceptable.
        graded.filter { it.result == null }.forEach {
            throw AssertionError("probe threw: \"${it.probe.q}\"", it.error)
        }

        // Regression floor, not a target. Measured on this pipeline with the
        // vector arm live; see the class comment for why a miss is reported
        // rather than failed. Raise it when the number improves.
        if (JvmCorpus.vectorArm) {
            val passed = graded.count { it.passed }
            assertTrue(
                "adversarial battery regressed: $passed/23 satisfied, floor is $FLOOR. " +
                    "Read the transcript above -- every probe prints its full answer.",
                passed >= FLOOR,
            )
        }
    }

    private companion object {
        /**
         * Measured 2026-09-07: 23/23 probes satisfied, with the fts5 and vector
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
        const val FLOOR = 22
    }
}
