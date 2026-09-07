package com.campusbrain.app.retrieval

import androidx.sqlite.SQLiteConnection
import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.data.BrainDb
import com.campusbrain.app.data.RetrievedChunk
import com.campusbrain.app.data.query

/**
 * FTS5 keyword arm.
 *
 * Works only because the app links the bundled SQLite: measured on the demo
 * device, the platform SQLite behind android.database.sqlite has no fts5
 * module, and the same MATCH below fails there with "no such module: fts5".
 */
class FtsSearch(private val conn: SQLiteConnection) {

    /**
     * Takes a bare connection rather than a [BrainDb] so the identical search
     * can run over the user's own corpus, which is a second database file with
     * the same schema and the same FTS5 tokenizer. See [UserCorpusDb].
     */
    constructor(db: BrainDb) : this(db.conn)

    /** True once probed; false means the caller must fall back to [LikeSearch]. */
    val available: Boolean by lazy {
        runCatching {
            conn.query("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'a' LIMIT 1") { }
            true
        }.getOrElse { false }
    }

    fun search(queryText: String, topK: Int): List<RetrievedChunk> {
        val expr = sanitize(queryText)
        // An empty MATCH expression is a syntax error, not an empty result.
        if (expr.isBlank()) return emptyList()
        val sql =
            "SELECT c.id, c.doc_id, c.section, c.content, bm25(chunks_fts) AS rank " +
                "FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid " +
                "WHERE chunks_fts MATCH ? ORDER BY rank ASC LIMIT ?"
        return runCatching {
            conn.query(sql, { it.bindText(1, expr); it.bindLong(2, topK.toLong()) }) {
                RetrievedChunk(
                    id = it.getLong(0),
                    docId = it.getText(1),
                    section = if (it.isNull(2)) null else it.getText(2),
                    content = it.getText(3),
                    // bm25 is "lower is better"; negate so higher is better,
                    // matching the Dart retriever.
                    score = -it.getDouble(4),
                )
            }
        }.getOrElse { emptyList() }
    }

    companion object {
        // FTS5 operator characters. Left in, an ordinary apostrophe, hyphen or
        // a bare AND/OR/NOT/NEAR throws a syntax error at runtime.
        private val OPERATOR_CHARS = Regex("[\"*:\\-()^]")
        private val KEYWORDS = setOf("AND", "OR", "NOT", "NEAR")

        /**
         * Tokens are quoted and joined with an explicit OR.
         *
         * The explicit OR matters: FTS5's implicit conjunction requires every
         * term to be present, which returns nothing for an ordinary
         * natural-language question. Same choice the Dart retriever documents.
         *
         * What the OR costs, and why the filler is dropped from it: under OR,
         * bm25 rewards a chunk for every term it carries, and "what", "is",
         * "if", "it" are carried by most of the corpus. On "what happens if I
         * miss it attendance" the four filler words outvoted "miss" and
         * "attendance", and the Attendance Policy's own tier table -- chunk
         * 150 -- came back at rank 25 of 25 while unrelated procedure notices
         * took the top four places. Filtered, 150 is rank 4 and the 65-74%
         * condonation rows are inside the window, which is the difference
         * between naming one tier and naming the ladder.
         *
         * The vocabulary is [AnswerCheck.isFiller], not a list local to this
         * file, on purpose: the answer check already decides which words of a
         * question a correct answer has to contain, and a keyword arm hunting
         * for a different set of words than the check demands is how a chunk
         * gets retrieved and then rejected. One list, stated once, with the
         * measurement behind each entry recorded there.
         *
         * Only the list is borrowed, not [AnswerCheck.contentTerms] whole.
         * That function also drops every token of two characters or fewer and
         * splits on non-alphanumerics, so "70" and "60%" do not survive it --
         * a documented consequence of its job, and the wrong trade here.
         * Measured on "am I eligible for a scholarship if my attendance is 70
         * percent": with the number kept, the eligibility matrix row carrying
         * the Alumni Grant's 80% threshold is the keyword arm's 4th hit and
         * the answer names it; using contentTerms it leaves the window and the
         * answer silently lists one scheme fewer.
         *
         * A question made entirely of filler ("what is it") reduces to nothing,
         * and an empty MATCH is a syntax error rather than an empty result, so
         * the unfiltered tokens stand in. Keeping the old behaviour for a
         * question the filter cannot improve is cheaper than a special case.
         */
        fun sanitize(queryText: String): String {
            val cleaned = OPERATOR_CHARS.replace(queryText, " ")
            val tokens = cleaned.split(Regex("\\s+"))
                .map { it.trim().trimEnd('?', '!', '.', ',', ';') }
                .filter { it.isNotBlank() && it.uppercase() !in KEYWORDS }
                .filter { it.any(Char::isLetterOrDigit) }
            val terms = tokens.filterNot(AnswerCheck::isFiller).ifEmpty { tokens }
            if (terms.isEmpty()) return ""
            return terms.joinToString(" OR ") { "\"${it.replace("\"", "")}\"" }
        }
    }
}

/**
 * Degradation path for a device whose SQLite somehow still lacks FTS5.
 *
 * Ranking is crude (count of matched tokens) and there is no bm25, but a demo
 * that answers worse beats one that cannot open its own corpus.
 */
class LikeSearch(private val conn: SQLiteConnection) {

    constructor(db: BrainDb) : this(db.conn)

    fun search(queryText: String, topK: Int): List<RetrievedChunk> {
        val tokens = queryText.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .distinct()
            .take(8)
        if (tokens.isEmpty()) return emptyList()
        val scoreExpr = tokens.joinToString(" + ") {
            "(CASE WHEN lower(content) LIKE '%' || ? || '%' THEN 1 ELSE 0 END)"
        }
        val sql = "SELECT id, doc_id, section, content, ($scoreExpr) AS hits " +
            "FROM chunks WHERE hits > 0 ORDER BY hits DESC, id LIMIT ?"
        return conn.query(sql, { st ->
            tokens.forEachIndexed { i, t -> st.bindText(i + 1, t) }
            st.bindLong(tokens.size + 1, topK.toLong())
        }) {
            RetrievedChunk(
                id = it.getLong(0),
                docId = it.getText(1),
                section = if (it.isNull(2)) null else it.getText(2),
                content = it.getText(3),
                score = it.getDouble(4),
            )
        }
    }
}
