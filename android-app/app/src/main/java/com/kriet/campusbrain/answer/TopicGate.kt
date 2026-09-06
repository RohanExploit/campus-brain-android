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
        if (namesCampusSubject(query)) return true
        val q = query.lowercase()
        val nonEducational = NON_EDUCATIONAL_TERMS.any { q.contains(it) }
        return !nonEducational
    }

    /**
     * Does the query actually SAY something about campus, as opposed to merely
     * not saying anything against it?
     *
     * The first line of [isEducational], separated out because the difference
     * between the two matters to one caller. [isEducational] answers true for
     * "what is the capital of France" -- nothing matched either list and the
     * documented bias resolves ambiguity toward answering. That is the right
     * default for deciding whether to try the cloud. It is the wrong test for
     * deciding whether a wrong answer would be a claim about this college, and
     * that is what this one is for.
     */
    fun namesCampusSubject(query: String): Boolean {
        val q = query.lowercase()
        return EDUCATIONAL_TERMS.any { q.contains(it) }
    }

    /**
     * Is [term] one of the domain words that identifies nothing in particular?
     *
     * Same list, read the other way round. [isEducational] asks whether a whole
     * query is about college at all; this asks whether ONE word carries any
     * information about which college question is being asked. "student",
     * "subject" and "exam" do not -- they are in most of the 493 chunks and in
     * most of the questions -- while "cheating" and "condonation" do.
     *
     * [AnswerCheck] needs that distinction because its topic-overlap floor
     * counts every content word as equal evidence, and two generic words are
     * enough to make an unrelated passage look like an answer. Reusing this
     * list rather than writing a second one is deliberate: a divergence between
     * "words the gate treats as campus vocabulary" and "words the answer check
     * treats as uninformative" would be invisible until it mis-answered.
     *
     * Matched by prefix in both directions so a stem catches its inflections
     * ("attendance" against "attend") and a plural catches its stem ("exams",
     * already reduced to "exam", against "exam"). Entries shorter than three
     * characters are skipped -- they would match half the vocabulary.
     */
    fun isDomainVocabulary(term: String): Boolean {
        val t = term.lowercase()
        return EDUCATIONAL_TERMS.asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 }
            .any { t.startsWith(it) || it.startsWith(t) }
    }
}
