package com.campusbrain.app.data

/**
 * On-device usage counters. No Android, no SQLite, no clock of its own.
 *
 * ## Why the counters are here and not at the chokepoint
 *
 * `QueryRouter.answer()` is the single place every question passes through,
 * and it is the obvious home for instrumentation. It is not used, for a reason
 * that is about locking rather than tidiness.
 *
 * `user_corpus.db` runs on the default rollback journal. A writer takes an
 * EXCLUSIVE lock, and [UserCorpusDb.write] holds one for a whole document --
 * up to fifty embeddings. The search path already reads that same file on a
 * shared connection. Putting a per-query INSERT inside the answer path would
 * add a THIRD writer, on the hot path, to a file that is already contended by
 * an import, in exchange for durability that a route histogram does not need.
 * A question that stalls because someone is adding a syllabus is a real defect;
 * losing a session's counts to a crash is a rounding error.
 *
 * So: counters in memory here, written down only at lifecycle boundaries
 * ([com.campusbrain.app.data.auth.Licensing.flushAnalytics], called from
 * `onStop` and when the analytics screen opens). The other half of the reason
 * is scope -- `retrieval/` belongs to someone else and this file does not need
 * it to. The `ui/` caller that already has the [AnswerResult] records into it.
 *
 * ## Why [record] cannot be handed a question
 *
 * There is no parameter on it that can carry one. Not a `String?` defaulted to
 * null, not an `Any?` bag: no string-shaped parameter at all, so no call site
 * anywhere can pass a student's question into the aggregate counters by
 * accident or by a well-meaning later edit. A structural test asserts that.
 *
 * Raw text has its own opt-in door, [recordText], which is inert unless
 * [keepQueryText] was explicitly turned on by an admin on this device. It is
 * local-only in the strongest available sense: nothing in this class, in
 * [AnalyticsStore], or on the export path opens a socket, and the export is
 * the Android share sheet handing the user their own file.
 */
class QueryLog {

    /**
     * Off by default, and the default is the thing that matters. A student's
     * question can name a person, a medical certificate or a fee dispute, and
     * the safe state for that is "was never recorded", not "was recorded and
     * we chose not to look".
     */
    @Volatile var keepQueryText: Boolean = false

    private val queries = LinkedHashMap<Route, Int>()
    private val abstentions = LinkedHashMap<Route, Int>()
    private val docHits = LinkedHashMap<String, Int>()
    private val texts = ArrayList<String>()

    /**
     * One answered question.
     *
     * [citedDocIds] is what the answer actually cited, which is what makes
     * "documents never retrieved" answerable -- the interesting number for an
     * institution is not which documents were read but which never were, and
     * that can only be computed by subtracting this set from the catalogue.
     *
     * Deliberately takes no question text and no student identifier. See the
     * class header.
     */
    @Synchronized
    fun record(route: Route, abstained: Boolean, citedDocIds: Collection<String>) {
        queries[route] = (queries[route] ?: 0) + 1
        if (abstained) abstentions[route] = (abstentions[route] ?: 0) + 1
        // Distinct, so one answer citing the same document three times is one
        // hit. The figure is "was this document ever reached", not "how many
        // passages came out of it".
        citedDocIds.toSet().forEach { docHits[it] = (docHits[it] ?: 0) + 1 }
    }

    /**
     * The opt-in door, and a no-op unless [keepQueryText] is on.
     *
     * Capped at [MAX_TEXTS]. An unbounded list would be a transcript of a
     * term's questions sitting in a process's heap, and the admin who ticked
     * this box was agreeing to a sample they could read, not to that.
     */
    @Synchronized
    fun recordText(text: String) {
        if (!keepQueryText) return
        val t = text.trim()
        if (t.isEmpty()) return
        if (texts.size >= MAX_TEXTS) texts.removeAt(0)
        texts.add(t)
    }

    /** A copy of the counters, leaving them in place. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(
        queries = LinkedHashMap(queries),
        abstentions = LinkedHashMap(abstentions),
        docHits = LinkedHashMap(docHits),
        texts = ArrayList(texts),
    )

    /**
     * A copy of the counters, resetting them.
     *
     * Used by the flush, so a merge that succeeds does not add the same
     * session twice on the next one. A merge that FAILS loses the session --
     * accepted, for the reason in the class header: this is aggregate usage,
     * not the student's data, and no decision anywhere depends on it.
     */
    @Synchronized
    fun drain(): Snapshot = snapshot().also { clear() }

