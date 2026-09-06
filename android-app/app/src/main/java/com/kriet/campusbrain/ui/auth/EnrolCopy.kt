package com.kriet.campusbrain.ui.auth

import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.auth.Entitlement
import com.kriet.campusbrain.data.auth.Entitlements
import com.kriet.campusbrain.data.auth.Identity
import com.kriet.campusbrain.data.auth.SupabaseAuth

/**
 * Everything the enrolment screen decides, with no Android object in sight.
 *
 * The precedent is [com.kriet.campusbrain.data.auth.EntitlementStore], which
 * takes a raw `SQLiteConnection` rather than a `Context` so that the logic
 * around it can be tested on the JVM. The same trick works for copy: a string
 * resource is an `int`, not an Android object, so the mapping from an enrolment
 * outcome to the words on screen can be asserted in a unit test while the only
 * thing left in the fragment is `getString`.
 *
 * That matters more here than it looks. There are four outcomes, one of them
 * has two quite different causes, and one of them -- the reinstall -- is a
 * SUCCESS that a careless reading of the server's response turns into a
 * failure. Those are decisions, and decisions belong somewhere they can be
 * pinned down.
 */

/**
 * What the form will accept before it is worth spending up to forty seconds of
 * a student's campus wifi finding out.
 *
 * Local checks only, and deliberately the weakest ones that still catch a
 * typo. The server is the authority on all three fields and this never
 * pretends otherwise; the job here is to avoid a round trip that was always
 * going to fail, not to reimplement GoTrue.
 */
object EnrolForm {

    /**
     * GoTrue's own default minimum. Repeated rather than discovered because
     * the alternative is finding out after a sign-up round trip, and the
     * failure it produces ("Password should be at least 6 characters") is
     * the server's prose rather than this app's.
     */
    const val MIN_PASSWORD_LENGTH = 6

    /** Which field a correction belongs under. */
    enum class Field { EMAIL, PASSWORD, CODE }

    /**
     * A single correction, addressed to one field.
     *
     * One at a time, in field order, rather than all three at once: the form
     * is three fields tall and a student filling it top to bottom should be
     * told about the first thing that is wrong, not handed a list.
     */
    enum class Problem(val field: Field, val messageRes: Int) {
        EMAIL_MALFORMED(Field.EMAIL, R.string.enrol_problem_email),
        PASSWORD_TOO_SHORT(Field.PASSWORD, R.string.enrol_problem_password),
        CODE_MISSING(Field.CODE, R.string.enrol_problem_code),
    }

    /**
     * The first thing wrong with this form, or null when it can be submitted.
     *
     * A blank email is not a problem: it is the supported case. The address
     * proves nothing -- see [SupabaseAuth.syntheticEmail] -- and a student who
     * does not want to give one gets a random `.invalid` address instead.
     *
     * The enrolment code gets no format check beyond "not blank". The
     * registrar decides what a code looks like and the server matches a
     * sha256 of its trimmed, uppercased form; a regex here would be this
     * client having an opinion about a format it does not own, and the first
     * institution to issue codes with a dash in them would find the app
     * refusing to send a code the server would have accepted.
     */
    fun problem(email: String, password: String, code: String): Problem? {
        val address = email.trim()
        if (address.isNotEmpty() && !SupabaseAuth.looksLikeEmail(address)) {
            return Problem.EMAIL_MALFORMED
        }
        if (password.length < MIN_PASSWORD_LENGTH) return Problem.PASSWORD_TOO_SHORT
        if (code.isBlank()) return Problem.CODE_MISSING
        return null
    }

    fun submittable(email: String, password: String, code: String): Boolean =
        problem(email, password, code) == null

    /**
     * The address that actually goes on the wire.
     *
     * [synthetic] is a parameter so a test can pin the blank-email branch
     * without asserting against sixteen random characters.
     *
     * Known seam, and not fixable from here: [Identity] does not expose the
     * `synthetic_email_domain` it parsed out of `config.json`, so a project
     * that configured a custom domain still gets
     * [SupabaseAuth.Companion.syntheticEmail]'s default. Harmless -- the
     * address receives no mail and proves nothing -- but it is a difference
     * between what the config says and what the server stores.
     */
    fun addressFor(
        email: String,
        synthetic: () -> String = { SupabaseAuth.syntheticEmail() },
    ): String = email.trim().ifEmpty { synthetic() }
}

/**
 * The outcome card: an icon, a headline, a sentence, and one thing to do next.
 *
 * Same two rules as the import card it is modelled on -- name the outcome in
 * the title, name the next move in the body, never say "error" -- plus a third
 * that belongs to this screen alone: every branch has to make clear that
 * answering a question was never at stake.
 */
