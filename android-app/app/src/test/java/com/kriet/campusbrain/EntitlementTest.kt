package com.kriet.campusbrain

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kriet.campusbrain.data.auth.Entitlement
import com.kriet.campusbrain.data.auth.EntitlementState
import com.kriet.campusbrain.data.auth.EntitlementStore
import com.kriet.campusbrain.data.auth.Entitlements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test

/**
 * The offline half of the auth layer: the grace window, the state machine, and
 * the promise that neither of them can stop a question being answered.
 *
 * Everything here is a pure function over an explicit clock, which is why the
 * 365-day window can be tested without waiting a year and why none of it needs
 * a device. The one test that touches SQLite is skipped rather than failed when
 * the bundled driver has no JVM native to load -- see the note on it.
 */
class EntitlementTest {

    private val t0 = 1_760_000_000_000L // an arbitrary but fixed wall clock

    private fun grant(
        graceDays: Int = 45,
        licence: String = "active",
        verifiedAtMs: Long = t0,
    ): Entitlement = requireNotNull(
        Entitlements.of("tenant_1", "student", licence, graceDays, verifiedAtMs, "Northfield")
    )

    // --- the invariant that is the entire product -------------------------

    @Test fun `every entitlement state still answers questions`() {
        // Written as an exhaustive sweep of the enum rather than as five
        // assertions, so that a state added later -- LOCKED, UNPAID, whatever
        // a future commercial conversation produces -- fails this test the
        // moment it forgets to allow retrieval.
        //
        // The claim being defended: an access token lives 3600 seconds, and the
        // airplane-mode demo has to survive minute 61. If retrieval ever
        // consults one, the product's central promise dies with the demo.
        EntitlementState.entries.forEach {
            assertTrue("$it must still allow retrieval", it.retrievalAllowed)
        }
    }

    @Test fun `a device that has never enrolled is UNKNOWN and still answers`() {
        assertEquals(EntitlementState.UNKNOWN, Entitlements.stateAt(null, t0))
        assertTrue(Entitlements.stateAt(null, t0).retrievalAllowed)
        assertNull("nothing to say to a device that never signed in",
            Entitlements.banner(null, t0))
    }

    // --- grace-window arithmetic ------------------------------------------

    @Test fun `the default window is 45 days and closes on the 45th`() {
        val e = grant(graceDays = 45)
        assertEquals(t0 + 45L * 86_400_000L, e.graceUntilMs)
        assertEquals(EntitlementState.ACTIVE, Entitlements.stateAt(e, t0))
        assertEquals(EntitlementState.ACTIVE, Entitlements.stateAt(e, t0 + 37 * DAY))
        // The last week nudges rather than warns.
        assertEquals(EntitlementState.EXPIRING, Entitlements.stateAt(e, t0 + 39 * DAY))
        assertEquals(EntitlementState.STALE, Entitlements.stateAt(e, t0 + 45 * DAY))
        assertEquals(EntitlementState.STALE, Entitlements.stateAt(e, t0 + 400 * DAY))
    }

    @Test fun `a 365 day window does not land in 1970`() {
        // 365 * 86_400_000 overflows a signed Int. Done in Int arithmetic the
        // deadline wraps negative and the tenant who bought the LONGEST offline
        // window is the one whose device is stale on day one.
        val e = grant(graceDays = 365)
        assertTrue("deadline went backwards: ${e.graceUntilMs}", e.graceUntilMs > t0)
        assertEquals(t0 + 365L * 86_400_000L, e.graceUntilMs)
        assertEquals(EntitlementState.ACTIVE, Entitlements.stateAt(e, t0 + 300 * DAY))
        assertEquals(EntitlementState.STALE, Entitlements.stateAt(e, t0 + 366 * DAY))
    }

    @Test fun `grace days are clamped to the range the server itself allows`() {
        // tenants_offline_grace_days_check: 1..365. A value outside it can only
        // come from a corrupted row or a response that is not what it claims,
        // and clamping keeps a nonsense number from producing a nonsense state.
        assertEquals(1, Entitlements.clampGraceDays(0))
        assertEquals(1, Entitlements.clampGraceDays(-9999))
        assertEquals(365, Entitlements.clampGraceDays(100_000))
        assertEquals(45, Entitlements.clampGraceDays(45))
        assertEquals(1, grant(graceDays = 0).graceDays)
    }

