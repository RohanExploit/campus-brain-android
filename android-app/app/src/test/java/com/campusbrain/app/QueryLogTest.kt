package com.campusbrain.app

import com.campusbrain.app.data.Estimates
import com.campusbrain.app.data.QueryLog
import com.campusbrain.app.data.Route
import com.campusbrain.app.ui.admin.UsageExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The usage counters and the arithmetic over them.
 *
 * All of it is pure by construction -- [QueryLog] has no Android, no SQLite
 * and no clock -- which is the whole reason the counters live there rather
 * than at the chokepoint in `QueryRouter.answer()`. See that class's header
 * for the locking argument; this file is the payoff.
 */
class QueryLogTest {

    private fun log(): QueryLog = QueryLog()

    // --- histogram and abstention arithmetic -------------------------------

    @Test fun `the histogram counts every route and reports zeroes`() {
        val l = log()
        l.record(Route.FACT, abstained = false, citedDocIds = listOf("a"))
        l.record(Route.FACT, abstained = false, citedDocIds = listOf("b"))
        l.record(Route.TABULAR, abstained = false, citedDocIds = emptyList())

        val bars = l.snapshot().histogram()
        assertEquals("every route gets a bar", Route.entries.size, bars.size)
        assertEquals(2, bars.first { it.first == Route.FACT }.second)
        assertEquals(1, bars.first { it.first == Route.TABULAR }.second)
        // A route at zero is a finding -- "nobody ever asked a relationship
        // question" -- and a route missing from the chart reads as a route
        // that does not exist.
        assertEquals(0, bars.first { it.first == Route.LOCAL }.second)
    }

    @Test fun `the abstention rate is abstentions over total`() {
        val l = log()
        repeat(3) { l.record(Route.FACT, abstained = false, citedDocIds = listOf("a")) }
        l.record(Route.GLOBAL, abstained = true, citedDocIds = emptyList())

        val s = l.snapshot()
        assertEquals(4, s.total)
        assertEquals(1, s.abstained)
        assertEquals(0.25, s.abstentionRate, 1e-9)
    }

    @Test fun `an empty log has a rate of zero rather than NaN`() {
        // 0/0 is the state every fresh install is in, and the screen renders
        // this number. NaN would print as "NaN%".
        val s = log().snapshot()
        assertEquals(0, s.total)
        assertEquals(0.0, s.abstentionRate, 0.0)
    }

    @Test fun `a document cited twice in one answer counts once`() {
        val l = log()
        l.record(Route.FACT, abstained = false, citedDocIds = listOf("fees.md", "fees.md"))
        assertEquals(1, l.snapshot().docHits["fees.md"])
    }

    @Test fun `never-retrieved is the catalogue minus what was cited`() {
        val l = log()
        l.record(Route.FACT, abstained = false, citedDocIds = listOf("fees.md"))
        l.record(Route.GLOBAL, abstained = true, citedDocIds = emptyList())

        val catalogue = listOf("fees.md", "hostel.md", "attendance.md")
        assertEquals(
            listOf("attendance.md", "hostel.md"),
            l.snapshot().neverRetrieved(catalogue)
        )
        // An abstention cites nothing, so it must not mark anything as used.
        assertFalse(l.snapshot().neverRetrieved(catalogue).contains("fees.md"))
    }

    @Test fun `drain returns the counts and resets them`() {
        val l = log()
        l.record(Route.FACT, abstained = false, citedDocIds = listOf("a"))
        assertEquals(1, l.drain().total)
        // The flush is additive at the store, so a second flush that returned
        // the same session again would double every figure.
        assertEquals(0, l.snapshot().total)
    }

    @Test fun `snapshots add`() {
        val a = QueryLog.Snapshot(mapOf(Route.FACT to 2), mapOf(Route.FACT to 1), mapOf("x" to 1))
        val b = QueryLog.Snapshot(mapOf(Route.FACT to 3, Route.LOCAL to 1), emptyMap(), mapOf("x" to 2))
        val sum = a + b
        assertEquals(5, sum.count(Route.FACT))
        assertEquals(1, sum.count(Route.LOCAL))
        assertEquals(1, sum.abstained)
        assertEquals(3, sum.docHits["x"])
    }

    // --- the structural promise about question text ------------------------

    @Test fun `record cannot be handed a question`() {
        // The privacy guarantee, asserted as a fact about the SIGNATURE rather
        // than as a fact about today's call sites. A `String?` parameter
        // defaulted to null would satisfy every behavioural test in this file
        // and would still be a hole: someone would pass the query into it
        // eventually, and nothing would fail.
        //
        // So: no parameter of `record` may be a string. Collections are
        // erased, so the cited doc ids are a `Collection` here, not a
        // `List<String>` -- doc ids are the app's own filenames, not the
        // student's words.
        val record = QueryLog::class.java.methods.single { it.name == "record" }
        record.parameterTypes.forEach { type ->
            assertFalse(
                "QueryLog.record must not accept ${type.name} -- that is how a " +
                    "student's question ends up in the aggregate counters",
                CharSequence::class.java.isAssignableFrom(type)
            )
        }
    }

