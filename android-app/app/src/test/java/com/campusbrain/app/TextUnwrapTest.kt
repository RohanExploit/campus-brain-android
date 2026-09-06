package com.campusbrain.app

import com.campusbrain.app.data.TextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hard-wrapped prose must survive chunking as one sentence.
 *
 * Downstream, AnswerCheck.SENTENCE_SPLIT treats a bare newline as a sentence
 * terminator -- correctly, because that is what separates one bullet from the
 * next. So any newline the chunker leaves inside a wrapped sentence becomes a
 * cut, and the composer answers with the fragment before it.
 *
 * That is not hypothetical. On a device, an imported document produced
 * "The Quasar Robotics Society meets every Thursday at 5:30 pm in" and stopped,
 * with "Laboratory 7B of the Mechanical Engineering block." one line below.
 * The bundled corpus never showed this because its chunks were unwrapped by an
 * offline exporter before they were ever shipped.
 */
class TextUnwrapTest {

    /** The device fixture, wrapped exactly as it was when it failed. */
    private val wrapped = """
        The Quasar Robotics Society meets every Thursday at 5:30 pm in
        Laboratory 7B of the Mechanical Engineering block.

        Annual membership costs 1450 rupees, payable to the society treasurer
        before the last working day of August.
    """.trimIndent()

    @Test fun `a wrapped sentence survives as one sentence`() {
        val text = TextChunker.chunk(wrapped).joinToString("\n") { it.content }
        assertTrue(
            "wrap not joined:\n$text",
            text.contains("meets every Thursday at 5:30 pm in Laboratory 7B"),
        )
        assertTrue(text.contains("1450 rupees, payable to the society treasurer before"))
    }

    @Test fun `the fragment that shipped is no longer a whole sentence`() {
        // The regression this guards: splitting on newline used to yield
        // "...at 5:30 pm in" as a complete unit, which is what reached the user.
        val text = TextChunker.chunk(wrapped).joinToString("\n") { it.content }
        val units = text.split(Regex("""(?<=[.!?])\s+|\n"""))
        assertTrue(
            "a sentence still ends mid-clause: $units",
            units.none { it.trim().endsWith(" in") },
        )
    }

    @Test fun `list items keep their own lines`() {
        // The reason the join has to be conservative. Welding these together
        // reads as nonsense, and costs more than a short extract would.
        val list = """
            Equipment rules:
            - Borrow for up to fourteen days
            - Return before the due date
            - Overdue members cannot borrow again
        """.trimIndent()
        val text = TextChunker.chunk(list).joinToString("\n") { it.content }
        assertTrue("bullets were joined:\n$text", text.contains("fourteen days\n"))
        assertEquals(3, text.lines().count { it.trimStart().startsWith("- ") })
    }

    @Test fun `numbered items and key-value fields keep their lines`() {
        val fields = """
            1. First step
            2. Second step
            Venue: Laboratory 7B
            Contact: the society treasurer
        """.trimIndent()
        val text = TextChunker.chunk(fields).joinToString("\n") { it.content }
        assertTrue(text.contains("1. First step\n"))
        assertTrue(text.contains("Venue: Laboratory 7B\n"))
    }

    @Test fun `a sentence that already ended is not joined to the next`() {
        val two = "The fee is 1450 rupees.\nMembers who join late pay 275 rupees."
        val text = TextChunker.chunk(two).joinToString("\n") { it.content }
        assertTrue("two sentences were welded:\n$text", text.contains("rupees.\nMembers"))
    }
}
