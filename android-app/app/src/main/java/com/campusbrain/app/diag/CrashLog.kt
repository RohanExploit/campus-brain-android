package com.campusbrain.app.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * An on-device crash log, and deliberately not a crash reporting SDK.
 *
 * The app has had none, which means a failure on a phone nobody here owns is
 * invisible: a student says "it closed" and there is nothing to look at. The
 * obvious fix is Crashlytics or Sentry, and it is not available to this
 * product. **The corpus, the student records and the query text never leave
 * the device** -- that sentence is the entire commercial claim, it is on the
 * welcome screen, and a reporter that uploads a breadcrumb trail from a
 * process that has all three in memory would breach it in the one moment
 * nobody is watching. It would also be a dependency, on a codebase whose HTTP
 * is `HttpURLConnection` and whose JSON is `org.json` for exactly that reason
 * (see `answer/CloudAnswer.kt`).
 *
 * So: a file. Nothing here opens a socket, and there is no method below that
 * could -- the same structural argument [com.campusbrain.app.data.auth.ControlPlane]
 * makes about its own signatures. The student shares it through the Android
 * share sheet, into an app they chose, with the text visible to them before
 * they send it, or they never share it at all and it is eventually overwritten
 * by the next one.
 *
 * ## What may be written here
 *
 * A timestamp, an app version, an Android version, a device model, a thread
 * name and a stack trace. No query text, no corpus content, no student record,
 * no token, no password, no enrolment code. Two things back that up rather
 * than a promise: [format] takes its facts as parameters and has no way to
 * reach the corpus, and the app's own throw sites carry static prose (see
 * `DocumentIngest`, which logs the exception's class name and says in a
 * comment why not the payload). **logcat is deliberately not read** -- it does
 * carry user content, `DocumentIngest` writes an imported document's id into
 * it, and shipping that into a share sheet would be the leak this file exists
 * to avoid.
 *
 * ## Why the split into pure functions
 *
 * `Build.MODEL` is null and `Build.VERSION.SDK_INT` is 0 in the unit-test
 * `android.jar`, so a formatter that read them could not be tested off a
 * device -- the same reason `SupabaseAuth` uses `java.util.Base64` over
 * `android.util.Base64`. The facts arrive as a [Facts]; [format] and
 * [compose] are pure, and what is left on the Android side is a file write.
 */
object CrashLog {

    /** Under `filesDir`, so it is private to the app and not on the SD card.
     * A directory of its own keeps it clear of `brain.db`, of the corpus stamp
     * file `BrainDb` writes beside it, and of `user_corpus.db`; none of those
     * is ever re-copied over this, and none of this is ever copied over them.
     * `android:allowBackup="false"` already keeps the whole of `filesDir` out
     * of cloud backup. Not `cacheDir`: diagnostics that the system may delete
     * the night before someone asks for them are worse than none. */
    const val DIR_NAME = "diagnostics"
    const val FILE_NAME = "crash.log"

    /**
     * The whole file, in characters.
     *
     * A crash loop -- a handler that fails on every launch -- must not be able
     * to fill a student's phone, and the cap is what makes that true rather
     * than a hope. Characters, not bytes: a trace is ASCII in practice, and in
     * the worst case UTF-8 triples it, so the file cannot exceed about 96 KB.
     * That is small enough to be the honest limit and large enough for several
     * traces.
     */
    const val FILE_CAP_CHARS = 32 * 1024

    /** One trace. A stack overflow produces tens of thousands of frames and
     * without this the newest entry alone would spend the entire file cap. */
    const val TRACE_CAP_CHARS = 8 * 1024

    /** Every entry starts with this, which is what lets [compose] drop whole
     * old entries instead of cutting one in half. */
    const val MARKER = "=== crash "

    /** A file bigger than the cap could possibly have produced was not written
     * by this, and reading it into memory inside a dying process is not worth
     * doing. Four bytes per character is UTF-8's worst case. */
    private const val MAX_READ_BYTES = 4L * FILE_CAP_CHARS

    /**
     * The context of a crash, none of it user content.
     *
     * [model] is `Build.MODEL`, the manufacturer's model name -- not a name
     * the student typed, which is `Settings.Global.DEVICE_NAME` and is not
     * read anywhere here.
     */
    data class Facts(
        val appVersion: String,
        val androidRelease: String,
        val sdkInt: Int,
        val model: String,
    )

    // --- pure ---------------------------------------------------------------

