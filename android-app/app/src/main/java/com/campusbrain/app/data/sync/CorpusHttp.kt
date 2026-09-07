package com.campusbrain.app.data.sync

import com.campusbrain.app.data.auth.AuthConfig
import com.campusbrain.app.data.auth.SupabaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * The corpus half of the PostgREST surface, behind an interface so the sync
 * logic can be tested without a socket.
 *
 * `data/auth/ControlPlane` lists the five calls the app was allowed to make
 * and notes that none of them can carry a document. These calls carry
 * documents, deliberately and in both directions, which is why they are here
 * and not there -- the two files hold different promises and merging them
 * would blur the one that matters. The promise this file makes instead:
 *
 *  - **nothing on it is reachable from the ask path.** [CorpusSync] is called
 *    at a lifecycle boundary or from an explicit refresh, never from
 *    `QueryRouter`, and a failure here changes nothing about what the phone
 *    can answer;
 *  - **no question, no answer and no query text ever goes up.** The upload
 *    direction carries documents the registrar chose to publish to the whole
 *    institution. There is no method here that takes a query;
 *  - **no tenant id is ever sent.** `tenant_id` defaults to
 *    `current_tenant_id()` server-side and is not even grantable to a client.
 *
 * `HttpURLConnection` and `org.json`, following
 * `data/auth/SupabaseAuth.SupabaseHttp`. No new Gradle dependency: that file
 * measured `supabase-kt` at +4-7 MB unminified for six HTTP calls, and this is
 * four more of the same shape.
 */
interface CorpusApi {

    data class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    /** [path] is relative to the PostgREST base, e.g.
     *  `corpus_documents?select=doc_id&revision=gt.4`. */
    suspend fun get(path: String): Response?

    /** [body] is a JSON array of rows. */
    suspend fun post(path: String, body: String, prefer: String = "return=minimal"): Response?

    /** [body] is a JSON object of columns to set. */
    suspend fun patch(path: String, body: String): Response?

    suspend fun delete(path: String): Response?
}

/** The real one. */
class SupabaseCorpusApi(
    private val config: AuthConfig,
    private val auth: SupabaseAuth,
) : CorpusApi {

    override suspend fun get(path: String): CorpusApi.Response? = call("GET", path, null, null)

    override suspend fun post(path: String, body: String, prefer: String): CorpusApi.Response? =
        call("POST", path, body, prefer)

    override suspend fun patch(path: String, body: String): CorpusApi.Response? =
        call("PATCH", path, body, "return=minimal")

    override suspend fun delete(path: String): CorpusApi.Response? = call("DELETE", path, null, null)

    private suspend fun call(
        method: String,
        path: String,
        body: String?,
        prefer: String?,
    ): CorpusApi.Response? = withContext(Dispatchers.IO) {
        // accessToken() refreshes if it needs to and returns null offline,
        // which becomes "no answer" here and "carry on with the corpus we
        // have" at the top.
        val token = auth.accessToken() ?: return@withContext null
        val connection = runCatching {
            URL("${config.restBase}/$path").openConnection() as HttpURLConnection
        }.getOrNull() ?: return@withContext null
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            // Longer than the control plane's 8s. A corpus page is up to a
            // megabyte of embeddings on a phone connection, where 8 seconds is
            // an ordinary transfer rather than evidence of a dead link -- and
            // the cost of being wrong is not a stalled screen, it is one page
            // retried on the next sync.
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("apikey", config.anonKey)
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "campus-brain/1.0")
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream =
                if (code in 200..299) connection.inputStream else connection.errorStream
            CorpusApi.Response(code, stream?.bufferedReader()?.use { it.readText() } ?: "")
        } catch (t: Throwable) {
            // Offline, DNS, TLS, timeout. Indistinguishable from here and
            // identical in consequence: the phone keeps the corpus it has.
            // Never logged with a body -- a corpus page is institution content
            // and a header carries a bearer token.
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
