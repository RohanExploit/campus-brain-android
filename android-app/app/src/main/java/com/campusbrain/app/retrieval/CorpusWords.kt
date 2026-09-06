package com.campusbrain.app.retrieval

import androidx.sqlite.SQLiteConnection
import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.data.query

/**
 * Does the corpus contain this word ANYWHERE -- not "did retrieval find it",
 * which is a different and much smaller question.
 *
 * [AnswerCheck.unsupportedSubject] needs both. Retrieval returns ten chunks out
 * of 493, so an ordinary English word is missing from them all the time: "how
 * long does a bonafide certificate take" retrieves the bonafide notice, which
 * says neither "long" nor "take", and refusing on that alone would abstain on a
 * question the app answers correctly today. Measured over the bundle, "long"
 * appears in 13 chunks and "take" in 11 -- they are ordinary vocabulary that
 * one notice happens not to use. "Cheating" appears in 0, "plagiarism" in 0,
 * "wifi" in 0. That is a different fact about the question, and it is the one
 * worth abstaining on.
 *
 * A substring probe, matching [AnswerCheck]'s own `mentions`. Over-matching is
 * the safe direction here: a false "yes, the corpus knows this word" only ever
 * stops the gate firing, and the gate firing is the thing that costs an answer.
 *
 * One full scan per distinct term, memoised. 493 rows with `instr` measured as
 * immaterial beside the ONNX embed already on the same query's path, and an
 * index would have to be a second FTS table for the sake of at most four
 * lookups.
 */
class CorpusWords(private val conn: SQLiteConnection) : AnswerCheck.CorpusVocabulary {

    constructor(db: BrainDb) : this(db.conn)

    private val seen = HashMap<String, Boolean>()

    override fun occursInCorpus(term: String): Boolean = seen.getOrPut(term) {
        // A failed probe reports "the corpus knows it", never the reverse. A
        // broken query must not be able to turn every answer into an
        // abstention.
        runCatching {
            conn.query(
                "SELECT 1 FROM chunks WHERE instr(lower(content), ?) > 0 LIMIT 1",
                bind = { it.bindText(1, term) },
            ) { true }.isNotEmpty()
        }.getOrElse { true }
    }
}
