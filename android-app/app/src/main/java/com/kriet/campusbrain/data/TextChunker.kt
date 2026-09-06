package com.kriet.campusbrain.data

/**
 * Splits a document into the same shape of chunk the bundled corpus already
 * holds, because a chunk that is shaped differently retrieves differently.
 *
 * Measured from brain.db (493 chunks): median 568 characters, max 1000,
 * boundaries on paragraph and heading breaks, and a `section` carrying the
 * heading the chunk sits under. Everything here is chosen to land inside that
 * distribution rather than to be independently sensible. A user's document
 * chunked into 3000-character blocks would dominate the RRF context budget
 * (5000 chars, `HybridSearch.CONTEXT_BUDGET_CHARS`) and crowd the college's
 * own documents out of every answer it appeared in; chunked into single
 * sentences it would lose to them every time on bm25. Matching the existing
 * corpus is the only setting that makes a user's document compete on merit.
 *
 * Pure: no Android types, no I/O, no database. That is deliberate -- it is the
 * one part of ingestion whose behaviour is worth pinning in a unit test that
 * runs in milliseconds.
 */
object TextChunker {

    /** Hard ceiling, matching the largest chunk in the shipped bundle. */
    const val MAX_CHARS = 1000

    /**
     * Below this a chunk is merged into its neighbour instead of standing
     * alone. The bundle does contain a 2-character chunk, but that is an
     * export artefact and not a thing worth reproducing: a two-word chunk
     * carries a full FTS5 row and an embedding for no retrievable content.
     */
    const val MIN_CHARS = 80

    data class Chunk(
        /** The heading this text sits under, or null at the top of a document. */
        val section: String?,
        val content: String,
    )

    private val ATX_HEADING = Regex("""^\s{0,3}(#{1,6})\s+(.+?)\s*#*\s*$""")
    private val SETEXT_UNDERLINE = Regex("""^\s{0,3}(={3,}|-{3,})\s*$""")

    /**
     * Sentence boundary for the oversize path only. Requires whitespace after
     * the terminator so "3.5%" and "Rs 50.00" do not become two sentences.
     */
    private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")

    /**
     * A line that carries its own structure and must keep its own newline: a
     * bullet, a numbered item, a table row, or a `key: value` field. Joining
     * these to the line above would run two list items into one sentence.
     */
    private val STRUCTURAL_LINE = Regex("""^\s*(?:[-*•·]|\d{1,3}[.)]|\|)\s+|^[^\s:]{1,40}:\s""")

    /**
     * Whether [prev] flows into [next] as one wrapped sentence.
     *
     * A hard-wrapped paragraph is the normal shape of a .txt file, and until
     * this existed every wrap became a sentence boundary downstream:
     * AnswerCheck.SENTENCE_SPLIT treats a bare newline as a terminator, which
     * it must, because that is what separates one bullet from the next. The
     * consequence on a device was an answer that stopped mid-clause -- "meets
     * every Thursday at 5:30 pm in" -- with the rest of the sentence sitting
     * one line below, unread.
     *
     * The bundled corpus never showed this: its chunks were built by an offline
     * exporter that had already unwrapped them. Only imported documents carry
     * source wrapping, which is why the defect appeared the day ingestion did.
     *
     * Conservative on purpose. A line is joined only when the previous line
     * does not end a sentence AND neither line is structural, so anything that
     * might be a list keeps its break. A missed join costs a slightly short
     * extract; a wrong join welds two list items together and reads as
     * nonsense.
     */
    private fun continuesSentence(prev: String, next: String): Boolean {
        if (prev.isEmpty() || next.isEmpty()) return false
        if (STRUCTURAL_LINE.containsMatchIn(next)) return false
        if (STRUCTURAL_LINE.containsMatchIn(prev)) return false
        // A terminator ends the sentence even mid-paragraph; the next line is a
        // new one and deserves its own boundary.
        return prev.last() !in ".!?:;"
    }