    /**
     * One entry, newest-first when [compose] puts it at the top of the file.
     *
     * The trace is truncated rather than dropped: the top frames are the ones
     * that say what happened, and half a trace is worth much more than a note
     * saying there was one.
     */
    fun format(
        facts: Facts,
        atUtc: String,
        threadName: String,
        trace: String,
        traceCap: Int = TRACE_CAP_CHARS,
    ): String {
        val body = if (trace.length <= traceCap) trace else
            trace.take(traceCap).trimEnd() + "\n… trace truncated at $traceCap characters"
        return buildString {
            append(MARKER).append(atUtc).append(" ===\n")
            append("app ").append(facts.appVersion)
            append(" · Android ").append(facts.androidRelease)
            append(" (SDK ").append(facts.sdkInt).append(')')
            append(" · ").append(facts.model).append('\n')
            append("thread ").append(threadName).append('\n')
            append(body.trimEnd()).append("\n\n")
        }
    }

    /**
     * The new entry on top, then as much of the old file as fits.
     *
     * Newest first so that truncation drops the crash nobody is asking about,
     * and so the first thing on screen is the one that just happened. Only
     * whole entries are kept: a file that begins mid-trace reads as corruption
     * and would send someone looking for a bug in this file instead of in the
     * one that crashed.
     */
    fun compose(existing: String, entry: String, cap: Int = FILE_CAP_CHARS): String {
        val fresh = if (entry.length <= cap) entry else entry.take(cap)
        val room = cap - fresh.length
        if (room <= 0) return fresh
        val kept = StringBuilder()
        for (old in entries(existing)) {
            if (kept.length + old.length > room) break
            kept.append(old)
        }
        return fresh + kept
    }

    /**
     * Splits a log back into its entries, oldest last.
     *
     * A file with no [MARKER] in it at all was not written by this -- an empty
     * file, or something else's -- and is returned as nothing rather than as
     * one giant entry, so it is dropped on the next write instead of eating
     * the cap forever.
     */
    fun entries(text: String): List<String> {
        val out = mutableListOf<String>()
        var i = text.indexOf(MARKER)
        while (i >= 0) {
            val next = text.indexOf(MARKER, i + MARKER.length)
            out.add(if (next < 0) text.substring(i) else text.substring(i, next))
            i = next
        }
        return out
    }

    /** The trace, causes and all. `printStackTrace` into a string is the whole
     * of it; nothing here inspects or rewrites a message. */
    fun stackTraceOf(error: Throwable): String = runCatching {
        StringWriter().also { w -> PrintWriter(w).use { error.printStackTrace(it) } }.toString()
    }.getOrElse { "stack trace unavailable (${error.javaClass.name})" }

    /**
     * The block the self test appends under its checks.
     *
     * Not a string resource, and that is consistent rather than lazy: every
     * word on that screen is built in code -- "ALL 14 CHECKS PASSED",
     * "[FAIL] " -- because it is a diagnostic wall read by whoever is holding
     * the phone thirty seconds before a demo, not product copy. What it does
     * owe the reader is the two facts they need before tapping share: how many
     * crashes are in here, and that none of it has gone anywhere.
     */
    fun section(log: String): String {
        val found = entries(log)
        if (found.isEmpty()) return "NO CRASHES RECORDED\n\n" +
            "       Nothing has crashed on this phone since the app was installed.\n"
        val n = found.size
        // On the wording of the second sentence: this app never puts a
        // question, a document, a record, a token or a code into an exception
        // message -- that was audited, and DocumentIngest logs an exception's
        // class name rather than its payload for the same reason. What it
        // cannot promise is what a bundled library writes into ITS message, so
        // the claim is scoped to what is enforceable and the traces are shown
        // in full above the share button rather than described.
        return "CRASH LOG — ${if (n == 1) "1 crash" else "$n crashes"}, newest first\n\n" +
            "       Stored on this phone only, and nothing has been sent. It holds the\n" +
            "       times, this app's version, the Android version, the device model and\n" +
            "       the stack traces below. Nothing this app writes into a trace is a\n" +
            "       question, a document, a student record, a password or a code. Read\n" +
            "       it here first — sharing it is your choice and it goes where you say.\n\n" +
            log.trimEnd() + "\n"
    }

    // --- the handler --------------------------------------------------------

    @Volatile private var installed = false

    /**
     * Set for the duration of one handled crash.
     *
     * Not re-entrancy paranoia: a `StackOverflowError` reaching the handler
     * can overflow again inside it, and an OOM can fail the allocation the
     * write needs. Either would recurse into this handler and never reach the
     * chain below, which is the one thing that must always happen.
     *
     * It also serialises two threads crashing at the same instant: the second
     * loses its entry rather than racing the first through a read-modify-write
     * of the same file. Losing the second trace is the cheaper outcome -- the
     * first one is the one that started it, and a file with two half-entries
     * spliced together is worse than either alone.
     */
    @Volatile private var writing = false

