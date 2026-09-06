package com.kriet.campusbrain.retrieval

/**
 * Splits a question that is really two questions.
 *
 * Measured, both halves individually answerable, both answered as one:
 *
 *   "how many students failed and what is the pass percentage"
 *       -> "Pass percentage: 90.5%."         (the 35 never appeared)
 *   "what is the minimum attendance and what happens if I miss it"
 *       -> the 75% rule                      (the consequence never appeared)
 *
 * The hard part is not the splitting, it is knowing when NOT to. These two
 * look identical to a naive split on "and" and must be treated as opposites:
 *
 *   "how many students failed AND what is the pass percentage"   two questions
 *   "how many students failed more than one subject AND have SGPA below 6"
 *                                                                one question
 *
 * The second is a conjunction of CONSTRAINTS -- answering its halves
 * separately would produce two numbers, neither of them the intersection the
 * student asked for, which is the same class of failure the constraint guard
 * in [SqlTemplates] exists to stop. The discriminator is whether each half is
 * itself interrogative. "have SGPA below 6" is not a question; "what is the
 * pass percentage" is. That single test separates every case in the battery.
 *
 * Conservative by construction: when in any doubt it returns the query
 * unsplit, and the caller falls back to the whole-query path, which is exactly
 * today's behaviour.
 */
object CompoundQuestion {

    private val SPLIT = Regex("""\s*[;?]\s+|\s*,?\s+and\s+(?=\S)""", RegexOption.IGNORE_CASE)

    /**
     * A half that stands on its own as a question. Anchored at the start:
     * "and what happens if I miss it" is a question, "and have SGPA below 6"
     * is a constraint, and the difference is entirely in the first word.
     */
    private val INTERROGATIVE = Regex(
        """^(?:how|what|which|who|whom|whose|when|where|why|is|are|was|were|do|does|did""" +
            """|can|could|should|will|would|am|list|show|tell|give|name)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * A half whose subject is only a pronoun has lost its referent in the
     * split -- "what happens if I miss IT". [carryOver] repairs those and only
     * those; rewriting a half that names its own subject would change what
     * retrieval sees for no reason.
     */
    private val DANGLING_PRONOUN = Regex(
        """\b(?:it|that|this|them|they|these|those|one)\b""",
        RegexOption.IGNORE_CASE
    )

    /** At most this many halves. Beyond three it is prose, not a question. */
    private const val MAX_PARTS = 3

    /**
     * The independent questions in [query], or a single-element list holding
     * [query] unchanged when it is not compound.
     */
    fun split(query: String): List<String> {
        val trimmed = query.trim().trimEnd('?', '.', '!').trim()
        val parts = SPLIT.split(trimmed)
            .map { it.trim().trim(',').trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2 || parts.size > MAX_PARTS) return listOf(query)
        // Every half has to be a question in its own right, and long enough
        // that "and what" or "and how" alone cannot trigger a split.
        val ok = parts.all { p ->
            INTERROGATIVE.containsMatchIn(p) && p.split(Regex("\\s+")).size >= 3
        }
        return if (ok) parts else listOf(query)
    }

    /**
     * [part] with the first half's subject words appended, when the part has a
     * pronoun and no subject of its own. Retrieval is a bag of words here, so
     * appending is enough -- the result is never shown to anybody, it only
     * decides which chunks come back.
     */
    fun carryOver(part: String, first: String): String {
        if (!DANGLING_PRONOUN.containsMatchIn(part)) return part
        val subject = SUBJECT_WORDS.findAll(first.lowercase())
            .map { it.value }
            .filter { it !in FILLER }
            .distinct()
            .toList()
        if (subject.isEmpty()) return part
        return part + " " + subject.joinToString(" ")
    }

    private val SUBJECT_WORDS = Regex("""[a-z]{4,}""")

    private val FILLER = setOf(
        "what", "which", "when", "where", "does", "many", "much", "there",
        "this", "that", "with", "from", "have", "will", "your", "their",
        "minimum", "maximum",
    )
}
