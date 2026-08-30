package com.kriet.campusbrain

import com.kriet.campusbrain.answer.CloudAnswer
import com.kriet.campusbrain.answer.TopicGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
