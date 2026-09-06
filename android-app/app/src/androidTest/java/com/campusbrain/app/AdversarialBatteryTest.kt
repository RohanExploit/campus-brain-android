package com.campusbrain.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adversarial counterpart to [QueryBatteryTest].
 *
 * That battery asks well-formed questions and logs what comes back, which shows
 * what the corpus can carry. This one is built to make the router fail, because
 * two correct answers to two well-phrased questions say almost nothing: the
 * suggestion chips are the phrasings the system was tuned on, so answering them
 * is closer to a memory test than a retrieval test.
 *
 * Every expectation below is a value computed directly from brain.db, so a
 * disagreement is a real defect and not a matter of taste:
 *
 *   students                      369
 *   result = FAIL                  35      result = PASS   334
 *   pass percentage             90.51
 *   students with >= 1 FF          35
 *   students with >= 2 FF          16      <- deliberately different from 35
 *   students with >= 3 FF          12
 *   subject with most failures  BTCOC502 (16)
 *   highest SGPA                 8.82
 *   students above 9.0 SGPA         0      <- a true zero, not a missing answer
 *
 * Assertions stay weak on purpose; the graded transcript is the artefact. Run:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=\
 *          com.campusbrain.app.AdversarialBatteryTest
 */
class AdversarialBatteryTest {

    /** [expect] is what a correct system must say; [why] names the failure mode. */
    private data class Probe(val group: String, val q: String, val expect: String, val why: String)

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

    @Test fun runAdversarialBattery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        BrainRepository.init(ctx)
        val state = BrainRepository.state.value
        assertTrue("corpus not ready: $state", state is InitState.Ready)
        val repo = (state as InitState.Ready).repo

        Log.i("ADVBAT", "===== BEGIN ADVERSARIAL BATTERY =====")
        for (p in probes) {
            val started = System.currentTimeMillis()
            val result = runCatching { repo.router.answer(p.q) }
            val ms = System.currentTimeMillis() - started
            Log.i("ADVBAT", "### [${p.group}] Q: ${p.q}")
            Log.i("ADVBAT", "EXPECT: ${p.expect}   (${p.why})")
            result.onSuccess { r ->
                Log.i("ADVBAT", "ROUTE=${r.route} ABSTAINED=${r.abstained} MS=$ms")
                r.answer.replace('\n', ' ').chunked(300).forEach { Log.i("ADVBAT", "A: $it") }
            }.onFailure { Log.i("ADVBAT", "ERROR ${it.javaClass.simpleName}: ${it.message}") }
            Log.i("ADVBAT", "---")
        }
        Log.i("ADVBAT", "===== END ADVERSARIAL BATTERY =====")
    }
}
