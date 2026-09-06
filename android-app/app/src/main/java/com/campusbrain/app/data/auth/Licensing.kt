package com.campusbrain.app.data.auth

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.campusbrain.app.data.AnalyticsStore
import com.campusbrain.app.data.QueryLog
import com.campusbrain.app.data.UserCorpusDb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * What this device is allowed to IMPORT, and nothing else.
 *
 * The composition root for the commercial layer, and a deliberate sibling of
 * [Identity] rather than a part of it. Identity's own header gives the
 * structural reason and it applies here word for word: *auth must never become
 * a dependency of retrieval*. If the licence were reachable through
 * `BrainRepository`, the first person who needed it inside the ask path would
 * find it already wired there. It is not, and there is nothing here for the
 * ask path to reach for.
 *
 * The one line worth being blunt about: **no value of [Tier] and no state of
 * this object can stop a question being answered.** FREE is not a trial. All
 * four routes, the bundled corpus, the document browser and the self test work
 * permanently, offline, with no key and no account. The only thing a licence
 * governs is the cap on adding a NEW document, and even that never touches a
 * document already added.
 *
 * ## The connection
 *
 * One more connection to `user_corpus.db`, opened here rather than borrowed,
 * for [Identity]'s reason: `UserCorpusDb.openOrCreate` runs inside repository
 * init and returns null whenever the corpus fails to open, and entering a
 * licence key must not depend on the import feature working. It carries a
 * `busy_timeout` for the same reason Identity's does -- an import holds an
 * EXCLUSIVE lock for up to fifty embeddings -- and every write through it is a
 * single statement, so it can never be the side that makes an import wait.
 *
 * It is shared with [AnalyticsStore], so the whole commercial layer costs one
 * connection rather than two.
 */
object Licensing {

    /**
     * The free import allowance.
     *
     * One document, and it is a sales tool rather than a trial. A prospective
     * buyer can put their own syllabus into the app in the room, on their own
     * file, with no account and no network, and ask it a question. That
     * demonstration is worth more than a second free document, and it is the
     * reason nothing else in the app is withheld.
     */
    const val FREE_MAX_DOCS = 1

    /**
     * And the byte allowance that goes with it.
     *
     * 4 MB, under `DocumentIngest.MAX_BYTES`, so on the free tier the file-size
     * refusal a student can act on ("that file is 9MB") is the one they hit,
     * not a licence wall they cannot.
     */
    const val FREE_MAX_TOTAL_KB = 4096

    /**
     * What the import path is actually allowed to do, resolved from the
     * licence and the clock into three plain numbers.
     *
     * A value class rather than a licence handed around, so no caller can
     * accidentally start branching on a tier for something other than a cap.
     */
    data class Caps(
        val tier: Tier,
        val maxDocs: Int,
        val maxTotalKb: Int,
    ) {
        companion object {
            /**
             * The compiled-in default, and the answer to every failure.
             *
             * No licence, an unreadable store, an expired key, a decode that
             * threw -- all of them land here. Lowest tier, smallest cap, and
             * never unlimited: a storage failure must never be interpretable
             * as a licence, because that is the failure mode in which the app
             * gives away the product for free to the one device whose disk is
             * broken and to every device an attacker can break it on.
             */
            val FREE = Caps(Tier.FREE, FREE_MAX_DOCS, FREE_MAX_TOTAL_KB)
        }
    }

    private val _license = MutableStateFlow<License?>(null)

    /** The current licence, or null for a device that has never been keyed. */
    val license: StateFlow<License?> = _license

    /**
     * Query counters, in memory, for the analytics screen.
     *
     * Public because the ONLY writer is a `ui/` caller: see [QueryLog]'s header
     * for why the counters are not inside `QueryRouter.answer()` where the
     * chokepoint is. Reading it costs nothing and gates nothing.
     */
    val queryLog = QueryLog()

    private var store: LicenseStore? = null
    private var analytics: AnalyticsStore? = null
    private var conn: SQLiteConnection? = null

