package com.kriet.campusbrain

import com.kriet.campusbrain.data.auth.Entitlement
import com.kriet.campusbrain.data.auth.Entitlements
import com.kriet.campusbrain.data.auth.Identity
import com.kriet.campusbrain.ui.auth.EnrolCopy
import com.kriet.campusbrain.ui.auth.EnrolForm
import com.kriet.campusbrain.ui.auth.IdentityPill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions the enrolment screen makes, none of which need a device.
 *
 * Three things are pinned here and they are the three that would be expensive
 * to get wrong on a student's phone: what the form will send, what each
 * outcome says, and -- the one that matters most -- that a device with no
 * grant produces no header text at all.
 *
 * A string resource is an `int` on this classpath, so the copy mapping is
 * assertable without Robolectric and without a `Context`. That is the same
 * move `EntitlementStore` makes by taking a `SQLiteConnection` instead of a
 * `Context`.
 */
class EnrolCopyTest {

    private val t0 = 1_760_000_000_000L // an arbitrary but fixed wall clock

    private fun grant(
        tenantId: String = "kriet",
        displayName: String? = "Northfield",
        graceDays: Int = 45,
        licence: String = "active",
        verifiedAtMs: Long = t0,
    ): Entitlement = requireNotNull(
        Entitlements.of(tenantId, "student", licence, graceDays, verifiedAtMs, displayName)
    )

    private fun enrolled(e: Entitlement = grant()) = Identity.EnrolResult.Enrolled(e)

    // --- the outcome mapping is total ---------------------------------------

    /**
     * Exhaustiveness, checked at RUN time rather than compile time.
     *
     * `when` over a sealed interface with no `else` is already exhaustive when
     * it compiles, which is precisely why it proves nothing here: a fifth
     * outcome added to `Identity.EnrolResult` would break the build in
     * `EnrolCopy.of` and someone would add an arm to make it compile again.
     * This test is the thing that makes them stop and choose the words.
     *
     * Java reflection, not `KClass.sealedSubclasses`: the latter needs
     * kotlin-reflect on the runtime classpath and this app declines new
     * dependencies. The four outcomes are nested inside the sealed interface,
     * so `declaredClasses` finds them; the filter drops anything synthetic the
     * compiler may add alongside.
     */
    @Test fun `every enrol outcome has copy`() {
        val declared = Identity.EnrolResult::class.java.declaredClasses
            .filter { Identity.EnrolResult::class.java.isAssignableFrom(it) }
        assertEquals(
            "EnrolResult grew an arm; EnrolCopy.of and strings.xml both need one too",
            4, declared.size,
        )

        val all = listOf(
            enrolled(),
            Identity.EnrolResult.Rejected("whatever the server said"),
            Identity.EnrolResult.Unavailable,
            Identity.EnrolResult.NotConfigured,
        )
        assertEquals("one case per declared outcome", declared.size, all.size)
        all.forEach {
            val o = EnrolCopy.of(it, t0)
            assertTrue("$it has no title", o.titleRes != 0)
            assertTrue("$it has no body", o.bodyRes != 0)
            assertTrue("$it has no action", o.actionRes != 0)
            assertTrue("$it has no icon", o.iconRes != 0)
        }
    }

    @Test fun `no two outcomes share a headline or a body`() {
        val outcomes = listOf(
            enrolled(),
            Identity.EnrolResult.Rejected("no"),
            Identity.EnrolResult.Unavailable,
            Identity.EnrolResult.NotConfigured,
        ).map { EnrolCopy.of(it, t0) }

        assertEquals("four distinct titles", 4, outcomes.map { it.titleRes }.toSet().size)
        assertEquals("four distinct bodies", 4, outcomes.map { it.bodyRes }.toSet().size)
    }

    /**
     * The reinstall.
     *
     * A student who reinstalls signs up (rejected, the address exists), signs
     * in, redeems the same code, and the server raises 23505. ControlPlane
     * reads that as AlreadyEnrolled, Identity.enrol carries on to fetch the
     * grant, and it arrives here as Enrolled. There is no "already enrolled"
     * branch to test because there must not be one -- if this ever stops being
     * a success, a correctly enrolled student is being told their code is bad.
     */
    @Test fun `only enrolment succeeds and it succeeds for a reinstall too`() {
        assertTrue(EnrolCopy.of(enrolled(), t0).succeeded)
        assertFalse(EnrolCopy.of(Identity.EnrolResult.Rejected("no"), t0).succeeded)
        assertFalse(EnrolCopy.of(Identity.EnrolResult.Unavailable, t0).succeeded)
        assertFalse(EnrolCopy.of(Identity.EnrolResult.NotConfigured, t0).succeeded)
    }