object EnrolCopy {

    data class Outcome(
        val iconRes: Int,
        val titleRes: Int,
        val bodyRes: Int,
        val actionRes: Int,
        /** True only for a grant that now exists on this device. */
        val succeeded: Boolean,
        /** Whether the action returns to the filled-in form or leaves the
         * screen. Retrying is only meaningful where retrying could work. */
        val returnsToForm: Boolean,
        /** The institution's name, for the success headline. Null otherwise. */
        val institution: String? = null,
        /** Whole days of confirmed offline access, for the success body. */
        val offlineDays: Int = 0,
    )

    /**
     * Total over [Identity.EnrolResult], and note which case is missing:
     * there is no "already enrolled" branch.
     *
     * That is not an oversight, it is the contract. A student who reinstalls
     * signs up (rejected: the address exists), signs in, redeems the same code
     * (the server raises 23505),
     * [com.kriet.campusbrain.data.auth.ControlPlane.RedeemOutcome.AlreadyEnrolled]
     * is treated as the success it is, and the grant is read exactly as it
     * would have been on a first enrolment. It reaches this function as
     * [Identity.EnrolResult.Enrolled]. So the success copy has to be true of
     * both histories at once, which is why it says "Enrolled with KRIET" and
     * not "Welcome" (wrong for a reinstall) or "Recovered" (wrong for a first
     * run).
     */
    fun of(result: Identity.EnrolResult, nowMs: Long = System.currentTimeMillis()): Outcome =
        when (result) {
            is Identity.EnrolResult.Enrolled -> Outcome(
                iconRes = R.drawable.ic_check,
                titleRes = R.string.enrol_done_title_fmt,
                bodyRes = R.string.enrol_done_body_fmt,
                actionRes = R.string.enrol_action_done,
                succeeded = true,
                returnsToForm = false,
                institution = institutionOf(result.entitlement),
                offlineDays = offlineDaysOf(result.entitlement, nowMs),
            )

            // One card for two causes, because the result type carries no way
            // to tell them apart and the server's prose is not trustworthy
            // enough to guess with: SupabaseHttp.errorMessage falls back to the
            // first 120 characters of the body, which on campus wifi can be a
            // captive portal's HTML, and ControlPlane maps SQLSTATE 28000 --
            // a credentials fault -- into the same Invalid arm as a bad code.
            // Matching on the message would produce a test that passes on
            // strings this file invented and a screen that lies on the device.
            //
            // The honest fix is a `stage` discriminator on EnrolResult, in
            // data/auth, which is read-only from here. Reported, not done.
            is Identity.EnrolResult.Rejected -> Outcome(
                iconRes = R.drawable.ic_alert,
                titleRes = R.string.enrol_rejected_title,
                bodyRes = R.string.enrol_rejected_body,
                actionRes = R.string.enrol_action_retry,
                succeeded = false,
                returnsToForm = true,
            )

            Identity.EnrolResult.Unavailable -> Outcome(
                iconRes = R.drawable.ic_alert,
                titleRes = R.string.enrol_unavailable_title,
                bodyRes = R.string.enrol_unavailable_body,
                actionRes = R.string.enrol_action_retry,
                succeeded = false,
                returnsToForm = true,
            )

            // Nothing to retry: there is no project to talk to. The action
            // leaves rather than offering a button that would do the same
            // nothing a second time.
            Identity.EnrolResult.NotConfigured -> Outcome(
                iconRes = R.drawable.ic_alert,
                titleRes = R.string.enrol_not_configured_title,
                bodyRes = R.string.enrol_not_configured_body,
                actionRes = R.string.enrol_action_back,
                succeeded = false,
                returnsToForm = false,
            )
        }

    /** The name a student would recognise, falling back to the id the server
     * keys on when the tenant row has never been read. */
    fun institutionOf(e: Entitlement): String =
        e.displayName?.takeIf { it.isNotBlank() } ?: e.tenantId

    /**
     * The window to quote in the success line.
     *
     * Normally the days left. A grant that was fetched seconds ago and already
     * reads as zero days left means this phone's clock is ahead of the
     * server's, and "confirmed for the next 0 days" is both alarming and
     * useless -- so in that case the granted window is quoted instead, which
     * is the number the institution actually set.
     */
    fun offlineDaysOf(e: Entitlement, nowMs: Long): Int =
        Entitlements.daysRemaining(e, nowMs).takeIf { it > 0 } ?: e.graceDays
}