    /**
     * This install's id, for an OWNER key's binding.
     *
     * Surfaced on the licence screen behind a long-press on the tier label,
     * following the `binding.header.setOnLongClickListener` precedent in
     * MainActivity: it is a support affordance, not a thing a student needs.
     * Null when the store could not be opened, in which case no OWNER key
     * verifies -- which is the correct direction to fail.
     */
    @Volatile var installId: String? = null
        private set

    @Volatile var initialised = false
        private set

    /**
     * Opens the store and publishes whatever was already keyed. Entirely
     * local; no network, ever, on any path in this file.
     *
     * Call on a background dispatcher from the activity, alongside
     * [Identity.init]. On a device in airplane mode this is the whole of the
     * commercial layer's startup work.
     */
    fun init(context: Context) {
        if (initialised) return
        synchronized(this) {
            if (initialised) return
            val opened = openStore(context)
            store = opened
            installId = opened?.installId()
            _license.value = opened?.load()
            // The opt-in, read back but never the counters: [queryLog] holds
            // only what THIS session has not written down yet, and the totals
            // are read from the store when the screen asks. Restoring them
            // into memory as well would double every figure on the next flush.
            queryLog.keepQueryText = analytics?.keepQueryText() ?: false
            initialised = true
        }
    }

    private fun openStore(context: Context): LicenseStore? = runCatching {
        val file = File(context.filesDir, UserCorpusDb.FILE_NAME)
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        connection.execSQL(EntitlementStore.PRAGMA_BUSY_TIMEOUT)
        conn = connection
        analytics = AnalyticsStore(connection).takeIf { it.ensureSchema() }
        LicenseStore(connection).takeIf { it.ensureSchema() }
    }.getOrNull()

    /**
     * The caps in force right now.
     *
     * Three things this function is careful about, and each one is a rule the
     * brief for this feature made explicit:
     *
     *  - **no licence -> [Caps.FREE].** Including the case where the store
     *    could not be opened at all, which is why the null branch is not
     *    "unknown, allow it".
     *  - **an expired licence -> [Caps.FREE].** Not zero. An institution that
     *    let a licence lapse drops to the same allowance a stranger has, and a
     *    student on that phone can still add their own timetable.
     *  - **nothing here can reduce what already exists.** These numbers are
     *    consulted by [com.campusbrain.app.data.DocumentIngest.ingest] and
     *    by the licence screen, and by nothing else. No caller deletes.
     */
    fun capsFor(l: License?, nowMs: Long): Caps {
        if (l == null) return Caps.FREE
        if (l.expiredAt(nowMs)) return Caps.FREE
        // coerceAtLeast the free floor: a key that somehow carried a cap below
        // the free allowance must not make a paying customer worse off than a
        // stranger. (LicenseKey rejects <= 0 already; this covers 1 vs FREE.)
        return Caps(
            tier = l.tier,
            maxDocs = maxOf(l.maxDocs, FREE_MAX_DOCS),
            maxTotalKb = maxOf(l.maxTotalKb, FREE_MAX_TOTAL_KB),
        )
    }

    fun caps(nowMs: Long = System.currentTimeMillis()): Caps = capsFor(_license.value, nowMs)

    /** The tier the UI should show. FREE whenever anything at all is wrong. */
    fun tier(nowMs: Long = System.currentTimeMillis()): Tier = caps(nowMs).tier

    /** Analytics is an institutional feature. It is a SCREEN, not a gate. */
    fun analyticsVisible(nowMs: Long = System.currentTimeMillis()): Boolean =
        tier(nowMs).seesAnalytics

    // --- entering a key ----------------------------------------------------

    sealed interface ApplyResult {
        data class Accepted(val license: License) : ApplyResult
        /** [prior] is what the device still has -- unchanged. */
        data class Refused(
            val reason: LicenseKey.Rejection,
            val prior: License?,
        ) : ApplyResult
        /** Verified, but there is nowhere to write it. Nothing changed. */
        data class NotStored(val prior: License?) : ApplyResult
    }

