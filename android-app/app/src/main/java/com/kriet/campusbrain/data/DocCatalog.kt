package com.kriet.campusbrain.data

/**
 * Categories and titles for the Documents tab, derived from `doc_id`.
 *
 * The shipped bundle has no `documents` table (user_version = 1), so
 * [DocsRepository] synthesises the list from `SELECT DISTINCT doc_id FROM
 * chunks` and files all 58 under "Other". The result is that the first row a
 * student sees in a campus app is "1 RAG MicroSim A Hybrid Retrieval Augmented
 * Generation And Market Micro Simulation Framework For High Frequency Trading
 * Analysis". That is a data-layer problem and it is fixed here rather than by
 * re-exporting the bundle, because the bundle is what is already on the
 * demo device.
 *
 * The `doc_id`s carry real structure and it costs nothing to read it:
 *
 *   01_academic_calendar_2026_27.md      -> Calendar and Timetables
 *   24_attendance_policy.md              -> Attendance
 *   svc_08_placement_policy.md           -> Placements
 *   svc_17_library_services_and_rules.md -> Library
 *   RAG-MicroSim Framework.md            -> Research Papers
 *
 * Matching is on keywords in the id, not on the numeric prefix. The numbers
 * are export ordering and would silently mis-file everything the day the
 * catalogue is regenerated in a different order; the words are what the
 * document is about.
 */
object DocCatalog {

    /**
     * Display order. Campus life first, research last -- a student opening the
     * Documents tab is looking for the fee deadline, not for a paper on
     * high-frequency trading. [ORDER] is the sort key; the strings are also
     * what the section headers read.
     */
    val ORDER = listOf(
        // A document the student added themselves is the one they most
        // recently decided mattered, and this group is empty for everybody
        // who has not imported anything, so it costs nothing when unused.
        UserCorpusDb.ADDED_CATEGORY,
        "Attendance",
        "Examinations",
        "Fees and Scholarships",
        "Placements and Training",
        "Calendar and Timetables",
        "Notices",
        "Events",
        "Hostel and Transport",
        "Library",
        "Student Handbook",
        "Courses and Departments",
        "Research Papers",
        "Other",
    )

    private val RULES: List<Pair<String, List<String>>> = listOf(
        // Order matters within this list, not just across it. "attendance"
        // must be tested before "notice", because 25_attendance_defaulter_
        // procedure is an attendance document that happens to be published as
        // a notice, and filing it under Notices buries it.
        "Attendance" to listOf("attendance", "condonation", "defaulter"),
        "Examinations" to listOf(
            "exam", "result", "revaluation", "reval", "marksheet", "hall_ticket", "grade",
        ),
        "Fees and Scholarships" to listOf(
            "fee", "fees", "scholarship", "freeship", "ebc", "post-matric", "post_matric",
            "merit_grant", "eligibility_matrix",
        ),
        "Placements and Training" to listOf(
            "placement", "training", "internship", "incubation", "recruit", "drive",
        ),
        "Calendar and Timetables" to listOf("calendar", "timetable", "time_table", "holiday", "schedule"),
        // "sports"/"cultural" sit here rather than earlier so that
        // svc_06_scholarship_procedure_sports_and_cultural_excellence_...
        // is filed by what it is (a scholarship) and svc_07_sports_cultural_
        // benefits by what it is (an event benefit). Tested against all 58
        // bundled ids; both land correctly.
        "Events" to listOf(
            "event", "fest", "meet", "camp", "lecture", "convocation", "visit",
            "sports", "cultural",
        ),
        "Hostel and Transport" to listOf("hostel", "bus", "transport", "mess"),
        "Library" to listOf("library", "librar"),
        "Student Handbook" to listOf("handbook", "dress_code", "ragging", "grievance", "anti_ragging"),
        "Courses and Departments" to listOf(
            "subject", "syllabus", "curriculum", "dept", "department", "course", "pattern",
        ),
        "Notices" to listOf("notice", "circular", "registration", "id_card", "bonafide", "deadline"),
        "Research Papers" to listOf(
            "rag-microsim", "rag_microsim", "ragmicrosim", "conference", "brochure",
            "icetis", "sample_paper", "artificial-intelligence", "artificial_intelligence",
            "framework", "analysis", "paper_template",
        ),
    )

