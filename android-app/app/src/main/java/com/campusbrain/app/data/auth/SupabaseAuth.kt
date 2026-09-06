package com.campusbrain.app.data.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.random.Random

/**
 * Sign-up, sign-in and token refresh against Supabase GoTrue, over plain
 * `HttpURLConnection` and `org.json`.
 *
 * No new Gradle dependency, and that is a measurement rather than a taste:
 * `supabase-kt` was weighed at +4-7 MB unminified on a 49 MB APK for six HTTP
 * calls. `answer/CloudAnswer.kt` made the same call for the same reason and
 * this file follows its shape deliberately -- a companion-level [Mutex] so
 * concurrent callers cannot double-rotate a refresh token, and every failure
 * path returning null instead of throwing into the caller, because every
 * caller here has a working offline answer to fall back to.
 *
 * ## What is allowed to cross the wire from this file
 *
 * An email address, a password, an enrolment code, and tokens. Nothing else.
 * No query text, no corpus content, no retrieved chunk, no student record.
 * That boundary is the product's entire commercial claim and it is enforced by
 * the fact that no method here accepts any of those things as a parameter.
 */
class SupabaseAuth(
    private val config: AuthConfig,
    private val store: EntitlementStore?,
) {

    /** Sign-in and sign-up have the same three outcomes worth distinguishing. */
    sealed interface Outcome {
        data class Ok(val session: EntitlementStore.Session) : Outcome
        /** The server answered and said no: wrong password, weak password,
         * address already registered. Retrying identically will not help. */
        data class Rejected(val message: String) : Outcome
        /** No answer at all: airplane mode, campus wifi behind a portal, DNS.
         * Retrying later is exactly the right move. */
        data object Unavailable : Outcome
    }

    /**
     * Creates the account. `mailer_autoconfirm` is on for this project, so no
     * confirmation email is sent and tokens come back on this response.
     *
     * That is not a convenience, it is the only workable design: Supabase's
     * built-in SMTP is capped at 2 emails per hour PROJECT-WIDE, so verifying
     * 400 students by email would take 200 hours. Enrolment is proved by the
     * institution's own code instead -- see [ControlPlane.redeem]. Phone OTP
     * was rejected on a harder ground than cost: it would ship every student's
     * number to Twilio, a third-party sub-processor, which is the boundary this
     * product exists to hold.
     */
    suspend fun signUp(email: String, password: String): Outcome =
        tokenCall("${config.authBase}/signup", JSONObject().apply {
            put("email", email)
            put("password", password)
        })

    suspend fun signIn(email: String, password: String): Outcome =
        tokenCall("${config.authBase}/token?grant_type=password", JSONObject().apply {
            put("email", email)
            put("password", password)
        })

    /**
     * A currently-valid access token, refreshing first if it is close to
     * expiry. Null when there is no session, or when the device is offline and
     * the stored token has aged out.
     *
     * **Nothing on the retrieval path may call this.** It exists for the four
     * control-plane reads. A null here means "we could not talk to the server",
     * which must never be allowed to mean "the student cannot ask a question".
     */
    suspend fun accessToken(nowMs: Long = System.currentTimeMillis()): String? {
        val current = store?.loadSession() ?: return null
        if (!needsRefresh(current.expiresAtEpochSec, nowMs)) return current.accessToken
        return refresh(nowMs)
    }

    /**
     * Rotates the refresh token, serialised so two screens waking at once
     * cannot both spend it.
     *
     * The re-read after taking the lock is the point of the lock: the caller
     * that waited would otherwise rotate a token the first caller has already
     * replaced. Supabase's own behaviour makes this survivable rather than
     * fatal -- a replayed refresh token returns the successor that was already
     * issued rather than revoking the family, which is why a refresh response
     * lost to flaky campus wifi recovers on the next attempt instead of
     * signing the student out. This lock means the app does not rely on that.
     */
    suspend fun refresh(nowMs: Long = System.currentTimeMillis()): String? = refreshGate.withLock {
        val local = store ?: return@withLock null
        val session = local.loadSession() ?: return@withLock null
        // Someone else refreshed while this call was queued. Take theirs.
        if (!needsRefresh(session.expiresAtEpochSec, nowMs)) return@withLock session.accessToken

        val outcome = tokenCall(
            "${config.authBase}/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", session.refreshToken),
            nowMs,
        )
        when (outcome) {
            is Outcome.Ok -> outcome.session.accessToken
            // The server rejected the refresh token outright, so it is dead and
            // keeping it only produces a failing call every hour. The
            // ENTITLEMENT is untouched: it is the durable grant, the token is
            // not, and a dead token is not evidence that a student stopped
            // being enrolled.
            is Outcome.Rejected -> { local.clearSession(); null }
            // Offline. Keep everything: this is the ordinary state.
            Outcome.Unavailable -> null
        }
    }

    /** Forgets tokens AND the grant. Only for an explicit sign-out -- an
     * expiring token must never take this path. */
    fun signOut() {
        store?.clearSession()
        store?.clear()
    }

    private suspend fun tokenCall(
        url: String,
        body: JSONObject,
        nowMs: Long = System.currentTimeMillis(),
    ): Outcome = withContext(Dispatchers.IO) {
        val response = SupabaseHttp.post(url, body, config.anonKey, bearer = null)
            ?: return@withContext Outcome.Unavailable
        if (response.code !in 200..299) {
            return@withContext when (classifyFailure(response.code, response.body)) {
                Failure.DEFINITIVE -> Outcome.Rejected(SupabaseHttp.errorMessage(response.body))
                Failure.TRANSIENT -> Outcome.Unavailable
            }
        }
        val session = parseTokenResponse(response.body, nowMs)
            // A 2xx whose body is not a token response. On campus this is
            // almost always a captive portal answering 200 with an HTML login
            // page, so it is TRANSIENT: it is not evidence that a refresh token
            // is dead, and treating it as such would destroy a working session
            // over a wifi splash screen. (It would also be what GoTrue returns
            // if this project's mailer_autoconfirm were turned off, which the
            // same retry would surface as a persistent failure to enrol.)
            ?: return@withContext Outcome.Unavailable
        store?.saveSession(session)
        Outcome.Ok(session)
    }

    /**
     * Whether a failed call is evidence the credentials are dead, or just
     * evidence that the network is having a day.
     *
     * The distinction is load-bearing because [refresh] destroys the stored
     * session on DEFINITIVE and keeps it on TRANSIENT, and getting it wrong
     * in the permissive direction costs a student their ability to renew.
     * Retrying is measurably safe on this project: replaying a refresh
     * token returns the successor that was already issued rather than
     * revoking the family, so a lost or garbled response recovers on the
     * next attempt. Erring toward TRANSIENT therefore costs one wasted
     * call; erring toward DEFINITIVE costs the session.
     *
     * Declared on the class rather than in the companion below, so that it
     * is named `SupabaseAuth.Failure`. Nested inside the companion it would
     * be `SupabaseAuth.Companion.Failure`, which reads as an implementation
     * detail leaking into every call site — and does not resolve under the
     * obvious spelling at all.
     */
    enum class Failure { DEFINITIVE, TRANSIENT }

    companion object {
        /** Process-wide, matching CloudAnswer's rate-limit gate: one device,
         * one refresh-token family, one rotation at a time. */
        private val refreshGate = Mutex()

        /**
         * Refresh this many seconds early. Covers the round trip plus a device
         * clock that is a little fast, so a call is not made with a token that
         * expires while it is in flight.
         */
        const val REFRESH_SKEW_SEC = 120L

        fun classifyFailure(status: Int, body: String): Failure = when {
            // 2xx handled by the caller; here for completeness of the mapping.
            status in 200..299 -> Failure.TRANSIENT
            // The server is up and said no, in its own words. A wrong password
            // or a revoked refresh token lands here and will land here again.
            status in 400..403 && looksLikeAuthError(body) -> Failure.DEFINITIVE
            // A 4xx with no error body did not come from GoTrue -- it came from
            // a proxy, a portal, or a middlebox.
            else -> Failure.TRANSIENT
        }

        /** True for a body that is recognisably GoTrue's or PostgREST's error
         * shape, rather than a proxy's HTML. */
        fun looksLikeAuthError(body: String): Boolean = runCatching {
            val json = JSONObject(body)
            listOf("error", "error_description", "error_code", "msg", "message", "code")
                .any { json.optString(it).isNotBlank() }
        }.getOrDefault(false)

        /**
         * Pure, so the refresh decision is testable without a network.
         *
         * `expiresAtEpochSec == 0` means "unknown", which is treated as due:
         * the worst case is one wasted refresh, whereas treating unknown as
         * fresh would send a dead token and fail the call.
         */
        fun needsRefresh(
            expiresAtEpochSec: Long,
            nowMs: Long,
            skewSec: Long = REFRESH_SKEW_SEC,
        ): Boolean = expiresAtEpochSec <= 0L || (nowMs / 1000L) + skewSec >= expiresAtEpochSec

        /**
         * Reads a GoTrue token response into a [EntitlementStore.Session].
         * Null unless both tokens are present -- a half-session is worse than
         * none, because it would overwrite a working one.
         */
        fun parseTokenResponse(
            body: String,
            nowMs: Long = System.currentTimeMillis(),
        ): EntitlementStore.Session? = runCatching {
            val json = JSONObject(body)
            val access = json.optString("access_token").takeIf { it.isNotBlank() } ?: return null
            val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() } ?: return null
            // Three sources for expiry, most authoritative first: the server's
            // own absolute `expires_at`, then the JWT's `exp`, then the
            // relative `expires_in` against this device's clock.
            val expiresAt = json.optLong("expires_at", 0L)
                .takeIf { it > 0L }
                ?: jwtExpiry(access)
                ?: json.optLong("expires_in", 0L).takeIf { it > 0L }?.let { nowMs / 1000L + it }
                ?: 0L
            EntitlementStore.Session(
                userId = json.optJSONObject("user")?.optString("id")?.takeIf { it.isNotBlank() }
                    ?: jwtSubject(access),
                accessToken = access,
                refreshToken = refresh,
                expiresAtEpochSec = expiresAt,
            )
        }.getOrNull()

        /**
         * The `exp` claim, in unix seconds, or null if the token is not a JWT
         * this can read.
         *
         * Read locally and **not verified**. This is a scheduling hint -- it
         * decides when to ask for a new token -- and nothing here treats it as
         * a security control. The signature that matters is checked by
         * PostgREST on every request; a forged claim dies there, which is
         * where it should die.
         *
         * `java.util.Base64`, not `android.util.Base64`: the latter is a stub
         * in the JVM unit-test android.jar and throws, which would make this
         * function untestable off a device. URL-safe alphabet, and the padding
         * is added back because JWT segments ship unpadded.
         */
        fun jwtExpiry(token: String): Long? = jwtClaims(token)
            ?.optLong("exp", 0L)?.takeIf { it > 0L }

        fun jwtSubject(token: String): String? = jwtClaims(token)
            ?.optString("sub")?.takeIf { it.isNotBlank() }

        fun jwtClaims(token: String): JSONObject? = runCatching {
            val parts = token.split('.')
            if (parts.size < 2) return null
            val payload = parts[1]
            val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
            JSONObject(String(Base64.getUrlDecoder().decode(padded), Charsets.UTF_8))
        }.getOrNull()

        /**
         * An address for a student who does not want to give one.
         *
         * GoTrue requires an identifier and this app has no use for it: the
         * enrolment code is what proves the student belongs to the institution,
         * and no mail is ever sent to this address because `mailer_autoconfirm`
         * is on. `.invalid` is reserved by RFC 2606 precisely so an address
         * that must never receive mail cannot accidentally reach a real inbox.
         *
         * Random, not derived from the enrolment code: two students sharing a
         * multi-use code must not collide on an address, and a derived address
         * would leak the code into a value the server stores in clear.
         */
        fun syntheticEmail(
            domain: String = AuthConfig.DEFAULT_SYNTHETIC_DOMAIN,
            random: Random = Random.Default,
        ): String {
            val local = (1..16).map { LOCAL_ALPHABET[random.nextInt(LOCAL_ALPHABET.length)] }
                .joinToString("")
            return "s$local@$domain"
        }

        private const val LOCAL_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** Cheap sanity check so an obvious typo is caught before a round trip. */
        fun looksLikeEmail(value: String): Boolean =
            Regex("""^[^\s@]+@[^\s@.]+(\.[^\s@.]+)+$""").matches(value.trim())
    }
}

