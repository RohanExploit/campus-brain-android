package com.kriet.campusbrain

import com.kriet.campusbrain.answer.CloudAnswer
import com.kriet.campusbrain.answer.TopicGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TopicGateTest {

    @Test fun `educational questions are recognised`() {
        listOf(
            "how do I apply for a scholarship",
            "minimum attendance",
            "when is the exam",
            "hostel fees",
            "placement eligibility with a backlog",
            "bonafide certificate",
        ).forEach { assertTrue(it, TopicGate.isEducational(it)) }
    }

    @Test fun `non-educational questions are recognised`() {
        listOf(
            "how do I cook biryani",
            "who won the cricket match",
            "what is the weather",
        ).forEach { assertFalse(it, TopicGate.isEducational(it)) }
    }

    @Test fun `an ambiguous question is biased toward educational`() {
        // Neither list matches "what is the deadline" cleanly, and the whole
        // point of the asymmetry documented on TopicGate is that ambiguity
        // must resolve toward answering, not toward refusing a student.
        assertTrue(TopicGate.isEducational("what is the deadline"))
    }

    @Test fun `domain vocabulary is the words that identify no question`() {
        // Read the other way round, this list is what AnswerCheck uses to tell
        // a question's subject from its background noise. "Students" is in a
        // quarter of the corpus and in most questions; "cheating" is in none.
        listOf("student", "students", "subject", "exam", "exams", "attendance",
            "scholarship", "college", "semester", "grade")
            .forEach { assertTrue(it, TopicGate.isDomainVocabulary(it)) }
        listOf("cheating", "caught", "plagiarism", "wifi", "condonation", "debarred")
            .forEach { assertFalse(it, TopicGate.isDomainVocabulary(it)) }
    }

    @Test fun `naming a campus subject is stricter than being educational`() {
        // The router's off-topic cloud suppression turns on this difference.
        // isEducational resolves ambiguity toward answering and so says yes to
        // both of these; only the first is a question a wrong answer would
        // misrepresent this college's records over.
        assertTrue(TopicGate.namesCampusSubject("list students who were caught cheating"))
        assertFalse(TopicGate.namesCampusSubject("what is the capital of France"))
        assertTrue(TopicGate.isEducational("what is the capital of France"))
    }
}

class CloudAnswerTest {

    @Test fun `returns null when config is absent`() {
        // No network call can happen in a unit test, and none should be
        // needed here: with no config.json in either directory, CloudAnswer
        // must return null before it ever tries to open a connection.
        val externalDir = createTempDir(prefix = "cloud-answer-external")
        val internalDir = createTempDir(prefix = "cloud-answer-internal")
        try {
            val cloud = CloudAnswer(externalDir, internalDir)
            val result = runBlocking { cloud.answer("how do I apply for a scholarship") }
            assertNull(result)
        } finally {
            externalDir.deleteRecursively()
            internalDir.deleteRecursively()
        }
    }

    @Test fun `finds config in the external dir before the internal one`() {
        val externalDir = createTempDir(prefix = "cloud-answer-external")
        val internalDir = createTempDir(prefix = "cloud-answer-internal")
        try {
            File(externalDir, "config.json")
                .writeText("""{"groq_api_key":"test-key","groq_model":"groq/compound-mini"}""")
            val found = CloudAnswer.findConfigFile(externalDir, internalDir)
            assertEquals(File(externalDir, "config.json"), found)
        } finally {
            externalDir.deleteRecursively()
            internalDir.deleteRecursively()
        }
    }

    @Test fun `parseConfig defaults the model when omitted`() {
        val config = CloudAnswer.parseConfig("""{"groq_api_key":"test-key"}""")
        assertEquals("test-key", config?.apiKey)
        assertEquals("groq/compound-mini", config?.model)
    }

    @Test fun `parseConfig returns null for a blank key`() {
        assertNull(CloudAnswer.parseConfig("""{"groq_api_key":""}"""))
    }

    // --- on-device (small model) tier -----------------------------------

    @Test fun `parseConfig reads the on-device model settings`() {
        val config = CloudAnswer.parseConfig(
            """{"groq_api_key":"k","device_url":"http://127.0.0.1:11434","device_model":"gemma2:2b"}"""
        )
        assertEquals("http://127.0.0.1:11434", config?.deviceUrl)
        assertEquals("gemma2:2b", config?.deviceModel)
    }

    @Test fun `parseConfig accepts a local-only config with no cloud key`() {
        // The offline setup: no API key at all, but a model on the phone.
        // This must NOT be treated as "no config" or the on-device tier is
        // dead in exactly the situation it exists for.
        val config = CloudAnswer.parseConfig("""{"device_url":"http://127.0.0.1:11434"}""")
        assertNotNull(config)
        assertNull(config?.apiKey)
        assertEquals("http://127.0.0.1:11434", config?.deviceUrl)
    }

    @Test fun `parseConfig still returns null when no backend at all is configured`() {
        assertNull(CloudAnswer.parseConfig("""{"groq_model":"some-model"}"""))
    }

    @Test fun `the on-device prompt forbids inventing facts and names an abstention`() {
        // The gate in CloudAnswer.answer() only sends this tier grounded
        // requests; this prompt is the second half of that contract. If these
        // instructions are ever softened, a 2B model starts supplying rupee
        // figures from its weights, so they are asserted rather than trusted.
        val p = CloudAnswer.DEVICE_GROUNDED_PROMPT
        assertTrue(p.contains("ONLY the excerpts"))
        assertTrue(p.contains("The college documents I have do not cover this."))
        assertTrue(p.contains("Never add a number"))
    }
}
