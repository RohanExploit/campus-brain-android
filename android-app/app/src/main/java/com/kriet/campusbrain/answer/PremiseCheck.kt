package com.kriet.campusbrain.answer

import com.kriet.campusbrain.Grades

/**
 * Catches a question whose premise the records contradict, and corrects the
 * premise instead of answering the question.
 *
 * "How many students got an A+ grade" has no true answer: this university's
 * scale runs EX, AA, AB ... FF and has no A+ in it at all. The app used to
 * route the question TABULAR, match no template, fall through to FACT, and
 * narrate the column documentation of an unrelated CSV -- "31 G1 - first
 * period grade (numeric: from 0 to 20)". Abstaining would have been better,
 * but abstaining is still not the answer. The answer is that the grade does
 * not exist, and that is something the app knows exactly, from [Grades].
 *
 * Deliberately narrow, and it should stay that way. This fires only on a
 * closed vocabulary the app owns the ground truth for, and only when the
 * question puts a grade-shaped token directly beside the word "grade". A
 * general false-premise detector would have to guess whether a premise is
 * false, and a wrong guess here re-creates the confident-and-wrong failure
 * the whole check exists to remove.
 */
object PremiseCheck {

    /** Every grade that exists: the marks scale plus the audit marker. */
    private val KNOWN: Set<String> = Grades.GRADE_POINTS.keys + Grades.AUDIT_GRADES

    /**
     * A grade-shaped token: one or two letters carrying a +/- suffix, a bare
     * pair of letters, or a bare single letter.
     *
     * The single letter was excluded at first, because in "a grade" the English
     * article is a single letter and refusing "A grade" as a false premise
     * would be a worse failure than answering it. On hardware that exclusion
     * cost the whole check: "how many students got an O grade" -- an O that
     * this scale does not have, on a scale where nothing is one letter long --
     * missed every alternative, fell through TABULAR to FACT and abstained
     * generically, while the identical A+ question was corrected exactly.
     *
     * The article is handled where it belongs instead, in [NOT_A_GRADE]. The
     * alternation keeps the two-letter form FIRST so "grade in sem 3" is read
     * as the exempt word "IN" rather than as a bare "I": the exemption list is
     * matched against whole tokens, so a token split short would slip past it.
     */
    private const val TOKEN = """([A-Za-z]{1,2}[+‑-]|[A-Za-z]{2}|[A-Za-z])"""

    private val BEFORE = Regex("""(?<![A-Za-z0-9])$TOKEN\s+grades?\b""", RegexOption.IGNORE_CASE)
    private val AFTER =
        Regex("""\bgrades?\s+(?:of\s+)?$TOKEN(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE)

    /**
     * Short English words that sit next to "grade" in ordinary questions --
     * "no grade", "my grades", "grade in sem 3". Without this list the check
     * would announce that there is no "NO" grade, which is true and useless. A
     * token carrying +/- never needs the exemption: no English word ends in
     * one.
     *
     * "A" and "I" are the two single letters that are words rather than
     * grades, and they are the reason the single-letter token was refused
     * outright until "an O grade" showed what that cost. Every other letter of
     * the alphabet, asked as "a <letter> grade", is a real question with the
     * same true answer: this scale has no one-letter grade at all.
     */
    private val NOT_A_GRADE = setOf(
        "A", "I",
        "AN", "MY", "NO", "IN", "OF", "IS", "IT", "TO", "SO", "OR", "AT",
        "ON", "UP", "BY", "DO", "IF", "WE", "HE", "ME", "US", "AS", "BE",
        "GO", "HI", "AM", "PER",
    )

    /**
     * The correction to show, or null when the question's premise is sound.
     *
     * Returned as an answer rather than an abstention by the caller: a
     * correction IS the answer, and routing it through the abstention path
     * would hand it to the cloud fallback, which would happily describe some
     * other institution's A+ scale as though it were this one's.
     */
    fun gradeScale(query: String): String? {
        val named = (BEFORE.findAll(query) + AFTER.findAll(query))
            .map { it.groupValues[1].uppercase().replace('‑', '-') }
            .filter { it !in NOT_A_GRADE }
            .toList()
        if (named.isEmpty()) return null
        val unknown = named.firstOrNull { it !in KNOWN } ?: return null
        return "There is no $unknown grade at this university, so no student has one. " +
            "The grade scale is ${scale()} — FF is the only failing grade — " +
            "plus AU for audit subjects."
    }

    /** The scale, best first, so the correction also serves as the answer to
     * "what grades are there". Read from [Grades] rather than restated here:
     * a second copy of this mapping is exactly what caused 'AB' to be
     * miscounted as a failure across three subsystems once already. */
    private fun scale(): String = Grades.GRADE_POINTS.entries
        .sortedByDescending { it.value }
        .joinToString(", ") { it.key }
}
