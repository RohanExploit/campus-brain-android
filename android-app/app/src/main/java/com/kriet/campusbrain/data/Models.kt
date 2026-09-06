package com.kriet.campusbrain.data

/** One of the backend's four routes (retrieval/router.py). */
enum class Route { FACT, LOCAL, GLOBAL, TABULAR }

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
     * True when this chunk came out of the user's own imported documents
     * rather than the college's bundled corpus.
     *
     * Derived from the id, not stored: [UserCorpusDb] allocates its rowids
     * from 1,000,000,000 up precisely so the two corpora can be fused into one
     * ranked list and still be told apart afterwards. Nothing has to remember
     * to set a flag, so nothing can forget to.
     */
    val isUserAdded: Boolean get() = UserCorpusDb.isUserChunk(id)
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
    /** True for a document the user imported. See [Source.isUserAdded]. */
    val isUserAdded: Boolean = false,
)
