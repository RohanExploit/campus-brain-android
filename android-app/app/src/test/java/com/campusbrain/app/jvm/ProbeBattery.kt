package com.campusbrain.app.jvm

import com.campusbrain.app.data.AnswerResult

/**
 * The scoring harness the two JVM battery ports share.
 *
 * The device batteries in `app/src/androidTest/` log a transcript and assert
 * almost nothing -- "assertions stay weak on purpose; the graded transcript is
 * the artefact". The ports keep that, because a probe mismatch is a finding to
 * read, not a build to break, and several of these probes are known-open
 * defects rather than regressions. What the ports add is a *graded* transcript:
 * the same `expect` strings the device files already carry, actually evaluated,
 * so a run produces a number instead of 43 paragraphs somebody has to read.
 */
object ProbeBattery {

    /** Copied verbatim from the device battery it came from. */
    data class Probe(val group: String, val q: String, val expect: String, val why: String)

    data class Graded(
        val probe: Probe,
        val result: AnswerResult?,
        val error: Throwable?,
        val passed: Boolean,
        val ms: Long,
    )

    /**
     * Does the answer satisfy the expectation?
     *
     * `expect` is prose written for a human reading a logcat dump, so the rules
     * are stated here rather than guessed at each call site:
     *
     *  - `/` separates alternatives; any one of them satisfies the probe. The
     *    device files use it for genuinely ambiguous questions ("BTAIHM503B /
     *    BTCOC502 -- either is defensible") and for answers that may be phrased
     *    several ways ("no SGPA / none / cannot").
     *  - the literal alternative `abstain` is satisfied by
     *    [AnswerResult.abstained], not by the word appearing in the text.
     *  - `0`, `none` and `no` are also satisfied by an answer that opens with
     *    an explicit denial. This is not a loosened expectation, it is the
     *    expectation the device files already write out longhand:
     *    HardQueryBatteryTest grades the same shape of question with
     *    `"0 / no students"` and `"0 / none / no"`, while
     *    AdversarialBatteryTest writes the terser `"0"` and `"none"` for
     *    `why = "true zero, must not invent"`. The app answers "No students
     *    have an SGPA of 9 or above." -- which is the true zero, stated in
     *    English. Grading that as a miss measures the matcher, not the system.
     *    The denial has to be the *opening* of the answer, so a sentence that
     *    merely contains "no" further in cannot satisfy it.
     *  - a bare number is matched with digit boundaries, so `35` is not
     *    satisfied by `135` and `0` is not satisfied by the `0` inside `9.0`.
     *    A decimal may be extended to its right (`7.34` accepts `7.344`)
     *    because the corpus figure and the rendered figure differ in precision.
     *  - anything else is a case-insensitive substring, which is how the
     *    device files' own `expect` values are written ("eligib", "75",
     *    "condonation").
     *
     * This is deliberately generous. The scoreboard is a reading aid; the
     * printed transcript is the evidence, and a disputed verdict is settled by
     * reading the answer, not by rewriting the matcher.
     */
    fun satisfies(expect: String, r: AnswerResult): Boolean =
        expect.split("/").map { it.trim() }.filter { it.isNotEmpty() }.any { alt ->
            when {
                alt.equals("abstain", ignoreCase = true) -> r.abstained
                alt in DENIALS -> DENIED.containsMatchIn(r.answer) ||
                    (NUMBER.matches(alt) && numberPresent(alt, r.answer))
                NUMBER.matches(alt) -> numberPresent(alt, r.answer)
                else -> r.answer.contains(alt, ignoreCase = true)
            }
        }

    private val NUMBER = Regex("""\d+(\.\d+)?""")

    /** Alternatives that a stated-in-English zero satisfies. */
    private val DENIALS = setOf("0", "none", "no")
    private val DENIED = Regex("""^\s*(no|none|not one|nobody|there are no)\b""", RegexOption.IGNORE_CASE)

    private fun numberPresent(alt: String, answer: String): Boolean {
        val trailing = if (alt.contains('.')) "" else "(?![0-9])"
        return Regex("(?<![0-9.])" + Regex.escape(alt) + trailing).containsMatchIn(answer)
    }

    /** Runs one probe through the real [QueryRouter][com.campusbrain.app.retrieval.QueryRouter]. */
    fun grade(p: Probe): Graded {
        val started = System.currentTimeMillis()
        val outcome = runCatching { JvmCorpus.router.answer(p.q) }
        val ms = System.currentTimeMillis() - started
        val r = outcome.getOrNull()
        return Graded(p, r, outcome.exceptionOrNull(), r != null && satisfies(p.expect, r), ms)
    }

    /**
     * Writes the transcript in the shape the device batteries log it, so the
     * two are diffable by eye, and returns the graded rows.
     *
     * It goes three places, and the reason is that the transcript is the
     * artefact:
     *
     *  - `build/reports/jvm-battery/<tag>.txt`, which is where to look after a
     *    run and needs no Gradle flag to appear;
     *  - the test's stdout, which Gradle captures as `system-out` inside the
     *    JUnit XML under `build/test-results/testDebugUnitTest/`;
     *  - the console, but only when the runner was asked for standard streams
     *    (`--info`, or `testLogging.showStandardStreams`). Turning that on
     *    globally would change the console output of all 334 tests that were
     *    here before this file, which is not this file's business.
     */
    fun run(tag: String, probes: List<Probe>): List<Graded> {
        val out = StringBuilder()
        fun line(s: String) {
            out.append(s).append('\n')
            println(s)
        }

        line("===== BEGIN $tag (${JvmCorpus.armLabel}) =====")
        val graded = probes.map { p ->
            val g = grade(p)
            line("### [${p.group}] Q: ${p.q}")
            line("EXPECT: ${p.expect}   (${p.why})")
            if (g.result != null) {
                line("ROUTE=${g.result.route} ABSTAINED=${g.result.abstained} MS=${g.ms}")
                g.result.answer.replace('\n', ' ').chunked(300).forEach { line("A: $it") }
                g.result.trace.forEach { (k, v) -> line("T: $k = $v") }
            } else {
                line("ERROR ${g.error?.javaClass?.simpleName}: ${g.error?.message}")
            }
            line("VERDICT: ${if (g.passed) "PASS" else "FAIL"}")
            line("---")
            g
        }
        val passed = graded.count { it.passed }
        line("SCOREBOARD $tag: $passed/${graded.size} probes satisfied (${JvmCorpus.armLabel})")
        graded.filterNot { it.passed }.forEach {
            line("  MISS [${it.probe.group}] \"${it.probe.q}\" want=${it.probe.expect} " +
                "route=${it.result?.route} abstained=${it.result?.abstained}")
        }
        line("===== END $tag =====")

        runCatching {
            val dir = java.io.File("build/reports/jvm-battery").apply { mkdirs() }
            val file = java.io.File(dir, tag.lowercase().replace(' ', '-') + ".txt")
            file.writeText(out.toString())
            println("transcript written to ${file.absolutePath}")
        }
        return graded
    }
}
