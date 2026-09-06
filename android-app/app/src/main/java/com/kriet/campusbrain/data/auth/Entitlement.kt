package com.kriet.campusbrain.data.auth

/**
 * What the institution has granted this device, and for how long it stays
 * granted with the network gone.
 *
 * The single most important line in this file is [EntitlementState.retrievalAllowed],
 * which is `true` for every value of the enum and is asserted exhaustively in
 * EntitlementTest. That is not defensive tidiness -- it is the product.
 *
 * This app's whole commercial claim is that the corpus, the embeddings and the
 * student's questions never leave the institution's hardware, and the
 * demonstration of that claim is a phone in airplane mode answering questions
 * from the college's own documents. An access token lives 3600 seconds. If any
 * part of the ask path consults one, the airplane-mode demo stops working at
 * minute 61 and the claim goes with it.
 *
 * So auth here proves enrolment ONCE. After that this blob is written locally
 * and retrieval never asks the network anything again. Everything below decides
 * only what BANNER a student sees, never whether they get an answer.
 */
data class Entitlement(
    /** The institution. Matches the server's `^[A-Za-z0-9_-]{1,64}$` check. */
    val tenantId: String,
    /** admin | registrar | faculty | student. */
    val role: String,
    /** trial | active | suspended | expired, as the tenant row last reported. */
    val licenceState: String,
    /** Per-tenant, 1..365, from `tenants.offline_grace_days` (server default 45). */
    val graceDays: Int,
    /** Wall clock at the last SUCCESSFUL server contact. */
    val verifiedAtMs: Long,
    /** [verifiedAtMs] plus [graceDays], always derived rather than supplied --
     * [Entitlements.of] is the only way to build one of these, and it computes
     * this field. Carrying it explicitly means every reader sees the same
     * deadline, and the window moves only when a fetch succeeds. */
    val graceUntilMs: Long,
    /** For the banner. Null until a tenant read has succeeded once. */
    val displayName: String? = null,
)

/**
 * Every state answers questions. The variation is in what, if anything, is
 * said above the answer.
 */
enum class EntitlementState(
    /** Invariant, and deliberately a property of the enum rather than a
     * constant somewhere: a future state that forgets to set it true fails
     * the exhaustive test instead of quietly killing offline retrieval. */
    val retrievalAllowed: Boolean = true,
) {
    /** No enrolment on this device yet. The corpus still answers -- the bundle
     * ships in the APK and belongs to whoever installed it. */
    UNKNOWN,

    /** Enrolled, inside the offline grace window. Nothing is shown. */
    ACTIVE,

    /** Inside the window but close to the end of it. A quiet nudge to open the
     * app once with wifi, not a warning. */
    EXPIRING,

    /** Past the window. A non-blocking banner, and answers as normal. */
    STALE,

    /** The institution's licence itself is suspended or expired. Still a
     * banner, still answers: the college paying late is not the student's
     * fault, and a phone that has already been handed the corpus taking it
     * hostage is not a behaviour worth shipping. */
    LAPSED,
}

/**
 * Pure functions over [Entitlement]. No clock of their own -- `nowMs` is always
 * passed in, which is what makes the grace-window arithmetic testable at 45
 * days and at 365 without waiting.
 */
object Entitlements {

    /** Server: `tenants_tenant_id_check`. */
    private val TENANT_ID = Regex("""^[A-Za-z0-9_-]{1,64}$""")

    /** Server: `memberships_role_check` / `enrolment_codes_role_check`. */
    val ROLES = setOf("admin", "registrar", "faculty", "student")

    /** Server: `tenants_licence_state_check`. */
    val LICENCE_STATES = setOf("trial", "active", "suspended", "expired")

    /** Server: `tenants_offline_grace_days_check`. */
    const val MIN_GRACE_DAYS = 1
    const val MAX_GRACE_DAYS = 365

    /** The server's own column default, repeated here for the case where the
     * tenant row has never been read (enrolled offline-first, licence details
     * still unknown). */
    const val DEFAULT_GRACE_DAYS = 45

    /** How long before the window closes the nudge starts. */
    const val EXPIRING_WITHIN_DAYS = 7

    const val MS_PER_DAY = 86_400_000L

    fun clampGraceDays(days: Int): Int = days.coerceIn(MIN_GRACE_DAYS, MAX_GRACE_DAYS)

    /**
     * `days.toLong()` first, deliberately: `365 * 86_400_000` overflows a
     * signed Int and would land the grace deadline in 1970, i.e. instantly
     * stale for the tenant who bought the longest window.
     */
    fun graceUntil(verifiedAtMs: Long, graceDays: Int): Long =
        verifiedAtMs + clampGraceDays(graceDays).toLong() * MS_PER_DAY