    /**
     * Installs the handler, chaining to whatever was there.
     *
     * The chain is not optional. Android's own handler is what kills the
     * process, shows the system dialog and reports to the platform; replacing
     * it would leave a crashed app sitting in a half-dead state with its
     * windows still up. So this writes and then hands the same thread and the
     * same throwable straight on.
     *
     * Idempotent, because installing twice would chain this handler to itself
     * and write the entry twice.
     */
    fun install(context: Context) {
        val app = context.applicationContext
        install(fileIn(app), factsOf(app))
    }

    /** Takes a file and the facts so a test can install a handler without a
     * `Context`, on a temporary directory. */
    fun install(file: File, facts: Facts) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Thread.setDefaultUncaughtExceptionHandler(
                handlerFor(file, facts, Thread.getDefaultUncaughtExceptionHandler())
            )
            installed = true
        }
    }

    /**
     * The handler itself, built rather than installed, so a test can exercise
     * it -- including the paths where the write fails -- without touching the
     * JVM's one global handler slot.
     */
    fun handlerFor(
        file: File,
        facts: Facts,
        previous: Thread.UncaughtExceptionHandler?,
    ): Thread.UncaughtExceptionHandler = Thread.UncaughtExceptionHandler { thread, error ->
        // The guard spans the whole invocation, the chain included: a crash
        // raised while a crash is being handled is a symptom of the first one,
        // and recording it would push the trace that matters out of a capped
        // file.
        val reentered = writing
        if (!reentered) writing = true
        try {
            // The process is already dying. Nothing in here may throw into the
            // platform's handler, and nothing may wait on a lock another
            // thread could still be holding -- so this is a plain synchronous
            // write with no coroutine, no dispatcher, and no scheduler that
            // may never get around to running it.
            if (!reentered) runCatching {
                write(file, compose(readOrEmpty(file), format(
                    facts, nowUtc(), thread.name, stackTraceOf(error),
                )))
            }
            // Whatever happened above, the system still gets its crash: it is
            // what kills the process and shows the dialog, and skipping it
            // would leave a dead app with its windows up.
            previous?.uncaughtException(thread, error)
        } finally {
            if (!reentered) writing = false
        }
    }

    // --- the file -----------------------------------------------------------

    fun fileIn(context: Context): File =
        File(File(context.filesDir, DIR_NAME), FILE_NAME)

    /** What the self test shows, and what the share sheet would send. Empty
     * for the ordinary case of an app that has never crashed. */
    fun read(file: File): String = readOrEmpty(file)

    fun clear(file: File) { runCatching { file.delete() } }

    private fun readOrEmpty(file: File): String = runCatching {
        if (!file.isFile) return@runCatching ""
        if (file.length() > MAX_READ_BYTES) {
            // Bigger than the cap could ever have produced, so it is not this
            // file's own work -- something else wrote there, or an older
            // build with a larger cap did. Deleted rather than merely
            // skipped: the next crash would rename a fresh file over it in
            // any case, but until one happens this is a file the app refuses
            // to read still taking up a student's storage, and the self test
            // would show "no crashes recorded" with it sitting right there.
            file.delete()
            return@runCatching ""
        }
        file.readText()
    }.getOrDefault("")

    /**
     * Write beside the file and rename over it.
     *
     * The rename is why: the process is being killed, and a plain overwrite
     * interrupted halfway leaves a truncated file where a whole history used
     * to be. A rename either happened or did not.
     */
    private fun write(file: File, text: String) {
        val dir = file.parentFile ?: return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            // Some filesystems refuse a rename onto an existing file. Falling
            // back to a direct write risks the truncation above, which is the
            // lesser loss of the two: a partial newest entry beats no entry.
            runCatching { file.writeText(text) }
            tmp.delete()
        }
    }

    private fun nowUtc(): String = runCatching { Instant.now().toString() }
        .getOrDefault("time unknown")

    /**
     * Version from the package manager rather than `BuildConfig`, which this
     * module does not generate (`buildFeatures.buildConfig` is off), and every
     * field defaulted rather than assumed -- a formatter that throws inside a
     * crash handler would lose the crash it was called about.
     */
    fun factsOf(context: Context): Facts {
        val version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
                else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName ?: "?"} ($code)"
        }.getOrDefault("unknown")
        return Facts(
            appVersion = version,
            androidRelease = Build.VERSION.RELEASE ?: "?",
            sdkInt = Build.VERSION.SDK_INT,
            model = Build.MODEL ?: "?",
        )
    }
}
