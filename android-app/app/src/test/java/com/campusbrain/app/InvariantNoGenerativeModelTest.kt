package com.campusbrain.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `docs/architecture.md` §9, invariant I6/I6a: **a correct abstention beats a
 * confident near-miss**, and today that is true because `AnswerComposer` is
 * extractive by construction -- answers are sentences lifted from retrieved
 * chunks, verbatim table cells, or deterministic SQL. There is no generative
 * model anywhere in the local answer path, and nothing currently asserts that
 * absence: adding one compiles cleanly and every behavioural test in the repo
 * stays green while the app starts inventing sentences instead of quoting
 * them.
 *
 * `answer/CloudAnswer.kt` is the one deliberate exception. It is the optional,
 * config-gated cloud fallback (`docs/architecture.md` §9 I6's own caveat, and
 * `CLAUDE.md`'s "one important caveat"): it is reached only after
 * `AnswerComposer` has already abstained, is labelled in the UI as coming from
 * outside the college's documents, and only exists at all when an operator has
 * hand-installed a `config.json` the app never writes itself. So it is excluded
 * by name below, not silently allowed by a loose pattern.
 *
 * This test scans every other `.kt` file under `answer/` for two kinds of
 * evidence of a generative dependency creeping into the extractive path: an
 * `import` of a known generation library, and a handful of unmistakable
 * generation call-shapes (`generateContent(`, `chatCompletion(`, a raw
 * `Interpreter(` load of a `.tflite`/`.gguf` model) that would work even
 * without a recognizable import.
 */
class InvariantNoGenerativeModelTest {

    // Package prefixes that mean "a generative model library is here", not
    // "some unrelated org.tensorflow-shaped utility". Kept as prefixes so a
    // subpackage still matches.
    private val forbiddenImportPrefixes = listOf(
        "ai.onnxruntime",
        "com.microsoft.onnxruntime",
        "org.tensorflow",
        "com.google.ai.generativelanguage",
        "com.google.ai.client.generativeai",
        "com.aallam.openai",
        "com.theokanning.openai",
        "ai.djl",
        "org.pytorch",
        "dev.langchain4j",
        "com.knuddels.jtokkit",
    )

    // Call/identifier shapes that would work even through a fully-qualified
    // reference with no matching import line above.
    private val forbiddenSymbols = listOf(
        "GenerativeModel(",
        "generateContent(",
        "chatCompletion(",
        "ChatCompletion(",
        ".gguf",
        ".tflite",
    )

    private val excludedFileName = "CloudAnswer.kt"

    @Test fun `the answer path has no generative-model dependency, except the excluded cloud fallback`() {
        val dir = sourceDir("answer")
        val files = dir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != excludedFileName }
            .toList()

        val violations = mutableListOf<String>()
        files.forEach { file ->
            file.readLines().forEachIndexed { idx, line ->
                val trimmed = line.trim()
                val importHit = trimmed.startsWith("import") &&
                    forbiddenImportPrefixes.any { trimmed.removePrefix("import").trim().startsWith(it) }
                val symbolHit = forbiddenSymbols.any { line.contains(it) }
                if (importHit || symbolHit) {
                    violations += "${file.path}:${idx + 1}: $line"
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "AnswerComposer is extractive by design (docs/architecture.md " +
                    "§9, I6/I6a) -- answers are lifted sentences, table cells, or " +
                    "SQL results, never a generated string, so a correct " +
                    "abstention always beats a confident near-miss. That design " +
                    "just picked up a generative-model dependency outside the " +
                    "one deliberate exception ($excludedFileName, the config-gated " +
                    "cloud fallback):\n" +
                    violations.joinToString("\n") +
                    "\n\nIf this is intentional, it belongs behind the same " +
                    "config-gate as CloudAnswer, clearly labelled in the UI as " +
                    "not from the college's documents -- not silently inside the " +
                    "local, always-on path."
            )
        }
    }

    @Test fun `the excluded file itself still exists, so the exclusion is not silently vacuous`() {
        val dir = sourceDir("answer")
        val excluded = File(dir, excludedFileName)
        assertTrue(
            "expected ${excluded.absolutePath} to exist -- if CloudAnswer.kt was " +
                "renamed or moved, the exclusion above no longer excludes anything " +
                "and this test would stop meaning what it says",
            excluded.isFile
        )
    }

    private fun sourceDir(pkg: String): File {
        val suffix = "src/main/java/com/campusbrain/app/$pkg"
        val candidates = listOf(
            File(suffix),
            File("app/$suffix"),
            File("android-app/app/$suffix"),
            File("android-app/$suffix"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: fail(
                "could not find the $pkg source directory from any candidate " +
                    "path; user.dir=${System.getProperty("user.dir")}; tried: " +
                    candidates.joinToString(", ") { it.absolutePath }
            ) as Nothing
    }
}
