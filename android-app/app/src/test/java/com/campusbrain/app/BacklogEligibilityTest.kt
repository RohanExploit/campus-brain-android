package com.campusbrain.app

import com.campusbrain.app.answer.AnswerCheck
import com.campusbrain.app.answer.AnswerComposer
import com.campusbrain.app.data.RetrievedChunk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cross-document shape [AnswerCheck.backlogAgainstDrive] answers: the cap
 * a student is judged against lives only in a placement drive's own notice,
 * and the rule that a cap above it disqualifies the student lives only in the
 * Placement Policy -- two documents that name each other but neither restates
 * the other's number.
 *
 * Measured on the JVM harness before this existed: "if I have one live
 * backlog can I sit for the Ratnagiri Softworks drive" retrieved both
 * documents and answered "Ratnagiri Softworks will conduct a campus placement
 * drive on 16 September 2026." -- the opening sentence of the drive notice,
 * true and useless, because nothing had ever compared the stated count
 * against the cap.
 *
 * Every fixture is verbatim `content` from the shipped bundle, chunk id in
 * the name, exactly as [MultiHopTest] and [AnswerCheckTest] do it:
 *
 *   sqlite3 brain.db "SELECT content FROM chunks WHERE id = 435"
 */
class BacklogEligibilityTest {

    private fun chunk(id: Long, doc: String, section: String?, content: String) =
        RetrievedChunk(id, doc, section, content, 0.0)

    /** chunks.id = 435 -- the Placement Policy's backlog rule. */
    private val placementPolicy = chunk(
        435, "svc_08_placement_policy.md", "Subject: Placement Policy, Academic Year 2026-27",
        "Backlog policy: The maximum live backlog count for each drive is set by the recruiting " +
            "company (see the per-drive cut-off in PLACEMENTS); a student with more live backlogs " +
            "than a drive's stated limit is not permitted to register for that specific drive " +
            "regardless of CGPA  \n" +
            "Dress code: Formal business attire is mandatory for every drive: full-sleeve shirt and " +
            "formal trousers for men, formal Indian or Western business wear for women; jeans, " +
            "T-shirts and sportswear are not permitted"
    )

    /** chunks.id = 437 -- a drive whose cap is 0, name and cap in the same chunk. */
    private val ratnagiriDrive = chunk(
        437, "svc_09_placement_drive_ratnagiri_softworks.md",
        "Subject: Campus Placement Drive: Ratnagiri Softworks",
        "Ratnagiri Softworks will conduct a campus placement drive on 16 September 2026. " +
            "Registration closes on 08 September 2026. Interested and eligible students must " +
            "register with the Placement Cell before the deadline; the institute's Placement " +
            "Policy (one-offer rule, backlog policy, dress code) applies to this drive.  \n" +
            "| Field                         | Detail                                       |\n" +
            "|-------------------------------|----------------------------------------------|\n" +
            "| Role                          | Software Engineer Trainee                    |\n" +
            "| Minimum CGPA                  | 7.0                                          |\n" +
            "| Maximum Live Backlogs Allowed | 0                                            |"
    )

    /** chunks.id = 449 -- a drive whose cap is 2, so a stated count can clear it. */
    private val vishalgadDrive = chunk(
        449, "svc_12_placement_drive_vishalgad_power_systems.md",
        "Subject: Campus Placement Drive: Vishalgad Power Systems",
        "Vishalgad Power Systems will conduct a campus placement drive on 28 October 2026. " +
            "Registration closes on 20 October 2026. Interested and eligible students must " +
            "register with the Placement Cell before the deadline; the institute's Placement " +
            "Policy (one-offer rule, backlog policy, dress code) applies to this drive.  \n" +
            "| Field                         | Detail                                        |\n" +
            "|-------------------------------|------------------------------------------------|\n" +
            "| Role                          | Graduate Engineer Trainee (Electronics)       |\n" +
            "| Minimum CGPA                  | 6.2                                           |\n" +
            "| Maximum Live Backlogs Allowed | 2                                             |"
    )

    /** chunks.id = 441 -- a second drive, present only to make ambiguity real. */
    private val konkangiriDrive = chunk(
        441, "svc_10_placement_drive_konkangiri_analytics.md",
        "Subject: Campus Placement Drive: Konkangiri Analytics",
        "Konkangiri Analytics will conduct a campus placement drive on 30 September 2026. " +
            "Registration closes on 22 September 2026. Interested and eligible students must " +
            "register with the Placement Cell before the deadline; the institute's Placement " +
            "Policy (one-offer rule, backlog policy, dress code) applies to this drive.  \n" +
            "| Field                         | Detail                                       |\n" +
            "| Maximum Live Backlogs Allowed | 0                                            |"
    )

    // --- the composition, directly ------------------------------------------

    @Test fun `a stated count above the named drive's cap is refused, quoting the policy rule`() {
        val q = AnswerCheck.parse("if I have 1 backlog can I sit for the Ratnagiri Softworks drive")
        val out = AnswerCheck.backlogAgainstDrive(q, listOf(ratnagiriDrive, placementPolicy))
        assertNotNull(out)
        assertEquals(
            "No — you stated 1, and Ratnagiri Softworks allows a maximum of 0 live backlogs. " +
                "Backlog policy: The maximum live backlog count for each drive is set by the " +
                "recruiting company (see the per-drive cut-off in PLACEMENTS); a student with " +
                "more live backlogs than a drive's stated limit is not permitted to register for " +
                "that specific drive regardless of CGPA.",
            out!!.text,
        )
        // Both documents, not whichever ranked first.
        assertEquals(setOf(0, 1), out.chunkIndices.toSet())
    }