    @Test fun `the raw text sample is off by default`() {
        val l = log()
        assertFalse("the safe state is 'was never recorded'", l.keepQueryText)
        l.recordText("what is my attendance in the third semester")
        assertTrue("nothing may be kept before an explicit opt-in", l.snapshot().texts.isEmpty())
    }

    @Test fun `the raw text sample records only after an explicit opt-in`() {
        val l = log()
        l.keepQueryText = true
        l.recordText("what is the hostel fee")
        assertEquals(listOf("what is the hostel fee"), l.snapshot().texts)
    }

    @Test fun `the raw text sample is capped`() {
        val l = log()
        l.keepQueryText = true
        repeat(QueryLog.MAX_TEXTS + 50) { l.recordText("question $it") }
        val texts = l.snapshot().texts
        assertEquals(QueryLog.MAX_TEXTS, texts.size)
        // Oldest dropped, so what is kept is a recent sample and not a
        // term's transcript sitting in a heap.
        assertTrue(texts.last().endsWith("${QueryLog.MAX_TEXTS + 49}"))
    }

    // --- staff hours, which must never look invented -----------------------

    @Test fun `the estimate is measured count times assumed minutes`() {
        val s = QueryLog.Snapshot(
            queries = mapOf(Route.FACT to 10, Route.TABULAR to 4),
            abstentions = emptyMap(),
            docHits = emptyMap(),
        )
        val e = Estimates.of(s, mapOf(Route.FACT to 3, Route.TABULAR to 5))
        assertEquals(10 * 3 + 4 * 5, e.totalMinutes)
        assertEquals(50 / 60.0, e.hours, 1e-9)
    }

    @Test fun `every estimate line carries both of its inputs`() {
        // The structural half of "never present an assumption as a
        // measurement": there is no way to render a saving without having the
        // count and the minutes in hand, because they are on the same object.
        val s = QueryLog.Snapshot(mapOf(Route.FACT to 7), emptyMap(), emptyMap())
        val line = Estimates.of(s, mapOf(Route.FACT to 2)).lines.single { it.route == Route.FACT }
        assertEquals(7, line.queries)
        assertEquals(2, line.minutesEach)
        assertEquals(14, line.minutes)
    }

    @Test fun `an absent or negative assumption contributes nothing`() {
        val s = QueryLog.Snapshot(mapOf(Route.FACT to 10), emptyMap(), emptyMap())
        assertEquals(0, Estimates.of(s, emptyMap()).totalMinutes)
        assertEquals(0, Estimates.of(s, mapOf(Route.FACT to -5)).totalMinutes)
    }

    @Test fun `the export never states a saving without its caveat`() {
        val caveat = "Estimated, based on the assumption above."
        val text = UsageExport.text(
            snapshot = QueryLog.Snapshot(mapOf(Route.FACT to 10), mapOf(Route.FACT to 2), mapOf("a" to 1)),
            minutes = mapOf(Route.FACT to 3),
            catalogue = listOf("a", "b"),
            caveat = caveat,
        )
        assertTrue("the caveat must travel with the figure", text.contains(caveat))
        assertTrue(text.contains("ESTIMATED"))
        assertTrue(text.contains("MEASURED"))
        // Every hours figure in the file is under the ESTIMATED heading, after
        // the caveat. A bare number above it could be lifted onto a slide.
        val estimatedAt = text.indexOf("ESTIMATED")
        assertTrue(text.indexOf("total_hours_estimated") > estimatedAt)
        assertTrue(text.indexOf(caveat) < text.indexOf("total_hours_estimated"))
        // The assumption itself is exported alongside the count that used it.
        assertTrue(text.contains("FACT,10,3,30"))
    }

    @Test fun `the export carries no question text`() {
        // The local sample exists so an admin can READ what students ask on
        // the phone in front of them. Putting it through the share sheet is
        // the one action that would take it off the device.
        val text = UsageExport.text(
            snapshot = QueryLog.Snapshot(
                mapOf(Route.FACT to 1), emptyMap(), emptyMap(),
                texts = listOf("am i short of attendance in maths"),
            ),
            minutes = mapOf(Route.FACT to 1),
            catalogue = listOf("a"),
            caveat = "Estimated.",
        )
        assertFalse(text.contains("attendance in maths"))
    }
}
