package com.campusbrain.app.data.auth

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * What a paying institution has bought, and the string that proves it.
 *
 * Read [Entitlement] first. That file's invariant -- every state still answers
 * questions -- is the product, and nothing here is allowed to weaken it. The
 * licence in this file governs exactly one thing: whether a NEW document may
 * be imported. It does not appear on the ask path, it does not hide documents
 * that are already imported, and there is no value of [Tier] for which a
 * question goes unanswered. The free tier is the whole demo: four routes, the
 * bundled corpus, the document browser and the self test, permanently, in
 * airplane mode.
 *
 * ## Why asymmetric, and not an HMAC
 *
 * `isMinifyEnabled = false` (see app/build.gradle.kts, and the reason it is
 * off: ONNX Runtime and the bundled SQLite both resolve classes reflectively).
 * The APK therefore decompiles cleanly, and any secret compiled into it is a
 * secret the first person to unzip it can read. A shared HMAC key would let
 * that person mint unlimited INSTITUTIONAL keys for themselves and for anyone
 * else, which is the one failure this whole mechanism exists to prevent.
 *
 * So the app ships a P-256 PUBLIC key and nothing else. Verification is
 * `java.security.Signature` doing local elliptic-curve arithmetic: no network,
 * no clock server, no dependency that is not already in the JDK. The private
 * key lives with the founder and is used by `scripts/issue_license.py`, which
 * is not part of the Android build.
 *
 * ## The security posture, stated honestly
 *
 * A device binding on OWNER keys stops a leaked owner key being reused by a
 * stranger, and stops casual sharing of it between phones. That is the whole
 * of what it buys.
 *
 * It does NOT stop someone with physical possession of the founder's unlocked
 * device, and it does not stop a sophisticated attacker who has the private
 * key from forging a key bound to a device they control -- at that point the
 * binding is a field they are choosing the value of. The private key staying
 * off every machine that is not the founder's is the actual line of defence;
 * everything in this file is the second one.
 */
enum class Tier {
    /**
     * No key. Everything the app does with the college's own corpus, forever.
     *
     * The import cap is [Licensing.FREE_MAX_DOCS] -- one document -- and it is
     * a sales tool, not a trial. "Import your own syllabus right now" is a
     * thing a prospective buyer can do in the room, on their own file, with no
     * account and no network. Crippling the ask path to manufacture urgency
     * would destroy the only claim this product makes.
     */
    FREE,

    /** Unlocked by a founder-issued key. The caps come out of the key. */
    INSTITUTIONAL,

    /**
     * Not a price. A reserved value that only the founder's own key carries,
     * and which unlocks the analytics screen and the largest caps.
     *
     * Verified through EXACTLY the same code path as [INSTITUTIONAL] -- the
     * tier is one signed field among nine. A second bypass mechanism (a magic
     * string, a debug flag, a hidden gesture that skips verification) would be
     * a second thing to get wrong, and it is always the second one that ships
     * broken.
     */
    OWNER;

    /** True for the two tiers that may open the analytics screen. */
    val seesAnalytics: Boolean get() = this != FREE

    companion object {
        /** Parses the signed field. Unknown text is not a tier and not a
         * downgrade -- it makes the whole key invalid, see [LicenseKey.parse]. */
        fun of(name: String): Tier? = Tier.entries.firstOrNull { it.name == name }
    }
}

/**
 * A verified licence. There is no way to construct one of these except by
 * putting a correctly signed key through [LicenseKey.verify], which is what
 * makes "this object exists" mean "the signature checked out".
 */