    @Test fun `a stated count within the named drive's cap is accepted, quoting the same rule`() {
        val q = AnswerCheck.parse("can I sit for the Vishalgad Power Systems drive with 1 backlog")
        val out = AnswerCheck.backlogAgainstDrive(q, listOf(placementPolicy, vishalgadDrive))
        assertNotNull(out)
        assertTrue("must accept, got: ${out!!.text}", out.text.startsWith("Yes on backlogs"))
        assertTrue(out.text.contains("Vishalgad Power Systems allows up to 2 live backlogs"))
    }

    @Test fun `a spelled-out count is read the same as a numeral`() {
        val digit = AnswerCheck.backlogAgainstDrive(
            AnswerCheck.parse("if I have 1 live backlog can I sit for the Ratnagiri Softworks drive"),
            listOf(ratnagiriDrive, placementPolicy),
        )
        val word = AnswerCheck.backlogAgainstDrive(
            AnswerCheck.parse("if I have one live backlog can I sit for the Ratnagiri Softworks drive"),
            listOf(ratnagiriDrive, placementPolicy),
        )
        assertNotNull(digit)
        assertEquals(digit!!.text, word!!.text)
    }

    @Test fun `no drive named is refused rather than guessed at`() {
        // Measured shape: a bare "backlog" question routinely retrieves several
        // drives' cap rows at once. Naming none of them is not evidence for
        // picking one -- the same refusal [applyToStated] makes on two general
        // attendance minima.
        val q = AnswerCheck.parse("can I sit for a placement drive with 1 backlog")
        assertNull(AnswerCheck.backlogAgainstDrive(q, listOf(ratnagiriDrive, placementPolicy)))
    }

    @Test fun `two drives in view and only one named is not ambiguous`() {
        val q = AnswerCheck.parse("can I sit for the Vishalgad Power Systems drive with 1 backlog")
        val out = AnswerCheck.backlogAgainstDrive(
            q, listOf(ratnagiriDrive, vishalgadDrive, konkangiriDrive, placementPolicy))
        assertNotNull(out)
        assertTrue(out!!.text.contains("Vishalgad Power Systems"))
    }

    @Test fun `no verdict without the policy's rule sentence in view`() {
        // The cap is a number; the policy sentence is what makes it a rule.
        // Retrieval that misses the policy chunk must not invent one.
        val q = AnswerCheck.parse("if I have 1 backlog can I sit for the Ratnagiri Softworks drive")
        assertNull(AnswerCheck.backlogAgainstDrive(q, listOf(ratnagiriDrive)))
    }

    @Test fun `a name and its cap are matched by document, not by chunk order`() {
        // The table sometimes splits across chunks -- the opening sentence in
        // one, the cap row in the next -- and retrieval re-ranks, so the two
        // need not be adjacent in the list handed to this function.
        val nameOnly = chunk(
            436, "svc_09_placement_drive_ratnagiri_softworks.md",
            "Konkan Ratna Institute of Engineering and Technology",
            "Ratnagiri Softworks will conduct a campus placement drive on 16 September 2026."
        )
        val capOnly = chunk(
            438, "svc_09_placement_drive_ratnagiri_softworks.md", null,
            "| Maximum Live Backlogs Allowed | 0                                            |"
        )
        val q = AnswerCheck.parse("if I have 1 backlog can I sit for the Ratnagiri Softworks drive")
        // capOnly listed BEFORE nameOnly on purpose.
        val out = AnswerCheck.backlogAgainstDrive(q, listOf(placementPolicy, capOnly, nameOnly))
        assertNotNull(out)
        assertTrue(out!!.text.contains("Ratnagiri Softworks allows a maximum of 0"))
    }

    @Test fun `no backlog stated is not a candidate at all`() {
        val q = AnswerCheck.parse("what is the minimum CGPA for the Ratnagiri Softworks drive")
        assertNull(AnswerCheck.backlogAgainstDrive(q, listOf(ratnagiriDrive, placementPolicy)))
    }

    // --- through the composer, end to end ------------------------------------

    @Test fun `the composer cites both documents, not just whichever ranked first`() {
        // ratnagiriDrive ranked first here on purpose -- before chunk indices
        // were threaded through, the passage list was retrieval rank alone,
        // so a verdict built from BOTH documents could still fail to show the
        // second one if it had not also made the top three.
        val out = AnswerComposer.compose(
            "if I have 1 backlog can I sit for the Ratnagiri Softworks drive",
            listOf(ratnagiriDrive, konkangiriDrive, vishalgadDrive, placementPolicy),
        )
        assertFalse(out.abstained)
        assertTrue(out.lead.startsWith("No — you stated 1"))
        val headings = out.passages.map { it.heading }
        assertTrue("policy passage missing: $headings",
            headings.any { it.contains("Placement Policy") })
        assertTrue("drive passage missing: $headings",
            headings.any { it.contains("Ratnagiri Softworks") })
    }
}
