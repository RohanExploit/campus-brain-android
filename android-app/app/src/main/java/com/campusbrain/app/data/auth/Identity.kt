package com.campusbrain.app.data.auth

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.campusbrain.app.data.UserCorpusDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The composition root for identity, and deliberately NOT part of
 * [com.campusbrain.app.data.BrainRepository].
 *
 * Two reasons it stands apart, and both are structural:
 *
 *  1. **It must be readable before the repository is Ready.** `BrainRepository`
 *     publishes `InitState.Ready` only after the corpus opens, the embedder
 *     loads and the graph warms. A sign-in screen and a licence banner have to
 *     be able to say something during that window, and folding entitlement into
 *     the repository would make the answer to "is this device enrolled?"
 *     depend on an 86 MB ONNX model having loaded.
 *
 *  2. **Auth must never become a dependency of retrieval.** If entitlement were
 *     reached through the repository, the first person to need it inside the
 *     ask path would find it already wired there. Keeping it in a separate
 *     object with its own connection means the ask path has nothing to reach
 *     for, which is a better guarantee than an instruction not to.
 *
 * The connection is this object's own, opened directly on
 * `filesDir/user_corpus.db`, and NOT the one `UserCorpusDb.openOrCreate`
 * returns -- that one is created inside repository init and is null whenever
 * the user corpus fails to open, which would make enrolment depend on the
 * import feature working. See [EntitlementStore] for the locking consequences
 * of a second connection to the same file.
 */
object Identity {

    private val _entitlement = MutableStateFlow<Entitlement?>(null)

    /**
     * The current grant, or null for a device that has never enrolled.
     *
     * Note what this flow does not have: any influence on whether a question
     * gets answered. [Entitlements.stateAt] turns it into a banner and that is
     * the entire extent of its authority.
     */
    val entitlement: StateFlow<Entitlement?> = _entitlement

    private var store: EntitlementStore? = null
    private var conn: SQLiteConnection? = null
    private var config: AuthConfig? = null
    private var auth: SupabaseAuth? = null
    private var plane: ControlPlane? = null

    /** True once [init] has run, whether or not it found a configuration. */
    @Volatile var initialised = false
        private set

