package com.campusbrain.app

import com.campusbrain.app.diag.CrashLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The crash log, and the two properties that make it shippable: it cannot fill
 * a phone, and it cannot swallow the crash it was written to record.
 *
 * All of it runs on the JVM. `CrashLog.format` takes its device facts as a
 * parameter precisely so that it can -- `Build.MODEL` is null and
 * `Build.VERSION.SDK_INT` is 0 in the unit-test `android.jar`, which is the
 * same reason `SupabaseAuth` reaches for `java.util.Base64`.
 */
class CrashLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private val facts = CrashLog.Facts(
        appVersion = "0.1 (1)",
        androidRelease = "16",
        sdkInt = 36,
        model = "test-device",
    )

    private fun entry(at: String, trace: String = "java.lang.RuntimeException: boom\n\tat A.b(A.kt:1)") =
        CrashLog.format(facts, at, "main", trace)

    // --- the entry ----------------------------------------------------------

    @Test fun `an entry names the version, the platform, the device and the thread`() {
        val text = entry("2026-01-01T00:00:00Z")
        assertTrue(text.startsWith(CrashLog.MARKER))
        listOf("2026-01-01T00:00:00Z", "0.1 (1)", "Android 16", "SDK 36", "test-device",
               "thread main", "RuntimeException: boom", "A.kt:1")
            .forEach { assertTrue("the entry does not mention $it", text.contains(it)) }
    }

    /**
     * A `StackOverflowError` arrives with tens of thousands of frames. Without
     * this cap one entry would spend the whole file on the least interesting
     * part of itself -- the repeated middle -- and the truncation is at the
     * end because the top frames are the ones that say what happened.
     */
    @Test fun `one enormous trace cannot spend the whole file`() {
        val huge = (1..40_000).joinToString("\n") { "\tat com.example.Deep.recurse(Deep.kt:$it)" }
        val text = CrashLog.format(facts, "2026-01-01T00:00:00Z", "main", huge, traceCap = 500)
        assertTrue("the cap was not applied", text.length < 900)
        assertTrue("the truncation is not admitted", text.contains("trace truncated"))
        assertTrue("the top frames were dropped", text.contains("Deep.kt:1)"))
    }

    // --- the file -----------------------------------------------------------

    @Test fun `the newest crash is the first thing in the file`() {
        val first = entry("2026-01-01T00:00:00Z")
        val second = entry("2026-01-02T00:00:00Z")
        val log = CrashLog.compose(CrashLog.compose("", first), second)
        assertTrue(log.startsWith(CrashLog.MARKER + "2026-01-02"))
        assertTrue(log.contains("2026-01-01"))
    }

    /**
     * The property the cap exists for: a crash loop is an app that dies on
     * every launch, which is exactly when this runs most often and exactly the
     * phone least able to spare the space.
     */
    @Test fun `a hundred crashes do not grow the file past the cap`() {
        val cap = 4_000
        var log = ""
        repeat(100) { i ->
            log = CrashLog.compose(log, entry("2026-01-01T00:00:%02dZ".format(i % 60)), cap)
            assertTrue("the file grew past the cap at crash $i", log.length <= cap)
        }
        assertTrue("the newest crash was dropped instead of the oldest", log.contains("thread main"))
    }

    /**
     * Whole entries only.
     *
     * A file that begins halfway through a stack trace reads as corruption,
     * and whoever opens it goes looking for a bug in the logging rather than
     * in the thing that crashed.
     */
    @Test fun `truncation drops whole entries and never cuts one in half`() {
        val one = entry("2026-01-01T00:00:01Z")
        val cap = (one.length * 2.5).toInt()
        var log = ""
        repeat(8) { i -> log = CrashLog.compose(log, entry("2026-01-01T00:00:0${i}Z"), cap) }

        val kept = CrashLog.entries(log)
        assertEquals("as many whole entries as fit, and no fragment", 2, kept.size)
        kept.forEach {
            assertTrue("an entry does not start at a marker", it.startsWith(CrashLog.MARKER))
            assertTrue("an entry lost its trace", it.contains("A.kt:1"))
        }
    }

    /** A single entry larger than the whole cap is cut rather than refused:
     * the first frames of a trace are worth more than an empty file. */
    @Test fun `an entry bigger than the cap is kept, cut, and alone`() {
        val log = CrashLog.compose("older", entry("2026-01-01T00:00:00Z"), cap = 40)
        assertEquals(40, log.length)
        assertTrue(log.startsWith(CrashLog.MARKER))
        assertFalse(log.contains("older"))
    }

    @Test fun `a file this did not write is dropped rather than kept forever`() {
        assertEquals(emptyList<String>(), CrashLog.entries(""))
        assertEquals(emptyList<String>(), CrashLog.entries("a log from somewhere else\n"))
        val log = CrashLog.compose("a log from somewhere else\n", entry("2026-01-01T00:00:00Z"))
        assertFalse(log.contains("somewhere else"))
    }

    // --- what the self test shows -------------------------------------------

    @Test fun `a phone that has never crashed says so and counts nothing`() {
        val section = CrashLog.section("")
        assertTrue(section.contains("NO CRASHES RECORDED"))
        assertFalse(section.contains(CrashLog.MARKER))
    }

    @Test fun `the section counts the crashes and says nothing was sent`() {
        val one = CrashLog.section(CrashLog.compose("", entry("2026-01-01T00:00:00Z")))
        assertTrue(one.contains("1 crash,"))

        val two = CrashLog.section(
            CrashLog.compose(CrashLog.compose("", entry("2026-01-01T00:00:00Z")),
                entry("2026-01-02T00:00:00Z"))
        )
        assertTrue(two.contains("2 crashes,"))
        assertTrue("the reader is not told where it has been", two.contains("nothing has been sent"))
    }

    // --- the handler ---------------------------------------------------------

    private fun crash(handler: Thread.UncaughtExceptionHandler, error: Throwable) =
        handler.uncaughtException(Thread.currentThread(), error)

    /**
     * The rule with no exceptions: the platform's handler is what kills the
     * process and shows the dialog, so it is called whatever happens here.
     */
    @Test fun `the handler writes and then hands the crash on`() {
        val file = File(tmp.newFolder(), "diagnostics/crash.log")
        val seen = mutableListOf<Throwable>()
        val handler = CrashLog.handlerFor(file, facts) { _, e -> seen.add(e) }

        val boom = RuntimeException("boom")
        crash(handler, boom)

        assertEquals(listOf<Throwable>(boom), seen)
        assertTrue("the directory was not created", file.isFile)
        assertTrue(file.readText().contains("RuntimeException: boom"))
    }

    /**
     * The write is best-effort and the chain is not. A log directory that
     * cannot be created -- a full disk, a locked-down profile -- must cost the
     * report, never the crash.
     */
    @Test fun `a failed write still hands the crash on`() {
        // A regular file where the log's parent directory should be, so
        // mkdirs() cannot succeed and neither can the write.
        val blocked = tmp.newFile("not-a-directory")
        val file = File(blocked, "crash.log")
        val seen = mutableListOf<Throwable>()
        val handler = CrashLog.handlerFor(file, facts) { _, e -> seen.add(e) }

        crash(handler, IllegalStateException("nope"))

        assertEquals(1, seen.size)
        assertFalse(file.exists())
    }

    /** No previous handler is a JVM-test artefact -- Android always has one --
     * and it must not become a NullPointerException inside a crash handler. */
    @Test fun `no previous handler is survivable`() {
        val file = File(tmp.newFolder(), "crash.log")
        crash(CrashLog.handlerFor(file, facts, null), RuntimeException("boom"))
        assertTrue(file.isFile)
    }

    /**
     * Re-entrancy. A `StackOverflowError` can overflow again inside the write
     * and an OOM can fail the allocation it needs; either recurses into the
     * handler, and the recursion must not loop and must not lose the chain.
     */
    @Test fun `a crash inside the handler does not loop`() {
        val file = File(tmp.newFolder(), "crash.log")
        val depth = intArrayOf(0)
        lateinit var handler: Thread.UncaughtExceptionHandler
        handler = CrashLog.handlerFor(file, facts) { t, e ->
            depth[0]++
            if (depth[0] < 3) handler.uncaughtException(t, e)
        }
        crash(handler, StackOverflowError("deep"))
        assertEquals("the nested calls did not terminate", 3, depth[0])
        // The outermost call is the one that wrote; the nested ones saw the
        // guard and went straight to the chain.
        assertEquals(1, CrashLog.entries(file.readText()).size)
    }

    /** Installing twice would chain the handler to itself and write every
     * crash twice. Guarded, and asserted here because it is a one-line field
     * that a later refactor could drop without any test noticing. */
    @Test fun `installing is idempotent`() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        try {
            val file = File(tmp.newFolder(), "crash.log")
            CrashLog.install(file, facts)
            val installed = Thread.getDefaultUncaughtExceptionHandler()
            assertNotNull(installed)
            CrashLog.install(File(tmp.newFolder(), "other.log"), facts)
            assertEquals(installed, Thread.getDefaultUncaughtExceptionHandler())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prior)
        }
    }

    // --- the boundary --------------------------------------------------------

    /**
     * The privacy claim, as far as a test can carry it.
     *
     * It cannot prove the log is free of user content -- a stack trace carries
     * whatever message the throwing code put in it -- so what it pins is the
     * part that IS structural: nothing but the facts passed in and the trace
     * itself reaches the file. There is no parameter here through which a
     * query, a document, a record or a token could arrive, which is the same
     * argument `ControlPlane.usagePayload` makes about its own signature.
     */
    @Test fun `an entry contains nothing but the facts and the trace`() {
        val text = entry("2026-01-01T00:00:00Z", trace = "java.lang.RuntimeException: boom")
        val expected = setOf(
            "===", "crash", "2026-01-01T00:00:00Z", "app", "0.1", "(1)", "·", "Android", "16",
            "(SDK", "36)", "test-device", "thread", "main",
            "java.lang.RuntimeException:", "boom",
        )
        val actual = text.split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
        assertEquals("the entry grew a field", expected, actual)
    }
}
