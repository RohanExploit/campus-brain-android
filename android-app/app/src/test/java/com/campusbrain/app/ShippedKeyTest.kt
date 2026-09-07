package com.campusbrain.app

import com.campusbrain.app.data.auth.LicenseKey
import com.campusbrain.app.data.auth.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one test that exercises the public key actually compiled into the app.
 *
 * Every other licence test mints a throwaway keypair and injects the public
 * half, which is right -- it keeps them independent of whatever key is
 * shipping. The cost is that [LicenseKey.PUBLIC_KEY_B64] is then never
 * executed, so a mistyped or stale constant passes CI and fails on the first
 * licence anybody pastes. That is the worst place to discover it: after the
 * keys have gone out.
 *
 * The licence below was minted by scripts/issue_license.py against the private
 * half, and is verified here through the DEFAULT parameter. So this asserts the
 * one thing the other tests cannot -- that the constant in the APK and the key
 * that signs licences are two halves of one pair.
 *
 * This mattered once already: the original private key was lost with a failed
 * drive, leaving a public constant nothing could satisfy. Nothing caught it,
 * because nothing ran it.
 *
 * A deliberate key rotation will fail this test. That is the point. Regenerate
 * the fixture with:
 *
 *   python scripts/issue_license.py --private-key <pem> --tenant-id demo_inst  *     --tenant-name "Demo Institute" --tier INSTITUTIONAL --expires 2030-01-01  *     --max-docs 500 --max-total-kb 200000
 */
class ShippedKeyTest {

    private val issued =
            "CBI-ABID-C7DE-MVWW-6X3J-NZZX-I7CE-MVWW-6ICJ-NZZX-I2LU-OV" +
            "2G-K7CJ-JZJV-ISKU-KVKE-ST2O-IFGH-YMJX-HA4D-ONRX-GEYD-KNJ" +
            "T-GR6D-COBZ-GM2D-KNRQ-GAYD-AMBQ-PQ2T-AMD4-GIYD-AMBQ-GB6D" +
            "-ARIC-EEAL-NTM6-DCHV-V2CD-QOGE-F4YE-UVLV-KUB3-MAOZ-RKYH-" +
            "F7RK-J3UY-FTG2-LZQC-EBEE-JDZE-LM36-ESR3-Y4QD-7YOP-GQ4P-F" +
            "PQ3-QJA5-PEIG-KO6N-I6J4-NMKL-A"

    /** Inside the fixture's validity, and fixed so the test cannot begin
     *  failing merely because time passed. */
    private val now = 1_800_000_000_000L   // 2027-01-15

    @Test fun `the shipped public key verifies a genuinely issued licence`() {
        val outcome = LicenseKey.verify(issued, now, deviceId = null)
        assertTrue(
            "compiled PUBLIC_KEY_B64 does not match the key that signed this licence: $outcome",
            outcome is LicenseKey.Outcome.Valid,
        )
        val licence = (outcome as LicenseKey.Outcome.Valid).license
        assertEquals("demo_inst", licence.tenantId)
        assertEquals(Tier.INSTITUTIONAL, licence.tier)
    }

    @Test fun `a single altered character in that licence is refused`() {
        // Guards the fixture itself. If the string above were inert -- truncated,
        // or verified by something that ignores it -- the test above would pass
        // and prove nothing. This fails in that case.
        val i = issued.length / 2
        val flipped = issued.substring(0, i) +
            (if (issued[i] == 'A') 'B' else 'A') + issued.substring(i + 1)
        assertTrue(
            "a corrupted licence was accepted",
            LicenseKey.verify(flipped, now, deviceId = null) is LicenseKey.Outcome.Invalid,
        )
    }
}
