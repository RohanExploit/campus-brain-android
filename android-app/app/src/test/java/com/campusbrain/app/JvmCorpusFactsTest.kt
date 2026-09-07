package com.campusbrain.app

import com.campusbrain.app.data.query
import com.campusbrain.app.jvm.JvmCorpus
import com.campusbrain.app.retrieval.VectorSearch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import kotlin.math.sqrt

/**
 * The ground truth, asserted against the shipped corpus by the app's own SQL.
 *
 * This is where the JVM harness earns its keep as a regression guard rather
 * than a transcript. Every figure below is a fact about `brain.db` that was
 * computed before the probe batteries were written and that the two device
 * battery files quote in their headers as the standard their probes are graded
 * against. None of them can legitimately change without the corpus changing, so
 * asserting them is free of the "a red suite means the known bugs are still
 * there" problem the batteries have.
 *
 * Until now none of this had executed anywhere except on a handset.
 * [com.campusbrain.app.SqlConstraintGuardTest] states the limitation plainly:
 * "the SQL those templates run is not [covered]: TabularQueries needs a
 * BrainDb, so every expected number below was computed against the shipped
 * bundle with sqlite3 and is recorded in the comment beside it." These tests
 * close that gap -- the numbers are now produced by
 * [com.campusbrain.app.retrieval.TabularQueries] itself.
 */
class JvmCorpusFactsTest {

    private val t get() = JvmCorpus.tabular
    private val conn get() = JvmCorpus.db.conn

    // --- the cohort -------------------------------------------------------

    @Test fun `the corpus holds 369 students, 334 PASS and 35 FAIL`() {
        assertTrue(t.studentCount().answer.contains("369 students"))
        assertTrue(t.resultCount("PASS").answer.startsWith("334 students passed"))
        assertTrue(t.resultCount("FAIL").answer.startsWith("35 students failed"))
        assertEquals(
            listOf(369L),
            conn.query("SELECT COUNT(*) FROM students") { it.getLong(0) },
        )
    }

    @Test fun `the pass percentage is 90 point 5, computed and not counted`() {
        // The trap this template exists for: text-to-SQL wrote
        // COUNT(*) FROM students WHERE result='FAIL', filtering before
        // aggregating, so the denominator equalled the numerator and the answer
        // was always exactly 100%.
        val a = t.passPercentage().answer
        assertTrue("pass percentage was \"$a\"", a.contains("90.5%"))
        assertTrue(a.contains("334 of 369"))
    }

    @Test fun `493 chunks and 493 embeddings, and the corpus carries its dimension`() {
        assertEquals(listOf(493L), conn.query("SELECT COUNT(*) FROM chunks") { it.getLong(0) })
        assertEquals(listOf(493L), conn.query("SELECT COUNT(*) FROM embeddings") { it.getLong(0) })
        assertEquals(384, JvmCorpus.db.embeddingDim)
    }

    // --- the two traps the hard battery is built around -------------------

    @Test fun `every FAIL row has a NULL sgpa, which is why the intersection is empty`() {
        // HardQueryBatteryTest's trap 1. 35 FAIL rows, 0 of them with a grade
        // point; all 334 PASS rows have one. "Students below 6 SGPA who failed"
        // is 0 -- not because nobody did badly, but because the two facts never
        // coexist on a row.
        assertEquals(
            listOf(0L),
            conn.query("SELECT COUNT(*) FROM students WHERE result = 'FAIL' AND sgpa IS NOT NULL") {
                it.getLong(0)
            },
        )
        assertEquals(
            listOf(334L),
            conn.query("SELECT COUNT(*) FROM students WHERE result = 'PASS' AND sgpa IS NOT NULL") {
                it.getLong(0)
            },
        )
        // And the production counter agrees, rather than inventing a number.
        val crossed = t.countStudentsMatching(sgpaBelow = 6.0, result = "FAIL").answer
        assertTrue("intersection was \"$crossed\"", crossed.contains("0") || crossed.contains("No "))
    }

