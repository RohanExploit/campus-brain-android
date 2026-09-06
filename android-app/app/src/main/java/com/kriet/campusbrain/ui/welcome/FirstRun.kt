package com.kriet.campusbrain.ui.welcome

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.query

/**
 * Whether the welcome screen has been seen, and what it says.
 *
 * Split the way the auth layer is split, for the same reason: the DECISION is
 * a pure function over a nullable Boolean and the STORAGE takes a raw
 * [SQLiteConnection] rather than a `Context`, so both are testable on the JVM
 * with no device and no Robolectric. See
 * [com.kriet.campusbrain.data.auth.EntitlementStore], which is the precedent
 * this file follows almost line for line.
 */
object FirstRun {

    /**
     * Show the welcome only on a device that is definitely new.
     *
     * Three inputs and only one of them shows it. `false` means the store was
     * read and holds no record: a genuine first run. `true` means it has been
     * seen. **`null` means the store could not be read, and that also means
     * do not show it** -- which is the whole reason this is a function rather
     * than a `!seen`.
     *
     * The asymmetry is deliberate. A phone whose `user_corpus.db` cannot be
     * opened is a phone where the seen-flag can never be written either, so
     * treating unknown as "new" would put the same four panes in front of the
     * same student on every single launch, forever. An onboarding screen shown
     * once too few is a missed introduction; shown every launch it is a fault
     * the student cannot clear.
     */
    fun shouldShow(seen: Boolean?): Boolean = seen == false

    /**
     * One pane: a claim and the sentence that backs it.
     *
     * Resource ids rather than strings so the copy stays in `strings.xml` and
     * the ordering below stays assertable in a unit test -- an int is not an
     * Android object, which is the same trick
     * [com.kriet.campusbrain.ui.auth.EnrolCopy] uses.
     */
    data class Pane(val titleRes: Int, val bodyRes: Int)

    /**
     * The four things a student has to be told before their first question,
     * in the order they matter.
     *
     * Citations lead. It is the claim that separates this app from every
     * chatbot the student has already used, and it is the one they can verify
     * on their very first answer by tapping the chip underneath it -- so it is
     * the promise that gets checked rather than believed.
     *
     * Offline is second because it is the most surprising, privacy third
     * because it is the consequence of offline rather than a separate
     * mechanism, and adding documents last because it is the only pane
     * describing something the student has to do rather than something the app
     * already did.
     */
    val PANES: List<Pane> = listOf(
        Pane(R.string.welcome_cited_title, R.string.welcome_cited_body),
        Pane(R.string.welcome_offline_title, R.string.welcome_offline_body),
        Pane(R.string.welcome_private_title, R.string.welcome_private_body),
        Pane(R.string.welcome_yours_title, R.string.welcome_yours_body),
    )

    /** True when [index] is the last pane, i.e. the action finishes rather
     * than advances. */
    fun isLast(index: Int): Boolean = index >= PANES.lastIndex

    /** Clamped so a restored index from an older build cannot land off the end. */
    fun paneAt(index: Int): Pane = PANES[index.coerceIn(0, PANES.lastIndex)]
}

/**
 * The seen-flag, in `user_corpus.db`.
 *
 * Not `brain.db`: [com.kriet.campusbrain.data.BrainDb] re-copies that file out
 * of the APK asset whenever `built_at_utc` changes, so a flag written there
 * would be destroyed by the next release and every student would be welcomed
 * again after every update.
 *
 * The locking rules are [com.kriet.campusbrain.data.auth.EntitlementStore]'s
 * and they are not optional here either: the corpus database runs on the
 * default rollback journal and a document import holds an EXCLUSIVE lock for
 * up to fifty embeddings, so the connection handed in is expected to carry
 * [com.kriet.campusbrain.data.auth.EntitlementStore.PRAGMA_BUSY_TIMEOUT], and
 * every write below is a single statement so this store can never be the thing
 * an import is waiting on.
 */
class FirstRunStore(private val conn: SQLiteConnection) {

    /** Idempotent, and safe on a connection that already holds the corpus
     * schema. `PRAGMA user_version` is left alone for the reason
     * `EntitlementStore.ensureSchema` gives: UserCorpusDb rewrites it on every
     * open, so bumping it here would read as a migration hook that does not
     * work. */
    fun ensureSchema(): Boolean = runCatching {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS ui_first_run (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), " +
                "welcome_seen_at_ms INTEGER NOT NULL)"
        )
        true
    }.getOrDefault(false)

    /**
     * True if the welcome has been seen, false if it has not, **null if that
     * could not be established** -- which [FirstRun.shouldShow] treats as "do
     * not show".
     *
     * The three-valued return is the point of this method. Collapsing the
     * failure into `false` would show the welcome on every launch of a device
     * whose store is broken; collapsing it into `true` would be a lie the
     * caller could not distinguish from a real record.
     */
    fun seen(): Boolean? = runCatching {
        if (!ensureSchema()) return null
        conn.query("SELECT welcome_seen_at_ms FROM ui_first_run WHERE id = 1") { it.getLong(0) }
            .firstOrNull() != null
    }.getOrNull()

    /**
     * Records it. One statement, no transaction.
     *
     * Called when the welcome is SHOWN, not when it is dismissed. That looks
     * like the wrong moment and is the right one: the student can leave this
     * screen by tapping a bottom-nav tab as well as by skipping or finishing
     * it, and a flag written only on the two deliberate exits would bring the
     * welcome back on the next launch for anyone who left by the third. The
     * cost is that a crash during the four panes loses the introduction, which
     * is a smaller failure than an onboarding screen that will not stay shut.
     */
    fun markSeen(nowMs: Long = System.currentTimeMillis()): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO ui_first_run (id, welcome_seen_at_ms) VALUES (1, ?)"
        ).use { st ->
            st.bindLong(1, nowMs)
            st.step()
        }
        true
    }.getOrDefault(false)
}
