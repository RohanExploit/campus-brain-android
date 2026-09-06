package com.campusbrain.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hard battery. [AdversarialBatteryTest] asks single-fact questions and
 * checks the system does not lie; this one asks questions whose *correct*
 * answer is hard to reach — two constraints at once, a join, a comparison
 * across documents, or a true value that contradicts the obvious guess.
 *
 * Every expectation was computed from brain.db before the probe was written,
 * so a disagreement is a defect and not a matter of taste.
 *
 * Two facts about this corpus generate most of the traps below, and neither is
 * discoverable from a question's wording:
 *
 *   1. **Every FAIL student has a NULL sgpa.** 35 FAIL rows, 0 of them carry a
 *      grade point; all 334 PASS rows do. So "students below 6 SGPA who failed"
 *      is 0 — not because nobody did badly, but because the two facts never
 *      coexist on a row. A system that joins them naively invents a number.
 *
 *   2. **Most failures is not lowest pass rate.** BTCOC502 has 16 FF, more than
 *      any other subject, and a 94.7% pass rate. BTAIHM503B has 6 FF and 90.9%.
 *      Asked which subject students do worst in, "BTCOC502" is the wrong answer
 *      to the right-sounding question.
 *
 * Ground truth, all recomputed 2026-09-04:
 *   369 students · 334 PASS / 35 FAIL · avg SGPA 7.344 (PASS only)
 *   25 below 6.0 SGPA, every one of them a PASS
 *   16 students with >=2 FF · 12 with >=3 FF · highest SGPA 8.82
 *   worst pass rate BTAIHM503B 90.9% · most failures BTCOC502 (16)
 *   grade scale AA AB BB BC CC CD DD DE EE EX AU FF — no A+, no O, no S
 *   is_supply and seat_cancelled are 0 for all 369 rows
 */
class HardQueryBatteryTest {

    private data class Probe(val group: String, val q: String, val expect: String, val why: String)

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

    @Test fun runHardBattery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        BrainRepository.init(ctx)
        val state = BrainRepository.state.value
        assertTrue("corpus not ready: $state", state is InitState.Ready)
        val repo = (state as InitState.Ready).repo

        Log.i("HARDBAT", "===== BEGIN HARD BATTERY =====")
        for (p in probes) {
            val started = System.currentTimeMillis()
            val result = runCatching { repo.router.answer(p.q) }
            val ms = System.currentTimeMillis() - started
            Log.i("HARDBAT", "### [${p.group}] Q: ${p.q}")
            Log.i("HARDBAT", "EXPECT: ${p.expect}   (${p.why})")
            result.onSuccess { r ->
                Log.i("HARDBAT", "ROUTE=${r.route} ABSTAINED=${r.abstained} MS=$ms")
                r.answer.replace('\n', ' ').chunked(300).forEach { Log.i("HARDBAT", "A: $it") }
            }.onFailure { Log.i("HARDBAT", "ERROR ${it.javaClass.simpleName}: ${it.message}") }
            Log.i("HARDBAT", "---")
        }
        Log.i("HARDBAT", "===== END HARD BATTERY =====")
    }
}
