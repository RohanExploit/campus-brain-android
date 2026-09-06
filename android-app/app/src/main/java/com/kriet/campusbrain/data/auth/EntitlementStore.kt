package com.kriet.campusbrain.data.auth

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.kriet.campusbrain.data.query

/**
 * Where the entitlement and the refresh token live between runs.
 *
 * Takes a raw [SQLiteConnection], never a `Context`. That is what makes every
 * line below runnable in a JVM unit test with no Robolectric and no device --
 * which matters more than usual here, because the thing being tested is
 * whether a bad server response can destroy a good local grant, and that is
 * not a property you want to first observe on a student's phone.
 *
 * ## Why `user_corpus.db` and not a new file
 *
 * [com.kriet.campusbrain.data.BrainDb] re-copies `brain.db` out of the APK
 * asset whenever `built_at_utc` changes -- i.e. on every app update -- so
 * anything written there is destroyed by the next release. `user_corpus.db` is
 * the app's own writable store, is never re-copied, and therefore survives
 * updates untouched. A student who has already redeemed their enrolment code
 * must not be asked for it again because the college pushed a new build.
 *
 * (Strictly the survive-an-update property comes from the LOCATION -- anything
 * in `filesDir` has it -- so a separate `entitlement.db` would work equally
 * well. Reuse was chosen to avoid a second file for two rows.)
 *
 * ## Locking
 *
 * The corpus database runs on the default rollback journal, so a writer holds
 * an EXCLUSIVE lock, and [com.kriet.campusbrain.data.UserCorpusDb.write] wraps
 * a whole document -- up to fifty embeddings -- in one BEGIN IMMEDIATE. An
 * entitlement read landing inside that window would get SQLITE_BUSY. Two
 * decisions follow:
 *
 *  - the connection this store is handed is expected to carry a busy_timeout
 *    (see [PRAGMA_BUSY_TIMEOUT] and its use in [Identity]), so a read waits
 *    rather than failing;
 *  - every write here is a SINGLE statement. No BEGIN, no multi-statement
 *    transaction, so this store can never be the thing holding a lock while an
 *    import waits.
 *
 * WAL is deliberately NOT enabled: retrieval reads this same file through a
 * different connection, and changing the journal mode underneath it is a
 * bigger change than an auth feature is entitled to make.
 */
class EntitlementStore(private val conn: SQLiteConnection) {

    /** The tokens. Kept apart from the entitlement because they have opposite
     * lifetimes: a token is disposable and rotates hourly, an entitlement is
     * the durable fact and must survive every token failure. */
    data class Session(
        val userId: String?,
        val accessToken: String,
        val refreshToken: String,
        /** Unix seconds, read off the JWT's `exp`. See [SupabaseAuth.jwtExpiry]:
         * this schedules a refresh, it is not a security control. */
        val expiresAtEpochSec: Long,
    )

    /**
     * Creates the two tables if they are absent. Idempotent, and safe to call
     * on a connection that already holds the corpus schema.
     *
     * Note what this does NOT do: touch `PRAGMA user_version`. UserCorpusDb
     * sets it to 1 unconditionally on every open, so bumping it here would be
     * undone on the next launch and would read as a migration hook that does
     * not work. `CREATE TABLE IF NOT EXISTS` is the whole migration story for
     * two singleton rows.
     */
    fun ensureSchema(): Boolean = runCatching {
        // The CHECK(id = 1) is the singleton: there is one device, one
        // enrolment, and a second row would be a bug that read as a race.
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS entitlement (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "tenant_id TEXT NOT NULL, display_name TEXT, role TEXT NOT NULL, " +
                "licence_state TEXT NOT NULL, grace_days INTEGER NOT NULL, " +
                "verified_at_ms INTEGER NOT NULL, grace_until_ms INTEGER NOT NULL)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS auth_session (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "user_id TEXT, access_token TEXT NOT NULL, refresh_token TEXT NOT NULL, " +
                "expires_at_epoch_sec INTEGER NOT NULL DEFAULT 0)"
        )
        true
    }.getOrDefault(false)

