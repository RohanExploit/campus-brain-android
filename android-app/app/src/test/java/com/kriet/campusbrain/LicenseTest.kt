package com.kriet.campusbrain

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.kriet.campusbrain.data.IngestResult
import com.kriet.campusbrain.data.ImportAllowance
import com.kriet.campusbrain.data.auth.License
import com.kriet.campusbrain.data.auth.LicenseKey
import com.kriet.campusbrain.data.auth.LicenseStore
import com.kriet.campusbrain.data.auth.Licensing
import com.kriet.campusbrain.data.auth.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The commercial layer: the key, the caps, and the promise that neither can
 * stop a question being answered.
 *
 * The keypair is generated in the test, not checked in. A committed private
 * key is a committed private key whatever the README says about it being a
 * fixture, and the shipped `LicenseKey.PUBLIC_KEY_B64` is deliberately never
 * exercised here -- these tests prove the ALGORITHM, and the app's own key is
 * a value the founder rotates without anything in this file caring.
 *
 * Everything below is a pure function over an explicit clock. The two tests
 * that touch SQLite are `Assume`-skipped when `sqlite-bundled` has no JVM
 * native on the unit-test classpath, exactly as EntitlementTest's are -- and
 * every guarantee that actually matters is also covered by a pure test above
 * them that cannot skip.
 */
class LicenseTest {

    private val t0 = 1_760_000_000_000L
    private val day = 86_400_000L

    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private val publicB64: String =
        java.util.Base64.getEncoder().encodeToString(keys.public.encoded)

