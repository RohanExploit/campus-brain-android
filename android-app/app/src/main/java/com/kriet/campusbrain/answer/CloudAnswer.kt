package com.kriet.campusbrain.answer

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * A single fallback call to Groq's OpenAI-compatible chat-completions
 * endpoint, used only when AnswerComposer has already abstained and
 * TopicGate has judged the question in scope for "college and campus life".
 *
 * Deliberately built on java.net.HttpURLConnection rather than a new Gradle
 * dependency -- this project keeps its dependency list short on purpose, and
 * the JDK client is enough for one JSON POST.
 *
 * No Gradle dependency change of any kind was made for this file.
 */
class CloudAnswer(
    /** Where BrainDb would find the corpus DB: app-external files dir first. */
    private val externalConfigDir: File?,
    /** Falls back here, matching BrainDb's external-then-internal order. */
    private val internalConfigDir: File,
) {

    /** Convenience constructor mirroring how BrainRepository hands BrainDb
     * a Context: external files dir, then internal files dir. */
    constructor(context: Context) : this(context.getExternalFilesDir(null), context.filesDir)

    data class Config(val apiKey: String, val model: String)

    /**
     * Reads Groq credentials off the device. A missing file is the normal,
     * expected, offline-by-default state -- not an error -- so this returns
     * null rather than throwing. Nothing here ever writes the key back out,
     * and the key never appears in source, logs, or trace output.
     */
    private fun loadConfig(): Config? = runCatching {
        val file = findConfigFile(externalConfigDir, internalConfigDir) ?: return null
        parseConfig(file.readText())
    }.getOrNull()

    /**
     * Runs the whole round trip: load config, rate-limit, POST, parse.
     * Returns null on any failure whatsoever -- missing config, no network,
     * a timeout, a non-2xx response, unparsable JSON -- so a caller can
     * always fall back to the existing abstention text without special
     * cases. Never throws into the caller.
     */
    suspend fun answer(query: String): String? = runCatching {
        val config = loadConfig() ?: return null
        withContext(Dispatchers.IO) {
            rateLimitGate.withLock {
                waitForRateLimitSlot()
                val result = call(config, query)
                lastCallAtMs = System.currentTimeMillis()
                result
            }
        }
    }.getOrNull()

    private suspend fun waitForRateLimitSlot() {
        // The free tier returned 429 after six calls in quick succession
        // during testing. A flat minimum gap between calls, serialized
        // through the mutex above, keeps a fast demo under that ceiling
        // without needing to track a rolling request count.
        val elapsed = System.currentTimeMillis() - lastCallAtMs
        val remaining = MIN_CALL_GAP_MS - elapsed
        if (remaining > 0) delay(remaining)
    }

    private fun call(config: Config, query: String): String? {
        val anthropic = config.apiKey.startsWith("sk-ant-")
        val endpoint = if (anthropic) ANTHROPIC_ENDPOINT else ENDPOINT
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            // Verified: Groq returns 403 for the default Java URLConnection user
            // agent, so this is not optional on either provider.
            connection.setRequestProperty("User-Agent", "campus-brain/1.0")

            val payload: JSONObject
            if (anthropic) {
                // Anthropic uses its own auth header and a top-level `system`
                // field rather than a system message inside `messages`.
                connection.setRequestProperty("x-api-key", config.apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                payload = JSONObject().apply {
                    put("model", config.model)
                    put("max_tokens", 400)
                    put("system", SYSTEM_PROMPT)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", query))
                    })
                }
            } else {
                connection.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                payload = JSONObject().apply {
                    put("model", config.model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                        put(JSONObject().put("role", "user").put("content", query))
                    })
                }
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (anthropic) {
                json.optJSONArray("content")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            } else {
                json.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }
        } catch (t: Throwable) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        private const val ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages"
        private const val DEFAULT_MODEL = "groq/compound-mini"
        private const val CONFIG_FILE_NAME = "config.json"
        private const val TIMEOUT_MS = 10_000
        private const val MIN_CALL_GAP_MS = 1_500L

        // Shared across every CloudAnswer instance in the process, matching
        // "serialise calls with a mutex" -- one demo device, one Groq key,
        // one clock the 1.5s gap is measured against.
        private val rateLimitGate = Mutex()
        @Volatile private var lastCallAtMs = 0L

        val SYSTEM_PROMPT = """
            You are answering a question for a student at an Indian engineering
            college affiliated to a state technological university. Use the
            Indian higher-education context: AICTE/UGC norms, fees in rupees,
            the Indian semester structure. Be concrete about the process the
            student should actually follow. Answer in 3-5 sentences.

            Do not name a specific portal, website, or URL unless it is a
            genuinely well-known national one (for example, the National
            Scholarship Portal for scholarships). Otherwise say "your
            college's student portal" or "the examination section". In prior
            testing this model invented plausible-but-wrong portal names --
            it suggested the National Scholarship Portal for a bonafide
            certificate, and produced a placeholder URL as though it were
            real. Facts about process, percentages, and timelines were
            reliable; invented portal names and URLs were not. Stay with the
            former and avoid the latter.
        """.trimIndent()

        /**
         * Finds config.json exactly the way BrainDb locates the corpus
         * database bundle: external files dir first, internal files dir
         * second. Exposed as a pure function (File in, File? out) so it is
         * unit-testable without an Android Context or Robolectric.
         */
        fun findConfigFile(externalDir: File?, internalDir: File): File? {
            val external = externalDir?.let { File(it, CONFIG_FILE_NAME) }
            if (external != null && external.exists()) return external
            val internal = File(internalDir, CONFIG_FILE_NAME)
            return if (internal.exists()) internal else null
        }

        /** Parses {"groq_api_key": "...", "groq_model": "..."}. The model
         * key is optional and defaults to groq/compound-mini; a missing or
         * blank api key means "no config", same as a missing file. */
        fun parseConfig(text: String): Config? = runCatching {
            val json = JSONObject(text)
            val key = json.optString("groq_api_key").takeIf { it.isNotBlank() } ?: return null
            val model = json.optString("groq_model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
            Config(key, model)
        }.getOrNull()
    }
}
