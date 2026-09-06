package com.campusbrain.app.data

import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * Plain text out of a .docx, with no new Gradle dependency.
 *
 * A .docx is a zip; the body is one entry, `word/document.xml`. Both halves of
 * that are in the JDK and in Android already -- `java.util.zip.ZipInputStream`
 * and `javax.xml.parsers` -- so reading one costs nothing at build time. That
 * matters here: the brief's other candidate, PDF, needs PdfBox and about 16MB
 * of APK, which is a decision for Rohan and not for this file.
 *
 * SAX rather than XmlPullParser, which was the suggestion. XmlPullParser on
 * Android comes from `android.util.Xml`, which is a stub in a plain JVM unit
 * test and would have forced this class onto Robolectric or onto the device.
 * `javax.xml.parsers` is present in both runtimes, so the extractor is tested
 * on the JVM in milliseconds against a zip built in the test itself. Same
 * output, no framework.
 *
 * What comes out is deliberately markdown-shaped -- `#` for headings, `|` for
 * table cells -- because everything downstream already understands that shape.
 * [TextChunker] reads headings to set a chunk's section, and
 * `AnswerCheck.parseBands` reads pipe rows to answer "can I ... with 60%"
 * against a table. A user's own attendance rules become answerable the same
 * way the college's are, without a second parser.
 */
object DocxText {

    /** The only entry that matters. Headers, footers and endnotes are skipped:
     * they are page furniture, and indexing them puts a running header into
     * every chunk of the document. */
    private const val BODY = "word/document.xml"

    class NotADocxException(message: String) : Exception(message)

    /**
     * Reads [input] to the end. Throws [NotADocxException] when the stream is
     * not a zip or carries no document body -- the caller turns that into an
     * `Unsupported` result rather than a crash.
     */
    fun extract(input: InputStream): String {
        val xml = readBodyEntry(input) ?: throw NotADocxException(
            "No $BODY inside the file, so it is not a Word document."
        )
        return parse(xml)
    }

    private fun readBodyEntry(input: InputStream): ByteArray? {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: return null
                if (entry.name == BODY) {
                    val out = ByteArrayOutputStream()
                    zip.copyTo(out)
                    return out.toByteArray()
                }
                zip.closeEntry()
            }
        }
    }

    private fun parse(xml: ByteArray): String {
        val handler = BodyHandler()
        // Namespace processing off on purpose: the qualified names are
        // "w:p", "w:t" and so on, and [local] strips the prefix. Turning
        // namespaces on would mean carrying the wordprocessingml URI around
        // for no gain, and would break on the handful of generators that emit
        // the body with a different prefix.
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = false
        // These two are the reason a hostile .docx cannot make the parser
        // fetch a URL or expand a billion-laughs entity. A user-supplied file
        // is untrusted input even when the user supplied it to themselves.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
        return handler.finish()
    }

    private fun local(qName: String): String = qName.substringAfter(':')

    private class BodyHandler : DefaultHandler() {
        private val doc = StringBuilder()
        private val para = StringBuilder()

        /** Heading level from w:pStyle, 0 for body text. */
        private var headingLevel = 0
        private var inTable = false
        private val row = ArrayList<String>()

        override fun startElement(uri: String?, localName: String?, qName: String, attrs: Attributes?) {
            when (local(qName)) {
                "tbl" -> inTable = true
                "tr" -> row.clear()
                "tab" -> para.append(' ')
                "br", "cr" -> para.append(' ')
                "pStyle" -> {
                    val style = attrs?.getValue("w:val") ?: attrs?.getValue("val") ?: return
                    headingLevel = headingLevelOf(style)
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            // Every character node inside a run lands here. Word splits a
            // single sentence across many w:t runs whenever formatting
            // changes, so text is accumulated and only broken at paragraph
            // level -- breaking per run would shatter sentences and defeat
            // the chunker's "never split mid-sentence" guarantee.
            para.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            when (local(qName)) {
                "p" -> endParagraph()
                "tc" -> { row += para.toString().trim(); para.setLength(0); headingLevel = 0 }
                "tr" -> endRow()
                "tbl" -> { inTable = false; doc.append('\n') }
            }
        }

        /**
         * A w:p inside a table cell must not terminate the cell -- a cell can
         * hold several paragraphs, and the cell's own end tag is what commits
         * it. Outside a table, the paragraph is the unit.
         */
        private fun endParagraph() {
            if (inTable) {
                if (para.isNotEmpty() && para.last() != ' ') para.append(' ')
                return
            }
            val text = para.toString().trim()
            para.setLength(0)
            val level = headingLevel
            headingLevel = 0
            if (text.isEmpty()) return
            if (level > 0) doc.append("\n").append("#".repeat(level)).append(' ').append(text).append("\n\n")
            else doc.append(text).append("\n\n")
        }

        private fun endRow() {
            if (row.isEmpty()) return
            // Markdown pipe row, so the same table parsing that reads the
            // college's attendance tiers reads a user's too.
            doc.append("| ").append(row.joinToString(" | ")).append(" |\n")
            row.clear()
        }

        private fun headingLevelOf(style: String): Int {
            val s = style.lowercase().replace("-", "").replace(" ", "")
            if (s == "title") return 1
            if (s == "subtitle") return 2
            if (!s.startsWith("heading")) return 0
            return s.removePrefix("heading").toIntOrNull()?.coerceIn(1, 6) ?: 0
        }

        fun finish(): String {
            endParagraph()
            // Word emits non-breaking spaces and soft hyphens freely; they read
            // as ordinary characters on screen but break substring matching in
            // both retrieval arms, so they are folded here rather than being
            // discovered later as a mysterious retrieval miss.
            return doc.toString()
                // Escaped rather than written literally: a bare U+00A0 in
                // source is indistinguishable from a space in every diff
                // and code review this file will ever get.
                .replace('\u00A0', ' ')     // non-breaking space
                .replace("\u00AD", "")      // soft hyphen
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\n{3,}"), "\n\n")
                .trim()
        }
    }
}