data class License(
    /** Same shape as [Entitlement.tenantId]: `^[A-Za-z0-9_-]{1,64}$`. */
    val tenantId: String,
    /** Shown on the licence screen. Arrives IN THE KEY -- the app never
     * writes an institution's name into its own copy. */
    val tenantDisplayName: String,
    val tier: Tier,
    val issuedAtMs: Long,
    /** Null means no expiry, which only an OWNER key is allowed to carry. */
    val expiresAtMs: Long?,
    val maxDocs: Int,
    val maxTotalKb: Int,
    /** OWNER only. Checked against [Licensing.installId]; null on every
     * INSTITUTIONAL key, deliberately -- see [LicenseKey]. */
    val deviceId: String?,
) {
    /** Expiry is a fact about NEW imports only. Nothing already imported
     * changes when this turns true; see [Licensing.capsFor]. */
    fun expiredAt(nowMs: Long): Boolean = expiresAtMs != null && nowMs >= expiresAtMs
}

/**
 * The key format, its codec, and the only verification path in the app.
 *
 * ## The string
 *
 * ```
 * CBI-<base32 of blob, in dash-separated groups of four>
 * ```
 *
 * `CBI` for Campus Brain Institutional, then RFC 4648 base32 over the signed
 * blob. Base32 rather than base64 because a licence key is dictated over a
 * phone and typed on a phone keyboard: the alphabet is A-Z and 2-7, so there
 * is no case to get wrong, no `+` or `/` to hunt for on a symbol page, and no
 * 0/O or 1/l confusion. Groups of four because a 300-character unbroken run is
 * unreadable and impossible to proof-read against an email.
 *
 * Dashes, spaces and case are all stripped before decoding, so a key pasted
 * out of an email with a line wrap in it still works.
 *
 * ## The blob
 *
 * ```
 * [2 bytes big-endian body length][body UTF-8][DER ECDSA signature]
 * ```
 *
 * and the body is a pipe-joined record, which is the whole reason there is no
 * JSON here: `org.json` on the JVM unit-test classpath is a stub whose methods
 * throw (see the note in app/build.gradle.kts), and a licence parser that can
 * only be tested on a device is a licence parser that is not tested.
 *
 * ```
 * <schemaVersion>|<tenantId>|<tenantDisplayName>|<tier>|<issuedAt>|<expiresAt>|<maxDocs>|<maxTotalKb>|<deviceId>
 * ```
 *
 * `expiresAt` and `deviceId` are the empty string when absent. The display
 * name is percent-escaped for `|` and `%` so a college called "Arts | Science"
 * cannot shift every field after it.
 *
 * ## No device binding on INSTITUTIONAL keys
 *
 * Scarcity in a per-organisation licence is "one key per paying institution",
 * not "one key per handset". Binding an institutional key to hardware would
 * break the ordinary case -- a phone is replaced, the app is reinstalled, the
 * same string is typed in again -- and buys nothing, because the thing being
 * sold is the right of an organisation to use the app at all, and that right
 * is not diminished by the key being on two of its phones.
 *
 * OWNER keys are the exception and carry a binding, because a leaked owner key
 * grants unlimited everything to whoever holds it.
 */
object LicenseKey {

    const val PREFIX = "CBI"

    /**
     * Bumped when the body's field list changes. An app that meets a version
     * it does not know REJECTS the key rather than parsing the fields it
     * recognises: a future field could be a restriction, and honouring a
     * restriction you cannot see is not something a parser can do by ignoring
     * it.
     */
    const val SCHEMA_VERSION = 1

    /**
     * The founder's P-256 public key, X.509 SubjectPublicKeyInfo, base64.
     *
     * Safe to read, safe to publish, useless for forging anything. Replaced by
     * whoever holds the matching private key; `scripts/issue_license.py
     * --print-public-key` emits exactly this line.
     */
    const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE4VROBJb95850HcEVSj+mxT0u1jTAVe0s" +
            "UPCcgQrgRl06sK6aoebSMpsj2yHlCveY8/unRhLP8jMquxY5OxiPeA=="

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** RFC 4648. No padding is written and none is accepted. */
    private const val B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    private val TENANT_ID = Regex("""^[A-Za-z0-9_-]{1,64}$""")