    @Test fun `days remaining floors at zero and never goes negative`() {
        val e = grant(graceDays = 10)
        assertEquals(10, Entitlements.daysRemaining(e, t0))
        assertEquals(3, Entitlements.daysRemaining(e, t0 + 7 * DAY))
        assertEquals(0, Entitlements.daysRemaining(e, t0 + 10 * DAY))
        assertEquals(0, Entitlements.daysRemaining(e, t0 + 999 * DAY))
        assertEquals(0, Entitlements.daysRemaining(null, t0))
    }

    @Test fun `a clock that runs backwards extends the window rather than closing it`() {
        // Deliberate. Failing open is the correct direction for a product whose
        // whole claim is that the device keeps working alone, and a monotonic
        // scheme would instead punish a phone for rebooting.
        val e = grant(graceDays = 45)
        assertEquals(EntitlementState.ACTIVE, Entitlements.stateAt(e, t0 - 500 * DAY))
    }

    @Test fun `the grant is re-read once a day, not once a launch`() {
        // A student who opens the app six times between lectures must not
        // spend six round trips of their own mobile data to re-learn a fact
        // that changes when a registrar acts, i.e. about never.
        val e = grant()
        assertFalse(Entitlements.dueForReverification(e, t0))
        assertFalse(Entitlements.dueForReverification(e, t0 + 23 * HOUR))
        assertTrue(Entitlements.dueForReverification(e, t0 + 25 * HOUR))
        // Nothing enrolled yet: due, and the call still costs nothing without
        // a session because there is no token to spend.
        assertTrue(Entitlements.dueForReverification(null, t0))
        // A clock that jumped backwards is due, but not stale -- see the
        // backwards-clock test above; the two decisions are separate.
        assertTrue(Entitlements.dueForReverification(e, t0 - 5 * DAY))
        assertEquals(EntitlementState.ACTIVE, Entitlements.stateAt(e, t0 - 5 * DAY))
    }

    // --- licence state ----------------------------------------------------

    @Test fun `a suspended or expired licence still answers, with a banner`() {
        listOf("suspended", "expired").forEach { licence ->
            val e = grant(licence = licence)
            val state = Entitlements.stateAt(e, t0)
            assertEquals(EntitlementState.LAPSED, state)
            assertTrue(state.retrievalAllowed)
            assertNotNull(Entitlements.banner(e, t0))
        }
    }

    @Test fun `no banner says answers have stopped`() {
        val banners = listOfNotNull(
            Entitlements.banner(grant(graceDays = 3), t0),
            Entitlements.banner(grant(graceDays = 1), t0 + 40 * DAY),
            Entitlements.banner(grant(licence = "suspended"), t0),
        )
        assertEquals(3, banners.size)
        banners.forEach {
            assertTrue("a banner must not read as a lockout: $it",
                it.contains("still work", ignoreCase = true) ||
                    it.contains("confirmed", ignoreCase = true))
        }
    }

    @Test fun `the header pill stays quiet unless there is something to say`() {
        // The header's live dot and "fully offline" line is the product's whole
        // promise; a licence footnote must not be sitting next to it on a
        // device that is perfectly fine.
        assertNull(Entitlements.shortBanner(null, t0))
        assertNull(Entitlements.shortBanner(grant(), t0))
        assertEquals("renew in 3d", Entitlements.shortBanner(grant(graceDays = 3), t0))
        assertEquals("unconfirmed", Entitlements.shortBanner(grant(graceDays = 1), t0 + 40 * DAY))
        assertEquals("licence", Entitlements.shortBanner(grant(licence = "expired"), t0))
        // Short enough for a one-line header that already carries two pills.
        listOf(grant(graceDays = 3), grant(graceDays = 1, verifiedAtMs = t0 - 40 * DAY),
            grant(licence = "suspended")).forEach {
            val s = Entitlements.shortBanner(it, t0)
            assertTrue("pill text too long: $s", (s?.length ?: 0) <= 14)
        }
    }

    // --- validation on the way in -----------------------------------------

