package com.campusbrain.app

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.campusbrain.app.data.AnalyticsStore
import com.campusbrain.app.data.QueryLog
import com.campusbrain.app.data.Route
import com.campusbrain.app.data.auth.AccountDeletion
import com.campusbrain.app.data.auth.ControlPlane
import com.campusbrain.app.data.auth.Entitlement
import com.campusbrain.app.data.auth.EntitlementStore
import com.campusbrain.app.data.auth.Entitlements
import com.campusbrain.app.data.auth.LicenseStore
import com.campusbrain.app.data.query
import com.campusbrain.app.jvm.JdbcSQLiteDriver
import com.campusbrain.app.ui.auth.DeleteAccountCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account deletion: the Play requirement, and the three ways it could quietly
 * do harm.
 *
 * Nothing here needs a device or a server. The HTTP response is a status and a
 * string, the local wipe is a real SQLite file over [JdbcSQLiteDriver], and
 * the decision joining them is a pure function -- which is the whole reason
 * the feature was split that way.
 *
 * The three harms, in the order they would be discovered on a student's phone
 * rather than here:
 *
 *  1. **A failed delete that forgets the device anyway.** The account still
 *     exists on the control plane and the phone no longer knows about it: the
 *     worst of both states, and unrecoverable without a fresh enrolment code.
 *  2. **A captive portal read as a deletion.** A 200 with an HTML body is the
 *     normal experience of campus wifi, and treating it as a confirmed delete
 *     signs a student out of an account that is still there.
 *  3. **Deleting the student's own data along with the account.** The
 *     entitlement lives in `user_corpus.db`, one table away from the documents
 *     the student imported and the on-device usage store. `DROP`ping the file
 *     would be one line and would take all of it.
 */
class DeleteAccountTest {

    private val t0 = 1_760_000_000_000L

    private fun grant(): Entitlement = requireNotNull(
        Entitlements.of("tenant_1", "student", "active", 45, t0, "Northfield")
    )

    private fun session() = EntitlementStore.Session(
        userId = "11111111-2222-3333-4444-555555555555",
        accessToken = "header.payload.signature",
        refreshToken = "refresh-token",
        expiresAtEpochSec = t0 / 1000L + 3600L,
    )

    private fun everyResult(): List<AccountDeletion.Result> = listOf(
        AccountDeletion.Result.Deleted,
        AccountDeletion.Result.NoAccount,
        AccountDeletion.Result.SessionExpired,
        AccountDeletion.Result.Unavailable,
    )

    // --- what the server said, and what it means --------------------------

    /**
     * `delete_my_account` is declared `returns boolean`, so PostgREST sends
     * the bare token `true` or `false`. Pinned here because the obvious
     * implementation -- run it through `JSONObject` like every other response
     * in this package -- throws on both.
     */
    @Test fun `a bare true is a deletion and a bare false is an empty one`() {
        assertEquals(
            ControlPlane.DeleteOutcome.Deleted,
            ControlPlane.classifyDelete(200, "true")
        )
        assertEquals(
            ControlPlane.DeleteOutcome.NoAccount,
            ControlPlane.classifyDelete(200, "false")
        )
        // Whitespace and case, because a body arrives off a stream reader and
        // nothing guarantees which of the two spellings a proxy passes on.
        assertEquals(
            ControlPlane.DeleteOutcome.Deleted,
            ControlPlane.classifyDelete(200, " TRUE\n")
        )
    }

