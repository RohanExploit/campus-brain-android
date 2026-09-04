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
}

data class Source(val docId: String, val section: String?)

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
)
