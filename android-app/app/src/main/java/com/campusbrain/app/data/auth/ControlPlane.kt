package com.campusbrain.app.data.auth

import com.campusbrain.app.data.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The four PostgREST calls this app is allowed to make, and nothing else.
 *
 * Read the list and note what is not on it: there is no endpoint here that
 * takes a question, a retrieved passage, a document, an embedding, or a
 * student's marks. The corpus and the query never leave the device, and the
 * enforcement of that is structural rather than a policy in a comment -- no
 * function below has a parameter that could carry one.
 *
 *  - [redeem]            the SECURITY DEFINER enrolment-code function
 *  - [fetchGrant]        the caller's own membership row + their tenant row
 *  - [fetchCorpusVersion] metadata about a newer corpus (not the corpus)
 *  - [postUsage]         a route label and a latency, aggregate telemetry only
 *
 * Tenancy is never sent. It is resolved server-side by `current_tenant_id()`
 * from `memberships`, so a client cannot claim its way into another
 * institution's rows -- proven against the live project: cross-tenant reads
 * returned `[]` and a privilege-escalation write returned 42501.
 */
class ControlPlane(
    private val config: AuthConfig,
    private val auth: SupabaseAuth,
) {

    // --- enrolment --------------------------------------------------------

    sealed interface RedeemOutcome {
        data class Enrolled(val tenantId: String, val role: String) : RedeemOutcome
        /**
         * This account already has a membership. NOT an error, and specifically
         * not "wrong code": `memberships` is keyed on `user_id`, so a student
         * who reinstalls, signs back in and re-enters their code lands here
         * every time. The caller recovers the existing grant with [fetchGrant]
         * rather than telling a correctly-enrolled student their code is bad.
         */
        data object AlreadyEnrolled : RedeemOutcome
        /**
         * The server looked at the code and said no: unknown, used up, or
         * expired.
         *
         * Carries no message. Nothing renders one -- see the note on
         * [Identity.EnrolResult.Rejected] -- and a field that exists only to
         * be shown one day is how a captive portal's HTML ends up on a
         * student's screen.
         */
        data object Invalid : RedeemOutcome
        /**
         * SQLSTATE 28000: the function ran with no authenticated user behind
         * it. A credentials fault, not a verdict on the code -- the code was
         * not looked at and is not spent. Its own arm rather than [Invalid]
         * because the two need different words on screen.
         */
        data object NotSignedIn : RedeemOutcome
        /** No answer. Nothing was decided, so nothing local changes. */
        data object Unavailable : RedeemOutcome
    }

    /**
     * Redeems an institution-issued enrolment code.
     *
     * The code, not the email address, is what proves the student belongs to
     * the institution -- which is why the address can be synthetic. The code is
     * matched server-side against a sha256 of its uppercased, trimmed form and
     * the plaintext is never stored, so this string exists on the wire for one
     * request and nowhere afterwards.
     */
    suspend fun redeem(code: String): RedeemOutcome = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext RedeemOutcome.Unavailable
        val response = SupabaseHttp.post(
            "${config.restBase}/rpc/redeem_enrolment_code",
            JSONObject().put("p_code", code.trim().uppercase()),
            config.anonKey,
            bearer = token,
        ) ?: return@withContext RedeemOutcome.Unavailable
        if (response.code in 200..299) {
            // A 2xx whose body is not the row this function returns is
            // UNAVAILABLE, not a bad code. The reasoning is
            // SupabaseAuth.tokenCall's, arrived at on the same wifi: on campus
            // a 200 with an unreadable body is almost always a portal's login
            // page, and calling that "your code was refused" would tell a
            // student with a perfectly good code to go and get another one --
            // the same wrong sentence this stage split exists to remove.
            val row = parseRedeem(response.body)
                ?: return@withContext RedeemOutcome.Unavailable
            return@withContext RedeemOutcome.Enrolled(row.first, row.second)
        }
        // SQLSTATE first, HTTP status second: the function raises 23505 for an
        // account that is already enrolled and 22023 for a bad code, and
        // PostgREST maps both into the 4xx range where they would be
        // indistinguishable.
        when (SupabaseHttp.sqlState(response.body)) {
            "23505" -> RedeemOutcome.AlreadyEnrolled
            "28000" -> RedeemOutcome.NotSignedIn
            // A 4xx that is not recognisably PostgREST's did not come from the
            // function, so it is not a verdict on the code either -- the same
            // reading SupabaseAuth.classifyFailure takes of a 4xx with no
            // error body.
            null -> RedeemOutcome.Unavailable
            else -> RedeemOutcome.Invalid
        }
    }

    // --- the grant --------------------------------------------------------

    /**
     * Reads the membership and the tenant, and returns a validated
     * [Entitlement] or null.
     *
     * Null on ANY doubt -- offline, a non-2xx, an empty array, a role the
     * server's own CHECK would reject. The caller writes only a non-null
     * result, which is the property that keeps a bad wifi day from resetting a
     * working device's grace clock. A failed fetch has to be a no-op, not a
     * partial write; that is the difference between a demo that survives a
     * conference centre's captive portal and one that does not.
     */
    suspend fun fetchGrant(nowMs: Long = System.currentTimeMillis()): Entitlement? =
        withContext(Dispatchers.IO) {
            val token = auth.accessToken(nowMs) ?: return@withContext null
            // RLS already scopes this to the caller's own row; the filter is
            // absent on purpose so the client is not the thing deciding scope.
            val membership = SupabaseHttp.get(
                "${config.restBase}/memberships?select=tenant_id,role,status",
                config.anonKey, token,
            ) ?: return@withContext null
            if (membership.code !in 200..299) return@withContext null
            val member = parseMembership(membership.body) ?: return@withContext null

            val tenant = SupabaseHttp.get(
                "${config.restBase}/tenants" +
                    "?select=tenant_id,display_name,licence_state,offline_grace_days",
                config.anonKey, token,
            ) ?: return@withContext null
            if (tenant.code !in 200..299) return@withContext null
            val row = firstRow(tenant.body) ?: return@withContext null

            Entitlements.of(
                tenantId = row.optString("tenant_id").takeIf { it.isNotBlank() },
                role = member.role,
                licenceState = row.optString("licence_state").takeIf { it.isNotBlank() },
                graceDays = row.optInt("offline_grace_days", Entitlements.DEFAULT_GRACE_DAYS),
                verifiedAtMs = nowMs,
                displayName = row.optString("display_name").takeIf { it.isNotBlank() },
            )
        }

    // --- corpus metadata --------------------------------------------------

    /**
     * What the newest published corpus for this tenant IS, never the corpus
     * itself. Metadata only: a version number, a build stamp, a digest and a
     * size. Downloading a corpus is a separate decision the institution makes,
     * and nothing here does it.
     */
    data class CorpusVersion(
        val version: Int,
        val builtAtUtc: String,
        val sha256: String,
        val sizeBytes: Long,
        val minAppVersion: Int,
        val storagePath: String,
    )

    suspend fun fetchCorpusVersion(): CorpusVersion? = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext null
        val response = SupabaseHttp.get(
            "${config.restBase}/corpus_versions" +
                "?select=version,built_at_utc,sha256,size_bytes,min_app_version,storage_path" +
                "&order=version.desc&limit=1",
            config.anonKey, token,
        ) ?: return@withContext null
        if (response.code !in 200..299) return@withContext null
        parseCorpusVersion(response.body)
    }

    // --- usage ------------------------------------------------------------

    /**
     * Posts one aggregate usage row. Fire and forget: the Boolean is for tests
     * and logs, and no caller is expected to act on it.
     *
     * Deliberately kept OUT of the retrieval call graph. Retrieval must not
     * gain a network call, even one it ignores the result of, because "the
     * answer path never touches the network" is a claim that stops being
     * checkable the moment it is only true by timing.
     */
    suspend fun postUsage(
        event: String,
        route: Route? = null,
        latencyMs: Int? = null,
        ok: Boolean? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val token = auth.accessToken() ?: return@withContext false
        val userId = SupabaseAuth.jwtSubject(token) ?: return@withContext false
        val tenantId = tenantIdProvider() ?: return@withContext false
        val payload = usagePayload(userId, tenantId, event, route, latencyMs, ok)
            ?: return@withContext false
        val response = SupabaseHttp.postWithHeaders(
            "${config.restBase}/usage_events",
            payload,
            config.anonKey,
            bearer = token,
            // Nothing is done with the inserted row, and asking for it back
            // would only spend bandwidth on a device that may be on a phone
            // connection paid for by a student.
            extraHeaders = mapOf("Prefer" to "return=minimal"),
        ) ?: return@withContext false
        response.code in 200..299
    }

    /** Set by [Identity]; the tenant comes from the local grant rather than
     * being asked for, so a usage post cannot be the thing that discovers it. */
    var tenantIdProvider: () -> String? = { null }

    companion object {

        // --- pure parsing and payload building ----------------------------

        data class Member(val tenantId: String, val role: String, val status: String)

        fun firstRow(body: String): JSONObject? = runCatching {
            val array = JSONArray(body)
            if (array.length() == 0) null else array.optJSONObject(0)
        }.getOrNull()

        fun parseRedeem(body: String): Pair<String, String>? = runCatching {
            val row = firstRow(body) ?: return null
            val tenant = row.optString("tenant_id").takeIf { it.isNotBlank() } ?: return null
            val role = row.optString("role").takeIf { it in Entitlements.ROLES } ?: return null
            tenant to role
        }.getOrNull()

        /** Only an `active` membership counts. A revoked one is a deliberate
         * act by a registrar and must not be read as a grant. */
        fun parseMembership(body: String): Member? = runCatching {
            val row = firstRow(body) ?: return null
            val tenant = row.optString("tenant_id").takeIf { it.isNotBlank() } ?: return null
            val role = row.optString("role").takeIf { it in Entitlements.ROLES } ?: return null
            val status = row.optString("status")
            if (status != "active") return null
            Member(tenant, role, status)
        }.getOrNull()

        fun parseCorpusVersion(body: String): CorpusVersion? = runCatching {
            val row = firstRow(body) ?: return null
            val version = row.optInt("version", 0).takeIf { it > 0 } ?: return null
            val sha = row.optString("sha256").takeIf { SHA256.matches(it) } ?: return null
            val size = row.optLong("size_bytes", 0L).takeIf { it > 0L } ?: return null
            CorpusVersion(
                version = version,
                builtAtUtc = row.optString("built_at_utc"),
                sha256 = sha,
                sizeBytes = size,
                minAppVersion = row.optInt("min_app_version", 1),
                storagePath = row.optString("storage_path"),
            )
        }.getOrNull()

        private val SHA256 = Regex("""^[0-9a-f]{64}$""")

        /** `usage_events_event_check` on the server. */
        val EVENTS = setOf("app_open", "query", "abstain", "cloud_fallback", "corpus_update")

        /** `usage_events_route_check` on the server. */
        val ROUTE_LABELS = setOf("tabular", "graph", "fts", "vector", "hybrid", "none")

        /**
         * The exact set of keys a usage row may carry. Asserted for EQUALITY in
         * the tests, not containment: a future field cannot be added to the
         * payload without a test failing and someone having to say out loud
         * what it is and why it is not student data.
         */
        val USAGE_KEYS = setOf("tenant_id", "user_id", "event", "route", "latency_ms", "ok")

        /**
         * Maps the app's route to the server's label.
         *
         * The two vocabularies do not line up and this does not pretend they
         * do. The server's enum names retrieval ARMS (`fts`, `vector`,
         * `hybrid`, `graph`); the app's `Route` names a CLASSIFICATION. FACT
         * and GLOBAL both dispatch to the same hybrid search -- differing only
         * in fan-out and per-document dedupe -- so both honestly map to
         * `hybrid` and the distinction between them is simply not recorded.
         * Inventing `fts` for FACT would have preserved a distinction by
         * misreporting which arm ran, which is worse than losing it.
         */
        fun routeLabel(route: Route?): String = when (route) {
            Route.TABULAR -> "tabular"
            Route.LOCAL -> "graph"
            Route.FACT, Route.GLOBAL -> "hybrid"
            null -> "none"
        }

        /**
         * Builds the usage row.
         *
         * The strongest guarantee in this file is this SIGNATURE. There is no
         * `String` parameter here that a question could be passed through --
         * the caller can supply an event name from a fixed set, a `Route`, a
         * latency and a Boolean, and there is no argument for it to put a query
         * in even by accident. The server backs this up by having no free-text
         * column on `usage_events` at all: `event` and `route` are constrained
         * to enums by CHECKs and `latency_ms` to a non-negative integer, so
         * there is nowhere for a question to land even if one were sent.
         *
         * Null when the event or route is not one the server would accept,
         * which keeps a typo from becoming a 400 at runtime.
         */
        fun usagePayload(
            userId: String,
            tenantId: String,
            event: String,
            route: Route?,
            latencyMs: Int?,
            ok: Boolean?,
        ): JSONObject? {
            if (event !in EVENTS) return null
            if (userId.isBlank() || tenantId.isBlank()) return null
            val label = routeLabel(route)
            if (label !in ROUTE_LABELS) return null
            return JSONObject().apply {
                put("tenant_id", tenantId)
                put("user_id", userId)
                put("event", event)
                put("route", label)
                // Clamped rather than dropped: the server's CHECK rejects a
                // negative, and a bad clock subtraction producing -3 ms should
                // not lose the row.
                put("latency_ms", (latencyMs ?: 0).coerceAtLeast(0))
                put("ok", ok ?: true)
            }
        }
    }
}
