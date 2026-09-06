package com.campusbrain.app.data

/**
 * What a citation should say.
 *
 * Observed on hardware: an answer about scholarships cited its source as
 * "Mrs. Deepali Ghorpade". That is the `section` value of the chunk it came
 * from, and in this corpus a section is whatever markdown heading preceded the
 * text -- which, at the foot of every circular, is the signatory's name. A
 * citation naming a person tells the student nothing they can go and check,
 * and reads as though the app is quoting somebody's opinion.
 *
 * The Documents tab already solved the naming half of this: a document's own
 * "Subject:" line is its title, and for the college's 43 circulars and
 * policies it has one. This reuses that derivation for provenance, so the two
 * surfaces call the same document by the same name -- which matters, because
 * the student's next move after reading a citation is to look for that title
 * in the Documents tab.
 *
 * Of the 191 distinct section values in the shipped bundle, 27 are a person's
 * name under an honorific and one is the letterhead, repeated across every
 * document that has one. Those are the ones dropped.
 */
class DocTitles(private val db: BrainDb) {

    /**
     * Built once, lazily. 58 documents and one indexed lookup each; the
     * Documents tab runs the identical query and measures as instant. Failure
     * yields an empty map rather than an exception: a bad citation label is a
     * cosmetic problem and must never be able to take an answer down with it.
     */
    private val titles: Map<String, String> by lazy {
        runCatching {
            db.conn.query(
                "SELECT c.doc_id, " +
                    "(SELECT s.section FROM chunks s WHERE s.doc_id = c.doc_id " +
                    " AND s.section LIKE 'Subject:%' ORDER BY s.id LIMIT 1) " +
                    "FROM chunks c GROUP BY c.doc_id"
            ) { it.getText(0) to (if (it.isNull(1)) null else it.getText(1)) }
                .associate { (docId, subject) ->
                    docId to (subjectTitle(subject) ?: DocCatalog.titleFor(docId))
                }
        }.getOrDefault(emptyMap())
    }

    /** The document's title, falling back to the filename derivation. */
    fun titleOf(docId: String): String = titles[docId] ?: DocCatalog.titleFor(docId)

    /** The string a citation should show for this chunk. */
    fun label(docId: String, section: String?): String =
        citation(titleOf(docId), section)

    companion object {

        /**
         * "Subject: Attendance Policy, Academic Year 2026-27" -> the part
         * after the colon. Same bounds as [DocsRepository]'s copy: too short
         * to be a title, or long enough to be a paragraph, and the filename is
         * the safer choice.
         */
        fun subjectTitle(section: String?): String? {
            if (section == null || !section.startsWith("Subject:", ignoreCase = true)) return null
            val t = section.substring("Subject:".length).trim()
            return if (t.length in 8..80) t else null
        }

        /** "Mrs. Deepali Ghorpade", "Prof. (Dr.) Kishor V. Otari". */
        private val HONORIFIC = Regex(
            """^\s*(?:mr|mrs|ms|dr|prof|shri|smt|sri|adv)\b""",
            RegexOption.IGNORE_CASE
        )

        /**
         * The letterhead. It heads a chunk in nearly every college document,
         * so citing it identifies nothing -- the reason [DocsRepository]
         * rejected first-heading titles and went to "Subject:" instead.
         */
        private const val LETTERHEAD = "konkan ratna institute"

        /**
         * The document title, plus the section only when the section adds
         * something. Never emits " > ": the answer UI renders a citation with
         * `substringAfterLast(" > ")`, which on a raw section value throws
         * away the document and keeps the leaf. Feeding it a string with no
         * separator makes that call a no-op and leaves the whole label
         * visible.
         */
        fun citation(title: String, section: String?): String {
            val leaf = section?.substringAfterLast(" > ")?.trim().orEmpty()
            val useful = leaf.isNotEmpty() &&
                leaf.length > 3 &&
                !HONORIFIC.containsMatchIn(leaf) &&
                !leaf.lowercase().contains(LETTERHEAD) &&
                !leaf.startsWith("Subject:", ignoreCase = true) &&
                !leaf.equals(title, ignoreCase = true) &&
                !title.contains(leaf, ignoreCase = true)
            return if (useful) "$title — $leaf" else title
        }
    }
}
