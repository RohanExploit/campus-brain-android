package com.kriet.campusbrain.ui.welcome

import android.content.Context
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kriet.campusbrain.data.UserCorpusDb
import com.kriet.campusbrain.data.auth.EntitlementStore
import java.io.File

/**
 * The four lines of `Context`-to-`SQLiteConnection` plumbing that
 * [FirstRunStore] deliberately does not contain.
 *
 * Everything decidable lives next door in [FirstRun] and [FirstRunStore],
 * which take a raw connection and a nullable Boolean and are therefore
 * testable without a device. What is left here is opening a file, and it is
 * kept in its own object so that the untestable part is small enough to read
 * in one go.
 *
 * The connection is opened and closed around each call rather than held. This
 * runs twice in an app's lifetime -- one read at launch, one write the first
 * time the welcome appears -- and a long-lived third connection to
 * `user_corpus.db` would add a permanent lock-contention surface with an
 * import, for a table holding one integer. [Identity] keeps its connection
 * because it re-reads on every refresh; this does not.
 *
 * Both calls must run off the main thread. Neither is fast enough to promise
 * otherwise on a phone whose importer currently holds the write lock -- the
 * busy timeout below is five seconds.
 */
object FirstRunGate {

    /**
     * True only for a device that has definitely never seen the welcome.
     *
     * Every failure -- the file will not open, the driver will not load, the
     * schema will not create -- arrives here as null from [FirstRunStore.seen]
     * and is turned into `false` by [FirstRun.shouldShow]. A phone that cannot
     * remember the welcome was shown does not get shown it again.
     */
    fun shouldShow(context: Context): Boolean =
        FirstRun.shouldShow(withStore(context) { it.seen() })

    fun markSeen(context: Context) {
        withStore(context) { it.markSeen() }
    }

    private fun <T> withStore(context: Context, block: (FirstRunStore) -> T): T? = runCatching {
        val file = File(context.filesDir, UserCorpusDb.FILE_NAME)
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        // try/finally rather than `use`: Identity closes its connection the
        // same way, and this does not depend on SQLiteConnection's closeable
        // shape staying what it is across an androidx.sqlite bump.
        try {
            // Same reasoning as Identity.openStore: a document import wraps up
            // to fifty embeddings in one BEGIN IMMEDIATE on the default
            // rollback journal, so a read landing inside that window would
            // fail with SQLITE_BUSY instead of waiting a moment.
            conn.execSQL(EntitlementStore.PRAGMA_BUSY_TIMEOUT)
            block(FirstRunStore(conn))
        } finally {
            runCatching { conn.close() }
        }
    }.getOrNull()
}
