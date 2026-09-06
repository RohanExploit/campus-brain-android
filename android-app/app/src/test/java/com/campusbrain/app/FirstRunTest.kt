package com.campusbrain.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.campusbrain.app.ui.welcome.FirstRun
import com.campusbrain.app.ui.welcome.FirstRunStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * The first-run decision, which has three inputs and only one of them shows a
 * welcome screen.
 *
 * The interesting case is the third. A store that cannot be read is not a new
 * device -- it is a device whose seen-flag can never be written -- so treating
 * it as new would put four panes in front of the same student on every launch
 * with no way to clear them. Everything below exists to make that behaviour
 * hard to remove by accident.
 */
class FirstRunTest {

    // --- the decision -------------------------------------------------------

    @Test fun `a device that has never seen the welcome sees it`() {
        assertTrue(FirstRun.shouldShow(false))
    }

    @Test fun `a device that has seen the welcome never sees it again`() {
        assertFalse(FirstRun.shouldShow(true))
    }

    /**
     * The rule that stops a broken store becoming an onboarding screen the
     * student cannot dismiss. Written as its own test with its own name so
     * that anyone tempted to simplify `seen == false` into `!seen` has to
     * delete a test that says why not.
     */
    @Test fun `a store that cannot be read does not show the welcome`() {
        assertFalse(FirstRun.shouldShow(null))
    }

    // --- the panes ----------------------------------------------------------

    @Test fun `there are four claims and the citation one leads`() {
        assertEquals(4, FirstRun.PANES.size)
        // Citations first: it is the claim that separates this from every
        // chatbot the student has already used, and the only one they can
        // verify on their very first answer.
        assertEquals(R.string.welcome_cited_title, FirstRun.PANES.first().titleRes)
        // Adding your own documents last: the only pane describing something
        // the student has to do rather than something the app already did.
        assertEquals(R.string.welcome_yours_title, FirstRun.PANES.last().titleRes)
    }

    @Test fun `the four claims are four different claims`() {
        assertEquals(4, FirstRun.PANES.map { it.titleRes }.toSet().size)
        assertEquals(4, FirstRun.PANES.map { it.bodyRes }.toSet().size)
        FirstRun.PANES.forEach {
            assertTrue("a pane with no title", it.titleRes != 0)
            assertTrue("a pane with no body", it.bodyRes != 0)
        }
    }

    @Test fun `only the last pane finishes`() {
        assertFalse(FirstRun.isLast(0))
        assertFalse(FirstRun.isLast(2))
        assertTrue(FirstRun.isLast(3))
        // Defensive: a restored index from a build with more panes must finish
        // rather than run off the end.
        assertTrue(FirstRun.isLast(9))
    }

    @Test fun `a restored index out of range still resolves to a pane`() {
        assertEquals(FirstRun.PANES.first(), FirstRun.paneAt(-1))
        assertEquals(FirstRun.PANES.last(), FirstRun.paneAt(99))
        assertEquals(FirstRun.PANES[1], FirstRun.paneAt(1))
    }

    // --- the store ----------------------------------------------------------

    /** Skipped rather than failed where the bundled driver has no JVM native
     * to load, matching EntitlementTest. */
    private fun memoryConn(): SQLiteConnection? =
        runCatching { BundledSQLiteDriver().open(":memory:") }.getOrNull()

    @Test fun `the flag survives a round trip and is written exactly once`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = FirstRunStore(conn!!)

        assertEquals("a fresh device has not seen it", false, store.seen())
        assertTrue(FirstRun.shouldShow(store.seen()))

        assertTrue(store.markSeen(1_760_000_000_000L))
        assertEquals(true, store.seen())
        assertFalse(FirstRun.shouldShow(store.seen()))

        // Singleton: marking twice replaces the row rather than adding one,
        // so the CHECK(id = 1) can never be the thing that fails a launch.
        assertTrue(store.markSeen(1_760_000_001_000L))
        assertEquals(true, store.seen())

        conn.close()
    }

    /**
     * A closed connection stands in for every way the store can be
     * unavailable -- a filesDir that will not open, a driver with no native,
     * a corrupted page. All of them have to arrive as null rather than as
     * false, because false means "new device" and would show the welcome
     * forever.
     */
    @Test fun `a store that cannot be reached reports unknown, not new`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = FirstRunStore(conn!!)
        assertTrue(store.ensureSchema())
        conn.close()

        assertNull("a dead connection is not a new device", store.seen())
        assertFalse(FirstRun.shouldShow(store.seen()))
        // And a write against it fails quietly rather than throwing into a
        // launch path.
        assertFalse(store.markSeen())
    }
}
