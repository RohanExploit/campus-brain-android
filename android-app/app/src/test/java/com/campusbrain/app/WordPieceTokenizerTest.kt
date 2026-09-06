package com.campusbrain.app

import com.campusbrain.app.embed.WordPieceTokenizer
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Ground truth captured from HuggingFace's BertTokenizer for
 * sentence-transformers/all-MiniLM-L6-v2 -- the tokenizer that produced the
 * corpus vectors in brain.db.
 *
 * This is the guard that matters. A tokenizer that is subtly wrong does not
 * throw: it returns ids, the model returns a 384d vector, and the rankings look
 * plausible while being computed against a corpus tokenized differently.
 * Nothing downstream can notice. Every case below is a real string from this
 * corpus, chosen for a specific hazard:
 *
 *   - "KRIET"      an all-caps out-of-vocabulary acronym (k ##rie ##t)
 *   - "Rs. 500/-"  punctuation that must split rather than vanish
 *   - "BTCOL506"   a subject code splitting into four pieces
 *   - "Hajare"     an Indian surname absent from the vocabulary
 *   - "condonation" domain vocabulary that splits (condo ##nation)
 */
class WordPieceTokenizerTest {

    // Gradle runs unit tests with the module directory as the working dir, but
    // that has moved between AGP versions and differs when run from an IDE.
    // Several candidates are tried because the first version of this test
    // resolved none of them, and every case then skipped -- ten green-looking
    // skips guarding nothing, which is worse than no test at all.
    private val vocabFile: File? = listOf(
        "src/main/assets/minilm/vocab.txt",
        "app/src/main/assets/minilm/vocab.txt",
        "android-app/app/src/main/assets/minilm/vocab.txt",
        "../app/src/main/assets/minilm/vocab.txt",
    ).map(::File).firstOrNull { it.exists() }

    private fun tokenizer(): WordPieceTokenizer? {
        // The vocab ships beside the 86MB model and is gitignored, so a checkout
        // without the assets skips rather than fails.
        val f = vocabFile ?: return null
        return f.inputStream().use { WordPieceTokenizer.fromVocab(it) }
    }

    /**
     * Fails loudly when the assets ARE present but the path logic above missed
     * them -- otherwise a stale path silently disarms every case in this file.
     */
    @Test fun `vocab is found when the assets exist`() {
        val exists = File(System.getProperty("user.dir"))
            .walkTopDown().maxDepth(6)
            .any { it.name == "vocab.txt" && it.path.contains("minilm") }
        if (exists) {
            org.junit.Assert.assertNotNull(
                "vocab.txt exists under this module but none of the candidate " +
                    "paths matched, so every tokenizer test would skip",
                vocabFile,
            )
        }
    }

    private fun check(text: String, expectedPieces: String, expectedIds: String) {
        val t = tokenizer()
        assumeTrue("minilm assets absent; run scripts/export_minilm_onnx.py", t != null)
        assertEquals("pieces for: $text", expectedPieces, t!!.tokenize(text).joinToString(" "))
        assertEquals("ids for: $text", expectedIds, t.encode(text).ids.joinToString(","))
    }

    @Test fun `plain question`() = check(
        "What is the minimum attendance percentage?",
        "what is the minimum attendance percentage ?",
        "101,2054,2003,1996,6263,5270,7017,1029,102",
    )

    @Test fun `acronyms out of vocabulary split into pieces`() = check(
        "KRIET is affiliated to DBATU, Lonere.",
        "k ##rie ##t is affiliated to db ##at ##u , lone ##re .",
        "101,1047,7373,2102,2003,6989,2000,16962,4017,2226,1010,10459,2890,1012,102",
    )

    @Test fun `punctuation currency and digits`() = check(
        "Fee payment deadline: 15 August 2026 (late fee Rs. 500/-).",
        "fee payment deadline : 15 august 202 ##6 ( late fee rs . 500 / - ) .",
        "101,7408,7909,15117,1024,2321,2257,16798,2575,1006,2397,7408,12667,1012," +
            "3156,1013,1011,1007,1012,102",
    )

    @Test fun `subject code`() = check(
        "Subject BTCOL506 carries 2 credits.",
        "subject bt ##col ##50 ##6 carries 2 credits .",
        "101,3395,18411,25778,12376,2575,7883,1016,6495,1012,102",
    )

    @Test fun `indian names absent from the vocabulary`() = check(
        "Marksheet of Hajare Nikhil Rajendra",
        "marks ##hee ##t of ha ##jar ##e nik ##hil raj ##endra",
        "101,6017,21030,2102,1997,5292,16084,2063,23205,19466,11948,19524,102",
    )

    @Test fun `slash separated abbreviations`() = check(
        "scholarship eligibility for SC/ST students",
        "scholarship eligibility for sc / st students",
        "101,6566,11395,2005,8040,1013,2358,2493,102",
    )

    @Test fun `domain vocabulary splits`() = check(
        "condonation", "condo ##nation", "101,25805,9323,102",
    )

    @Test fun `no space before punctuation`() = check(
        "Rs.500/-", "rs . 500 / -", "101,12667,1012,3156,1013,1011,102",
    )

    @Test fun `every encoding is wrapped in CLS and SEP`() {
        val t = tokenizer()
        assumeTrue(t != null)
        val e = t!!.encode("attendance")
        assertEquals(101L, e.ids.first())
        assertEquals(102L, e.ids.last())
        // No padding is emitted, so the mask is all ones -- mean pooling divides
        // by its sum, and a stray zero would skew the vector.
        assertEquals(e.ids.size, e.attentionMask.size)
        assertEquals(e.ids.size.toLong(), e.attentionMask.sum())
    }

    @Test fun `long input is truncated leaving room for both special tokens`() {
        val t = tokenizer()
        assumeTrue(t != null)
        val e = t!!.encode((1..500).joinToString(" ") { "attendance" }, maxLen = 32)
        assertEquals(32, e.ids.size)
        assertEquals(101L, e.ids.first())
        assertEquals(102L, e.ids.last())
    }
}