    /**
     * Verifies a pasted key and, only on success, replaces the stored one.
     *
     * **A rejected key leaves the prior licence exactly where it was.** That is
     * the single most important line in this function and it is why the write
     * is after the verification rather than before it, with no clear() in
     * between. An admin who pastes last year's key on top of this year's, or
     * fat-fingers one character of it, must not thereby downgrade a working
     * institutional install to FREE -- the failure of a paste is not evidence
     * about the licence the device already holds. Same rule, same reason, as
     * `Entitlements.of` returning null rather than half an entitlement.
     *
     * Never logs [key]. A licence key is a bearer credential; a logcat line
     * containing one is a licence key published to every app on the phone that
     * can read logs.
     */
    fun apply(key: String, nowMs: Long = System.currentTimeMillis()): ApplyResult {
        val result = decide(store, _license.value, key, nowMs, installId)
        if (result is ApplyResult.Accepted) _license.value = result.license
        return result
    }

    /**
     * The decision, with the singleton's state passed in rather than read.
     *
     * Split out so the property above -- a rejected key changes nothing -- can
     * be asserted by a JVM test with no `Context`, no SQLite native and no
     * initialised singleton. That property is the one worth testing hardest,
     * because the way it breaks is invisible: a `clear()` added "to be tidy"
     * before the verification, and every mistyped key silently downgrades a
     * paying institution to FREE until someone notices imports failing.
     *
     * Note there is no branch that writes on a failure, and no branch that
     * clears. The only mutation in this function is the one inside `save`, and
     * it is inside the `Valid` arm.
     */
    fun decide(
        store: LicenseStore?,
        prior: License?,
        key: String,
        nowMs: Long,
        deviceId: String?,
        /** Overridden only by tests, which sign with a throwaway keypair.
         * Every caller in the app takes the default, which is the founder's. */
        publicKeyB64: String = LicenseKey.PUBLIC_KEY_B64,
    ): ApplyResult = when (val outcome = LicenseKey.verify(key, nowMs, deviceId, publicKeyB64)) {
        is LicenseKey.Outcome.Invalid -> ApplyResult.Refused(outcome.reason, prior)
        is LicenseKey.Outcome.Valid ->
            if (store == null || !store.save(outcome.license)) ApplyResult.NotStored(prior)
            else ApplyResult.Accepted(outcome.license)
    }

    /**
     * Back to FREE, on an explicit admin action.
     *
     * Removes the licence row and nothing else. Every document the institution
     * imported stays imported, stays listed and stays searchable -- see
     * [com.campusbrain.app.data.DocumentIngest] for why that is a promise and
     * not an oversight.
     */
    fun removeLicense(): Boolean {
        val ok = store?.clear() ?: false
        if (ok) _license.value = null
        return ok
    }

    // --- analytics persistence --------------------------------------------

    /**
     * Writes the in-memory counters down. Called at lifecycle boundaries only
     * -- `MainActivity.onStop`, and when the analytics screen opens -- never
     * once per query. [QueryLog]'s header has the contention argument.
     *
     * Silently does nothing when there is no store. Losing a session's route
     * counts is a rounding error; making an answer wait on a write lock is not.
     */
    fun flushAnalytics(): Boolean {
        val a = analytics ?: return false
        return a.merge(queryLog.drain())
    }

    /** The persisted totals plus whatever this session has not flushed yet. */
    fun analyticsSnapshot(): QueryLog.Snapshot {
        flushAnalytics()
        return analytics?.load() ?: queryLog.snapshot()
    }

    /** Per-route "minutes a human would have spent on this", admin-entered. */
    fun minutesAssumption(): Map<com.campusbrain.app.data.Route, Int> =
        analytics?.minutes() ?: AnalyticsStore.DEFAULT_MINUTES

    fun setMinutesAssumption(route: com.campusbrain.app.data.Route, minutes: Int): Boolean =
        analytics?.setMinutes(route, minutes) ?: false

    fun keepQueryText(): Boolean = analytics?.keepQueryText() ?: false

    /** The opt-in. Local only: nothing in this app can transmit it. */
    fun setKeepQueryText(on: Boolean): Boolean {
        queryLog.keepQueryText = on
        return analytics?.setKeepQueryText(on) ?: false
    }

    /** For tests and a clean process teardown. */
    fun close() {
        runCatching { conn?.close() }
        conn = null
        store = null
        analytics = null
        installId = null
        _license.value = null
        queryLog.clear()
        initialised = false
    }
}