    /**
     * The category for [docId].
     *
     * "Other" is the honest answer for the handful of files whose names say
     * nothing -- `DOC-20260212-WA0018.md` is a WhatsApp export and there is no
     * way to know what is in it from the name. Guessing a category for those
     * would put them somewhere a student would then trust.
     */
    fun categoryOf(docId: String): String {
        val key = docId.lowercase()
        // The svc_ prefix is a service document by construction, so a bare
        // "svc_09_placement_drive_..." is already well described by the words
        // after it; no special case is needed beyond reading them.
        for ((category, keywords) in RULES) {
            if (keywords.any { key.contains(it) }) return category
        }
        return "Other"
    }

    /** Sort key: category position, then title. Unknown categories sink. */
    fun orderOf(category: String): Int =
        ORDER.indexOf(category).let { if (it < 0) ORDER.size else it }

    private val EXPORT_PREFIX = Regex("^(svc_)?\\d+[_\\-]")
    private val TRAILING_COPY = Regex("\\s*\\(\\d+\\)\\s*$")

    /**
     * A readable title.
     *
     * The old derivation was filename-to-title-case, which produced "Ai Free
     * RAG MicroSim A Hybrid Retrieval Aug" -- a truncated filename asked to be
     * a title. Two things fix most of it: an explicit acronym list, so "AI"
     * and "NSS" stop being "Ai" and "Nss"; and a length cap that cuts at a
     * word boundary and elides, so a 120-character filename becomes a title
     * rather than a paragraph.
     *
     * A document's own first heading is a better title still, and
     * [DocsRepository] passes one in when the corpus has one. This is the
     * fallback for when it does not.
     */
    fun titleFor(docId: String): String {
        val stem = docId.substringAfterLast('/').substringBeforeLast('.')
        val cleaned = TRAILING_COPY.replace(
            EXPORT_PREFIX.replace(stem, "").replace('_', ' ').replace('-', ' ').trim(), ""
        )
        if (cleaned.isEmpty()) return stem
        val words = cleaned.split(' ').filter { it.isNotBlank() }.map { word ->
            val bare = word.trim('.', ',')
            when {
                bare.uppercase() in ACRONYMS -> bare.uppercase()
                // Already mixed-case in the filename ("RAG-MicroSim",
                // "DBATU") -- the author capitalised it deliberately and
                // title-casing would destroy that.
                bare.any { it.isUpperCase() } && bare.any { it.isLowerCase() } -> bare
                bare == bare.uppercase() && bare.length in 2..6 -> bare
                else -> bare.lowercase().replaceFirstChar { it.uppercase() }
            }
        }
        return elide(words.joinToString(" "))
    }

    /** Cut at a word boundary rather than mid-word, and say so with an ellipsis. */
    private fun elide(title: String, max: Int = 58): String {
        if (title.length <= max) return title
        val cut = title.take(max).substringBeforeLast(' ')
        return (if (cut.length < max / 2) title.take(max) else cut).trimEnd(',', '.', ':') + "…"
    }

    private val ACRONYMS = setOf(
        "AI", "ML", "NSS", "NCC", "ID", "HOD", "SGPA", "CGPA", "GPA", "RAG", "LLM",
        "CSE", "EXTC", "IT", "AIML", "MBA", "MCA", "BE", "ME", "PHD", "UG", "PG",
        "DBATU", "AICTE", "UGC", "KRIET", "TPO", "EBC", "SC", "ST", "OBC", "HR",
    )
}
