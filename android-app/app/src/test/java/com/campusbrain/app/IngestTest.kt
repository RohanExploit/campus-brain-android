package com.campusbrain.app

import com.campusbrain.app.data.DocCatalog
import com.campusbrain.app.data.DocxText
import com.campusbrain.app.data.TextChunker
import com.campusbrain.app.data.UserCorpusDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The parts of document ingestion that do not need a device.
 *
 * Deliberately the two that decide whether an imported document is any good:
 * whether the text came out of the file intact, and whether it was cut into
 * pieces the retriever can rank. Everything downstream of those -- the write,
 * the FTS index, the vector -- is mechanical, and everything upstream is a
 * ContentResolver call that only exists on a phone.
 *
 * The DOCX fixture is a real zip built in the test, not a checked-in binary.
 * A binary fixture would be unreviewable in a diff, and the point of these
 * tests is to make the format assumptions legible.
 */
class IngestTest {

    // --- chunker ----------------------------------------------------------

    @Test fun `paragraphs are packed, not emitted one per chunk`() {
        // The bundled corpus's median chunk is 568 characters. One paragraph
        // per chunk would put the median near 200 and a user's document would
        // lose every bm25 comparison against the college's, not because it is
        // a worse answer but because it is a smaller passage.
        val text = (1..8).joinToString("\n\n") { "Paragraph $it. " + "word ".repeat(20).trim() + "." }
        val chunks = TextChunker.chunk(text)
        assertTrue("expected packing, got ${chunks.size} chunks for 8 short paragraphs",
            chunks.size < 4)
        chunks.forEach { assertTrue(it.content.length <= TextChunker.MAX_CHARS) }
    }

    @Test fun `a heading closes the chunk before it and names the next`() {
        val text = """
            # Attendance Policy

            A minimum of 75% attendance is required to sit the examination.

            # Fee Payment

            Fees are payable at the counter before the last date.
        """.trimIndent()
        val chunks = TextChunker.chunk(text)
        assertEquals(2, chunks.size)
        assertEquals("Attendance Policy", chunks[0].section)
        assertEquals("Fee Payment", chunks[1].section)
        // Text either side of a heading is about two different things, and
        // fusing them makes both harder to retrieve.
        assertFalse(chunks[0].content.contains("Fees are payable"))
    }

    @Test fun `no chunk ever exceeds the ceiling`() {
        val huge = "This is a sentence about attendance rules. ".repeat(200)
        val chunks = TextChunker.chunk(huge)
        assertTrue(chunks.isNotEmpty())
        chunks.forEach {
            assertTrue("chunk of ${it.content.length} chars exceeds ${TextChunker.MAX_CHARS}",
                it.content.length <= TextChunker.MAX_CHARS)
        }
    }

    @Test fun `an oversize paragraph is cut at sentence boundaries`() {
        val chunks = TextChunker.chunk("Alpha beta gamma. ".repeat(100))
        // A chunk that ends mid-sentence becomes a lead sentence that ends
        // mid-sentence, and that is what the reader sees.
        chunks.dropLast(1).forEach {
            assertTrue("chunk ends mid-sentence: ...${it.content.takeLast(30)}",
                it.content.trimEnd().endsWith("."))
        }
    }

    @Test fun `a single word longer than the ceiling does not hang or vanish`() {
        val chunks = TextChunker.chunk("x".repeat(2500))
        assertTrue(chunks.isNotEmpty())
        assertEquals(2500, chunks.sumOf { it.content.length })
    }

