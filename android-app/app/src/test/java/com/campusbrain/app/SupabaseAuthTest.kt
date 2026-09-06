package com.campusbrain.app

import com.campusbrain.app.data.Route
import com.campusbrain.app.data.auth.AuthConfig
import com.campusbrain.app.data.auth.ControlPlane
import com.campusbrain.app.data.auth.Entitlements
import com.campusbrain.app.data.auth.SupabaseAuth
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The online half of the auth layer, at the seam where it is pure.
 *
 * Every HTTP call in `data/auth` is a thin shell around a decision and a parse,
 * and both of those are free functions precisely so this file can pin them
 * without a network. Nothing here contacts the live project: a unit test that
 * needs `campus-brain-dev` to be up is a test that fails for reasons unrelated
 * to the code.
 */
class SupabaseAuthTest {

    private val nowMs = 1_760_000_000_000L
    private val nowSec = nowMs / 1000L

    /** A real, unsigned, structurally valid JWT. Built here rather than pasted
     * so the payload is legible in the diff and no live token is checked in. */
    private fun jwt(claims: JSONObject): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        val header = enc.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = enc.encodeToString(claims.toString().toByteArray())
        return "$header.$payload.not-a-real-signature"
    }

    // --- refresh timing ---------------------------------------------------

    @Test fun `a token is refreshed before it expires, not after`() {
        // The skew covers the round trip: a call made with a token that dies
        // in flight fails for a reason the user cannot act on.
        assertFalse(SupabaseAuth.needsRefresh(nowSec + 3600, nowMs))
        assertFalse(SupabaseAuth.needsRefresh(nowSec + 121, nowMs))
        assertTrue(SupabaseAuth.needsRefresh(nowSec + 119, nowMs))
        assertTrue(SupabaseAuth.needsRefresh(nowSec, nowMs))
        assertTrue(SupabaseAuth.needsRefresh(nowSec - 10_000, nowMs))
    }

    @Test fun `an unknown expiry is treated as due, not as fresh`() {
        // The worst case is one wasted refresh. Treating unknown as fresh would
        // send a dead token and fail the call it was needed for.
        assertTrue(SupabaseAuth.needsRefresh(0L, nowMs))
        assertTrue(SupabaseAuth.needsRefresh(-1L, nowMs))
    }

    // --- token parsing ----------------------------------------------------

    @Test fun `a token response yields a session with an absolute expiry`() {
        val access = jwt(JSONObject().put("sub", "user-uuid").put("exp", nowSec + 3600))
        val body = JSONObject()
            .put("access_token", access)
            .put("refresh_token", "refresh-abc")
            .put("expires_in", 3600)
            .put("expires_at", nowSec + 3600)
            .put("user", JSONObject().put("id", "user-uuid"))
            .toString()
        val session = SupabaseAuth.parseTokenResponse(body, nowMs)
        assertNotNull(session)
        assertEquals("user-uuid", session!!.userId)
        assertEquals("refresh-abc", session.refreshToken)
        assertEquals(nowSec + 3600, session.expiresAtEpochSec)
        assertFalse(SupabaseAuth.needsRefresh(session.expiresAtEpochSec, nowMs))
    }

    @Test fun `expiry falls back to the jwt claim and then to expires_in`() {
        val access = jwt(JSONObject().put("sub", "u").put("exp", nowSec + 900))
        val noAbsolute = JSONObject()
            .put("access_token", access).put("refresh_token", "r").toString()
        assertEquals(nowSec + 900, SupabaseAuth.parseTokenResponse(noAbsolute, nowMs)!!.expiresAtEpochSec)

        // A token with no readable claims at all: the relative value against
        // this device's clock is the last thing left.
        val opaque = JSONObject()
            .put("access_token", "not.a.jwt").put("refresh_token", "r")
            .put("expires_in", 60).toString()
        assertEquals(nowSec + 60, SupabaseAuth.parseTokenResponse(opaque, nowMs)!!.expiresAtEpochSec)
    }

    @Test fun `half a session is no session`() {
        // Returning a partial session would let it overwrite a working one in
        // the store, which is a worse outcome than a failed sign-in.
        assertNull(SupabaseAuth.parseTokenResponse("""{"access_token":"a"}""", nowMs))
        assertNull(SupabaseAuth.parseTokenResponse("""{"refresh_token":"r"}""", nowMs))
        // What GoTrue returns when email confirmation is required -- a user
        // object and no tokens. Worth failing on loudly: it would mean the
        // project's mailer_autoconfirm setting changed underneath the app.
        assertNull(SupabaseAuth.parseTokenResponse("""{"user":{"id":"u"}}""", nowMs))
        assertNull(SupabaseAuth.parseTokenResponse("", nowMs))
        assertNull(SupabaseAuth.parseTokenResponse("<html>503</html>", nowMs))
    }

    @Test fun `jwt claims are read with the JVM base64 decoder and unpadded input`() {
        // android.util.Base64 is a stub that throws in JVM unit tests, so this
        // is java.util.Base64 with the URL alphabet and the padding restored --
        // JWT segments ship without it, and a strict decoder rejects them.
        val token = jwt(JSONObject().put("sub", "abc-123").put("exp", 1_800_000_000L))
        assertEquals(1_800_000_000L, SupabaseAuth.jwtExpiry(token))
        assertEquals("abc-123", SupabaseAuth.jwtSubject(token))

        // And nothing here throws on input that is not a token. The local read
        // is a scheduling hint; the signature that matters is checked server
        // side, where a forged claim dies at verification.
        assertNull(SupabaseAuth.jwtExpiry("garbage"))
        assertNull(SupabaseAuth.jwtExpiry("a.b.c"))
        assertNull(SupabaseAuth.jwtExpiry(""))
        assertNull(SupabaseAuth.jwtSubject("only-one-part"))
    }

    // --- when a failure is fatal to the session, and when it is weather ----

    @Test fun `only the server's own no destroys a session`() {
        // This classification is the difference between a student who can renew
        // and one who cannot. A DEFINITIVE result deletes the stored refresh
        // token; a TRANSIENT one keeps it for the next attempt, which is safe
        // here because replaying a refresh token on this project returns the
        // successor that was already issued rather than revoking the family.
        val gotrue = """{"error":"invalid_grant","error_description":"Invalid Refresh Token"}"""
        assertEquals(SupabaseAuth.Failure.DEFINITIVE, SupabaseAuth.classifyFailure(400, gotrue))
        assertEquals(SupabaseAuth.Failure.DEFINITIVE, SupabaseAuth.classifyFailure(401, gotrue))
        assertEquals(
            SupabaseAuth.Failure.DEFINITIVE,
            SupabaseAuth.classifyFailure(403, """{"msg":"Invalid login credentials"}"""),
        )
    }

    @Test fun `a bad wifi day does not cost a student their session`() {
        val portal = "<html><body>Sign in to CampusWiFi</body></html>"
        // A captive portal answering a 4xx with HTML did not come from GoTrue.
        assertEquals(SupabaseAuth.Failure.TRANSIENT, SupabaseAuth.classifyFailure(403, portal))
        // The server having a bad minute is not the token being dead.
        listOf(500, 502, 503, 504, 408, 429).forEach {
            assertEquals("$it must be survivable",
                SupabaseAuth.Failure.TRANSIENT, SupabaseAuth.classifyFailure(it, gotrueDown))
        }
        // And a 200 that is not a token response -- the portal case again, and
        // the single most likely way to lose a working session by accident.
        assertEquals(SupabaseAuth.Failure.TRANSIENT, SupabaseAuth.classifyFailure(200, portal))
        assertNull(SupabaseAuth.parseTokenResponse(portal, nowMs))
    }

    private val gotrueDown = """{"message":"upstream connect error"}"""

    // --- configuration ----------------------------------------------------

    @Test fun `config needs both a url and a key, and refuses plain http`() {
        val good = AuthConfig.parse(
            """{"supabase_url":"https://x.supabase.co/","supabase_anon_key":"anon-key"}"""
        )
        assertNotNull(good)
        assertEquals("https://x.supabase.co", good!!.url) // trailing slash removed
        assertEquals("https://x.supabase.co/auth/v1", good.authBase)
        assertEquals("https://x.supabase.co/rest/v1", good.restBase)
        assertEquals(AuthConfig.DEFAULT_SYNTHETIC_DOMAIN, good.syntheticEmailDomain)

        // Half a configuration is worse than none: it produces a sign-in button
        // that always fails.
        assertNull(AuthConfig.parse("""{"supabase_url":"https://x.supabase.co"}"""))
        assertNull(AuthConfig.parse("""{"supabase_anon_key":"anon-key"}"""))
        assertNull(AuthConfig.parse("""{"supabase_url":"http://x.supabase.co","supabase_anon_key":"k"}"""))
        assertNull(AuthConfig.parse("not json at all"))
        // The cloud-answer keys share this file and must not be mistaken for it.
        assertNull(AuthConfig.parse("""{"groq_api_key":"gsk_x"}"""))
    }

    @Test fun `a synthetic address is unique, well formed, and unroutable`() {
        // The enrolment code proves the student belongs to the institution, so
        // the address proves nothing and must never receive mail. RFC 2606
        // reserves .invalid for exactly that.
        val a = SupabaseAuth.syntheticEmail()
        val b = SupabaseAuth.syntheticEmail()
        assertTrue(SupabaseAuth.looksLikeEmail(a))
        assertTrue(a.endsWith("@student.campusbrain.invalid"))
        assertFalse("two students on one multi-use code must not collide", a == b)
        assertTrue(SupabaseAuth.looksLikeEmail("student@kriet.ac.in"))
        assertFalse(SupabaseAuth.looksLikeEmail("no-at-sign"))
        assertFalse(SupabaseAuth.looksLikeEmail("two@@at.in"))
        assertFalse(SupabaseAuth.looksLikeEmail("no@domain"))
    }

    // --- the usage payload: the privacy boundary, structurally ------------

    @Test fun `a usage row carries exactly six keys and none of them is free text`() {
        val payload = ControlPlane.usagePayload(
            userId = "user-uuid", tenantId = "tenant_1",
            event = "query", route = Route.TABULAR, latencyMs = 143, ok = true,
        )
        assertNotNull(payload)
        val keys = payload!!.keys().asSequence().toSet()
        // EQUALITY, not containment. A test that only checked "no query text
        // present" would pass forever while someone added a `question` field
        // next to it; this one fails the moment the shape changes and makes
        // whoever changed it say out loud what the new field is.
        assertEquals(ControlPlane.USAGE_KEYS, keys)
        assertEquals("tenant_1", payload.getString("tenant_id"))
        assertEquals("query", payload.getString("event"))
        assertEquals("tabular", payload.getString("route"))
        assertEquals(143, payload.getInt("latency_ms"))
    }

    @Test fun `there is no parameter a question could travel through`() {
        // The real guarantee is the signature: usagePayload takes an event from
        // a fixed set, a Route, an Int and a Boolean. There is no String the
        // caller controls that reaches the server -- `event` is checked against
        // the server's own enum below, and `route` is derived, not passed.
        //
        // Reflection over the declared parameters, so that adding a `query:
        // String` argument breaks the build's tests rather than the promise.
        val params = ControlPlane.Companion::class.java.declaredMethods
            .first { it.name == "usagePayload" }
            .parameterTypes
            .map { it.simpleName }
        assertEquals(
            listOf("String", "String", "String", "Route", "Integer", "Boolean"),
            params,
        )
    }

    @Test fun `an event the server would reject is dropped here rather than sent`() {
        assertNull(ControlPlane.usagePayload("u", "t", "search_text", null, 1, true))
        assertNull(ControlPlane.usagePayload("", "t", "query", null, 1, true))
        assertNull(ControlPlane.usagePayload("u", "", "query", null, 1, true))
        ControlPlane.EVENTS.forEach {
            assertNotNull(it, ControlPlane.usagePayload("u", "t", it, null, null, null))
        }
        // A negative latency is a clock artefact, not a reason to lose the row.
        assertEquals(0, ControlPlane.usagePayload("u", "t", "query", null, -3, true)!!.getInt("latency_ms"))
    }

    @Test fun `route labels are honest about the arm that ran, and FACT collapses into GLOBAL`() {
        // The server's enum names retrieval ARMS; the app's Route names a
        // CLASSIFICATION, and the two vocabularies do not line up. FACT and
        // GLOBAL both dispatch to the same hybrid search, differing only in
        // fan-out and per-document dedupe, so both report `hybrid` and the
        // distinction between them is simply not recorded. Mapping FACT to
        // `fts` would have kept the distinction by misreporting the arm.
        assertEquals("tabular", ControlPlane.routeLabel(Route.TABULAR))
        assertEquals("graph", ControlPlane.routeLabel(Route.LOCAL))
        assertEquals("hybrid", ControlPlane.routeLabel(Route.FACT))
        assertEquals("hybrid", ControlPlane.routeLabel(Route.GLOBAL))
        assertEquals("none", ControlPlane.routeLabel(null))
        Route.entries.forEach {
            assertTrue("$it maps outside usage_events_route_check",
                ControlPlane.routeLabel(it) in ControlPlane.ROUTE_LABELS)
        }
    }

    // --- control-plane response parsing -----------------------------------

    @Test fun `a redeemed code yields the tenant and role the server chose`() {
        val body = """[{"tenant_id":"tenant_1","role":"student"}]"""
        assertEquals("tenant_1" to "student", ControlPlane.parseRedeem(body))
        // The client never picks its own tenancy or role, so anything it does
        // not recognise is dropped rather than trusted.
        assertNull(ControlPlane.parseRedeem("""[{"tenant_id":"t","role":"superuser"}]"""))
        assertNull(ControlPlane.parseRedeem("[]"))
        assertNull(ControlPlane.parseRedeem("""{"code":"22023"}"""))
        assertNull(ControlPlane.parseRedeem(""))
    }

    @Test fun `a revoked membership is not a grant`() {
        val active = """[{"tenant_id":"tenant_1","role":"faculty","status":"active"}]"""
        assertEquals("faculty", ControlPlane.parseMembership(active)!!.role)
        val revoked = """[{"tenant_id":"tenant_1","role":"faculty","status":"revoked"}]"""
        assertNull("a registrar revoking access is a deliberate act", ControlPlane.parseMembership(revoked))
        assertNull(ControlPlane.parseMembership("[]"))
    }

    @Test fun `corpus metadata is validated the way the server validates it`() {
        val sha = "a".repeat(64)
        val body = """[{"version":7,"built_at_utc":"2026-09-04T06:00:00Z","sha256":"$sha",
            "size_bytes":2293760,"min_app_version":1,"storage_path":"tenant_1/brain-7.db"}]"""
        val v = ControlPlane.parseCorpusVersion(body)
        assertNotNull(v)
        assertEquals(7, v!!.version)
        assertEquals(2_293_760L, v.sizeBytes)
        // corpus_versions_sha256_check: a digest that is not 64 lowercase hex
        // characters is not a digest, and a corpus is the one thing worth being
        // paranoid about before downloading.
        assertNull(ControlPlane.parseCorpusVersion("""[{"version":7,"sha256":"nope","size_bytes":1}]"""))
        assertNull(ControlPlane.parseCorpusVersion("""[{"version":0,"sha256":"$sha","size_bytes":1}]"""))
        assertNull(ControlPlane.parseCorpusVersion("""[{"version":7,"sha256":"$sha","size_bytes":0}]"""))
        assertNull(ControlPlane.parseCorpusVersion("[]"))
    }

    @Test fun `the label sets match the server's own CHECK constraints`() {
        // Read off campus-brain-dev's pg_constraint output. If a migration
        // widens either enum, this is where the app finds out.
        assertEquals(
            setOf("app_open", "query", "abstain", "cloud_fallback", "corpus_update"),
            ControlPlane.EVENTS,
        )
        assertEquals(
            setOf("tabular", "graph", "fts", "vector", "hybrid", "none"),
            ControlPlane.ROUTE_LABELS,
        )
        assertEquals(setOf("admin", "registrar", "faculty", "student"), Entitlements.ROLES)
        assertEquals(setOf("trial", "active", "suspended", "expired"), Entitlements.LICENCE_STATES)
        assertEquals(45, Entitlements.DEFAULT_GRACE_DAYS)
    }
}
