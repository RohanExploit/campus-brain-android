package com.kriet.campusbrain.data.auth

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.kriet.campusbrain.data.query

/**
 * Where the licence and this install's own id live between runs.
 *
 * Built exactly like [EntitlementStore], for exactly the same reasons, and the
 * two doc comments are worth reading together:
 *
 *  - it takes a raw [SQLiteConnection] and never a `Context`, so every line is
 *    runnable in a JVM unit test with no Robolectric and no device;
 *  - it lives in `user_corpus.db`, which survives app updates -- `brain.db` is
 *    re-copied out of the APK whenever `built_at_utc` changes, so a licence
 *    written there would be destroyed by the next release, and an institution
 *    would have to re-key every phone after every update;
 *  - every write is a SINGLE statement, no BEGIN, so this store can never be
 *    the thing holding an EXCLUSIVE lock while a fifty-embedding import waits.
 *
 * ## What a failure here means
 *
 * Every method fails soft: [load] returns null, [save] returns false. That is
 * deliberate and it has one direction. A store that cannot be read leaves the
 * app on the compiled-in default, which is the LOWEST tier and the SMALLEST
 * cap -- never unlimited. Storage breaking is not a licence. See
 * [Licensing.capsFor], where that rule is enforced rather than assumed.
 */
class LicenseStore(private val conn: SQLiteConnection) {

    /**
     * Creates the tables if they are absent. Idempotent.
     *
     * Does not touch `PRAGMA user_version`: that belongs to
     * [com.kriet.campusbrain.data.UserCorpusDb], which owns the corpus schema
     * and its migrations. `CREATE TABLE IF NOT EXISTS` is the whole migration
     * story for two singleton rows, the same conclusion [EntitlementStore]
     * reached.
     */
    fun ensureSchema(): Boolean = runCatching {
        // CHECK(id = 1) is the singleton: one device, one licence. A second
        // row would be a bug that read as a race.
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS license (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "tenant_id TEXT NOT NULL, display_name TEXT NOT NULL, tier TEXT NOT NULL, " +
                "issued_at_ms INTEGER NOT NULL, expires_at_ms INTEGER, " +
                "max_docs INTEGER NOT NULL, max_total_kb INTEGER NOT NULL, device_id TEXT)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS install_id (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), value TEXT NOT NULL)"
        )
        true
    }.getOrDefault(false)

    /**
     * The stored licence, or null.
     *
     * Note what is NOT re-checked here: the signature. The key string is not
     * kept -- only the fields it carried -- so this is trust in the app's own
     * private file, the same trust [EntitlementStore.load] places in its row.
     * The tier and the caps ARE re-validated, because a garbage `tier` column
     * must not become a tier, and because the cost of checking one enum and
     * two integers is nothing next to acting on them.
     *
     * Expiry is deliberately not checked here either. An expired licence is
     * still the licence this institution bought, the screen has to be able to
     * say "this ran out on the 3rd", and the only consequence -- a smaller cap
     * on NEW imports -- is applied in [Licensing.capsFor] where the clock is.
     */
    fun load(): License? = runCatching {
        conn.query(
            "SELECT tenant_id, display_name, tier, issued_at_ms, expires_at_ms, " +
                "max_docs, max_total_kb, device_id FROM license WHERE id = 1"
        ) { st ->
            val tier = Tier.of(st.getText(2))
            val maxDocs = st.getLong(5).toInt()
            val maxKb = st.getLong(6).toInt()
            if (tier == null || tier == Tier.FREE || maxDocs <= 0 || maxKb <= 0) null
            else License(
                tenantId = st.getText(0),
                tenantDisplayName = st.getText(1),
                tier = tier,
                issuedAtMs = st.getLong(3),
                expiresAtMs = if (st.isNull(4)) null else st.getLong(4),
                maxDocs = maxDocs,
                maxTotalKb = maxKb,
                deviceId = if (st.isNull(7)) null else st.getText(7),
            )
        }.firstOrNull()
    }.getOrNull()

    /**
     * Replaces the licence. One statement, so there is no window in which the
     * old row is gone and the new one is not yet there -- which matters here
     * more than usual, because that window is a moment in which a paying
     * institution's phone would report itself as FREE.
     *
     * Callers must pass something that came out of [LicenseKey.verify]. A
     * rejected key must never reach here: see [Licensing.apply], where the
     * whole "a bad paste cannot downgrade a good licence" property lives.
     */
    fun save(l: License): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO license(id, tenant_id, display_name, tier, " +
                "issued_at_ms, expires_at_ms, max_docs, max_total_kb, device_id) " +
                "VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?)"
        ).use { st ->
            st.bindText(1, l.tenantId)
            st.bindText(2, l.tenantDisplayName)
            st.bindText(3, l.tier.name)
            st.bindLong(4, l.issuedAtMs)
            if (l.expiresAtMs == null) st.bindNull(5) else st.bindLong(5, l.expiresAtMs)
            st.bindLong(6, l.maxDocs.toLong())
            st.bindLong(7, l.maxTotalKb.toLong())
            if (l.deviceId == null) st.bindNull(8) else st.bindText(8, l.deviceId)
            st.step()
        }
        true
    }.getOrDefault(false)

    /** Back to FREE. Removes nothing the user imported -- see [Licensing]. */
    fun clear(): Boolean = runCatching {
        conn.execSQL("DELETE FROM license WHERE id = 1")
        true
    }.getOrDefault(false)

    /**
     * This install's id, created on first read and stable thereafter.
     *
     * A UUID this app generates, NOT `Settings.Secure.ANDROID_ID`. Two reasons
     * and the first is decisive: ANDROID_ID is documented as changing on
     * factory reset and is observed rotating on some OEM builds and after some
     * OS upgrades, so an owner key bound to it stops working for the owner --
     * a hardware lock whose main victim is the person it was issued to. The
     * second is that it is a device identifier with privacy weight, and this
     * app's entire claim is that it collects nothing; a random UUID in the
     * app's own file identifies an install of this app and nothing else.
     *
     * Returns null only when the store itself is broken, in which case an
     * OWNER key cannot be verified. Correct: the fallback for "cannot tell
     * which device this is" is no owner mode, not owner mode.
     */
    fun installId(generate: () -> String = { java.util.UUID.randomUUID().toString() }): String? =
        runCatching {
            val existing = conn.query("SELECT value FROM install_id WHERE id = 1") {
                it.getText(0)
            }.firstOrNull()?.takeIf { it.isNotBlank() }
            if (existing != null) return@runCatching existing
            val fresh = generate()
            // INSERT OR IGNORE, not REPLACE: if two callers race, the loser
            // must adopt the winner's id rather than overwrite it, or an owner
            // key issued a second ago stops matching.
            conn.prepare("INSERT OR IGNORE INTO install_id(id, value) VALUES (1, ?)").use {
                it.bindText(1, fresh)
                it.step()
            }
            conn.query("SELECT value FROM install_id WHERE id = 1") { it.getText(0) }.firstOrNull()
        }.getOrNull()
}