    /** Retrying is offered exactly where retrying could work. There is nothing
     * to retry against a build with no configuration. */
    @Test fun `only the recoverable outcomes offer the form back`() {
        assertTrue(EnrolCopy.of(Identity.EnrolResult.Rejected("no"), t0).returnsToForm)
        assertTrue(EnrolCopy.of(Identity.EnrolResult.Unavailable, t0).returnsToForm)
        assertFalse(EnrolCopy.of(Identity.EnrolResult.NotConfigured, t0).returnsToForm)
        assertFalse(EnrolCopy.of(enrolled(), t0).returnsToForm)
    }

    @Test fun `the success headline names the institution`() {
        assertEquals("Northfield", EnrolCopy.of(enrolled(), t0).institution)
        // The tenant row has never been read, so there is no display name yet.
        // The id the server keys on is a worse name than "Northfield" and a much
        // better one than an empty headline.
        assertEquals(
            "kriet",
            EnrolCopy.of(enrolled(grant(displayName = null)), t0).institution,
        )
        assertEquals(
            "a blank display_name is not a name",
            "kriet",
            EnrolCopy.of(enrolled(grant(displayName = "   ")), t0).institution,
        )
    }

    /** Nothing but a success has an institution to name. */
    @Test fun `failures carry no institution`() {
        listOf(
            Identity.EnrolResult.Rejected("no"),
            Identity.EnrolResult.Unavailable,
            Identity.EnrolResult.NotConfigured,
        ).forEach { assertNull(EnrolCopy.of(it, t0).institution) }
    }

    @Test fun `the success body quotes the window that is actually left`() {
        assertEquals(45, EnrolCopy.of(enrolled(), t0).offlineDays)
        // Two days in. The card should say what is left, not what was granted.
        assertEquals(
            43,
            EnrolCopy.of(enrolled(), t0 + 2 * Entitlements.MS_PER_DAY).offlineDays,
        )
    }

    /**
     * A grant fetched seconds ago that already reads as zero days left means
     * this phone's clock is ahead of the server's. "Confirmed for the next 0
     * days" is alarming and useless, so the granted window is quoted instead.
     */
    @Test fun `a phone with a fast clock is told the granted window`() {
        val e = grant(graceDays = 30)
        val wayLater = t0 + 400L * Entitlements.MS_PER_DAY
        assertEquals(0, Entitlements.daysRemaining(e, wayLater))
        assertEquals(30, EnrolCopy.offlineDaysOf(e, wayLater))
    }

    // --- what the form will send --------------------------------------------

    @Test fun `a blank email is the supported case and not a problem`() {
        assertNull(EnrolForm.problem("", "hunter2", "NORTHFIELD-2026"))
        assertNull(EnrolForm.problem("   ", "hunter2", "NORTHFIELD-2026"))
        assertTrue(EnrolForm.submittable("", "hunter2", "NORTHFIELD-2026"))
    }

    @Test fun `an address with a typo is caught before a round trip`() {
        assertEquals(
            EnrolForm.Problem.EMAIL_MALFORMED,
            EnrolForm.problem("student@college", "hunter2", "CODE"),
        )
        assertEquals(
            EnrolForm.Problem.EMAIL_MALFORMED,
            EnrolForm.problem("student.college.edu", "hunter2", "CODE"),
        )
        assertNull(EnrolForm.problem("student@college.edu", "hunter2", "CODE"))
        // Trimmed before it is judged: a keyboard that appends a space should
        // not produce a correction the student cannot see.
        assertNull(EnrolForm.problem("  student@college.edu  ", "hunter2", "CODE"))
    }

    @Test fun `the password floor matches the server's`() {
        assertEquals(6, EnrolForm.MIN_PASSWORD_LENGTH)
        assertEquals(
            EnrolForm.Problem.PASSWORD_TOO_SHORT,
            EnrolForm.problem("", "short", "CODE"),
        )
        assertNull(EnrolForm.problem("", "sixchr", "CODE"))
        // A password is not trimmed. Leading and trailing spaces are
        // characters the server will hash, and silently dropping them here
        // would make a password that works on this screen fail on the next
        // device the student enrols.
        assertNull(EnrolForm.problem("", "  abcd  ", "CODE"))
    }

    @Test fun `the code is required and gets no format opinion`() {
        assertEquals(
            EnrolForm.Problem.CODE_MISSING,
            EnrolForm.problem("", "hunter2", ""),
        )
        assertEquals(
            EnrolForm.Problem.CODE_MISSING,
            EnrolForm.problem("", "hunter2", "   "),
        )
        // Whatever shape the registrar chose. This client does not own the
        // format and must not refuse a code the server would have accepted.
        listOf("NORTHFIELD-2026", "abc123", "A B C", "2026/CS/0042", "x").forEach {
            assertNull("the app must be willing to send \"$it\"",
                EnrolForm.problem("", "hunter2", it))
        }
    }

