package com.kriet.campusbrain.data

/** The four retrievers the router dispatches to. Order carries no meaning. */
enum class Route { TABULAR, FACT, LOCAL, GLOBAL }

/** One provenance entry: which document an answer leaned on, and where in it. */
data class Source(val docId: String, val section: String?)

/**
 * One row out of `chunks`, optionally carrying rank/score bookkeeping from the
 * hybrid search fusion.
 *
 * [ftsRank] and [vecRank] are null until a search fills them in; [foundByBoth]
 * is what [com.kriet.campusbrain.retrieval.HybridSearch] uses to report how
 * often the two arms agree.
 */
data class RetrievedChunk(
    val id: Long,
    val docId: String,
    val section: String?,
    val content: String,
    val score: Double,
    val ftsRank: Int? = null,
    val vecRank: Int? = null,
) {
    val foundByBoth: Boolean get() = ftsRank != null && vecRank != null
}

/**
 * One routed, answered, cited question.
 *
 * [passages] and [sources] are empty for TABULAR answers -- they come from
 * SQL, not from a document -- which is a legitimate state, not a bug; see
 * MessageAdapter and AnswerCard-equivalent rendering, which hide those
 * sections entirely rather than showing an empty header.
 */
data class AnswerResult(
    val route: Route,
    val answer: String,
    val passages: List<Pair<String, String>> = emptyList(),
    val sources: List<Source> = emptyList(),
    val trace: List<Pair<String, String>> = emptyList(),
    val abstained: Boolean = false,
)

/** One row of `students`. `result` is NOT NULL in the bundle; see the export script. */
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

/** One row of `student_subjects`. `gradePoint` is already `base_point * credit`. */
data class SubjectRow(
    val subjectCode: String,
    val credit: Int,
    val grade: String?,
    val gradePoint: Double,
    val rawGradeString: String?,
)

/** One row of `documents`, or a chunk-derived stand-in on a bundle without that table. */
data class DocumentSummary(
    val docId: String,
    val title: String,
    val category: String,
    val chunkCount: Int,
    val preview: String?,
)
