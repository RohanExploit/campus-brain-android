package com.campusbrain.app

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs a battery of realistic student questions through the REAL router
 * against the REAL on-device corpus, and logs each answer in full.
 *
 * This exists because tapping the UI with `adb shell input` proved unreliable
 * (the suggestion chips shift under the soft keyboard and swallow taps), and
 * because approximating retrieval in a Python script would test a
 * reimplementation rather than the code that ships. Here the assertions are
 * deliberately weak -- the value is the logged transcript, which says which
 * questions the corpus can actually carry.
 */
class QueryBatteryTest {

    private val queries = listOf(
        // --- multi-constraint / decision-support ---
        "What is the minimum attendance percentage required to sit for exams?",
        "What happens if my attendance falls below the required percentage?",
        "How do I apply for revaluation and what does it cost?",
        "What is the procedure to get a bonafide certificate?",
        "What scholarships are available and who is eligible?",
        "What documents do I need for the scholarship application?",
        "When does the semester start and when are the exams?",
        "Who do I contact about hostel allotment?",
        "What is the fee structure and when is the last date to pay?",
        "How do I report a grievance and who handles it?",
        "What are the placement eligibility criteria?",
        "Which companies are hiring and what are the packages?",
        "What is the library book issue limit and fine policy?",
        "How do I apply for a duplicate ID card?",
        // --- tabular / analytical ---
        "What is the pass percentage?",
        "Which subject has the most failures?",
        "Top 3 students by SGPA",
        "How many students are there?",
        // --- should abstain or go general ---
        "Who won the 2019 cricket world cup?",
    )

    @Test fun runBattery() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        BrainRepository.init(ctx)
        val state = BrainRepository.state.value
        assertTrue("corpus not ready: $state", state is InitState.Ready)
        val repo = (state as InitState.Ready).repo

        Log.i("BATTERY", "===== BEGIN QUERY BATTERY =====")
        for (q in queries) {
            val started = System.currentTimeMillis()
            val result = runCatching { repo.router.answer(q) }
            val ms = System.currentTimeMillis() - started
            Log.i("BATTERY", "### Q: $q")
            result.onSuccess { r ->
                Log.i("BATTERY", "ROUTE=${r.route} ABSTAINED=${r.abstained} MS=$ms")
                Log.i("BATTERY", "SOURCES=" + r.sources.joinToString("; ") { it.docId + "/" + it.section })
                // Chunk the answer: logcat truncates long single lines.
                r.answer.chunked(300).forEach { Log.i("BATTERY", "A: $it") }
            }.onFailure { Log.i("BATTERY", "ERROR ${it.javaClass.simpleName}: ${it.message}") }
            Log.i("BATTERY", "---")
        }
        Log.i("BATTERY", "===== END QUERY BATTERY =====")
    }
}
