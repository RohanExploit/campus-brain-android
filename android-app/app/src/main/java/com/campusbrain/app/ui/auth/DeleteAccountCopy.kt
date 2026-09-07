package com.campusbrain.app.ui.auth

import com.campusbrain.app.R
import com.campusbrain.app.data.auth.AccountDeletion

/**
 * What the deletion screen says, with no Android object in sight.
 *
 * Same trick as [EnrolCopy], for the same reason: a string resource is an
 * `int` on this classpath, so every card can be asserted in a JVM unit test
 * and all that is left in the fragment is `getString`. It earns its keep
 * harder here than it did there, because two of the four cards describe a
 * deletion that did NOT happen, and a card that gets that wrong tells a
 * student their account is gone while the row is still on the server.
 *
 * The rule the whole file is written to: **every branch has to be true about
 * two different things at once** -- what happened to the account, and what
 * happened to the answers. The second is always the same sentence, because
 * the second is always the same fact. Retrieval never consulted the
 * enrolment, so no outcome here can change what the app will answer.
 */
object DeleteAccountCopy {

    data class Outcome(
        val iconRes: Int,
        val titleRes: Int,
        val bodyRes: Int,
        val actionRes: Int,
        /**
         * True when there is no account on this device any more -- whether
         * this call deleted it or found it already gone. The screen uses it to
         * take the destructive button away: offering "delete this account" a
         * second time, under a card saying there is nothing to delete, would
         * be inviting a student to re-test a result they were just given.
         */
        val accountGone: Boolean,
        /**
         * Whether the action returns to the explanation, or leaves the screen.
         *
         * Only the outcome that could genuinely go differently next time comes
         * back. A dead session does not: the next move is to enrol again,
         * which is the previous screen, so that card leaves rather than
         * dropping the student back on a button that will fail identically.
         */
        val returnsToStart: Boolean,
    )

    /**
     * Total over [AccountDeletion.Result] -- four results, four cards, no two
     * of them sharing a line of copy.
     *
     * Note what none of them does: read a message off the wire. The reasoning
     * is [EnrolCopy]'s. `SupabaseHttp.errorMessage` falls back to the first
     * 120 characters of the response body, which on campus wifi is a captive
     * portal's HTML, and [AccountDeletion.Result] deliberately carries no
     * field one could arrive in.
     */
    fun of(result: AccountDeletion.Result): Outcome = when (result) {
        AccountDeletion.Result.Deleted -> Outcome(
            iconRes = R.drawable.ic_check,
            titleRes = R.string.delete_done_title,
            bodyRes = R.string.delete_done_body,
            actionRes = R.string.delete_action_done,
            accountGone = true,
            returnsToStart = false,
        )

        // A check mark, not an alert. The student asked for a state that
        // already held, and nothing went wrong on the way to discovering it --
        // an alert icon here would invent a fault out of a second tap.
        AccountDeletion.Result.NoAccount -> Outcome(
            iconRes = R.drawable.ic_check,
            titleRes = R.string.delete_none_title,
            bodyRes = R.string.delete_none_body,
            actionRes = R.string.delete_action_done,
            accountGone = true,
            returnsToStart = false,
        )

        // The account still exists. The card says so, and the action goes back
        // to enrolment, because signing in again is the actual prerequisite.
        AccountDeletion.Result.SessionExpired -> Outcome(
            iconRes = R.drawable.ic_alert,
            titleRes = R.string.delete_session_title,
            bodyRes = R.string.delete_session_body,
            actionRes = R.string.delete_action_back,
            accountGone = false,
            returnsToStart = false,
        )

        AccountDeletion.Result.Unavailable -> Outcome(
            iconRes = R.drawable.ic_alert,
            titleRes = R.string.delete_unavailable_title,
            bodyRes = R.string.delete_unavailable_body,
            actionRes = R.string.delete_action_retry,
            accountGone = false,
            returnsToStart = true,
        )
    }
}