    /**
     * Builds an [Entitlement] only from values the server itself would accept,
     * and returns null otherwise.
     *
     * This is the "invalid input leaves prior good state untouched" property at
     * its source: a caller that gets null writes nothing, so a garbled response,
     * a captive-portal HTML page parsed as JSON, or a truncated read can never
     * overwrite a good local entitlement with a broken one. Validating on the
     * way IN rather than on the way out means a bad row cannot exist to be read.
     */
    fun of(
        tenantId: String?,
        role: String?,
        licenceState: String?,
        graceDays: Int?,
        verifiedAtMs: Long,
        displayName: String? = null,
    ): Entitlement? {
        if (tenantId == null || !TENANT_ID.matches(tenantId)) return null
        if (role == null || role !in ROLES) return null
        val licence = licenceState ?: return null
        if (licence !in LICENCE_STATES) return null
        // A negative or absurd clock is not a reason to refuse enrolment, but
        // it is a reason not to compute a deadline from it.
        if (verifiedAtMs <= 0L) return null
        val grace = clampGraceDays(graceDays ?: DEFAULT_GRACE_DAYS)
        return Entitlement(
            tenantId = tenantId,
            role = role,
            licenceState = licence,
            graceDays = grace,
            verifiedAtMs = verifiedAtMs,
            graceUntilMs = graceUntil(verifiedAtMs, grace),
            displayName = displayName?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * The banner decision, and nothing more.
     *
     * Wall clock, on purpose. A student who moves the phone's clock backwards
     * gets a longer grace window; that is the correct failure direction for
     * this product, and a monotonic-clock scheme would instead punish a device
     * that rebooted. There is nothing here worth defending against a user who
     * already has the corpus in their pocket.
     */
    fun stateAt(e: Entitlement?, nowMs: Long): EntitlementState {
        if (e == null) return EntitlementState.UNKNOWN
        if (e.licenceState == "suspended" || e.licenceState == "expired") return EntitlementState.LAPSED
        val remaining = e.graceUntilMs - nowMs
        return when {
            remaining <= 0L -> EntitlementState.STALE
            remaining <= EXPIRING_WITHIN_DAYS * MS_PER_DAY -> EntitlementState.EXPIRING
            else -> EntitlementState.ACTIVE
        }
    }

    /**
     * How stale a grant has to get before the app bothers re-reading it.
     *
     * Once a day, not once a launch. The grant changes when a registrar
     * revokes a membership or a bursar renews a licence -- events measured in
     * weeks -- and a call on every cold start would spend a student's mobile
     * data to learn nothing, several times a day, for a window measured in
     * forty-five of them.
     */
    const val REVERIFY_AFTER_MS = 86_400_000L

    fun dueForReverification(e: Entitlement?, nowMs: Long): Boolean {
        if (e == null) return true
        val age = nowMs - e.verifiedAtMs
        // A negative age means the clock moved backwards. Not a reason to
        // hammer the server, and not a reason to distrust the grant either.
        return age < 0L || age >= REVERIFY_AFTER_MS
    }

    /** Whole days left in the window, floored at 0. */
    fun daysRemaining(e: Entitlement?, nowMs: Long): Int {
        if (e == null) return 0
        val remaining = e.graceUntilMs - nowMs
        return if (remaining <= 0L) 0 else (remaining / MS_PER_DAY).toInt()
    }

    /**
     * Null means show nothing. Note what is absent from every string: any
     * suggestion that answers have stopped, because they have not.
     */
    fun banner(e: Entitlement?, nowMs: Long): String? = when (stateAt(e, nowMs)) {
        EntitlementState.UNKNOWN, EntitlementState.ACTIVE -> null
        EntitlementState.EXPIRING -> {
            val d = daysRemaining(e, nowMs)
            "Offline access confirmed for $d more ${if (d == 1) "day" else "days"} — " +
                "open once on wifi to renew."
        }
        EntitlementState.STALE ->
            "Enrolment not confirmed recently. Answers still work offline; " +
                "connect once to renew."
        EntitlementState.LAPSED ->
            "This institution's licence needs attention. Answers still work offline."
    }

    /**
     * The same thing in a few words, for the header pill.
     *
     * The header already carries the offline claim next to the live dot, and
     * that line is the one thing it must not give up -- so this goes in the
     * tertiary slot beside the document count, and stays short enough not to
     * push the line into eliding mid-word.
     */
    fun shortBanner(e: Entitlement?, nowMs: Long): String? = when (stateAt(e, nowMs)) {
        EntitlementState.UNKNOWN, EntitlementState.ACTIVE -> null
        EntitlementState.EXPIRING -> "renew in ${daysRemaining(e, nowMs)}d"
        EntitlementState.STALE -> "unconfirmed"
        EntitlementState.LAPSED -> "licence"
    }
}
