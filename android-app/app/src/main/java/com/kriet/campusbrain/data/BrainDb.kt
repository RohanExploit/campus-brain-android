package com.kriet.campusbrain.data

import android.content.Context
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

const val TAG = "CampusBrain"

/**
 * Runs [sql], binding parameters (1-based, per androidx.sqlite) via [bind] and
 * mapping every row (columns are 0-based) via [map].
 *
 * The one place every retrieval class in this app touches SQLite, so a schema
 * change or a driver quirk has exactly one call site to fix.
 */
fun <T> SQLiteConnection.query(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {},
    map: (SQLiteStatement) -> T,
): List<T> {
    val out = ArrayList<T>()
    prepare(sql).use { st ->
        bind(st)
        while (st.step()) out.add(map(st))
    }
    return out
}

/**
 * Opens the on-device corpus bundle (see scripts/export_mobile_bundle.py for
 * the schema) via the bundled SQLite driver, never the platform one.
 *
 * Platform SQLite on the demo device (Android 16) has no FTS5 module --
 * measured, not assumed, see SelfTest -- so [androidx.sqlite.driver.bundled]
 * is what makes `chunks_fts MATCH` work at all.
 */
class BrainDb private constructor(
    val conn: SQLiteConnection,
    val path: String,
    val source: String,
) {
    /** Every meta row, e.g. tenant_id / chunk_count / document_count / built_at_utc. */
    val meta: Map<String, String> by lazy {
        conn.query("SELECT key, value FROM meta") { it.getText(0) to it.getText(1) }.toMap()
    }

    val embeddingDim: Int by lazy { meta["embedding_dim"]?.toIntOrNull() ?: DEFAULT_EMBEDDING_DIM }

    /** user_version 1 bundles predate the `documents` table; callers must degrade, not crash. */
    val hasDocumentsTable: Boolean by lazy { hasTable("documents") }

    fun hasTable(name: String): Boolean = conn.query(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        bind = { it.bindText(1, name) },
    ) { true }.isNotEmpty()

    companion object {
        private const val ASSET_NAME = "brain.db"
        private const val DEFAULT_EMBEDDING_DIM = 384

        /**
         * Opens the bundle at an exact file path. Pure I/O with no [Context],
         * so it is the seam a JVM test opens a synthetic bundle through --
         * everything above this point (locating the real file on a device) is
         * not something a unit test should need Robolectric for.
         */
        fun openPath(path: String, source: String): BrainDb =
            BrainDb(BundledSQLiteDriver().open(path), path, source)

        /**
         * Resolution order mirrors [com.kriet.campusbrain.embed.MiniLmEmbedder]:
         * an adb-pushed bundle in the external files dir wins, so the corpus can
         * be swapped without a rebuild; otherwise it is copied out of assets into
         * internal storage once, since sqlite needs a real file path, not an APK
         * asset stream.
         */
        fun open(context: Context): BrainDb {
            val external = context.getExternalFilesDir(null)?.let { File(it, ASSET_NAME) }
            val (file, source) = if (external != null && external.exists() && external.length() > 0L) {
                external to "external files dir"
            } else {
                val internal = File(context.filesDir, ASSET_NAME)
                if (!internal.exists() || internal.length() == 0L) {
                    context.assets.open(ASSET_NAME).use { input ->
                        internal.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                internal to "bundled assets"
            }
            return openPath(file.absolutePath, source)
        }
    }
}