    @Test fun `the average SGPA of students who failed is refused, never rendered as zero`() {
        // AVG over 35 rows that all hold NULL is NULL, not 0.0, and a null
        // coerced through "%.2f" prints "0.00" -- a fabricated measurement in
        // the one place this app is meant to be exact.
        val a = t.averageSgpa("FAIL").answer
        assertTrue("a fabricated average appeared: \"$a\"", !a.contains("0.00"))
        assertTrue("failed to explain the empty set: \"$a\"", a.contains("No SGPA is recorded"))
    }

    @Test fun `the average SGPA is 7 point 34 over the 334 rows that have one`() {
        val a = t.averageSgpa().answer
        assertTrue("average was \"$a\"", a.contains("7.34"))
        assertTrue(a.contains("334 of 369"))
    }

    @Test fun `most failures and lowest pass rate are different subjects`() {
        // HardQueryBatteryTest's trap 2, and the reason "which subject do
        // students struggle with most" is graded as ambiguous.
        val counts = t.subjectFailureCounts(limit = 3).answer
        assertTrue("most failures was \"$counts\"", counts.contains("BTCOC502: 16 failures"))

        val rates = t.subjectPassRates(worstFirst = true, limit = 3).answer
        assertTrue("worst rate was \"$rates\"", rates.lineSequence().drop(1).first().contains("BTAIHM503B"))
        assertTrue("worst rate figure was \"$rates\"", rates.contains("90.9%"))
    }

    @Test fun `BTCOC502 has the most failures and still a 94 point 7 percent pass rate`() {
        val rate = conn.query(
            "SELECT 100.0 * COUNT(*) FILTER (WHERE grade NOT IN ('FF','F')) / COUNT(*) " +
                "FROM student_subjects WHERE subject_code = 'BTCOC502' AND grade IS NOT NULL",
        ) { it.getDouble(0) }.first()
        assertEquals(94.7, rate, 0.05)
    }

    // --- the counts the adversarial battery must not conflate -------------

    @Test fun `35 students failed something, but only 16 failed two subjects and 12 failed three`() {
        // Answering 35 to "how many failed at least two subjects" is the single
        // most likely silent error in the system, which is why all three
        // numbers are pinned together.
        val one = t.studentsFailedAtLeast(1).answer
        val two = t.studentsFailedAtLeast(2).answer
        val three = t.studentsFailedAtLeast(3).answer
        assertTrue("at-least-1 was \"${one.lineSequence().first()}\"",
            one.startsWith("Found 35 students"))
        assertTrue("at-least-2 was \"${two.lineSequence().first()}\"",
            two.startsWith("Found 16 students"))
        assertTrue("at-least-3 was \"${three.lineSequence().first()}\"",
            three.startsWith("Found 12 students"))
    }

    @Test fun `25 students sit below 6 SGPA and every one of them passed`() {
        val below = t.listBelowSgpa(6.0).answer
        assertTrue("below-6 was \"${below.lineSequence().first()}\"",
            below.startsWith("25 students with SGPA below 6"))
        assertEquals(
            "a below-6 student who did not pass would break the hard battery's premise",
            listOf(25L),
            conn.query(
                "SELECT COUNT(*) FROM students WHERE sgpa IS NOT NULL AND sgpa < 6.0 AND result = 'PASS'",
            ) { it.getLong(0) },
        )
    }

    @Test fun `the highest SGPA is 8 point 82, so nobody is above 9`() {
        val top = t.toppersBySgpa(limit = 1).answer
        assertTrue("top was \"$top\"", top.contains("SGPA 8.82"))
        assertTrue(t.countSgpaAtLeast(9.0).answer.startsWith("No students"))
        assertTrue(t.countSgpaAtLeast(9.5).answer.startsWith("No students"))
    }