    /**
     * Opens the local store and publishes whatever was already granted.
     *
     * Entirely local: no network call, no token check. Call it on a background
     * dispatcher from `Application.onCreate` or the activity, ahead of
     * `BrainRepository.init`. On a device in airplane mode this is the whole of
     * the auth system's startup work, which is the point.
     */
    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val opened = openStore(context)
            store = opened
            _entitlement.value = opened?.load()
            config = AuthConfig.load(context.getExternalFilesDir(null), context.filesDir)
            config?.let { cfg ->
                val a = SupabaseAuth(cfg, opened)
                auth = a
                plane = ControlPlane(cfg, a).also { p ->
                    p.tenantIdProvider = { _entitlement.value?.tenantId }
                }
            }
            initialised = true
        }
    }

    private fun openStore(context: Context): EntitlementStore? = runCatching {
        val file = File(context.filesDir, UserCorpusDb.FILE_NAME)
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        // A document import wraps up to fifty embeddings in one BEGIN
        // IMMEDIATE, and this file is on the default rollback journal, so a
        // read landing inside that window would otherwise fail with
        // SQLITE_BUSY instead of waiting a moment.
        connection.execSQL(EntitlementStore.PRAGMA_BUSY_TIMEOUT)
        conn = connection
        EntitlementStore(connection).takeIf { it.ensureSchema() }
    }.getOrNull()

    // --- the one online moment --------------------------------------------

    sealed interface EnrolResult {
        data class Enrolled(val entitlement: Entitlement) : EnrolResult

        /**
         * The server answered and said no, at a step this names.
         *
         * [stage] exists because "rejected" alone is not something a student
         * can act on. [enrol] runs several sequential calls and a refusal at
         * one of them means something entirely different from a refusal at
         * another -- a password that does not match is a different next move
         * from a code that has been used up -- and a screen that names both
         * is naming one thing it already knows to be innocent.
         *
         * It carries no message. The server's own prose was the obvious thing
         * to put here and it is exactly the wrong thing: [SupabaseHttp.errorMessage]
         * falls back to the first 120 characters of the response body, which
         * on campus wifi is a captive portal's HTML, and none of it is copy
         * anyone wrote for a student to read. Leaving the field out is a
         * stronger guarantee that it will never reach a screen than a comment
         * asking that it not be.
         */
        data class Rejected(val stage: Stage) : EnrolResult

        /** Offline, or the server never answered. Any prior grant is intact. */
        data object Unavailable : EnrolResult

        /** No `config.json` on the device, so there is no project to talk to.
         * A supported state: the app still answers from the bundled corpus. */
        data object NotConfigured : EnrolResult

        /**
         * What was refused -- not which HTTP call refused it.
         *
         * Naming the step would not survive contact with the server:
         * `redeem_enrolment_code` refuses a bad code and refuses a request
         * with no session behind it, both from inside the same call, and
         * those need different words. So each value names the thing the
         * student would have to change, and the copy for it has to be true
         * on every path that reaches it.
         */
        enum class Stage {
            /**
             * Sign-up was refused and the sign-in fallback was refused too.
             *
             * Almost always an account that already exists at this address
             * with a different password. Not only that, and the copy must not
             * promise it is: a sign-up refused on its own merits -- a project
             * that raised the password minimum in its dashboard, a rate limit
             * -- is followed by a sign-in that fails because there is no such
             * account, and lands here as well. What is true on both paths is
             * that this email and this password were turned down together,
             * and that the enrolment code was never sent.
             */
            CREDENTIALS,

            /**
             * The account is signed in and the server looked at the code and
             * said no: unknown, spent, or expired.
             *
             * Reached only from [ControlPlane.RedeemOutcome.Invalid], which
             * is now raised only when the server genuinely decided. A 2xx
             * that does not parse is [ControlPlane.RedeemOutcome.Unavailable]
             * instead -- see the note there -- so this stage never blames a
             * code for a portal.
             */
            CODE,

            /**
             * SQLSTATE 28000: the redeem function found no authenticated user.
             *
             * Its own stage rather than folded into [CODE], because the code
             * was not looked at and is not spent, and rather than folded into
             * [EnrolResult.Unavailable], because the institution answered --
             * saying it could not be reached would be a plain lie about a
             * server that replied.
             */
            SESSION,
        }
    }

    /**
     * Sign up (or sign in), redeem the institution's code, read the grant,
     * write it down. The only moment in this app's life that requires a
     * network, and it happens once.
     *
     * [email] may be [SupabaseAuth.syntheticEmail]; it proves nothing and
     * receives nothing. The enrolment code is what proves the student belongs
     * to the institution.
     */
    suspend fun enrol(email: String, password: String, code: String): EnrolResult {
        val a = auth ?: return EnrolResult.NotConfigured
        val p = plane ?: return EnrolResult.NotConfigured

        // Sign-up first, falling back to sign-in: a student who already has an
        // account -- because they reinstalled, or because the first attempt's
        // response was lost -- must not be stopped by "already registered".
        when (a.signUp(email, password)) {
            is SupabaseAuth.Outcome.Ok -> Unit
            SupabaseAuth.Outcome.Unavailable -> return EnrolResult.Unavailable
            is SupabaseAuth.Outcome.Rejected -> when (a.signIn(email, password)) {
                is SupabaseAuth.Outcome.Ok -> Unit
                SupabaseAuth.Outcome.Unavailable -> return EnrolResult.Unavailable
                // Both credential calls were refused, so the code below is
                // never sent. That is worth saying on the card: a student who
                // mistyped their password has not spent a single-use code.
                is SupabaseAuth.Outcome.Rejected ->
                    return EnrolResult.Rejected(EnrolResult.Stage.CREDENTIALS)
            }
        }

        when (p.redeem(code)) {
            is ControlPlane.RedeemOutcome.Enrolled -> Unit
            // Already enrolled is a success with a different history: the
            // membership row this device needs already exists, and the grant
            // is read below exactly as it would have been.
            ControlPlane.RedeemOutcome.AlreadyEnrolled -> Unit
            ControlPlane.RedeemOutcome.Invalid ->
                return EnrolResult.Rejected(EnrolResult.Stage.CODE)
            ControlPlane.RedeemOutcome.NotSignedIn ->
                return EnrolResult.Rejected(EnrolResult.Stage.SESSION)
            ControlPlane.RedeemOutcome.Unavailable -> return EnrolResult.Unavailable
        }

        return when (val grant = refresh()) {
            null -> EnrolResult.Unavailable
            else -> EnrolResult.Enrolled(grant)
        }
    }

    /**
     * Re-reads the grant and restarts the offline clock. Safe to call on every
     * app open: it costs two small reads when there is a network and nothing
     * at all when there is not.
     *
     * Returns null and **writes nothing** on any failure. That single rule is
     * what stops a captive portal from resetting a device's grace window --
     * the deadline moves forward only on a fetch that fully succeeded and fully
     * validated.
     */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()): Entitlement? {
        val p = plane ?: return null
        val grant = p.fetchGrant(nowMs) ?: return null
        if (store?.save(grant) != true) return null
        _entitlement.value = grant
        return grant
    }

    /**
     * The once-a-day re-read, safe to fire on every app open.
     *
     * Costs nothing on a device with no configuration, no session, or a grant
     * checked recently -- all three return before any socket is opened, which
     * is what makes it safe to call from a launch path that has to work in
     * airplane mode. Like [refresh], it writes only on a complete success.
     */
    suspend fun refreshIfDue(nowMs: Long = System.currentTimeMillis()): Entitlement? {
        if (plane == null) return null
        val current = _entitlement.value
        if (!Entitlements.dueForReverification(current, nowMs)) return current
        return refresh(nowMs)
    }

    /** Non-blocking banner text, or null. Never gates anything. */
    fun banner(nowMs: Long = System.currentTimeMillis()): String? =
        Entitlements.banner(_entitlement.value, nowMs)

    /** Two or three words for the header pill. Null when there is nothing to say. */
    fun shortBanner(nowMs: Long = System.currentTimeMillis()): String? =
        Entitlements.shortBanner(_entitlement.value, nowMs)

    fun state(nowMs: Long = System.currentTimeMillis()): EntitlementState =
        Entitlements.stateAt(_entitlement.value, nowMs)

    /**
     * Aggregate telemetry, on an explicit call from a UI-level caller only.
     *
     * Not wired into the router, and not wired into anything on the answer
     * path. A route label and a latency are all that can be sent -- see
     * [ControlPlane.usagePayload] -- and even that is skipped silently when
     * there is no session.
     */
    suspend fun reportUsage(
        event: String,
        route: com.campusbrain.app.data.Route? = null,
        latencyMs: Int? = null,
        ok: Boolean? = null,
    ): Boolean = plane?.postUsage(event, route, latencyMs, ok) ?: false

    /** Forgets everything on this device. Retrieval carries on regardless. */
    fun signOut() {
        auth?.signOut()
        _entitlement.value = null
    }

    /** For tests and for a clean process teardown. */
    fun close() {
        runCatching { conn?.close() }
        conn = null
        store = null
        auth = null
        plane = null
        config = null
        _entitlement.value = null
        initialised = false
    }
}