    @Test fun `values the server itself would reject never become an entitlement`() {
        // Validating on the way IN is what makes "a bad response cannot damage a
        // good grant" true by construction: a row that would fail these checks
        // can never be built, so it can never be written, so it can never be
        // read back and acted on.
        assertNull(Entitlements.of("tenant 1", "student", "active", 45, t0))   // space
        assertNull(Entitlements.of("", "student", "active", 45, t0))
        assertNull(Entitlements.of("t".repeat(65), "student", "active", 45, t0))
        assertNull(Entitlements.of("tenant_1", "superuser", "active", 45, t0)) // not a role
        assertNull(Entitlements.of("tenant_1", "student", "cancelled", 45, t0)) // not a state
        assertNull(Entitlements.of(null, "student", "active", 45, t0))
        assertNull(Entitlements.of("tenant_1", null, "active", 45, t0))
        assertNull(Entitlements.of("tenant_1", "student", "active", 45, 0L))    // no clock
        // And the shapes that are fine.
        assertNotNull(Entitlements.of("tenant-2_A", "registrar", "trial", null, t0))
        Entitlements.ROLES.forEach {
            assertNotNull(it, Entitlements.of("tenant_1", it, "active", 45, t0))
        }
    }

    // --- the store --------------------------------------------------------

    /**
     * A real SQLite connection, or a skip.
     *
     * `androidx.sqlite:sqlite-bundled` is on the unit-test classpath, but
     * whether Gradle resolves a JVM-native variant for it is not something this
     * agent could run `gradlew` to confirm. Skipping keeps a green suite honest
     * instead of red for an environment reason -- and every guarantee that
     * matters above is asserted by a pure test that cannot skip.
     */
    private fun memoryConn(): SQLiteConnection? =
        runCatching { BundledSQLiteDriver().open(":memory:") }.getOrNull()

    @Test fun `a grant survives a round trip through the store`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = EntitlementStore(conn!!)
        assertTrue(store.ensureSchema())
        assertNull("a fresh store holds nothing", store.load())

        val e = grant()
        assertTrue(store.save(e))
        assertEquals(e, store.load())

        // Idempotent, and a singleton: saving twice replaces rather than adds.
        assertTrue(store.save(e.copy(licenceState = "trial")))
        assertEquals("trial", store.load()?.licenceState)

        assertTrue(store.clear())
        assertNull(store.load())
        conn.close()
    }

    @Test fun `a failed refresh cannot move a deadline that is already set`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = EntitlementStore(conn!!)
        store.ensureSchema()
        val good = grant(graceDays = 45)
        store.save(good)

        // What an offline refresh actually produces: Entitlements.of returns
        // null for the garbage, the caller has nothing to hand save(), and so
        // the stored deadline is untouched. This is the demo-killing bug the
        // whole no-op rule exists to prevent -- a captive portal resetting the
        // grace clock of a device that was working fine.
        val fromBadResponse = Entitlements.of("<html>", null, null, null, t0 + DAY)
        assertNull(fromBadResponse)
        assertEquals(good, store.load())
        assertEquals(good.graceUntilMs, store.load()?.graceUntilMs)
        conn.close()
    }

    @Test fun `a dead token clears the session and leaves the grant alone`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = EntitlementStore(conn!!)
        store.ensureSchema()
        store.save(grant())
        store.saveSession(
            EntitlementStore.Session("uid-1", "access", "refresh", 1_760_000_000L)
        )
        assertNotNull(store.loadSession())

        // The refresh path calls exactly this on a server rejection. An expired
        // token is not evidence that a student stopped being enrolled, and
        // deleting the grant here would turn every long offline stretch into a
        // re-enrolment.
        assertTrue(store.clearSession())
        assertNull(store.loadSession())
        assertNotNull("the grant must survive a dead token", store.load())
        conn.close()
    }

    @Test fun `a half written session is not a session`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = EntitlementStore(conn!!)
        store.ensureSchema()
        store.saveSession(EntitlementStore.Session(null, "access", "", 0L))
        assertNull("a session with no refresh token is useless", store.loadSession())
        conn.close()
    }

    @Test fun `the store never throws at a caller, even on a closed connection`() {
        val conn = memoryConn()
        Assume.assumeTrue("bundled SQLite has no JVM native here", conn != null)
        val store = EntitlementStore(conn!!)
        store.ensureSchema()
        conn.close()
        // Every path returns a value. A screen asking "is this device enrolled"
        // during shutdown must get an answer, not an exception. The assertion
        // is on the absence of a throw rather than on the Booleans: which
        // failure a closed connection reports is the driver's business, but
        // that it never reaches the caller is this store's.
        runCatching {
            store.load(); store.loadSession(); store.save(grant()); store.clear()
            store.saveSession(EntitlementStore.Session(null, "a", "b", 1L))
            store.clearSession()
        }.onFailure { fail("the store threw at its caller: $it") }
        assertNull(store.load())
        assertNull(store.loadSession())
    }

    private companion object {
        const val DAY = 86_400_000L
        const val HOUR = 3_600_000L
    }
}
