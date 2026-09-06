package com.campusbrain.app.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Where the usage aggregates live between runs.
 *
 * Aggregates, and only aggregates. There is no row here for "a question", and
 * that is a design decision rather than a schema that has not grown one yet:
 * a per-query table would be a log of what a named student asked the college,
 * on the college's own phone, and this product's whole claim is that no such
 * thing exists. What is stored is three counts per route, one count per
 * document, and -- if and only if an admin ticked the box -- a capped sample
 * of question text that never leaves the device.
 *
 * Like [com.campusbrain.app.data.auth.EntitlementStore] and
 * [com.campusbrain.app.data.auth.LicenseStore], it takes a raw
 * [SQLiteConnection] and never a `Context`, so all of it is JVM-testable, and
 * every write is a single statement so it can never hold an EXCLUSIVE lock
 * while an import waits.
 */
class AnalyticsStore(private val conn: SQLiteConnection) {

    fun ensureSchema(): Boolean = runCatching {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS analytics_route (" +
                "route TEXT PRIMARY KEY, queries INTEGER NOT NULL DEFAULT 0, " +
                "abstentions INTEGER NOT NULL DEFAULT 0)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS analytics_doc_hits (" +
                "doc_id TEXT PRIMARY KEY, hits INTEGER NOT NULL DEFAULT 0)"
        )
        // The admin's assumption, one row per route. Stored rather than
        // constant because it is the number the buyer argues with, and a
        // figure you cannot argue with is a figure you cannot trust.
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS analytics_minutes (" +
                "route TEXT PRIMARY KEY, minutes INTEGER NOT NULL)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS analytics_settings (" +
                "id INTEGER PRIMARY KEY CHECK (id = 1), keep_query_text INTEGER NOT NULL DEFAULT 0)"
        )
        // Only if the sample is ever switched on. Created up front anyway so
        // that turning the setting on is one write and not a schema change.
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS analytics_query_text (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL)"
        )
        true
    }.getOrDefault(false)

    /**
     * Adds a session's counts to the stored totals.
     *
     * UPSERT with `+=` rather than a read-modify-write, so two flushes racing
     * on the two connections this file already has cannot lose one of them:
     * the addition happens inside SQLite, under the same lock as the write.
     *
     * Returns false rather than throwing on any failure. The caller is a
     * lifecycle boundary and has nothing useful to do about it -- and losing a
     * session's route counts changes no behaviour anywhere in the app.
     */
    fun merge(delta: QueryLog.Snapshot): Boolean = runCatching {
        if (delta.total == 0 && delta.docHits.isEmpty() && delta.texts.isEmpty()) return true
        conn.prepare(
            "INSERT INTO analytics_route(route, queries, abstentions) VALUES (?, ?, ?) " +
                "ON CONFLICT(route) DO UPDATE SET queries = queries + excluded.queries, " +
                "abstentions = abstentions + excluded.abstentions"
        ).use { st ->
            Route.entries.forEach { r ->
                val q = delta.queries[r] ?: 0
                val a = delta.abstentions[r] ?: 0
                if (q == 0 && a == 0) return@forEach
                st.reset()
                st.bindText(1, r.name)
                st.bindLong(2, q.toLong())
                st.bindLong(3, a.toLong())
                st.step()
            }
        }
        conn.prepare(
            "INSERT INTO analytics_doc_hits(doc_id, hits) VALUES (?, ?) " +
                "ON CONFLICT(doc_id) DO UPDATE SET hits = hits + excluded.hits"
        ).use { st ->
            delta.docHits.forEach { (docId, hits) ->
                st.reset()
                st.bindText(1, docId)
                st.bindLong(2, hits.toLong())
                st.step()
            }
        }
        if (delta.texts.isNotEmpty()) {
            conn.prepare("INSERT INTO analytics_query_text(text) VALUES (?)").use { st ->
                delta.texts.forEach { t -> st.reset(); st.bindText(1, t); st.step() }
            }
        }
        true
    }.getOrDefault(false)

    /** The stored totals. An empty snapshot when anything is wrong. */
    fun load(): QueryLog.Snapshot = runCatching {
        val queries = LinkedHashMap<Route, Int>()
        val abstentions = LinkedHashMap<Route, Int>()
        conn.query("SELECT route, queries, abstentions FROM analytics_route") {
            Triple(it.getText(0), it.getLong(1).toInt(), it.getLong(2).toInt())
        }.forEach { (name, q, a) ->
            // A row whose route this build does not know is skipped, not
            // guessed at. Route names come from the app's own enum, so this
            // only happens after a downgrade, and inventing a bucket for it
            // would put a made-up bar on the histogram.
            val r = Route.entries.firstOrNull { it.name == name } ?: return@forEach
            queries[r] = q
            abstentions[r] = a
        }
        val hits = LinkedHashMap<String, Int>()
        conn.query("SELECT doc_id, hits FROM analytics_doc_hits") {
            it.getText(0) to it.getLong(1).toInt()
        }.forEach { (d, h) -> hits[d] = h }

        val texts = if (!keepQueryText()) emptyList() else conn.query(
            "SELECT text FROM analytics_query_text ORDER BY id DESC LIMIT ${QueryLog.MAX_TEXTS}"
        ) { it.getText(0) }

        QueryLog.Snapshot(queries, abstentions, hits, texts)
    }.getOrDefault(QueryLog.Snapshot(emptyMap(), emptyMap(), emptyMap()))

    /** The admin's per-route assumption, defaulted where unset. */
    fun minutes(): Map<Route, Int> = runCatching {
        val out = LinkedHashMap(DEFAULT_MINUTES)
        conn.query("SELECT route, minutes FROM analytics_minutes") {
            it.getText(0) to it.getLong(1).toInt()
        }.forEach { (name, m) ->
            Route.entries.firstOrNull { it.name == name }?.let { out[it] = m.coerceAtLeast(0) }
        }
        out.toMap()
    }.getOrDefault(DEFAULT_MINUTES)

    fun setMinutes(route: Route, minutes: Int): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO analytics_minutes(route, minutes) VALUES (?, ?)"
        ).use { st ->
            st.bindText(1, route.name)
            st.bindLong(2, minutes.coerceIn(0, MAX_MINUTES).toLong())
            st.step()
        }
        true
    }.getOrDefault(false)

    fun keepQueryText(): Boolean = runCatching {
        conn.query("SELECT keep_query_text FROM analytics_settings WHERE id = 1") {
            it.getLong(0)
        }.firstOrNull() == 1L
    }.getOrDefault(false)

    /**
     * The opt-in, and its off switch.
     *
     * Turning it OFF deletes the sample that was already collected. A setting
     * that stops adding to a list it leaves lying on disk is not consent
     * withdrawn, it is consent withdrawn for the future only, and that is not
     * what an admin unticking this box believes they are doing.
     */
    fun setKeepQueryText(on: Boolean): Boolean = runCatching {
        conn.prepare(
            "INSERT OR REPLACE INTO analytics_settings(id, keep_query_text) VALUES (1, ?)"
        ).use { st -> st.bindLong(1, if (on) 1L else 0L); st.step() }
        if (!on) conn.execSQL("DELETE FROM analytics_query_text")
        true
    }.getOrDefault(false)

    companion object {
        /**
         * The starting assumption, and it is a starting point rather than a
         * finding. Two minutes is roughly "a clerk reads the question and
         * points at the noticeboard"; the tabular route gets more because
         * looking a result up in a spreadsheet is the slower errand.
         *
         * Every one of these is meant to be overwritten by the institution
         * with a number they will defend. Shipping zeroes instead would make
         * the screen show a saving of nothing until someone found the field,
         * which reads as a broken feature rather than an honest one.
         */
        val DEFAULT_MINUTES: Map<Route, Int> = mapOf(
            Route.FACT to 2,
            Route.LOCAL to 3,
            Route.GLOBAL to 4,
            Route.TABULAR to 5,
        )

        /** A day per question is already absurd; past that it is a typo. */
        const val MAX_MINUTES = 480
    }
}