    /**
     * The stored grant, or null if there is none.
     *
     * Re-validated through [Entitlements.of] on the way out rather than
     * trusted. A row can only have got here through the same validation, but a
     * file on a phone can be corrupted by things that are not this code, and
     * the cost of checking four short strings is nothing next to acting on a
     * `licence_state` of garbage.
     */
    fun load(): Entitlement? = runCatching {
        // `grace_until_ms` is stored but deliberately not read back: the
        // deadline is recomputed from verified_at_ms + grace_days, so the two
        // can never drift and there is no way for a hand-edited column to
        // extend a window. The column stays because it is what a support
        // engineer with sqlite3 would look for first.
        conn.query(
            "SELECT tenant_id, display_name, role, licence_state, grace_days, " +
                "verified_at_ms FROM entitlement WHERE id = 1"
        ) { st ->
            Entitlements.of(
                tenantId = st.getText(0),
                displayName = if (st.isNull(1)) null else st.getText(1),
                role = st.getText(2),
                licenceState = st.getText(3),
                graceDays = st.getLong(4).toInt(),
                verifiedAtMs = st.getLong(5),
            )
        }.firstOrNull()
    }.getOrNull()

    /**
     * Replaces the grant. One statement, so there is no window in which the
     * old row is gone and the new one is not yet there.
     *
     * Callers must pass a value object they already built through
     * [Entitlements.of]; a null from a failed fetch must never reach here,
     * because writing a fresh `verified_at_ms` off a response the app could
     * not parse is exactly how a working offline device would lose its grace
     * window on a bad campus wifi day.
     */
    fun save(e: Entitlement): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO entitlement" +
                "(id, tenant_id, display_name, role, licence_state, grace_days, " +
                " verified_at_ms, grace_until_ms) VALUES (1, ?, ?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, e.tenantId)
            if (e.displayName == null) st.bindNull(2) else st.bindText(2, e.displayName)
            st.bindText(3, e.role)
            st.bindText(4, e.licenceState)
            st.bindLong(5, e.graceDays.toLong())
            st.bindLong(6, e.verifiedAtMs)
            st.bindLong(7, e.graceUntilMs)
            st.step()
        }
        true
    }.getOrDefault(false)

    /** Sign-out, or an enrolment that the server has revoked. */
    fun clear(): Boolean = runCatching {
        conn.execSQL("DELETE FROM entitlement WHERE id = 1")
        true
    }.getOrDefault(false)

    fun loadSession(): Session? = runCatching {
        conn.query(
            "SELECT user_id, access_token, refresh_token, expires_at_epoch_sec " +
                "FROM auth_session WHERE id = 1"
        ) { st ->
            Session(
                userId = if (st.isNull(0)) null else st.getText(0),
                accessToken = st.getText(1),
                refreshToken = st.getText(2),
                expiresAtEpochSec = st.getLong(3),
            )
        }.firstOrNull()?.takeIf { it.refreshToken.isNotBlank() }
    }.getOrNull()

    /**
     * Stores the rotated pair.
     *
     * Plain text in an app-private file, and worth being explicit about why
     * that is the chosen trade rather than an oversight. Encrypting it would
     * mean `androidx.security:security-crypto`, i.e. a new Gradle dependency,
     * which this task rules out and this codebase avoids on principle (see the
     * header of `answer/CloudAnswer.kt`). What the token can actually do is the
     * mitigating fact: it reads one membership row, one tenant row and one
     * corpus-version row for a single student, and writes route labels. It
     * cannot reach the corpus, the student's academic record, or anyone else's
     * tenant -- RLS resolves tenancy server-side from `memberships`, never from
     * a claim the holder of this token could set.
     */
    fun saveSession(s: Session): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO auth_session" +
                "(id, user_id, access_token, refresh_token, expires_at_epoch_sec) " +
                "VALUES (1, ?, ?, ?, ?)"
        ).use { st ->
            if (s.userId == null) st.bindNull(1) else st.bindText(1, s.userId)
            st.bindText(2, s.accessToken)
            st.bindText(3, s.refreshToken)
            st.bindLong(4, s.expiresAtEpochSec)
            st.step()
        }
        true
    }.getOrDefault(false)

    /**
     * Drops the tokens and LEAVES THE ENTITLEMENT ALONE.
     *
     * Called when a refresh is finally rejected. The device stays enrolled: the
     * grace window is the whole mechanism for surviving a server the phone
     * cannot reach, and deleting the grant because a token expired would turn
     * every long offline stretch into a re-enrolment.
     */
    fun clearSession(): Boolean = runCatching {
        conn.execSQL("DELETE FROM auth_session WHERE id = 1")
        true
    }.getOrDefault(false)

    companion object {
        /** Long enough to outlast a fifty-chunk document import holding the
         * write lock, short enough that a genuinely wedged file does not hang
         * a screen. */
        const val PRAGMA_BUSY_TIMEOUT = "PRAGMA busy_timeout = 5000"
    }
}