    @Test fun `a captive portal answering 200 is not a deletion`() {
        val portal = "<html><body><h1>Sign in to CampusWiFi</h1></body></html>"
        assertEquals(
            "a portal's HTML must never read as a confirmed delete",
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(200, portal)
        )
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(200, "")
        )
        // The shape a future JSON-returning version would send. Still not a
        // boolean, so still not evidence of anything.
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(200, """{"deleted":true}""")
        )
    }

    @Test fun `a dead session is told apart from a network that is having a day`() {
        // The function's own raise, the same SQLSTATE redeem_enrolment_code
        // uses for the same condition.
        assertEquals(
            ControlPlane.DeleteOutcome.NotSignedIn,
            ControlPlane.classifyDelete(400, """{"code":"28000","message":"no user"}""")
        )
        assertEquals(
            ControlPlane.DeleteOutcome.NotSignedIn,
            ControlPlane.classifyDelete(401, """{"message":"JWT expired"}""")
        )
        assertEquals(
            ControlPlane.DeleteOutcome.NotSignedIn,
            ControlPlane.classifyDelete(403, """{"code":"42501"}""")
        )
    }

    /**
     * The migration in `supabase/migrations/` has to be applied by hand. Until
     * it is, PostgREST answers 404/PGRST202 -- and the one thing that must not
     * do is convince a device its account is gone.
     */
    @Test fun `an unapplied migration reads as unreachable, not as deleted`() {
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(404, """{"code":"PGRST202","message":"not found"}""")
        )
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(500, """{"message":"boom"}""")
        )
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(503, "")
        )
    }

    /**
     * The failure the migration is most likely to produce in practice: a table
     * that references `auth.users` with the default `NO ACTION` makes the final
     * DELETE raise 23503, which aborts the function and deletes nothing.
     *
     * It must read as "nothing was deleted". A SQLSTATE that is not 28000 is
     * not a statement about the session, and any arm other than Unavailable
     * here would clear a device whose account is demonstrably still there.
     */
    @Test fun `a foreign-key violation deletes nothing and says so`() {
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(
                409,
                """{"code":"23503","message":"violates foreign key constraint"}"""
            )
        )
        assertEquals(
            ControlPlane.DeleteOutcome.Unavailable,
            ControlPlane.classifyDelete(400, """{"code":"23503"}""")
        )
    }

    @Test fun `every server outcome maps to exactly one result`() {
        // Not a `when` in the test: a `when` here would be exhaustive against
        // the same sealed interface the production mapping walks, and would
        // pass even if both were wrong in the same way.
        assertEquals(
            AccountDeletion.Result.Deleted,
            AccountDeletion.resultOf(ControlPlane.DeleteOutcome.Deleted)
        )
        assertEquals(
            AccountDeletion.Result.NoAccount,
            AccountDeletion.resultOf(ControlPlane.DeleteOutcome.NoAccount)
        )
        assertEquals(
            AccountDeletion.Result.SessionExpired,
            AccountDeletion.resultOf(ControlPlane.DeleteOutcome.NotSignedIn)
        )
        assertEquals(
            AccountDeletion.Result.Unavailable,
            AccountDeletion.resultOf(ControlPlane.DeleteOutcome.Unavailable)
        )
    }

    // --- the copy ---------------------------------------------------------

    /**
     * Exhaustiveness at RUN time, the reasoning [EnrolCopyTest] gives: a
     * `when` with no `else` is exhaustive at compile time only while the
     * sealed interface is closed, and a card that throws on a new arm should
     * fail here rather than on a phone.
     */
    @Test fun `every result produces a card`() {
        everyResult().forEach { result ->
            val outcome = DeleteAccountCopy.of(result)
            assertTrue("$result has no title", outcome.titleRes != 0)
            assertTrue("$result has no body", outcome.bodyRes != 0)
            assertTrue("$result has no action", outcome.actionRes != 0)
            assertTrue("$result has no icon", outcome.iconRes != 0)
        }
    }

    /**
     * Four outcomes, four different things to say. Totality alone would pass
     * with a copy-pasted branch, and the copy-pasted branch is the bug: it is
     * how "nothing was deleted" ends up wearing the words of "deleted".
     */
    @Test fun `no two cards share their words`() {
        val titles = everyResult().map { DeleteAccountCopy.of(it).titleRes }
        val bodies = everyResult().map { DeleteAccountCopy.of(it).bodyRes }
        assertEquals("two outcomes share a title", titles.size, titles.toSet().size)
        assertEquals("two outcomes share a body", bodies.size, bodies.toSet().size)
    }

    @Test fun `only the outcomes that left an account offer to try again`() {
        // Retrying is offered exactly where it could work. A dead session
        // cannot be fixed by tapping the same button, so that card leaves the
        // screen towards enrolment instead.
        assertTrue(DeleteAccountCopy.of(AccountDeletion.Result.Unavailable).returnsToStart)
        assertFalse(DeleteAccountCopy.of(AccountDeletion.Result.SessionExpired).returnsToStart)
        assertFalse(DeleteAccountCopy.of(AccountDeletion.Result.Deleted).returnsToStart)
        assertFalse(DeleteAccountCopy.of(AccountDeletion.Result.NoAccount).returnsToStart)

        // And the destructive button is withdrawn only where there is nothing
        // left to delete.
        assertTrue(DeleteAccountCopy.of(AccountDeletion.Result.Deleted).accountGone)
        assertTrue(DeleteAccountCopy.of(AccountDeletion.Result.NoAccount).accountGone)
        assertFalse(DeleteAccountCopy.of(AccountDeletion.Result.SessionExpired).accountGone)
        assertFalse(DeleteAccountCopy.of(AccountDeletion.Result.Unavailable).accountGone)
    }

    // --- what may be cleared, and what may not ----------------------------

    @Test fun `only a settled answer clears this device`() {
        assertTrue(AccountDeletion.clearsLocalState(AccountDeletion.Result.Deleted))
        assertTrue(AccountDeletion.clearsLocalState(AccountDeletion.Result.NoAccount))
        assertFalse(
            "a refused session must leave the enrolment in place",
            AccountDeletion.clearsLocalState(AccountDeletion.Result.SessionExpired)
        )
        assertFalse(
            "an unreachable server must leave the enrolment in place",
            AccountDeletion.clearsLocalState(AccountDeletion.Result.Unavailable)
        )
    }

    /**
     * Equality, not containment -- the reasoning is `ControlPlane.USAGE_KEYS`'s.
     * A table added to this list is a table account deletion begins destroying,
     * and on this database the neighbours are the student's own documents.
     */
    @Test fun `the account owns exactly two tables`() {
        assertEquals(
            listOf("auth_session", "entitlement"),
            EntitlementStore.ACCOUNT_TABLES
        )
        assertTrue(
            "a table cannot be both the account's and the student's",
            EntitlementStore.ACCOUNT_TABLES.intersect(AccountDeletion.KEPT_TABLES.toSet()).isEmpty()
        )
    }

    // --- the local wipe, against a real database --------------------------

    /**
     * The test this feature exists to have.
     *
     * One file, holding the account AND the student's imported documents AND
     * the on-device usage store AND the licence, which is exactly the shape of
     * the real `user_corpus.db`. After a successful delete the first is gone
     * and the other three are byte-for-byte where they were.
     */
    @Test fun `a successful delete takes the account and nothing else`() {
        val conn = JdbcSQLiteDriver().open(":memory:")
        try {
            val store = EntitlementStore(conn)
            assertTrue(store.ensureSchema())
            assertTrue(store.save(grant()))
            assertTrue(store.saveSession(session()))

            val analytics = AnalyticsStore(conn)
            assertTrue(analytics.ensureSchema())
            assertTrue(
                analytics.merge(
                    QueryLog.Snapshot(
                        queries = mapOf(Route.FACT to 7),
                        abstentions = mapOf(Route.FACT to 1),
                        docHits = mapOf("timetable_2026" to 3),
                    )
                )
            )
            val licenses = LicenseStore(conn)
            assertTrue(licenses.ensureSchema())
            val installId = licenses.installId { "install-abc" }

            createUserCorpusTables(conn)
            writeImportedDocument(conn)

            assertNotNull("precondition: enrolled", store.load())
            assertNotNull("precondition: signed in", store.loadSession())
            assertEquals(2, count(conn, "chunks"))

            assertTrue(store.clearAccount())

            // Gone.
            assertNull("the grant survived a delete", store.load())
            assertNull("the refresh token survived a delete", store.loadSession())

            // Kept. The student imported these; they are not the account's.
            assertEquals("an imported document was deleted", 1, count(conn, "documents"))
            assertEquals("imported passages were deleted", 2, count(conn, "chunks"))
            assertEquals("the FTS index was emptied", 2, count(conn, "chunks_fts"))
            assertEquals("an embedding was deleted", 1, count(conn, "embeddings"))

            // Kept. The usage store never left the phone in the first place.
            val usage = analytics.load()
            assertEquals("the usage aggregates were reset", 7, usage.count(Route.FACT))
            assertEquals(3, usage.docHits["timetable_2026"])

            // Kept. A licence is issued to a device by the vendor, not to an
            // account by the identity service; deleting an account does not
            // un-buy one, and the install id is how a licence is bound.
            assertEquals("the install id changed", installId, licenses.installId { "other" })
        } finally {
            conn.close()
        }
    }

    /**
     * The mirror image, and the one that would be expensive on a phone: the
     * server did not settle the question, so the device keeps everything --
     * including the refresh token, which is what makes the next attempt
     * possible at all.
     *
     * The `if` below is the same one `Identity.deleteAccount` runs. It is
     * written out rather than called because `Identity` is a `Context`-taking
     * composition root; the decision it delegates to is what matters and it is
     * pure.
     */
    @Test fun `a delete that did not happen leaves this device alone`() {
        val conn = JdbcSQLiteDriver().open(":memory:")
        try {
            val store = EntitlementStore(conn)
            assertTrue(store.ensureSchema())
            assertTrue(store.save(grant()))
            assertTrue(store.saveSession(session()))

            listOf(AccountDeletion.Result.SessionExpired, AccountDeletion.Result.Unavailable)
                .forEach { result ->
                    if (AccountDeletion.clearsLocalState(result)) store.clearAccount()

                    assertNotNull("$result destroyed the grant", store.load())
                    assertNotNull("$result destroyed the session", store.loadSession())
                    assertEquals("tenant_1", store.load()?.tenantId)
                    assertEquals("refresh-token", store.loadSession()?.refreshToken)
                }
        } finally {
            conn.close()
        }
    }

    // --- fixtures ---------------------------------------------------------

    /** The four tables `UserCorpusDb.createSchema` creates, copied because
     * that function is private and this test needs the same file to hold the
     * account and the documents at once -- which is the situation being
     * tested. (`UserCorpusDb.onConnection` would do it properly now; this copy
     * is kept because the point here is that these tables and the entitlement
     * tables coexist, not that either schema is right. `documents.origin` is
     * carried so the copy does not drift out of readable range of the real
     * one; the sync tables are `CorpusSyncStore`'s and are not part of it.) */
    private fun createUserCorpusTables(conn: SQLiteConnection) {
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS chunks (" +
                "id INTEGER PRIMARY KEY, doc_id TEXT NOT NULL, section TEXT, content TEXT NOT NULL)"
        )
        conn.execSQL(
            "CREATE VIRTUAL TABLE IF NOT EXISTS chunks_fts USING fts5(" +
                "content, doc_id, content='chunks', content_rowid='id', " +
                "tokenize='porter unicode61')"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS embeddings (" +
                "chunk_id INTEGER PRIMARY KEY REFERENCES chunks(id), vec BLOB NOT NULL)"
        )
        conn.execSQL(
            "CREATE TABLE IF NOT EXISTS documents (" +
                "doc_id TEXT PRIMARY KEY, title TEXT NOT NULL, category TEXT NOT NULL, " +
                "chunk_count INTEGER NOT NULL, preview TEXT, source_uri TEXT, " +
                "added_at_utc TEXT NOT NULL, size_bytes INTEGER, " +
                "origin TEXT NOT NULL DEFAULT 'user', remote_doc_id TEXT, revision INTEGER)"
        )
    }

    private fun writeImportedDocument(conn: SQLiteConnection) {
        conn.execSQL(
            "INSERT INTO chunks(id, doc_id, section, content) " +
                "VALUES (1, 'timetable_2026', 'Monday', 'Data structures at nine')"
        )
        conn.execSQL(
            "INSERT INTO chunks(id, doc_id, section, content) " +
                "VALUES (2, 'timetable_2026', 'Tuesday', 'Operating systems at eleven')"
        )
        conn.execSQL(
            "INSERT INTO chunks_fts(rowid, content, doc_id) " +
                "VALUES (1, 'Data structures at nine', 'timetable_2026')"
        )
        conn.execSQL(
            "INSERT INTO chunks_fts(rowid, content, doc_id) " +
                "VALUES (2, 'Operating systems at eleven', 'timetable_2026')"
        )
        conn.execSQL("INSERT INTO embeddings(chunk_id, vec) VALUES (1, x'00000000')")
        conn.execSQL(
            "INSERT INTO documents(doc_id, title, category, chunk_count, preview, " +
                "source_uri, added_at_utc, size_bytes) VALUES " +
                "('timetable_2026', 'My timetable', 'Added by you', 2, 'Data structures', " +
                "'content://downloads/1', '2026-09-07T10:00:00Z', 2048)"
        )
    }

    private fun count(conn: SQLiteConnection, table: String): Int =
        conn.query("SELECT count(*) FROM $table") { it.getLong(0).toInt() }.first()
}