    fun chunk(rawText: String): List<Chunk> {
        val lines = rawText.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<Chunk>()

        var section: String? = null
        val buffer = StringBuilder()
        var bufferSection: String? = null

        fun flush() {
            val text = buffer.toString().trim()
            buffer.setLength(0)
            if (text.isEmpty()) return
            // Merge a runt into whatever came before it, provided that does not
            // push the previous chunk past the ceiling and they share a section.
            val last = out.lastOrNull()
            if (text.length < MIN_CHARS && last != null &&
                last.section == bufferSection &&
                last.content.length + text.length + 2 <= MAX_CHARS
            ) {
                out[out.size - 1] = last.copy(content = last.content + "\n\n" + text)
                return
            }
            splitOversize(text).forEach { out += Chunk(bufferSection, it) }
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val atx = ATX_HEADING.matchEntire(line)
            // A setext heading is the line above a run of === or ---, which is
            // how a DOCX converted to text and a hand-written note both tend to
            // mark a title.
            val setext = i + 1 < lines.size && line.isNotBlank() &&
                SETEXT_UNDERLINE.matchEntire(lines[i + 1]) != null

            if (atx != null || setext) {
                // A heading closes the chunk before it. Retrieval quality
                // depends on this: text either side of a heading is about two
                // different things, and fusing them makes both harder to find.
                flush()
                section = (atx?.groupValues?.get(2) ?: line).trim()
                bufferSection = section
                if (setext) i++   // consume the underline
                i++
                continue
            }

            if (line.isBlank()) {
                // Paragraph break: close only if another paragraph would not
                // fit. Packing to the ceiling is what reproduces the corpus's
                // median size; closing on every blank line would not.
                if (buffer.isNotEmpty()) {
                    val next = nextParagraphLength(lines, i)
                    if (next > 0 && buffer.length + next + 2 > MAX_CHARS) flush()
                    else if (buffer.isNotEmpty()) buffer.append("\n\n")
                }
                i++
                continue
            }

            if (buffer.isEmpty()) bufferSection = section
            val trimmed = line.trim()
            // Join a wrapped continuation to the line above with a space rather
            // than a newline. See [continuesSentence]: downstream a newline is
            // a sentence boundary, so keeping the source's wrapping here is
            // what cut imported answers off mid-clause.
            val prev = buffer.dropLastWhile { it == '\n' }.takeLastWhile { it != '\n' }.toString()
            if (buffer.isNotEmpty() && continuesSentence(prev, trimmed)) {
                buffer.setLength(buffer.length - 1)   // drop the pending '\n'
                buffer.append(' ').append(trimmed).append('\n')
            } else {
                buffer.append(trimmed).append('\n')
            }
            i++
        }
        flush()
        return out.filter { it.content.isNotBlank() }
    }

    /** Characters in the paragraph starting after the blank line at [from]. */
    private fun nextParagraphLength(lines: List<String>, from: Int): Int {
        var n = 0
        var i = from
        while (i < lines.size && lines[i].isBlank()) i++
        while (i < lines.size && lines[i].isNotBlank()) {
            if (ATX_HEADING.matchEntire(lines[i]) != null) break
            n += lines[i].trim().length + 1
            i++
        }
        return n
    }

    /**
     * Last resort for a single paragraph longer than the ceiling: split on
     * sentence boundaries, and only if one sentence is itself oversize, on
     * word boundaries. A chunk that ends mid-sentence produces a lead sentence
     * that ends mid-sentence, and that is what the reader sees.
     */
    private fun splitOversize(text: String): List<String> {
        if (text.length <= MAX_CHARS) return listOf(text)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in text.split(SENTENCE_END)) {
            for (piece in hardSplit(sentence)) {
                if (current.isNotEmpty() && current.length + piece.length + 1 > MAX_CHARS) {
                    out += current.toString().trim()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(piece)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.filter { it.isNotBlank() }
    }

    /** A single sentence longer than the ceiling, cut at spaces. */
    private fun hardSplit(sentence: String): List<String> {
        if (sentence.length <= MAX_CHARS) return listOf(sentence)
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (word in sentence.split(' ')) {
            // A "word" can be longer than the ceiling on its own -- a base64
            // blob or a long URL pasted into a note. Without this the ceiling
            // is only a ceiling for well-formed prose, and the one document
            // that breaks it is the one nobody tested.
            for (part in word.chunked(MAX_CHARS)) {
                if (current.isNotEmpty() && current.length + part.length + 1 > MAX_CHARS) {
                    out += current.toString()
                    current.setLength(0)
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(part)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