    @Test fun `the grade scale has no A plus, no O and no S`() {
        val grades = conn.query("SELECT DISTINCT grade FROM student_subjects WHERE grade IS NOT NULL") {
            it.getText(0)
        }.toSet()
        assertTrue("unexpected grade scale: $grades", grades.isNotEmpty())
        listOf("A+", "O", "S").forEach {
            assertTrue("the corpus unexpectedly contains grade $it: $grades", it !in grades)
        }
    }

    @Test fun `is_supply and seat_cancelled are zero for all 369 rows`() {
        assertEquals(
            listOf(0L),
            conn.query("SELECT COUNT(*) FROM students WHERE is_supply <> 0 OR seat_cancelled <> 0") {
                it.getLong(0)
            },
        )
    }

    @Test fun `334 students have no FF in any subject`() {
        assertEquals(
            listOf(334L),
            conn.query(
                "SELECT COUNT(*) FROM students s WHERE NOT EXISTS (" +
                    "SELECT 1 FROM student_subjects ss WHERE ss.roll_no = s.roll_no " +
                    "AND ss.grade IN ('FF','F'))",
            ) { it.getLong(0) },
        )
    }

    // --- the fused pipeline, not just the SQL -----------------------------

    @Test fun `the fused search finds the attendance rule with both arms`() {
        val r = JvmCorpus.hybrid.search("what is the minimum attendance requirement", topK = 5)
        assertTrue("keyword arm returned nothing", r.ftsHits > 0)
        assertTrue(
            "no chunk mentioning attendance in the top 5: " +
                r.chunks.map { it.docId },
            r.chunks.any { it.content.contains("attendance", ignoreCase = true) },
        )
        if (JvmCorpus.vectorArm) {
            assertTrue("vector arm returned nothing despite an embedder", r.vecHits > 0)
            assertTrue("no chunk agreed by both arms", r.bothCount > 0)
        }
    }

    @Test fun `the ONNX embedder puts a query vector in the same space as the corpus`() {
        // model.onnx is gitignored -- 86MB -- so a machine without it skips
        // rather than fails, exactly as WordPieceTokenizerTest skips without
        // vocab.txt. What is NOT allowed is a model that is present and does
        // not load: that is a defect, and the assertion below says so.
        Assume.assumeTrue(
            "no minilm/model.onnx on this machine; battery ran ${JvmCorpus.armLabel}",
            JvmCorpus.modelAssetPresent,
        )
        val embedder = JvmCorpus.embedder
        assertNotNull(
            "model.onnx is present but the embedder would not load: ${JvmCorpus.embedderFailure}",
            embedder,
        )

        val v = embedder!!.embed("what is the minimum attendance requirement")
        assertEquals("MiniLM must produce 384 dimensions", 384, v.size)
        val norm = sqrt(v.sumOf { (it * it).toDouble() })
        assertEquals("the query vector must be L2-normalised", 1.0, norm, 1e-4)

        val vs = VectorSearch(JvmCorpus.db).apply { warm() }
        assertEquals(493, vs.size)
        val ranked = vs.rank(v, 5)
        assertTrue("the vector arm ranked nothing", ranked.isNotEmpty())
        // The guard that matters. Mean-pooling swapped for the [CLS] token, or
        // a different MiniLM export, still returns 384 floats and still ranks --
        // every similarity is just quietly wrong. A query vector genuinely in
        // the corpus's space scores its best chunk well clear of noise, and
        // that chunk is about attendance.
        assertTrue(
            "top cosine ${ranked.first().second} is at noise level; the query vector " +
                "is probably not in the corpus's space",
            ranked.first().second > 0.4,
        )
        val ids = ranked.joinToString(",") { it.first.toString() }
        val texts = conn.query("SELECT content FROM chunks WHERE id IN ($ids)") { it.getText(0) }
        assertTrue(
            "nothing about attendance in the top 5 vector hits",
            texts.any { it.contains("attendance", ignoreCase = true) },
        )
    }
}