/**
 * The four lines of `HttpURLConnection` boilerplate every call in this package
 * needs, in one place.
 *
 * Kept out of [SupabaseAuth] so that class is only about tokens, and kept
 * deliberately thin: everything that could be got wrong -- the refresh
 * decision, the response parse, the usage payload -- lives in a pure function
 * with a unit test, and what remains here is a connection, a write, and a read.
 */
internal object SupabaseHttp {

    data class Response(val code: Int, val body: String)

    /**
     * Short on purpose. A control-plane read that is slow is indistinguishable
     * from a control-plane read that will never arrive, and in both cases the
     * right answer is to carry on offline.
     */
    private const val TIMEOUT_MS = 8_000

    fun post(url: String, body: JSONObject, apiKey: String, bearer: String?): Response? =
        call("POST", url, body, apiKey, bearer, extraHeaders = emptyMap())

    fun postWithHeaders(
        url: String,
        body: JSONObject,
        apiKey: String,
        bearer: String?,
        extraHeaders: Map<String, String>,
    ): Response? = call("POST", url, body, apiKey, bearer, extraHeaders)

    fun get(url: String, apiKey: String, bearer: String?): Response? =
        call("GET", url, null, apiKey, bearer, emptyMap())

    private fun call(
        method: String,
        url: String,
        body: JSONObject?,
        apiKey: String,
        bearer: String?,
        extraHeaders: Map<String, String>,
    ): Response? {
        val connection = runCatching { URL(url).openConnection() as HttpURLConnection }
            .getOrNull() ?: return null
        return try {
            connection.requestMethod = method
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("apikey", apiKey)
            connection.setRequestProperty("Accept", "application/json")
            // CloudAnswer measured a 403 from the default Java URLConnection
            // user agent against one provider; naming the client costs nothing
            // and removes a whole class of confusing failure.
            connection.setRequestProperty("User-Agent", "campus-brain/1.0")
            // The anon key doubles as the bearer when there is no user yet,
            // which is what GoTrue expects on sign-up and sign-in.
            connection.setRequestProperty("Authorization", "Bearer ${bearer ?: apiKey}")
            extraHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            // The error stream carries PostgREST's SQLSTATE, which is how an
            // "already enrolled" is told apart from a bad code. Discarding it
            // would collapse two outcomes that need different words on screen.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Response(code, text)
        } catch (t: Throwable) {
            // Offline, DNS failure, TLS failure, timeout. Indistinguishable
            // from here and identical in consequence: carry on offline.
            null
        } finally {
            connection.disconnect()
        }
    }

    /** GoTrue and PostgREST both return a JSON body on failure, with the human
     * part under a different key each. Falls back to the raw body, truncated,
     * so a captive portal's HTML does not end up on screen in full. */
    fun errorMessage(body: String): String = runCatching {
        val json = JSONObject(body)
        listOf("error_description", "msg", "message", "error")
            .firstNotNullOfOrNull { json.optString(it).takeIf { s -> s.isNotBlank() } }
    }.getOrNull() ?: body.take(120).ifBlank { "request failed" }

    /** The SQLSTATE PostgREST reports for an exception raised inside a
     * function, e.g. `23505` from redeem_enrolment_code's already-enrolled
     * branch. Null when the body is not a PostgREST error. */
    fun sqlState(body: String): String? = runCatching {
        JSONObject(body).optString("code").takeIf { it.isNotBlank() }
    }.getOrNull()
}