    @Test fun `a runt is merged rather than given its own row`() {
        // A two-word chunk costs a full FTS5 row and a 1536-byte embedding and
        // retrieves nothing.
        val text = "A full paragraph of real content about the attendance policy here.\n\nOk."
        val chunks = TextChunker.chunk(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Ok."))
    }

    @Test fun `empty and whitespace input produce nothing, not a blank chunk`() {
        assertTrue(TextChunker.chunk("").isEmpty())
        assertTrue(TextChunker.chunk("   \n\n  \t \n").isEmpty())
    }

    @Test fun `windows line endings do not become part of the text`() {
        val chunks = TextChunker.chunk("# Title\r\n\r\nSome body text that is long enough to keep.\r\n")
        assertEquals(1, chunks.size)
        assertFalse(chunks[0].content.contains('\r'))
        assertEquals("Title", chunks[0].section)
    }

    // --- docx extractor ---------------------------------------------------

    private fun docx(bodyXml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // A real .docx has [Content_Types].xml first; including it keeps
            // the fixture honest about entry order not mattering.
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write("<Types/>".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(
                ("""<?xml version="1.0"?><w:document """ +
                    """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
                    """<w:body>$bodyXml</w:body></w:document>""").toByteArray()
            )
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun para(vararg runs: String) =
        "<w:p>" + runs.joinToString("") { "<w:r><w:t>$it</w:t></w:r>" } + "</w:p>"

    @Test fun `text comes out of a docx`() {
        val bytes = docx(para("Attendance is compulsory.") + para("Fees are due in July."))
        val text = DocxText.extract(ByteArrayInputStream(bytes))
        assertTrue(text.contains("Attendance is compulsory."))
        assertTrue(text.contains("Fees are due in July."))
    }

    @Test fun `a sentence split across runs is not split in the output`() {
        // Word starts a new w:r whenever formatting changes, so a single
        // sentence with one bold word arrives as three runs. Emitting a break
        // per run would shatter it and defeat the chunker's promise never to
        // cut mid-sentence.
        val bytes = docx(para("A minimum of ", "75%", " attendance is required."))
        val text = DocxText.extract(ByteArrayInputStream(bytes))
        assertTrue("runs were not joined: $text",
            text.contains("A minimum of 75% attendance is required."))
    }

    @Test fun `heading styles become markdown headings`() {
        val body =
            """<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Attendance</w:t></w:r></w:p>""" +
                para("Seventy five percent is the floor.")
        val text = DocxText.extract(ByteArrayInputStream(docx(body)))
        assertTrue("expected an ATX heading, got: $text", text.contains("# Attendance"))
        // And the chunker has to be able to read it back, which is the only
        // reason the extractor emits markdown at all.
        val chunks = TextChunker.chunk(text)
        assertEquals("Attendance", chunks.first().section)
    }

    @Test fun `a table becomes markdown pipe rows`() {
        // Which matters for more than looks: AnswerCheck.parseBands reads pipe
        // rows, so a user's own rules table becomes answerable by the same
        // comparison that answers the college's attendance tiers.
        val body = "<w:tbl><w:tr>" +
            "<w:tc>" + para("Below 65%") + "</w:tc>" +
            "<w:tc>" + para("Debarred from the examination") + "</w:tc>" +
            "</w:tr></w:tbl>"
        val text = DocxText.extract(ByteArrayInputStream(docx(body)))
        assertTrue("expected a pipe row, got: $text",
            text.contains("| Below 65% | Debarred from the examination |"))
    }

    @Test fun `a non-zip is refused rather than crashing`() {
        val notAZip = "This is just some text, not a Word file at all.".toByteArray()
        var threw = false
        try {
            DocxText.extract(ByteArrayInputStream(notAZip))
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("a non-zip must be refused, not silently accepted", threw)
    }

    @Test fun `a zip with no document body is refused`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use {
            it.putNextEntry(ZipEntry("other.txt")); it.write("hi".toByteArray()); it.closeEntry()
        }
        var threw = false
        try {
            DocxText.extract(ByteArrayInputStream(out.toByteArray()))
        } catch (e: DocxText.NotADocxException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test fun `non-breaking spaces are folded so retrieval can match them`() {
        // Word emits these freely. They read as ordinary spaces on screen and
        // break substring matching in both retrieval arms, so a policy line
        // carrying one would never match a query for "attendance".
        //
        // Written as an escape, never as the character: a literal U+00A0 is
        // indistinguishable from a space in every diff this file will ever
        // get, and a test whose fixture is invisible is a test nobody can
        // check.
        val nbsp = '\u00A0'
        val bytes = docx(para("A minimum of${nbsp}75%${nbsp}attendance"))
        val text = DocxText.extract(ByteArrayInputStream(bytes))
        assertTrue("nbsp was not folded", text.contains("A minimum of 75% attendance"))
        assertFalse("nbsp survived extraction", text.contains(nbsp))
    }

    // --- catalogue --------------------------------------------------------

    @Test fun `categories are derived from the doc_id, over the real bundle's names`() {
        // Every id below is a real doc_id from the shipped brain.db.
        assertEquals("Attendance", DocCatalog.categoryOf("24_attendance_policy.md"))
        assertEquals("Attendance", DocCatalog.categoryOf("25_attendance_defaulter_procedure.md"))
        assertEquals("Examinations", DocCatalog.categoryOf("06_exam_schedule_odd_2026_27.md"))
        assertEquals("Fees and Scholarships", DocCatalog.categoryOf("07_notice_fee_payment.md"))
        assertEquals(
            "Fees and Scholarships",
            DocCatalog.categoryOf("svc_04_scholarship_procedure_rajarshi_shahu_maharaj_freeship_for_ebc.md")
        )
        assertEquals("Placements and Training", DocCatalog.categoryOf("svc_08_placement_policy.md"))
        assertEquals("Library", DocCatalog.categoryOf("svc_17_library_services_and_rules.md"))
        assertEquals("Hostel and Transport", DocCatalog.categoryOf("12_notice_bus_pass_renewal.md"))
        assertEquals("Calendar and Timetables", DocCatalog.categoryOf("02_timetable_comp_sem3_divA.md"))
        assertEquals("Research Papers", DocCatalog.categoryOf("RAG-MicroSim Framework.md"))
        assertEquals(
            "Research Papers",
            DocCatalog.categoryOf("Use-of-Artificial-Intelligence-in-Marketing-and-Finance.md")
        )
    }

    @Test fun `an attendance document published as a notice is filed under attendance`() {
        // Rule order inside DocCatalog is load-bearing, not incidental.
        assertEquals("Attendance", DocCatalog.categoryOf("25_attendance_defaulter_procedure.md"))
        // And the reverse: a scholarship that is about sports is a scholarship.
        assertEquals(
            "Fees and Scholarships",
            DocCatalog.categoryOf("svc_06_scholarship_procedure_sports_and_cultural_excellence_scholarship.md")
        )
        assertEquals("Events", DocCatalog.categoryOf("svc_07_sports_cultural_benefits.md"))
    }

    @Test fun `an unreadable filename is not given a category it does not deserve`() {
        // A WhatsApp export could be anything. Filing it somewhere a student
        // would then trust is worse than admitting we do not know.
        assertEquals("Other", DocCatalog.categoryOf("DOC-20260212-WA0018..md"))
    }

    @Test fun `research sorts last and campus life first`() {
        assertTrue(
            DocCatalog.orderOf("Attendance") < DocCatalog.orderOf("Research Papers")
        )
        assertTrue(
            DocCatalog.orderOf("Research Papers") < DocCatalog.orderOf("Other")
        )
        assertEquals(0, DocCatalog.orderOf(UserCorpusDb.ADDED_CATEGORY))
    }

    @Test fun `titles stop mangling acronyms and stop running on`() {
        // What the old filename-to-title-case produced, verbatim:
        // "Ai Free RAG MicroSim A Hybrid Retrieval Aug".
        val t = DocCatalog.titleFor("Ai free RAG-MicroSim A Hybrid Retrieval-Aug.md")
        assertTrue("acronym was title-cased: $t", t.startsWith("AI "))
        assertTrue("deliberate mixed case was destroyed: $t", t.contains("MicroSim"))

        val long = DocCatalog.titleFor(
            "1 RAG-MicroSim_ A Hybrid Retrieval-Augmented Generation and Market " +
                "Micro-Simulation Framework for High-Frequency Trading Analysis.md"
        )
        assertTrue("title runs on at ${long.length} chars: $long", long.length <= 60)
        assertTrue("elision must be visible: $long", long.endsWith("…"))
        // Cut at a word boundary, never mid-word.
        assertFalse(long.dropLast(1).endsWith(" "))

        assertEquals("Attendance Policy", DocCatalog.titleFor("24_attendance_policy.md"))
    }

    // --- the id space that keeps the two corpora apart --------------------

    @Test fun `user chunk ids never collide with bundled ones`() {
        // The bundle's highest chunk id is 493. This is the whole mechanism
        // behind Source.isUserAdded and behind HybridSearch fusing two
        // databases into one ranked list.
        assertFalse(UserCorpusDb.isUserChunk(1))
        assertFalse(UserCorpusDb.isUserChunk(493))
        assertFalse(UserCorpusDb.isUserChunk(UserCorpusDb.ID_BASE - 1))
        assertTrue(UserCorpusDb.isUserChunk(UserCorpusDb.ID_BASE))
        assertTrue(UserCorpusDb.isUserChunk(UserCorpusDb.ID_BASE + 12))
    }

    @Test fun `vectors are encoded exactly as the bundle encodes them`() {
        // Confirmed against a shipped row before writing one: 1536 bytes,
        // float32 little-endian. A mismatch here would not fail loudly, it
        // would quietly rank every imported document as noise.
        val v = FloatArray(384) { it / 1000f }
        val blob = UserCorpusDb.encodeVector(v)
        assertEquals(1536, blob.size)
        val back = java.nio.ByteBuffer.wrap(blob)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(384)
        back.get(out)
        assertEquals(v[0], out[0], 1e-9f)
        assertEquals(v[383], out[383], 1e-9f)
    }
}