    /**
     * Why a key was not accepted.
     *
     * Every one of these is a total rejection. There is no partial trust: a
     * key whose signature verifies but whose schema is unknown grants nothing,
     * and a key that has expired grants nothing, rather than silently
     * degrading to some smaller allowance the issuer never agreed to.
     */
    enum class Rejection {
        /** Not a `CBI-…` string, or it has characters the alphabet lacks. */
        MALFORMED,
        /** Decoded, but the length header and the bytes disagree. */
        TRUNCATED,
        /** The signature is not this public key's signature over this body. */
        BAD_SIGNATURE,
        /** Signed by the right key, for a version of this app that is newer. */
        UNKNOWN_SCHEMA,
        /** The body verified but a field is not a legal value. */
        BAD_FIELD,
        /** Past `expiresAt`. */
        EXPIRED,
        /** An OWNER key issued for a different install of the app. */
        WRONG_DEVICE,

        /**
         * Something in this file threw. A defect here, not a bad paste.
         *
         * Separate from [MALFORMED] because the two were once the same value,
         * and that cost a release cycle. `parse` threw on every key whose
         * signature had just VERIFIED -- see the note in `parse` -- and the
         * blanket catch below reported it as "that does not look like a
         * licence key". Every forged key was correctly refused and every
         * genuine one was refused too, while the app said the admin had
         * mistyped it.
         *
         * The catch stays: a licence screen that crashes on a paste is worse
         * than one that says no. But a defect must never again be able to wear
         * the label for user error, because that label is the one a reader
         * stops investigating.
         */
        INTERNAL,
    }

    sealed interface Outcome {
        data class Valid(val license: License) : Outcome
        data class Invalid(val reason: Rejection) : Outcome
    }

    /**
     * The whole verification, and the only entry point.
     *
     * [deviceId] is this install's own id, needed only to check an OWNER key's
     * binding; pass null and an OWNER key is rejected with [Rejection.WRONG_DEVICE]
     * rather than being waved through.
     *
     * [publicKeyB64] is a parameter with a default rather than a hard-coded
     * read, so a test can sign with a throwaway keypair. It is NOT a way for
     * anything at runtime to supply its own trust root: every caller in the app
     * uses the default.
     *
     * Never throws. A corrupt paste, a truncated blob, a signature over a
     * different curve -- all of them come back as [Outcome.Invalid]. A licence
     * screen that can crash on a bad paste is worse than one that says no.
     */
    fun verify(
        key: String,
        nowMs: Long,
        deviceId: String?,
        publicKeyB64: String = PUBLIC_KEY_B64,
    ): Outcome = runCatching { verifyOrThrow(key, nowMs, deviceId, publicKeyB64) }
        // [Rejection.INTERNAL], not MALFORMED. Everything below this line that
        // can legitimately be defeated by hostile input already returns its own
        // reason; anything that reaches here THREW, which is a defect in this
        // file and not a statement about what the admin pasted.
        .getOrElse { Outcome.Invalid(Rejection.INTERNAL) }

