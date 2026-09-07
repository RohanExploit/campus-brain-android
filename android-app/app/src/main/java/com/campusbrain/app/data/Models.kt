package com.campusbrain.app.data

/** One of the backend's four routes (retrieval/router.py). */
enum class Route { FACT, LOCAL, GLOBAL, TABULAR }

/**
 * Where a chunk or a document came from. Three sources now share one
 * searchable corpus and a student has to be able to tell them apart.
 *
 *  - [BUNDLE] shipped inside the APK, in `brain.db`.
 *  - [INSTITUTION] published by a registrar and synced down into
 *    `user_corpus.db`. The college said it; this device did not choose it and
 *    cannot delete it.
 *  - [USER] a file this student imported themselves.
 *
 * The distinction that matters on an answer card is [INSTITUTION] versus
 * [USER]: "the registrar published this" and "you added this" are different
 * claims, and before sync existed there was nothing in `user_corpus.db` that
 * was not the second one. See [UserCorpusDb.isOwnChunk] for how the two are
 * told apart without a namespace tag.
 */
enum class Provenance { BUNDLE, INSTITUTION, USER }

data class RetrievedChunk(
    val id: Long,
    val docId: String,
    val section: String?,
    val content: String,
    val score: Double,
    /** Which arms found this chunk, and at what rank. Feeds the UI trace. */
    val ftsRank: Int? = null,
    val vecRank: Int? = null,
) {
    val foundByBoth: Boolean get() = ftsRank != null && vecRank != null

    /**
     * True when this chunk came out of a document **this student imported**.
     *
     * Derived from the id, not stored: [UserCorpusDb] allocates rowids from
     * 1,000,000,000 up precisely so the corpora can be fused into one ranked
     * list and still be told apart afterwards. Nothing has to remember to set
     * a flag, so nothing can forget to.
     *
     * Note this is [UserCorpusDb.isOwnChunk] and **not**
     * [UserCorpusDb.isUserChunk]. The two used to be the same predicate and
     * are not any more: `isUserChunk` answers "which database holds this
     * row", which is what `HybridSearch` needs to fetch it, while this answers
     * "whose document is this", which is what the citation under an answer is
     * claiming. Synced institution documents live in the same file as the
     * student's own and would be labelled as theirs by the old reading.
     */
    val isUserAdded: Boolean get() = UserCorpusDb.isOwnChunk(id)

    /** Bundle, institution or this student's own. See [Provenance]. */
    val provenance: Provenance get() = UserCorpusDb.provenanceOf(id)
}

/**
 * One citation under an answer.
 *
 * [isUserAdded] is the load-bearing field, and it is here rather than only on
 * the Documents list on purpose. A student reading an answer has to be able to
 * tell the registrar's circular from a file a friend sent them, and the answer
 * card is the only place that judgement is actually being made. The Documents
 * tab marks the import; the answer marks the claim.
 */
data class Source(
    val docId: String,
    val section: String?,
    val isUserAdded: Boolean = false,
)

/**
 * A finished answer plus everything needed to explain how it was reached.
 *
 * [trace] mirrors the backend's `metadata` keys (template, debug_sql,
 * tabular_fallback, local_mode, global_mode, linked_entities) so the phone and
 * the dashboard describe the same run in the same words.
 */
data class AnswerResult(
    val route: Route,
    /** The short answer. For TABULAR this is the full deterministic result. */
    val answer: String,
    /**
     * Source passages behind a toggle. Empty for TABULAR, whose answer is the
     * query result itself and has no passage to show.
     */
    val passages: List<Pair<String, String>> = emptyList(),
    val sources: List<Source> = emptyList(),
    val trace: List<Pair<String, String>> = emptyList(),
    val abstained: Boolean = false,
)

data class StudentRow(
    val rollNo: String,
    val name: String?,
    val sgpa: Double?,
    val estimatedSgpa: Double?,
    val totalMarks: Long?,
    val result: String,
    val isSupply: Boolean,
    val seatCancelled: Boolean,
)

data class SubjectRow(
    val subjectCode: String,
    val credit: Int,
    val grade: String?,
    val gradePoint: Double,
    val rawGradeString: String?,
)

data class DocumentSummary(
    val docId: String,
    val title: String,
    val category: String,
    val chunkCount: Int,
    val preview: String?,
    /**
     * Bundle, institution or this student's own.
     *
     * Replaces the `isUserAdded` Boolean this class used to carry as a stored
     * field. A Boolean could only ask one question, and there are now three
     * answers -- and worse, the flag was set by the CALLER (`.copy(isUserAdded
     * = true)` at two call sites), so a synced institution document listed
     * through either of them would have been announced as the student's own.
     */
    val provenance: Provenance = Provenance.BUNDLE,
) {
    /**
     * True only for a document this student imported.
     *
     * Derived, so it cannot disagree with [provenance]. Kept as a name because
     * the Documents list and the answer card both read it, and because
     * "is this mine" is genuinely the question they are asking -- an
     * institution document is not removable by the student and is not theirs.
     */
    val isUserAdded: Boolean get() = provenance == Provenance.USER
}
