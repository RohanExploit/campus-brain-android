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
     * How much of an institution's name the header will carry.
     *
     * The elision is done here rather than left to `ellipsize="end"` on the
     * view, and that is not belt and braces. The header is a horizontal
     * LinearLayout whose title takes `layout_weight="1"`; a wrap_content pill
     * that grows past the available width squeezes the TITLE to nothing and
     * then overflows the gutter, so the view never reaches the width at which
     * it would ellipsize. Capping the only unbounded part of the string --
     * `display_name`, which the institution chooses and this app does not
     * control -- is what keeps the row one line. The commit that had to stop
     * the header status line clipping mid-word is the precedent.
     *
     * 18 characters fits "Institute of Tech…" and leaves the state word
     * intact, which is the half of the pill that changes.
     */
    const val MAX_NAME_CHARS = 18

    /** Ellipsis character, not three dots: it is one glyph and it is what the
     * rest of the app's copy uses. */
    private const val ELLIPSIS = "…"

    fun shortenName(name: String): String =
        if (name.length <= MAX_NAME_CHARS) name
        else name.take(MAX_NAME_CHARS - 1).trimEnd() + ELLIPSIS

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
        val name = shortenName((e.displayName?.takeIf { it.isNotBlank() } ?: e.tenantId).trim())
        val state = Entitlements.shortBanner(e, nowMs) ?: activeLabel
        return name + SEPARATOR + state
    }
}