    /** Signs a body with the throwaway private key, exactly as the issuer does. */
    private fun sign(body: String): ByteArray =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(body.toByteArray(Charsets.UTF_8))
            sign()
        }

    private fun institutionalKey(
        tenantId: String = "tenant_1",
        // Deliberately a placeholder. No real institution's name appears in
        // this repo's fixtures: the name is data that arrives inside a signed
        // key, never something the app or its tests write down.
        displayName: String = "Example Institute",
        expiresAtMs: Long? = t0 + 365 * day,
        maxDocs: Int = 25,
        maxTotalKb: Int = 51_200,
        schemaVersion: Int = LicenseKey.SCHEMA_VERSION,
    ): String {
        val body = LicenseKey.body(
            tenantId, displayName, Tier.INSTITUTIONAL, t0, expiresAtMs,
            maxDocs, maxTotalKb, deviceId = null, schemaVersion = schemaVersion,
        )
        return LicenseKey.encode(body, sign(body))
    }

    private fun ownerKey(deviceId: String): String {
        val body = LicenseKey.body(
            "founder", "Campus Brain", Tier.OWNER, t0, null,
            100_000, 10_485_760, deviceId,
        )
        return LicenseKey.encode(body, sign(body))
    }

    private fun verify(key: String, nowMs: Long = t0 + day, deviceId: String? = null) =
        LicenseKey.verify(key, nowMs, deviceId, publicB64)

    private fun valid(key: String, nowMs: Long = t0 + day, deviceId: String? = null): License {
        val o = verify(key, nowMs, deviceId)
        assertTrue("expected a valid key, got $o", o is LicenseKey.Outcome.Valid)
        return (o as LicenseKey.Outcome.Valid).license
    }

    private fun rejection(key: String, nowMs: Long = t0 + day, deviceId: String? = null) =
        (verify(key, nowMs, deviceId) as? LicenseKey.Outcome.Invalid)?.reason

    // --- the invariant that is the entire product -------------------------

    @Test fun `no tier withholds an answer`() {
        // The counterpart to EntitlementTest's exhaustive sweep of
        // EntitlementState, and it defends the same claim from the other side.
        // A licence in this app governs exactly one verb -- import -- and the
        // way that is kept true is structural: there is no property on Tier
        // that could gate retrieval, and the only capability it carries is
        // whether a SCREEN is reachable.
        //
        // If a future commit adds `val canAsk: Boolean` to Tier, this test
        // does not fail, but the compile-time surface it is guarding does not
        // exist yet either. What this asserts is the thing that IS checkable:
        // every tier, including the default one, has a positive import
        // allowance, so no tier is a dead end.
        Tier.entries.forEach { tier ->
            val caps = when (tier) {
                Tier.FREE -> Licensing.Caps.FREE
                else -> Licensing.capsFor(
                    License("t", "Example Institute", tier, t0, null, 5, 5, null), t0
                )
            }
            assertTrue("$tier must allow at least one import", caps.maxDocs >= 1)
            assertTrue("$tier must allow some bytes", caps.maxTotalKb >= 1)
        }
    }

    @Test fun `the free default is the lowest tier and the smallest cap`() {
        assertEquals(Tier.FREE, Licensing.Caps.FREE.tier)
        assertEquals(Licensing.FREE_MAX_DOCS, Licensing.Caps.FREE.maxDocs)
        assertEquals(Licensing.FREE_MAX_TOTAL_KB, Licensing.Caps.FREE.maxTotalKb)
        // Never unlimited. A storage failure resolves here, and a storage
        // failure interpreted as a licence is the app giving itself away.
        assertTrue(Licensing.Caps.FREE.maxDocs < Int.MAX_VALUE)
        assertTrue(Licensing.Caps.FREE.maxTotalKb < Int.MAX_VALUE)
    }

    @Test fun `a null licence resolves to free, not to unknown`() {
        assertEquals(Licensing.Caps.FREE, Licensing.capsFor(null, t0))
    }

    // --- sign and verify ---------------------------------------------------

    @Test fun `a signed key round-trips through the codec`() {
        val l = valid(institutionalKey())
        assertEquals("tenant_1", l.tenantId)
        assertEquals("Example Institute", l.tenantDisplayName)
        assertEquals(Tier.INSTITUTIONAL, l.tier)
        assertEquals(25, l.maxDocs)
        assertEquals(51_200, l.maxTotalKb)
        assertNull("institutional keys carry no device binding", l.deviceId)
    }

    @Test fun `the key is typeable and case-insensitive`() {
        val key = institutionalKey()
        assertTrue("keys start with the CBI prefix", key.startsWith("CBI-"))
        // The alphabet a person has to read off an email and type on a phone.
        assertTrue(
            "unexpected characters in $key",
            key.all { it in 'A'..'Z' || it in '2'..'7' || it == '-' }
        )
        // Pasted out of a mail client that lower-cased it, wrapped it and put
        // a space in. All three must still verify: a key that only works when
        // copied perfectly is a key that generates support calls.
        val mangled = key.lowercase().replace("-", " ").chunked(20).joinToString("\n")
        assertEquals(Tier.INSTITUTIONAL, valid(mangled).tier)
    }

    @Test fun `a flipped signature byte is rejected`() {
        val key = institutionalKey()
        // Flip one character of the encoded blob, near the end, where the
        // signature lives. The body is untouched, so this is specifically the
        // signature check doing the work and not a field validator.
        val i = key.length - 3
        val c = key[i]
        val swapped = if (c == 'A') 'B' else 'A'
        val tampered = key.substring(0, i) + swapped + key.substring(i + 1)
        assertTrue(
            "a tampered key must not verify",
            rejection(tampered) != null
        )
    }

    @Test fun `a body edited after signing is rejected`() {
        // The attack the whole scheme exists to stop: take a real key, raise
        // the cap. Re-encoding with the original signature must fail.
        val body = LicenseKey.body(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL, t0,
            t0 + 365 * day, 25, 51_200, null,
        )
        val signature = sign(body)
        val greedy = body.replace("|25|", "|9999|")
        assertEquals(
            LicenseKey.Rejection.BAD_SIGNATURE,
            rejection(LicenseKey.encode(greedy, signature))
        )
    }

    @Test fun `a key signed by a different keypair is rejected`() {
        // Anyone can generate a P-256 keypair. Only one of them is the app's.
        val other = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
        }
        val body = LicenseKey.body(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL, t0,
            t0 + day, 25, 51_200, null,
        )
        val sig = Signature.getInstance("SHA256withECDSA").run {
            initSign(other.private); update(body.toByteArray()); sign()
        }
        assertEquals(
            LicenseKey.Rejection.BAD_SIGNATURE,
            rejection(LicenseKey.encode(body, sig))
        )
    }

    @Test fun `corrupt input is rejected without throwing`() {
        // The licence screen calls this on whatever is in the field. Every one
        // of these used to be a plausible crash: a decoder that throws on a
        // bad character, an array index on a short blob, a NumberFormatException
        // on a field that is not a number.
        val junk = listOf(
            "", "   ", "hello", "CBI", "CBI-", "CBI-!!!!", "CBI-0189",
            "CBI-AAAA", "CBI-AAAA-AAAA", "CBI-" + "A".repeat(4000),
            "cbi-aaaa-bbbb-cccc", "CBI-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA",
        )
        junk.forEach { s ->
            val outcome = verify(s)
            assertTrue("\"$s\" should have been refused", outcome is LicenseKey.Outcome.Invalid)
            // Refused by a NAMED reason, never by an exception the outer catch
            // relabelled. See the next test for why that distinction is worth
            // a separate assertion.
            assertFalse(
                "\"$s\" was refused by something throwing, not by a check",
                (outcome as LicenseKey.Outcome.Invalid).reason == LicenseKey.Rejection.INTERNAL
            )
        }
    }

    @Test fun `nothing in the verifier throws, on any input, valid or hostile`() {
        // The regression test for the defect that took this suite down.
        //
        // `parse` used `split('|', limit = -1)`, which is the Java and Python
        // idiom for "keep trailing empty fields". Kotlin's `split` keeps them
        // anyway and its `limit` is require(limit >= 0), so -1 threw
        // IllegalArgumentException -- inside the one arm that is reached only
        // AFTER a signature has verified. Every forged key was still correctly
        // rejected. Every GENUINE key was rejected too, reported as though the
        // admin had mistyped it.
        //
        // What makes it worth its own test is that the acceptance tests above
        // could not name the cause: they all just said "not valid". This one
        // fails with the word INTERNAL in it, which points at this file rather
        // than at the key.
        val fixtures = listOf(
            institutionalKey(),
            institutionalKey(expiresAtMs = t0 - day),
            institutionalKey(schemaVersion = 99),
            institutionalKey(displayName = "Arts | Science"),
            // The empty trailing field is the whole point: an institutional
            // key's `deviceId` is always "", so the record's ninth field is
            // empty and a split that dropped it would leave eight.
            ownerKey("5b1f0c62-0000-4000-8000-abcdefabcdef"),
            "", "CBI-", "CBI-AAAA", "garbage",
        )
        fixtures.forEach { key ->
            val outcome = verify(key, deviceId = "5b1f0c62-0000-4000-8000-abcdefabcdef")
            val reason = (outcome as? LicenseKey.Outcome.Invalid)?.reason
            assertTrue(
                "verifying a key threw instead of deciding about it",
                reason != LicenseKey.Rejection.INTERNAL
            )
        }
    }

    @Test fun `an institutional key really does carry nine fields, the last one empty`() {
        // Pinned directly, because the field count is a wire format shared with
        // scripts/issue_license.py and the failure mode when the two disagree
        // is every key being rejected at once.
        val body = LicenseKey.body(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL, t0, t0 + day, 25, 51_200, null,
        )
        val fields = body.split('|')
        assertEquals("the record is nine fields", 9, fields.size)
        assertEquals("the ninth is the empty device binding", "", fields.last())
    }

    @Test fun `a truncated key is rejected`() {
        val key = institutionalKey()
        // Half a key, as an email client that wrapped and lost a line gives it.
        assertNotNull(rejection(key.substring(0, key.length / 2)))
    }

    @Test fun `an expired key is rejected, and rejected as expired`() {
        val key = institutionalKey(expiresAtMs = t0 + 10 * day)
        assertEquals(Tier.INSTITUTIONAL, valid(key, nowMs = t0 + 9 * day).tier)
        // The distinction matters to the copy: "ask for a renewal" and "check
        // what you typed" are different instructions, and giving the wrong one
        // costs an admin an hour.
        assertEquals(LicenseKey.Rejection.EXPIRED, rejection(key, nowMs = t0 + 11 * day))
    }

    @Test fun `expiry is inclusive at the boundary`() {
        val key = institutionalKey(expiresAtMs = t0 + 10 * day)
        assertEquals(LicenseKey.Rejection.EXPIRED, rejection(key, nowMs = t0 + 10 * day))
    }

    @Test fun `an unknown schema version is rejected even though it verifies`() {
        // Signed correctly, by the right key, and still refused. A later
        // schema could add a RESTRICTION, and a parser cannot honour a
        // restriction it cannot see by ignoring the field it arrived in.
        assertEquals(
            LicenseKey.Rejection.UNKNOWN_SCHEMA,
            rejection(institutionalKey(schemaVersion = LicenseKey.SCHEMA_VERSION + 1))
        )
    }

    @Test fun `an institutional key may not be perpetual`() {
        assertEquals(
            LicenseKey.Rejection.BAD_FIELD,
            rejection(institutionalKey(expiresAtMs = null))
        )
    }

    @Test fun `a FREE key is not a thing that can be issued`() {
        val body = LicenseKey.body(
            "tenant_1", "Example Institute", Tier.FREE, t0, t0 + day, 5, 5, null,
        )
        assertEquals(
            LicenseKey.Rejection.BAD_FIELD,
            rejection(LicenseKey.encode(body, sign(body)))
        )
    }

    @Test fun `a display name containing the separator survives the round trip`() {
        // The field is user-supplied text inside a pipe-delimited record. An
        // unescaped separator here would shift every field after it, which is
        // a tier and two caps.
        val l = valid(institutionalKey(displayName = "Arts | Science (100%)"))
        assertEquals("Arts | Science (100%)", l.tenantDisplayName)
    }

    // --- owner mode --------------------------------------------------------

    @Test fun `an owner key is accepted only on the device it names`() {
        val mine = "5b1f0c62-0000-4000-8000-abcdefabcdef"
        val theirs = "0000ffff-0000-4000-8000-abcdefabcdef"
        val key = ownerKey(mine)

        assertEquals(Tier.OWNER, valid(key, deviceId = mine).tier)
        assertEquals(LicenseKey.Rejection.WRONG_DEVICE, rejection(key, deviceId = theirs))
        // No install id at all -- a device whose licence store would not open.
        // Failing towards "no owner mode" is the correct direction: a leaked
        // owner key must not become universal the moment a disk misbehaves.
        assertEquals(LicenseKey.Rejection.WRONG_DEVICE, rejection(key, deviceId = null))
    }

    @Test fun `an owner key must carry a binding`() {
        // An unbound owner key would be a key that works everywhere, which is
        // the exact thing the field exists to prevent.
        val body = LicenseKey.body(
            "founder", "Campus Brain", Tier.OWNER, t0, null, 100, 100, deviceId = null,
        )
        assertEquals(
            LicenseKey.Rejection.BAD_FIELD,
            rejection(LicenseKey.encode(body, sign(body)), deviceId = "anything")
        )
    }

    @Test fun `an institutional key must not carry a binding`() {
        // Institutional licences are sold per organisation, not per handset.
        // A bound one is an issuer mistake, and honouring it would silently
        // sell a hardware lock nobody agreed to buy.
        val body = LicenseKey.body(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL, t0, t0 + day,
            5, 5, deviceId = "some-device",
        )
        assertEquals(
            LicenseKey.Rejection.BAD_FIELD,
            rejection(LicenseKey.encode(body, sign(body)), deviceId = "some-device")
        )
    }

    @Test fun `owner and institutional are verified by the same code path`() {
        // Not an assertion about behaviour so much as about structure: the
        // only difference between the two is the value of one signed field,
        // and both arrive through LicenseKey.verify. A second mechanism for
        // owner mode -- a magic string, a debug flag, a gesture that skips the
        // signature -- would be a second thing to get wrong, and it is always
        // the second one that ships broken.
        val device = "5b1f0c62-0000-4000-8000-abcdefabcdef"
        assertEquals(Tier.INSTITUTIONAL, valid(institutionalKey()).tier)
        assertEquals(Tier.OWNER, valid(ownerKey(device), deviceId = device).tier)
    }

    // --- caps --------------------------------------------------------------

    @Test fun `an expired licence drops to the free allowance, not to zero`() {
        val l = License(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL,
            t0, t0 + 10 * day, 25, 51_200, null,
        )
        assertEquals(25, Licensing.capsFor(l, t0 + 9 * day).maxDocs)

        val lapsed = Licensing.capsFor(l, t0 + 11 * day)
        assertEquals(Tier.FREE, lapsed.tier)
        assertEquals(Licensing.FREE_MAX_DOCS, lapsed.maxDocs)
        // An institution that let a licence lapse gets the allowance a
        // stranger has, and a student on that phone can still add a timetable.
        assertTrue("a lapsed licence must still permit an import", lapsed.maxDocs >= 1)
    }

    @Test fun `a paying customer is never worse off than a free one`() {
        val stingy = License(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL, t0, t0 + day, 1, 1, null,
        )
        val caps = Licensing.capsFor(stingy, t0)
        assertTrue(caps.maxDocs >= Licensing.FREE_MAX_DOCS)
        assertTrue(caps.maxTotalKb >= Licensing.FREE_MAX_TOTAL_KB)
    }

    @Test fun `analytics is visible above the free tier and not at it`() {
        assertFalse(Tier.FREE.seesAnalytics)
        assertTrue(Tier.INSTITUTIONAL.seesAnalytics)
        assertTrue(Tier.OWNER.seesAnalytics)
    }

    // --- the import gate ---------------------------------------------------

    @Test fun `the document cap binds at used equals cap`() {
        val caps = Licensing.Caps(Tier.FREE, maxDocs = 1, maxTotalKb = 4096)
        assertNull("an empty corpus may import", ImportAllowance.documentCap(0, caps))

        val refused = ImportAllowance.documentCap(1, caps)
        assertNotNull("the second import must be refused", refused)
        assertEquals(1, refused!!.used)
        assertEquals(1, refused.cap)
        assertEquals(Tier.FREE, refused.tier)
        assertEquals(IngestResult.LicenseRequired.Limit.DOCUMENTS, refused.limit)
    }

    @Test fun `the document cap reports the institutional tier and its own cap`() {
        val caps = Licensing.Caps(Tier.INSTITUTIONAL, maxDocs = 25, maxTotalKb = 51_200)
        assertNull(ImportAllowance.documentCap(24, caps))
        val refused = ImportAllowance.documentCap(25, caps)!!
        assertEquals(25, refused.used)
        assertEquals(25, refused.cap)
        assertEquals(Tier.INSTITUTIONAL, refused.tier)
    }

    @Test fun `the byte cap accounts for the incoming file`() {
        val caps = Licensing.Caps(Tier.INSTITUTIONAL, maxDocs = 100, maxTotalKb = 1000)
        // 900KB used, a 50KB file: room.
        assertNull(ImportAllowance.byteCap(900 * 1024L, 50 * 1024L, caps))
        // 900KB used, a 200KB file: not room, even though 900 < 1000. A cap
        // checked against the stored total alone would have admitted it and
        // then been over.
        val refused = ImportAllowance.byteCap(900 * 1024L, 200 * 1024L, caps)!!
        assertEquals(900, refused.used)
        assertEquals(1000, refused.cap)
        assertEquals(IngestResult.LicenseRequired.Limit.KILOBYTES, refused.limit)
    }

    @Test fun `a one byte file costs a kilobyte, so tiny files cannot defeat the cap`() {
        val caps = Licensing.Caps(Tier.FREE, maxDocs = 9999, maxTotalKb = 1)
        assertNull(ImportAllowance.byteCap(0L, 1L, caps))
        assertNotNull(ImportAllowance.byteCap(1024L, 1L, caps))
    }

    @Test fun `lowering a cap refuses new imports and deletes nothing`() {
        // The contract on IngestResult.LicenseRequired, asserted as far as a
        // pure test can: the refusal is a value returned to the caller, and it
        // carries no instruction to remove anything. There is no path from
        // this type to UserCorpusDb.remove -- the type has no such field, the
        // function has no such parameter, and DocsFragment's branch for it
        // renders text.
        val caps = Licensing.Caps(Tier.FREE, maxDocs = 1, maxTotalKb = 4096)
        val refused = ImportAllowance.documentCap(50, caps)!!
        // The 50 documents already there are reported, not reduced.
        assertEquals(50, refused.used)
        assertEquals(1, refused.cap)
        assertTrue(
            "a refusal reports what exists rather than trimming it",
            refused.used > refused.cap
        )
    }

    // --- a bad paste must never downgrade a good licence -------------------

    @Test fun `a rejected key leaves the prior licence untouched`() {
        val good = License(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL,
            t0, t0 + 365 * day, 25, 51_200, null,
        )
        // Deliberately a null store: if the decision ever cleared or wrote on
        // a failure it would have to touch one, and there is none to touch.
        // What is being asserted is that the refusal carries the prior licence
        // back out unchanged, which is what the screen renders and what the
        // caller keeps.
        listOf(
            "", "not a key", "CBI-AAAA-BBBB", institutionalKey().dropLast(8),
            institutionalKey(schemaVersion = 99),
        ).forEach { bad ->
            val result = Licensing.decide(null, good, bad, t0 + day, null, publicB64)
            assertTrue("\"$bad\" should be refused", result is Licensing.ApplyResult.Refused)
            assertEquals(
                "the licence already on this device must be unchanged",
                good, (result as Licensing.ApplyResult.Refused).prior
            )
        }
    }

    @Test fun `an expired key pasted over a live one does not replace it`() {
        // The specific scenario: an admin pastes last year's key on top of
        // this year's. Losing an institutional licence to that would be a
        // support call on the day of a demo.
        val live = License(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL,
            t0, t0 + 365 * day, 25, 51_200, null,
        )
        val stale = institutionalKey(expiresAtMs = t0 - day)
        val result = Licensing.decide(null, live, stale, t0, null, publicB64)
        assertEquals(
            LicenseKey.Rejection.EXPIRED,
            (result as Licensing.ApplyResult.Refused).reason
        )
        assertEquals(live, result.prior)
    }

    @Test fun `a valid key with nowhere to store it changes nothing`() {
        val prior = License(
            "tenant_1", "Example Institute", Tier.INSTITUTIONAL,
            t0, t0 + 365 * day, 25, 51_200, null,
        )
        val result = Licensing.decide(null, prior, institutionalKey(), t0 + day, null, publicB64)
        // Not Accepted. Publishing a licence the disk refused would mean the
        // screen showing a tier that vanishes on the next launch.
        assertTrue(result is Licensing.ApplyResult.NotStored)
        assertEquals(prior, (result as Licensing.ApplyResult.NotStored).prior)
    }

    // --- persistence -------------------------------------------------------

    /**
     * `sqlite-bundled` has no JVM native on the unit-test classpath on every
     * machine, so these skip rather than fail there -- the same accommodation
     * EntitlementTest makes, and for the same reason. Every guarantee that
     * matters above is covered by a pure test that cannot skip; what is left
     * down here is SQL.
     */
    private fun connectOrSkip(): SQLiteConnection {
        val conn = try {
            BundledSQLiteDriver().open(":memory:")
        } catch (t: Throwable) {
            Assume.assumeNoException("sqlite-bundled has no JVM native here", t)
            throw t
        }
        return conn
    }

    @Test fun `the store round-trips a licence and upserts rather than duplicating`() {
        val conn = connectOrSkip()
        try {
            val store = LicenseStore(conn)
            assertTrue(store.ensureSchema())
            assertNull("nothing stored yet", store.load())

            val first = License(
                "tenant_1", "Example Institute", Tier.INSTITUTIONAL,
                t0, t0 + 365 * day, 25, 51_200, null,
            )
            assertTrue(store.save(first))
            assertEquals(first, store.load())

            // A renewal replaces rather than adds. The CHECK(id = 1) singleton
            // makes a second row impossible, so this is really asserting that
            // the write is INSERT OR REPLACE and not INSERT.
            val renewed = first.copy(expiresAtMs = t0 + 730 * day, maxDocs = 50)
            assertTrue(store.save(renewed))
            assertEquals(renewed, store.load())

            assertTrue(store.clear())
            assertNull(store.load())
        } finally {
            conn.close()
        }
    }

    @Test fun `the install id is generated once and then stable`() {
        val conn = connectOrSkip()
        try {
            val store = LicenseStore(conn)
            assertTrue(store.ensureSchema())
            var minted = 0
            val first = store.installId { minted++; "id-$minted" }
            val second = store.installId { minted++; "id-$minted" }
            assertEquals("the id must not rotate between reads", first, second)
            assertEquals("the generator must run exactly once", 1, minted)
        } finally {
            conn.close()
        }
    }

    @Test fun `a garbage tier column does not become a tier`() {
        val conn = connectOrSkip()
        try {
            val store = LicenseStore(conn)
            assertTrue(store.ensureSchema())
            conn.prepare(
                "INSERT INTO license(id, tenant_id, display_name, tier, issued_at_ms, " +
                    "expires_at_ms, max_docs, max_total_kb, device_id) " +
                    "VALUES (1, 'tenant_1', 'Example Institute', 'SUPREME', 1, NULL, 5, 5, NULL)"
            ).use { it.step() }
            assertNull("an unknown tier is not a licence", store.load())
        } finally {
            conn.close()
        }
    }
}
