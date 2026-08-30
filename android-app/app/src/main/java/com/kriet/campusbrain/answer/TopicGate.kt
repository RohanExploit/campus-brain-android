package com.kriet.campusbrain.answer

/**
 * Decides whether a question is in scope for a "college and campus life"
 * answer, before the router is allowed to fall back to the cloud.
 *
 * The bias here is deliberate and asymmetric, the mirror image of the one in
 * AnswerComposer: there, a wrong confident answer is the expensive failure,
 * so the bar for staying silent is low. Here, the expensive failure runs the
 * other way. A false "educational" costs one useless general-knowledge
 * answer from Groq about the weather -- mildly embarrassing, cheap to shrug
 * off. A false "not educational" refuses a legitimate student mid-demo with
 * "that's not what I do", which is the one failure mode this whole change
 * exists to eliminate. So the vocabulary below is kept wide, matched by
 * loose substring/stem rather than exact word boundaries, and an ambiguous
 * or unmatched query defaults to educational rather than to abstention.
 */
object TopicGate {

    // Loose stems on purpose: "librar" catches library/librarian, "attend"
    // catches attendance/attending, "reval" catches revaluation, etc. A
    // stray extra match (e.g. "intern" inside "international") only ever
    // pushes a query toward the cheap failure mode above.
    private val EDUCATIONAL_TERMS = listOf(
        "student", "marks", "mark ", "grade", "sgpa", "cgpa", "exam", "attend",
        "fee", "scholarship", "hostel", "librar", "placement", "intern",
        "admission", "syllabus", "subject", "faculty", "professor", "timetable",
        "time table", "holiday", "certificate", "bonafide", "migration",
        "transcript", "backlog", "reval", "convocation", "campus", "college",
        "university", "course", "semester", "sem ", "project", "lab",
        "department", "branch", "credit", "result", "roll no", "roll number",
        "principal", "hod", "class", "lecture", "assignment", "viva",
        "practical", "id card", "bus pass", "ragging", "ncc", "nss", "notice",
        "circular", "dean", "deadline", "portal", "registration",
    )

    // A small, explicit non-educational list. It only wins when nothing in
    // EDUCATIONAL_TERMS matched -- see isEducational below.
    private val NON_EDUCATIONAL_TERMS = listOf(
        "recipe", "cook", "biryani", "cricket score", "cricket match",
        "football score", "football match", "election", "politician",
        "politics", "movie", "celebrity", "actor", "actress", "vacation",
        "flight ticket", "hotel booking", "symptom", "medicine dosage",
        "diagnos", "relationship advice", "dating", "weather", "who won the",
        "sports score", "video game", "song lyrics",
    )

    /**
     * True unless the query clearly matches only the non-educational list
     * and nothing in the educational one. See the class doc for why the
     * asymmetry is intentional.
     */
    fun isEducational(query: String): Boolean {
        val q = query.lowercase()
        if (EDUCATIONAL_TERMS.any { q.contains(it) }) return true
        val nonEducational = NON_EDUCATIONAL_TERMS.any { q.contains(it) }
        return !nonEducational
    }
}