    @Synchronized
    fun clear() {
        queries.clear()
        abstentions.clear()
        docHits.clear()
        texts.clear()
    }

    /**
     * The numbers, and the arithmetic over them.
     *
     * Everything on this type is either a count that was measured or a
     * quotient of two counts that were measured. Nothing here is an estimate;
     * estimates live in [Estimates] and are labelled as such wherever they are
     * shown, for the same reason `AnswerCheck` and `PremiseCheck` refuse to
     * present an unsupported claim as a quoted one.
     */
    data class Snapshot(
        val queries: Map<Route, Int>,
        val abstentions: Map<Route, Int>,
        val docHits: Map<String, Int>,
        val texts: List<String> = emptyList(),
    ) {
        val total: Int get() = queries.values.sum()
        val abstained: Int get() = abstentions.values.sum()

        /** 0.0 for an empty log. Not NaN, and not "100% answered": a device
         * that has been asked nothing has not answered anything either. */
        val abstentionRate: Double get() = if (total == 0) 0.0 else abstained.toDouble() / total

        fun count(route: Route): Int = queries[route] ?: 0

        /**
         * The histogram, every route present even at zero.
         *
         * A route missing from the chart reads as a route that does not exist;
         * a route at zero reads as a capability nobody used, which is the
         * finding an institution is actually paying to see.
         */
        fun histogram(): List<Pair<Route, Int>> = Route.entries.map { it to count(it) }

        /** Documents in [catalogue] that nothing has ever cited. Sorted, so
         * the screen and the export agree on the order. */
        fun neverRetrieved(catalogue: Collection<String>): List<String> =
            catalogue.filter { (docHits[it] ?: 0) == 0 }.sorted()

        /** Adds another snapshot's counts. Used by the store's merge. */
        operator fun plus(other: Snapshot): Snapshot = Snapshot(
            queries = merge(queries, other.queries),
            abstentions = merge(abstentions, other.abstentions),
            docHits = merge(docHits, other.docHits),
            texts = texts + other.texts,
        )

        private fun <K> merge(a: Map<K, Int>, b: Map<K, Int>): Map<K, Int> {
            val out = LinkedHashMap(a)
            b.forEach { (k, v) -> out[k] = (out[k] ?: 0) + v }
            return out
        }
    }

    companion object {
        /** Enough for an admin to read a sample; far short of a transcript. */
        const val MAX_TEXTS = 200
    }
}

/**
 * Staff hours saved, computed in the only honest way available to a device
 * that cannot observe a counter clerk.
 *
 * The app measures one thing: how many questions it answered, per route. It
 * does not and cannot measure how long a human would have taken to answer the
 * same question. So the second number is entered by the admin, kept editable,
 * shown next to the result, and named as an assumption every single time.
 *
 * This is the same discipline `AnswerCheck` and `PremiseCheck` enforce on the
 * answer path -- never present an assumption as a measurement -- applied to
 * the one figure in this product with the strongest pull towards being
 * invented. A deck that says "saved 340 staff hours" with no visible
 * assumption is a claim the buyer cannot check, and the moment they work out
 * that it was a constant multiplied by a count, every measured figure beside
 * it is suspect too.
 */
object Estimates {

    /** One route's contribution, with its inputs kept attached. */
    data class Line(
        val route: Route,
        /** Measured. */
        val queries: Int,
        /** Assumed, admin-entered. */
        val minutesEach: Int,
    ) {
        val minutes: Int get() = queries * minutesEach
    }

    data class Estimate(
        val lines: List<Line>,
        val totalMinutes: Int,
    ) {
        val hours: Double get() = totalMinutes / 60.0
    }

    /**
     * `measured count x editable assumption`, per route, and the sum.
     *
     * The result carries both inputs on every line precisely so that no caller
     * can render the total without having the assumption in hand. There is no
     * function on this object that returns a bare number of hours.
     */
    fun of(snapshot: QueryLog.Snapshot, minutesPerRoute: Map<Route, Int>): Estimate {
        val lines = Route.entries.map { route ->
            Line(
                route = route,
                queries = snapshot.count(route),
                minutesEach = (minutesPerRoute[route] ?: 0).coerceAtLeast(0),
            )
        }
        return Estimate(lines, lines.sumOf { it.minutes })
    }
}
