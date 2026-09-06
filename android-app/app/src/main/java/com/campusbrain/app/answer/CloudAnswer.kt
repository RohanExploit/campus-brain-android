package com.campusbrain.app.answer

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

    data class Config(
        /** Null when the device is configured with no cloud key at all, which
         * is a supported setup: the local tiers below still work. */
        val apiKey: String? = null,
        val model: String = DEFAULT_MODEL,
        /** Optional laptop Ollama, e.g. http://10.0.0.5:11434. Null disables it. */
        val ollamaUrl: String? = null,
        val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
        /** Optional on-device Ollama (Termux), normally http://127.0.0.1:11434.
         * Null disables it. See the gating rule in [answer]: this tier is only
         * ever asked to rephrase retrieved text, never to supply facts. */
        val deviceUrl: String? = null,
        val deviceModel: String = DEFAULT_DEVICE_MODEL,
    )

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
    /**
     * [text] is what the student sees. [grounded] is true only when the model
     * reported that the retrieved excerpts supplied the main facts, which is
     * what the caller's provenance label must key on.
     */
    data class Answer(val text: String, val grounded: Boolean)

    /** Kept for callers that do not care about provenance. */
    suspend fun answer(query: String, context: String? = null): String? =
        answerWithProvenance(query, context)?.text

    suspend fun answerWithProvenance(query: String, context: String? = null): Answer? = runCatching {
        val config = loadConfig() ?: return null
        withContext(Dispatchers.IO) {
            rateLimitGate.withLock {
                waitForRateLimitSlot()
                // This is a RAG app, not a chatbot -- the model must ground on
                // whatever the retrieval pass found before it reaches for its
                // own training knowledge. AnswerComposer abstained because it
                // was not confident enough to state the passages as fact on
                // its own, but the passages it found are still the best
                // evidence available and go in first.
                val draftQuery = if (context.isNullOrBlank()) query else
                    "Retrieved excerpts from the college's own documents " +
                        "(may be partial or not a perfect match):\n" + context +
                        "\n\nQuestion: " + query +
                        "\n\nAnswer from the excerpts above wherever they cover it. " +
                        "Only add general Indian higher-education knowledge for parts " +
                        "the excerpts do not cover." +
                        // Retrieval returns the top chunks whether or not they are
                        // relevant, so "we supplied context" does not mean "the
                        // answer came from it" -- a revaluation question pulled an
                        // unrelated research paper and was correctly answered from
                        // general knowledge. Only the model knows which it did, so
                        // it is asked, and the caller labels the answer accordingly.
                        "\n\nFinally, on its own last line, write exactly " +
                        "\"" + GROUNDED_MARKER + " YES\" if the excerpts supplied the " +
                        "main facts of your answer, or \"" + GROUNDED_MARKER + " NO\" if " +
                        "they did not cover the question and you answered from general " +
                        "knowledge."
                // Tier order is cloud, then laptop, then this phone. The last
                // tier is GATED ON HAVING CONTEXT, and that gate is the whole
                // safety argument for shipping a small model at all.
                //
                // A ~0.5-2B model is adequate at restating facts that are
                // already sitting in its context window, and unreliable at
                // producing them from its weights. Those are different jobs.
                // Grounded, it rephrases the college's own retrieved text.
                // Ungrounded, it would invent a scholarship amount or a fee
                // figure in the same confident register as a correct one, and
                // a student would act on it. So when there is no context,
                // this tier is not consulted and the app abstains instead --
                // abstaining is recoverable, a wrong rupee figure is not.
                val hasContext = !context.isNullOrBlank()
                val rawDraft = call(config, draftQuery)
                    ?: callOllama(config, draftQuery, SYSTEM_PROMPT)
                    ?: if (hasContext) callDevice(config, draftQuery, DEVICE_GROUNDED_PROMPT) else null
                lastCallAtMs = System.currentTimeMillis()
                // Read the marker off the draft and strip it before anything
                // else sees it. The verifier pass rewrites freely and would
                // drop it, so provenance has to be captured here.
                val grounded = hasContext && markerSaysGrounded(rawDraft)
                val draft = rawDraft?.let(::stripMarker)
                if (draft == null) null else {
                    // Second pass: the model checks its own draft before a
                    // student sees it. This is a measured need, not ceremony.
                    // Drafts named the National Scholarship Portal (which is
                    // scholarships only) as the place to get a bonafide
                    // certificate, and produced "university-exam.in" as though
                    // it were a real site. Those are exactly the parts a
                    // student acts on, and acting on them leads nowhere.
                    waitForRateLimitSlot()
                    val prompt = "Question: " + query + "\n\nDraft answer:\n" + draft
                    // The phone tier deliberately does NOT verify. Verification
                    // means judging whether a claim is wrong, which is exactly
                    // the reasoning a small model is weakest at -- letting it
                    // rewrite a good cloud draft would more likely corrupt the
                    // draft than improve it. If the two capable tiers are gone,
                    // the draft ships as-is.
                    val verified = call(config, prompt, VERIFIER_PROMPT)
                        ?: callOllama(config, prompt, VERIFIER_PROMPT)
                    lastCallAtMs = System.currentTimeMillis()
                    // Keep the draft if verification fails for any reason: a
                    // slightly loose answer beats no answer at all.
                    Answer(stripMarker(verified ?: draft), grounded)
                }
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

    private fun call(config: Config, query: String, system: String = SYSTEM_PROMPT): String? {
        // No key configured is a supported state, not an error: the caller
        // falls straight through to the local tiers.
        val apiKey = config.apiKey ?: return null
        val anthropic = apiKey.startsWith("sk-ant-")
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
                connection.setRequestProperty("x-api-key", apiKey)
                connection.setRequestProperty("anthropic-version", "2023-06-01")
                payload = JSONObject().apply {
                    put("model", config.model)
                    put("max_tokens", 400)
                    put("system", system)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "user").put("content", query))
                    })
                }
            } else {
                connection.setRequestProperty("Authorization", "Bearer $apiKey")
                payload = JSONObject().apply {
                    put("model", config.model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().put("role", "system").put("content", system))
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

    /**
     * Laptop Ollama over the LAN, used only when the cloud call has already
     * failed. Same contract as [call]: null on any problem, never throws.
     * Timeout is far longer than the cloud's -- a 4B model on a laptop GPU is
     * slower than an API, and having got this far a slow answer beats none.
     */
    /** Laptop tier: the LAN host named by ollama_url. */
    private fun callOllama(config: Config, query: String, system: String): String? =
        callOllamaAt(config.ollamaUrl, config.ollamaModel, query, system, OLLAMA_TIMEOUT_MS)

    /** On-device tier: Ollama inside Termux on this phone. A shorter read
     * timeout than the laptop's -- a small model on phone silicon either
     * answers quickly or is not going to finish inside a demo. */
    private fun callDevice(config: Config, query: String, system: String): String? =
        callOllamaAt(config.deviceUrl, config.deviceModel, query, system, DEVICE_TIMEOUT_MS)

    private fun callOllamaAt(
        url: String?,
        model: String,
        query: String,
        system: String,
        readTimeoutMs: Int,
    ): String? {
        val base = url?.trimEnd('/') ?: return null
        val connection = URL("$base/api/generate").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            val payload = JSONObject().apply {
                put("model", model)
                put("prompt", system + "\n\n" + query)
                put("stream", false)
                put("options", JSONObject().put("temperature", 0))
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("response").trim().takeIf { it.isNotEmpty() }
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
        // 5s, not 10. A stalled cloud call must fall through to the laptop
        // model fast enough that a demo does not visibly hang.
        /** Sentinel the draft pass appends so the caller can label provenance. */
        const val GROUNDED_MARKER = "GROUNDED:"

        /** True only for an explicit "GROUNDED: YES". A missing or malformed
         * marker is treated as not grounded, so the weaker claim is the one
         * made when the model did not answer the question. */
        fun markerSaysGrounded(text: String?): Boolean =
            text != null && Regex(
                """^\s*${Regex.escape(GROUNDED_MARKER)}\s*YES\b""",
                setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
            ).containsMatchIn(text)

        /** Removes the marker line wherever it landed. Students never see it. */
        fun stripMarker(text: String): String = text
            .replace(
                Regex(
                    """^\s*${Regex.escape(GROUNDED_MARKER)}\s*(YES|NO)\b.*$""",
                    setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE)
                ),
                ""
            )
            .trim()

        private const val TIMEOUT_MS = 5_000
        private const val OLLAMA_TIMEOUT_MS = 30_000
        private const val DEVICE_TIMEOUT_MS = 20_000
        private const val DEFAULT_OLLAMA_MODEL = "qwen3:4b-instruct-2507-q4_K_M"
        private const val DEFAULT_DEVICE_MODEL = "gemma2:2b"
        private const val MIN_CALL_GAP_MS = 1_500L

        // Shared across every CloudAnswer instance in the process, matching
        // "serialise calls with a mutex" -- one demo device, one Groq key,
        // one clock the 1.5s gap is measured against.
        private val rateLimitGate = Mutex()
        @Volatile private var lastCallAtMs = 0L

        val VERIFIER_PROMPT = """
            You rewrite a draft answer for an Indian engineering student so it reads as
            a finding, not an errand.

            STATE THE FACTS DECLARATIVELY. Never tell the student to "visit", "go to",
            "contact", "check with" or "reach out to" anyone. Sending a student away is
            not an answer -- it is the problem they opened the app to avoid. Say what the
            process IS and who owns it: "The bonafide certificate is issued by the
            Student Affairs office within 2-3 working days and costs Rs 50-100", never
            "Visit the Student Affairs office to get a bonafide certificate".

            KEEP every concrete detail: percentages, fee ranges, document names, office
            names, timelines, statutory helplines, and genuinely national portals such
            as the National Scholarship Portal for scholarships. Never add hedges like
            "if one is available" or "this may vary" to things that are standard across
            Indian institutions.

            Correct ONLY these, because a student acts on them and is sent nowhere:
            1. A portal named for the wrong purpose. The National Scholarship Portal is
               scholarships only, never bonafide certificates, examinations or placements.
            2. Invented or placeholder URLs.

            Reply with the rewritten answer only, 3-5 sentences, no preamble, and no
            mention that anything was changed.
        """.trimIndent()

        /**
         * The on-device tier's prompt. Deliberately far more restrictive than
         * SYSTEM_PROMPT: this model is only ever invoked WITH retrieved
         * excerpts (see the gate in [answer]), and its only job is to restate
         * them. Every instruction here pushes it away from supplying anything
         * of its own, because that is the failure mode a small model has.
         */
        val DEVICE_GROUNDED_PROMPT = """
            You restate information for a student using ONLY the excerpts you
            are given. You are not the source of any fact.

            Rules, in order of importance:
            1. Use ONLY facts that appear in the excerpts. Never add a number,
               fee, percentage, date, office name, portal, or deadline that is
               not written there.
            2. If the excerpts do not answer the question, say exactly:
               "The college documents I have do not cover this."
               Do not guess, and do not fill the gap from general knowledge.
            3. Do not tell the student to visit, go to, or contact anyone.
               State what the documents say.
            4. Keep any notice or reference number exactly as written.

            Answer in 2-4 plain sentences.
        """.trimIndent()

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
            val key = json.optString("groq_api_key").takeIf { it.isNotBlank() }
            val model = json.optString("groq_model").takeIf { it.isNotBlank() } ?: DEFAULT_MODEL
            // Optional. Absent means no laptop fallback, which is the correct
            // default for a device that is meant to work with no host nearby.
            val ollama = json.optString("ollama_url").takeIf { it.isNotBlank() }
            val ollamaModel = json.optString("ollama_model")
                .takeIf { it.isNotBlank() } ?: DEFAULT_OLLAMA_MODEL
            val device = json.optString("device_url").takeIf { it.isNotBlank() }
            val deviceModel = json.optString("device_model")
                .takeIf { it.isNotBlank() } ?: DEFAULT_DEVICE_MODEL
            // A config with no cloud key but a reachable local model is valid,
            // not "no config". Requiring the key here would have silently
            // disabled the on-device tier in exactly the offline setup it
            // exists to serve.
            if (key == null && ollama == null && device == null) return null
            Config(key, model, ollama, ollamaModel, device, deviceModel)
        }.getOrNull()
    }
}
