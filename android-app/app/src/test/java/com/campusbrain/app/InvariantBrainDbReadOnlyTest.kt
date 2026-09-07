package com.campusbrain.app

import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `docs/architecture.md` §9, invariant I5: `brain.db` is read-only; user data
 * lives in `user_corpus.db`. Breaking it looks like an app update silently
 * deleting every imported document, every entitlement and every licence, on
 * the release build, weeks after the change, with nothing in the diff that
 * looks like a delete -- because `BrainDb.open` re-copies the bundled asset
 * whenever `built_at_utc` changes, and anything written into `brain.db` itself
 * would be destroyed by the next release.
 *
 * The actual guarantee is `BrainDb.openWith` running `PRAGMA query_only = ON`
 * on every connection, including the JVM test one. There is already a
 * behavioural test for this
 * (`JdbcSQLiteAdapterTest.'query_only really is read-only, so the bundle
 * cannot be written'`, which attempts a real `CREATE TABLE` against an open
 * connection and asserts it fails) -- this test does not duplicate that. It
 * instead pins the *source*, so a refactor that quietly drops the PRAGMA line
 * from `openWith` (while some other connection elsewhere happens to still
 * enforce read-only-ness, or while nobody notices the behavioural test itself
 * gets deleted alongside it) is caught here too.
 *
 * A source scan is what the task brief calls for when a behavioural test isn't
 * reachable without a device -- this one *is* reachable on the JVM (see
 * `JdbcSQLiteAdapterTest`), so treat this as belt-and-suspenders on the source,
 * not a replacement for that behavioural coverage.
 */
class InvariantBrainDbReadOnlyTest {

    @Test fun `BrainDb still applies PRAGMA query_only = ON when opening a connection`() {
        val file = brainDbSource()
        val text = file.readText()

        val hasPragma = Regex(
            """PRAGMA\s+query_only\s*=\s*ON""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)

        if (!hasPragma) {
            fail(
                "BrainDb.kt no longer contains `PRAGMA query_only = ON`. This is " +
                    "the entire enforcement of docs/architecture.md §9 invariant " +
                    "I5 -- brain.db must be read-only so a bad write can never " +
                    "desync it from the catalog that produced it, and so a future " +
                    "app update's re-copy-on-stamp-change logic can never be " +
                    "mistaken for a place it is safe to persist writes. Restore " +
                    "the PRAGMA in BrainDb.openWith; do not just restore a " +
                    "behavioural test around it -- see " +
                    "JdbcSQLiteAdapterTest for that half."
            )
        }
    }

    @Test fun `the pragma is applied inside openWith, not merely present in a comment`() {
        val file = brainDbSource()
        val lines = file.readLines()
        val pragmaLineIdx = lines.indexOfFirst {
            Regex("""PRAGMA\s+query_only\s*=\s*ON""", RegexOption.IGNORE_CASE).containsMatchIn(it) &&
                !it.trim().startsWith("//") &&
                !it.trim().startsWith("*")
        }

        if (pragmaLineIdx < 0) {
            fail(
                "no non-comment line in BrainDb.kt executes `PRAGMA query_only = " +
                    "ON` -- a mention in a doc comment would satisfy the weaker " +
                    "text-scan test above without ever being applied to a real " +
                    "connection. It must appear inside an executable call such as " +
                    "`conn.execSQL(\"PRAGMA query_only = ON\")` in `openWith`."
            )
        }
    }

    private fun brainDbSource(): File {
        val suffix = "src/main/java/com/campusbrain/app/data/BrainDb.kt"
        val candidates = listOf(
            File(suffix),
            File("app/$suffix"),
            File("android-app/app/$suffix"),
            File("android-app/$suffix"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: fail(
                "could not find BrainDb.kt from any candidate path; " +
                    "user.dir=${System.getProperty("user.dir")}; tried: " +
                    candidates.joinToString(", ") { it.absolutePath }
            ) as Nothing
    }
}