    /** One correction at a time, in field order, so a student filling the form
     * top to bottom is told the next thing to fix rather than handed a list. */
    @Test fun `the first problem in field order is the one reported`() {
        val p = EnrolForm.problem("bad-address", "x", "")
        assertEquals(EnrolForm.Problem.EMAIL_MALFORMED, p)
        assertEquals(EnrolForm.Field.EMAIL, p?.field)

        val q = EnrolForm.problem("", "x", "")
        assertEquals(EnrolForm.Problem.PASSWORD_TOO_SHORT, q)
        assertEquals(EnrolForm.Field.PASSWORD, q?.field)
    }

    @Test fun `every problem has copy attached to it`() {
        EnrolForm.Problem.entries.forEach {
            assertTrue("${it.name} has no message", it.messageRes != 0)
        }
        assertEquals(
            "a correction per field, and no field without one",
            EnrolForm.Field.entries.size,
            EnrolForm.Problem.entries.map { it.field }.toSet().size,
        )
    }

    @Test fun `a blank address becomes a synthetic one`() {
        assertEquals("s@nowhere.invalid", EnrolForm.addressFor("") { "s@nowhere.invalid" })
        assertEquals("s@nowhere.invalid", EnrolForm.addressFor("  ") { "s@nowhere.invalid" })
        assertEquals(
            "a real address is sent as typed, minus the keyboard's spaces",
            "me@college.edu",
            EnrolForm.addressFor(" me@college.edu ") { "s@nowhere.invalid" },
        )
    }

    // --- the header, and the silence that matters ---------------------------

    /**
     * The rule this whole file exists to protect.
     *
     * A student who never enrols must see the header they see today: no nag,
     * no empty slot, no greyed placeholder. Null here is what keeps the view
     * GONE, which is the state activity_main.xml ships in.
     */
    @Test fun `no entitlement means the header says nothing at all`() {
        assertNull(IdentityPill.text(null, "enrolled", t0))
    }

    @Test fun `a healthy grant names the institution and its state`() {
        assertEquals("Northfield · enrolled", IdentityPill.text(grant(), "enrolled", t0))
    }

    @Test fun `the pill borrows the short banner for every unhealthy state`() {
        // Three days left inside a five-day window.
        val expiring = grant(graceDays = 5, verifiedAtMs = t0 - 2 * Entitlements.MS_PER_DAY)
        assertEquals("Northfield · renew in 3d", IdentityPill.text(expiring, "enrolled", t0))

        val stale = grant(graceDays = 1, verifiedAtMs = t0 - 9 * Entitlements.MS_PER_DAY)
        assertEquals("Northfield · unconfirmed", IdentityPill.text(stale, "enrolled", t0))

        val lapsed = grant(licence = "suspended")
        assertEquals("Northfield · licence", IdentityPill.text(lapsed, "enrolled", t0))
    }

    @Test fun `the pill falls back to the tenant id when there is no name yet`() {
        assertEquals(
            "kriet · enrolled",
            IdentityPill.text(grant(displayName = null), "enrolled", t0),
        )
    }

    /**
     * The header is one line and the title beside it takes the remaining
     * width, so an institution with a long `display_name` would squeeze the
     * app's own name to nothing rather than eliding itself. The cap is applied
     * to the name, which is the only part of the string this app does not
     * control.
     */
    @Test fun `a long institution name is shortened rather than allowed to grow`() {
        val long = "Kanpur Institute of Technology and Management"
        val text = IdentityPill.text(grant(displayName = long), "enrolled", t0)
        assertNotNull(text)
        assertTrue("still ends in the state word", text!!.endsWith(" · enrolled"))
        val name = text.removeSuffix(" · enrolled")
        // At most the cap, and possibly one shorter: the trailing space left
        // by cutting mid-phrase is trimmed before the ellipsis goes on, so
        // "Kanpur Institute …" never reaches the screen.
        assertTrue("$name is longer than the cap", name.length <= IdentityPill.MAX_NAME_CHARS)
        assertTrue("elided names say so", name.endsWith("…"))
        assertFalse("no space before the ellipsis", name.endsWith(" …"))
    }

    @Test fun `a name that fits is left exactly as the institution wrote it`() {
        val exact = "a".repeat(IdentityPill.MAX_NAME_CHARS)
        assertEquals(exact, IdentityPill.shortenName(exact))
        assertEquals("Northfield", IdentityPill.shortenName("Northfield"))
    }
}
