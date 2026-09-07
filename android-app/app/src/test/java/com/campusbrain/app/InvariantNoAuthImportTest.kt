package com.campusbrain.app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The product's central commercial claim, made structural.
 *
 * `docs/architecture.md` section 9, invariant I1 / I1a: **retrieval must never
 * gate on auth or licence state**. A user with no licence, an expired one, or a
 * storage failure still gets every answer, in airplane mode, forever. The way
 * that promise is actually held today is by absence -- nothing in `retrieval/`
 * or `answer/` references `com.campusbrain.app.data.auth` at all -- and the
 * architecture doc calls this the single highest-value missing test in the
 * repo, because that absence compiles cleanly either way: adding an import of
 * `Licensing` or `Identity` into the ask path breaks nothing else, and every
 * one of the other 384 tests stays green while the airplane-mode demo quietly
 * starts expiring after a licence token's lifetime.
 *
 * This test converts that absence from convention to a source-level check: it
 * walks every `.kt` file under `retrieval/` and `answer/` and fails, by name
 * and line, on the first reference to the auth package -- whether that
 * reference arrives as an `import` or as a fully-qualified use with no import
 * at all.
 *
 * If you are reading this because it just went red: do not weaken the
 * assertion. Move the auth-touching code out of `retrieval/`/`answer/`, or
 * route the decision through a caller that already sits outside those
 * packages (see `MainActivity`, `data/DocumentIngest`, and the handful of
 * `ui/` screens named in `CLAUDE.md` as the only legitimate callers of
 * `Identity`/`Licensing`).
 */
class InvariantNoAuthImportTest {

    private val forbidden = "com.campusbrain.app.data.auth"

    @Test fun `retrieval and answer never reference data auth`() {
        val roots = listOf("retrieval", "answer").map { pkg ->
            pkg to sourceDir(pkg)
        }

        val violations = mutableListOf<String>()
        for ((pkg, dir) in roots) {
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { idx, line ->
                        if (line.contains(forbidden)) {
                            violations += "${file.path}:${idx + 1}: $line"
                        }
                    }
                }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Retrieval must never gate on auth or licence state -- that is " +
                    "this product's entire commercial claim (docs/architecture.md " +
                    "§9, I1/I1a), and the guarantee is held ONLY by the " +
                    "absence of any reference from retrieval/ or answer/ to " +
                    "$forbidden. That absence just broke:\n" +
                    violations.joinToString("\n") +
                    "\n\nMove this code out of retrieval/ or answer/, or reroute " +
                    "it through MainActivity/DocumentIngest/ui as documented in " +
                    "CLAUDE.md -- do not delete or loosen this test."
            )
        }
    }

    @Test fun `the two scanned source directories actually exist and are non-empty`() {
        // A silently-empty walk is a test that always passes for the wrong
        // reason. Prove the directories were really found before trusting the
        // test above.
        for (pkg in listOf("retrieval", "answer")) {
            val dir = sourceDir(pkg)
            val ktFiles = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            assertTrue(
                "expected .kt files under ${dir.absolutePath} (pkg=$pkg) but found none " +
                    "-- the source-scan test above would pass vacuously",
                ktFiles.isNotEmpty()
            )
        }
    }

    /**
     * Locates `app/src/main/java/com/campusbrain/app/<pkg>` regardless of
     * whether Gradle's working directory for this test task is the `app`
     * module dir or something else -- probed rather than assumed, per the
     * task brief. Fails loudly, listing every candidate tried, rather than
     * silently walking an empty directory.
     */
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
