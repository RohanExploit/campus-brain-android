package com.campusbrain.app.data

import android.content.Context
import android.util.Log
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File

const val TAG = "CampusBrain"

/**
 * Thrown rather than opening an empty database.
 *
 * Serving zero results looks like "the corpus has nothing on that", which is a
 * lie the user cannot distinguish from a real answer. A missing bundle has to
 * be loud.
 */
class BrainDbMissingException(message: String) : Exception(message)

/**
 * The offline corpus: chunks + FTS index + embeddings + graph edges + student
 * tables, exported by scripts/export_mobile_bundle.py.
 *
 * Opened through [BundledSQLiteDriver], NOT android.database.sqlite. Measured on
 * the demo device (vivo I2501, Android 16, platform SQLite 3.44.3): a query
 * against chunks_fts fails with "no such module: fts5". Android's AOSP build
 * enables FTS3/FTS4 and not FTS5 -- the same reason Room ships @Fts3 and @Fts4
 * annotations and no @Fts5. The bundled driver compiles SQLite into the APK with
 * FTS5 on, so behaviour matches the SQLite 3.35.5 that wrote the file and does
 * not vary by handset.
 */
class BrainDb private constructor(
    val conn: SQLiteConnection,
    val path: String,
    val source: String,
) {
    val meta: Map<String, String> by lazy {
        buildMap {
            conn.prepare("SELECT key, value FROM meta").use { st ->
                while (st.step()) put(st.getText(0), st.getText(1))
            }
        }
    }

    val embeddingDim: Int
        get() = meta["embedding_dim"]?.toIntOrNull() ?: 384

    /** True when the bundle carries the documents table (user_version >= 2). */
    val hasDocumentsTable: Boolean by lazy { hasTable("documents") }

    fun hasTable(name: String): Boolean =
        conn.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { st ->
            st.bindText(1, name)
            st.step()
        }

    fun close() = conn.close()

    companion object {
        private const val ASSET_NAME = "brain.db"
        private const val STAMP_NAME = "brain.db.stamp"

        /**
         * Resolution order, mirroring the Flutter app's:
         *
         *  1. the app's external files dir -- the `adb push` target, checked
         *     FIRST so a rebuilt corpus can be swapped in at a venue without
         *     rebuilding the APK;
         *  2. internal storage, where the bundled asset gets copied;
         *  3. the bundled asset itself, copied out on first run.
         *
         * Re-copies when the asset's built_at_utc differs from the stamp, so a
         * newly installed APK is never shadowed by an older extracted copy.
         */
        fun open(context: Context): BrainDb {
            val external = context.getExternalFilesDir(null)?.let { File(it, ASSET_NAME) }
            if (external != null && external.exists() && external.length() > 0) {
                return openAt(external.absolutePath, "external (adb push)")
            }

            val internal = File(context.filesDir, ASSET_NAME)
            val assetStamp = readAssetStamp(context)
            val stampFile = File(context.filesDir, STAMP_NAME)
            val staleCopy = internal.exists() &&
                assetStamp != null &&
                (!stampFile.exists() || stampFile.readText() != assetStamp)

            if (!internal.exists() || internal.length() == 0L || staleCopy) {
                if (!copyAsset(context, internal)) {
                    throw BrainDbMissingException(
                        "No corpus on this device.\n\n" +
                            "Push one with:\n" +
                            "adb push brain.db /sdcard/Android/data/" +
                            context.packageName + "/files/brain.db"
                    )
                }
                assetStamp?.let { stampFile.writeText(it) }
            }
            return openAt(internal.absolutePath, if (staleCopy) "asset (refreshed)" else "internal")
        }

        private fun openAt(path: String, source: String): BrainDb {
            val conn = BundledSQLiteDriver().open(path)
            // Read-only at the connection level: nothing in this app should ever
            // write to the bundle, and a stray UPDATE would desync it from the
            // catalog that produced it.
            conn.execSQL("PRAGMA query_only = ON")
            Log.i(TAG, "opened brain.db from $source: $path")
            return BrainDb(conn, path, source)
        }

        private fun copyAsset(context: Context, dest: File): Boolean = try {
            context.assets.open(ASSET_NAME).use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "no bundled $ASSET_NAME asset: ${e.javaClass.simpleName}")
            false
        }

        /**
         * built_at_utc read straight out of the asset without extracting it, so
         * the staleness check costs no disk. Returns null when there is no
         * bundled asset at all.
         */
        private fun readAssetStamp(context: Context): String? = try {
            val tmp = File.createTempFile("stampprobe", ".db", context.cacheDir)
            context.assets.open(ASSET_NAME).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val c = BundledSQLiteDriver().open(tmp.absolutePath)
            val v = c.prepare("SELECT value FROM meta WHERE key='built_at_utc'").use { st ->
                if (st.step()) st.getText(0) else null
            }
            c.close()
            tmp.delete()
            v
        } catch (e: Exception) {
            null
        }
    }
}

/** Run a query and map every row, so no call site writes a step() loop. */
inline fun <T> SQLiteConnection.query(
    sql: String,
    bind: (SQLiteStatement) -> Unit = {},
    map: (SQLiteStatement) -> T,
): List<T> = prepare(sql).use { st ->
    bind(st)
    val out = ArrayList<T>()
    while (st.step()) out.add(map(st))
    out
}