    private fun verifyOrThrow(
        key: String,
        nowMs: Long,
        deviceId: String?,
        publicKeyB64: String,
    ): Outcome {
        val blob = decode(key) ?: return Outcome.Invalid(Rejection.MALFORMED)
        // Two bytes of length, and at least one byte of body and one of
        // signature, before any of it is worth looking at.
        if (blob.size < 4) return Outcome.Invalid(Rejection.TRUNCATED)
        val bodyLen = ((blob[0].toInt() and 0xFF) shl 8) or (blob[1].toInt() and 0xFF)
        if (bodyLen <= 0 || 2 + bodyLen >= blob.size) return Outcome.Invalid(Rejection.TRUNCATED)

        val bodyBytes = blob.copyOfRange(2, 2 + bodyLen)
        val signature = blob.copyOfRange(2 + bodyLen, blob.size)

        val pub = publicKey(publicKeyB64) ?: return Outcome.Invalid(Rejection.MALFORMED)
        val verified = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).run {
                initVerify(pub)
                update(bodyBytes)
                verify(signature)
            }
        }.getOrDefault(false)
        // Signature first, fields second, always. Nothing below this line is
        // reading attacker-chosen text: it is reading text the holder of the
        // private key wrote.
        if (!verified) return Outcome.Invalid(Rejection.BAD_SIGNATURE)

        return parse(String(bodyBytes, Charsets.UTF_8), nowMs, deviceId)
    }

    /** Split, validate, and turn nine strings into a [License]. */
    private fun parse(body: String, nowMs: Long, deviceId: String?): Outcome {
        // No `limit` argument, and that is load-bearing rather than a default
        // taken lazily.
        //
        // The trailing `deviceId` field is EMPTY on every institutional key,
        // and if a split dropped it the record would arrive here with eight
        // fields instead of nine and every valid institutional key would be
        // rejected. Java's `String.split(regex)` does drop trailing empties,
        // so the reflex is to pass -1 the way you would there -- and this
        // function was written that way. Kotlin's `split` is not Java's: it
        // keeps trailing empty strings already, and its `limit` parameter is
        // `require(limit >= 0)`, so -1 does not mean "no limit", it throws
        // IllegalArgumentException.
        //
        // That threw here, inside the arm reached only AFTER a signature had
        // verified, where `verify`'s outer runCatching turned it into
        // Rejection.MALFORMED. The visible symptom was the exact inverse of a
        // security bug and much easier to misread: every forged key was
        // correctly rejected, and every GENUINE key was rejected too.
        val f = body.split('|')
        if (f.size != 9) return Outcome.Invalid(Rejection.BAD_FIELD)

        val schema = f[0].toIntOrNull() ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        if (schema != SCHEMA_VERSION) return Outcome.Invalid(Rejection.UNKNOWN_SCHEMA)

        val tenantId = f[1]
        if (!TENANT_ID.matches(tenantId)) return Outcome.Invalid(Rejection.BAD_FIELD)

        val displayName = unescape(f[2]).takeIf { it.isNotBlank() }
            ?: return Outcome.Invalid(Rejection.BAD_FIELD)

        val tier = Tier.of(f[3]) ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        // A FREE key is not a thing anyone would issue and not a thing this
        // app needs: FREE is what you have when you have no key at all.
        // Accepting one would create a second way to be on the default tier,
        // and a second way to be somewhere is a second thing to reason about.
        if (tier == Tier.FREE) return Outcome.Invalid(Rejection.BAD_FIELD)

        val issuedAt = f[4].toLongOrNull() ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        if (issuedAt <= 0L) return Outcome.Invalid(Rejection.BAD_FIELD)

        val expiresAt = if (f[5].isEmpty()) null
            else f[5].toLongOrNull() ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        // Only an OWNER key may be perpetual. An institutional licence that
        // never expires is a renewal conversation that never happens.
        if (expiresAt == null && tier != Tier.OWNER) return Outcome.Invalid(Rejection.BAD_FIELD)

        val maxDocs = f[6].toIntOrNull() ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        val maxTotalKb = f[7].toIntOrNull() ?: return Outcome.Invalid(Rejection.BAD_FIELD)
        if (maxDocs <= 0 || maxTotalKb <= 0) return Outcome.Invalid(Rejection.BAD_FIELD)

        val bound = f[8].takeIf { it.isNotEmpty() }
        when (tier) {
            // The binding is mandatory on OWNER, not optional. An owner key
            // with an empty deviceId field would otherwise be a leaked key
            // that works everywhere, which is the exact thing the field is for.
            Tier.OWNER -> {
                if (bound == null) return Outcome.Invalid(Rejection.BAD_FIELD)
                if (deviceId == null || bound != deviceId) {
                    return Outcome.Invalid(Rejection.WRONG_DEVICE)
                }
            }
            // Present on an institutional key means the issuer used the wrong
            // flag. Rejecting is right: honouring it would silently sell a
            // hardware lock nobody agreed to buy.
            Tier.INSTITUTIONAL -> if (bound != null) return Outcome.Invalid(Rejection.BAD_FIELD)
            Tier.FREE -> return Outcome.Invalid(Rejection.BAD_FIELD)
        }

        // Expiry last, so a key that is merely out of date is distinguishable
        // from one that is malformed. The licence screen says different things
        // about the two, and "renew this" is a much better sentence than
        // "check what you typed" when nothing was mistyped.
        if (expiresAt != null && nowMs >= expiresAt) return Outcome.Invalid(Rejection.EXPIRED)

        return Outcome.Valid(
            License(
                tenantId = tenantId,
                tenantDisplayName = displayName,
                tier = tier,
                issuedAtMs = issuedAt,
                expiresAtMs = expiresAt,
                maxDocs = maxDocs,
                maxTotalKb = maxTotalKb,
                deviceId = bound,
            )
        )
    }

    // --- codec -------------------------------------------------------------

    /**
     * The body a signer signs. Shared with `scripts/issue_license.py`, which
     * builds the identical string in Python -- kept here in Kotlin as well so
     * a test can round-trip without shelling out to the issuer.
     */
    fun body(
        tenantId: String,
        tenantDisplayName: String,
        tier: Tier,
        issuedAtMs: Long,
        expiresAtMs: Long?,
        maxDocs: Int,
        maxTotalKb: Int,
        deviceId: String?,
        schemaVersion: Int = SCHEMA_VERSION,
    ): String = listOf(
        schemaVersion.toString(),
        tenantId,
        escape(tenantDisplayName),
        tier.name,
        issuedAtMs.toString(),
        expiresAtMs?.toString() ?: "",
        maxDocs.toString(),
        maxTotalKb.toString(),
        deviceId ?: "",
    ).joinToString("|")

    /** Wraps a signed body into the typeable string. */
    fun encode(body: String, signature: ByteArray): String {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val blob = ByteArray(2 + bodyBytes.size + signature.size)
        blob[0] = ((bodyBytes.size shr 8) and 0xFF).toByte()
        blob[1] = (bodyBytes.size and 0xFF).toByte()
        bodyBytes.copyInto(blob, 2)
        signature.copyInto(blob, 2 + bodyBytes.size)
        return PREFIX + "-" + base32(blob).chunked(4).joinToString("-")
    }

    /**
     * Null for anything that is not a `CBI-` string over the base32 alphabet.
     *
     * Deliberately not an exception: this runs on every keystroke's worth of
     * pasted text on the licence screen, and "not a key yet" is the normal
     * case, not an error condition.
     */
    private fun decode(key: String): ByteArray? {
        val cleaned = key.uppercase().filter { it != '-' && it != ' ' && it != '\n' && it != '\r' }
        if (!cleaned.startsWith(PREFIX)) return null
        val payload = cleaned.removePrefix(PREFIX)
        if (payload.isEmpty()) return null

        var buffer = 0L
        var bits = 0
        val out = java.io.ByteArrayOutputStream(payload.length * 5 / 8 + 1)
        for (c in payload) {
            val v = B32.indexOf(c)
            if (v < 0) return null
            buffer = (buffer shl 5) or v.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        // Leftover bits are the encoder's zero padding. Anything set in them
        // means this is not a string this encoder produced.
        if (bits > 0 && (buffer and ((1L shl bits) - 1)) != 0L) return null
        return out.toByteArray()
    }

    private fun base32(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0L
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                sb.append(B32[((buffer shr bits) and 0x1F).toInt()])
            }
        }
        if (bits > 0) sb.append(B32[((buffer shl (5 - bits)) and 0x1F).toInt()])
        return sb.toString()
    }

    private fun publicKey(b64: String): PublicKey? = runCatching {
        val der = java.util.Base64.getDecoder().decode(b64)
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }.getOrNull()

    /** `|` and `%` only. The separator is the only character that can break
     * the record, and escaping more would be a format nobody can hand-check. */
    private fun escape(s: String): String = s.replace("%", "%25").replace("|", "%7C")

    private fun unescape(s: String): String = s.replace("%7C", "|").replace("%25", "%")
}
