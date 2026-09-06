package com.campusbrain.app

import android.util.Log
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.Route
import com.campusbrain.app.data.TAG

/**
 * Canned queries with their expected route, run against the real bundle.
 *
 * The cases are not invented: each one is a regression the backend's own
 * comments record paying for -- the bare-roll rule, the "student" word being
 * too weak to route on alone, the aggregate veto on name lookups, the SGPA
 * threshold being anchored to its keyword rather than the first number in the
 * sentence, and the empty query that would otherwise print the whole roster.
 */
object RouterSmoke {

    data class Case(val query: String, val expected: Route, val why: String)

    val CASES = listOf(
        // Pins a near-miss worth keeping pinned. AGG_KW contains "pass
        // percentage" / "pass rate" / "pass %", NOT bare "percentage", so an
        // attendance-policy question stays on the document path instead of
        // being dragged to the student tables by the word "percentage".
        Case("What is the minimum attendance percentage?", Route.FACT,
            "bare 'percentage' is not an aggregate keyword"),
        Case("Did student 23067571263053 pass?", Route.TABULAR, "bare roll number"),
        Case("roll no 23067571263053", Route.TABULAR, "roll-number pattern"),
        Case("student mentorship program", Route.FACT, "bare 'student' must NOT route alone"),
        Case("what is the pass percentage", Route.TABULAR, "pass_percentage template"),
        Case("what is the fail percentage", Route.TABULAR, "must not be swallowed by fail-count"),
        Case("top 3 by sgpa", Route.TABULAR, "toppers_by_sgpa(3)"),
        Case("which subject has the most failures", Route.TABULAR, "subject_failure_counts"),
        Case("how many students failed two or more subjects", Route.TABULAR,
            "worded count -> students_failed_at_least(2)"),
        Case("how many students are there", Route.TABULAR, "student_count"),
        Case("authors of RAG-MicroSim", Route.FACT, "document-attribute regex beats aggregates"),
        Case("What documents do I need for a bonafide certificate?", Route.FACT, "plain fact lookup"),
        Case("who won the 2019 cricket world cup", Route.FACT, "out of corpus - expect abstention"),
    )

    fun run(repo: BrainRepository): List<Check> {
        val checks = mutableListOf<Check>()
        var routeOk = 0
        for (c in CASES) {
            val got = runCatching { repo.router.classify(c.query) }.getOrNull()
            val ok = got?.first == c.expected
            if (ok) routeOk++
            Log.i(TAG, "route ${if (ok) "OK  " else "MISS"} ${got?.first} (want ${c.expected}) " +
                "[${got?.second}]  \"${c.query}\"")
        }
        checks += Check("router classification", routeOk == CASES.size,
            "$routeOk/${CASES.size} cases routed as expected")

        // Empty input must never reach the roster branch.
        val blank = runCatching { repo.router.answer("   ") }.getOrNull()
        val leaked = blank?.answer?.contains(Regex("\\d{10,}")) ?: true
        checks += Check("blank query leaks no roster", !leaked,
            blank?.answer?.take(60) ?: "threw")

        // End-to-end answers, including the fallback path.
        val probes = listOf(
            "what is the pass percentage",
            "how many students are there",
            "What documents do I need for a bonafide certificate?",
            "who won the 2019 cricket world cup",
        )
        for (p in probes) {
            val r = runCatching { repo.router.answer(p) }.getOrNull()
            Log.i(TAG, "answer [${r?.route}] \"$p\" -> ${r?.answer?.replace("\n", " ")?.take(110)}")
            r?.trace?.forEach { (k, v) -> Log.i(TAG, "    $k = $v") }
        }

        // End-to-end latency, measured rather than assumed. The whole reason
        // this app composes extractively instead of running a small LLM is that
        // the generative options cost 2-15s to first token on a handset; that
        // trade only pays if the extractive path is genuinely fast.
        val timings = probes.map { q ->
            val t0 = System.nanoTime()
            runCatching { repo.router.answer(q) }
            q to (System.nanoTime() - t0) / 1_000_000
        }
        val worst = timings.maxByOrNull { it.second }
        checks += Check("answer latency under 5s", (worst?.second ?: 0L) < 5000,
            timings.joinToString("  ") { (q, ms) -> "${q.take(18)}=${ms}ms" })

        val pct = runCatching { repo.router.answer("what is the pass percentage") }.getOrNull()
        checks += Check("pass percentage answered deterministically",
            pct?.route == Route.TABULAR && pct.answer.contains("90.5"),
            pct?.answer?.take(80) ?: "threw")

        val oos = runCatching { repo.router.answer("who won the 2019 cricket world cup") }.getOrNull()
        checks += Check("out-of-corpus question abstains",
            oos?.abstained == true,
            oos?.answer?.take(80) ?: "threw")

        checks.forEach { Log.i(TAG, "[${if (it.ok) "PASS" else "FAIL"}] ${it.name} :: ${it.detail}") }
        return checks
    }
}
