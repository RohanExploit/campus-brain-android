package com.kriet.campusbrain.ui.auth

import com.kriet.campusbrain.data.auth.Entitlement
import com.kriet.campusbrain.data.auth.Entitlements

/**
 * What the header's last slot says about the licence, and -- far more
 * importantly -- when it says nothing at all.
 *
 * The rule this exists to pin down is the second one: **a device that has
 * never enrolled must see the header it sees today.** No nag, no empty slot,
 * no greyed placeholder inviting a tap. Enrolment is opt-in, and an interface
 * that keeps a hole in the header until you sign in is not opt-in, it is a
 * queue. So [text] returns null for a null grant and the caller keeps the view
 * GONE, which is exactly the state `activity_main.xml` ships in.
 *
 * Pure and clock-injected for the same reason
 * [com.kriet.campusbrain.data.auth.Entitlements] is: the grace window is
 * measured in weeks and no test should have to wait for one.
 */
object IdentityPill {

    /** Matches the separator the header and the Documents count already use. */
    private const val SEPARATOR = " · "

    /**
     * `"KRIET · enrolled"`, or null when there is nothing to say.
     *
     * [activeLabel] is passed in rather than written here so the one word this
     * function contributes still lives in `strings.xml`. The other three
     * states borrow [Entitlements.shortBanner] verbatim -- "renew in 3d",
     * "unconfirmed", "licence" -- because a second set of words for the same
     * four states is a drift waiting to happen, and those are already sized
     * for this slot.
     *
     * Why a healthy grant gets a word at all, when `shortBanner` deliberately
     * returns null for it: `shortBanner` was written when this slot held only
     * a warning, and silence was the right answer because the only thing it
     * could have said was "nothing is wrong". Now the slot leads with the
     * institution's name, and a name with no state beside it reads as a label
     * rather than a status. The un-enrolled case is untouched, which is the
     * one that had to be.
     */
    fun text(
        entitlement: Entitlement?,
        activeLabel: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val e = entitlement ?: return null
        val name = e.displayName?.takeIf { it.isNotBlank() } ?: e.tenantId
        val state = Entitlements.shortBanner(e, nowMs) ?: activeLabel
        return name + SEPARATOR + state
    }
}
