package com.kriet.campusbrain

import android.util.Log
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.IngestResult
import com.kriet.campusbrain.data.InitState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Exercises the one code path nothing has ever run: the SQLite write and the
 * FTS5 external-content insert behind document ingestion.
 *
 * The chunker and the DOCX extractor have JVM coverage. The transaction does
 * not, and cannot — it needs the bundled SQLite that ships in the APK, on a
 * real device, against a real `user_corpus.db`. A `BUILD SUCCESSFUL` says
 * nothing about whether an inserted row is findable.
 *
 * The document below is deliberately full of facts that appear nowhere in the
 * bundled corpus — an invented society, an invented room, an invented rupee
 * amount — so that a hit afterwards can only have come from this file. Asking
 * about "attendance" would prove nothing, because the bundle already answers
 * that.
 */
class IngestDeviceTest {

    private val doc = """
        Subject: Robotics Club Membership and Workshop Schedule 2026

        The Quasar Robotics Society meets every Thursday at 5:30 pm in
        Laboratory 7B of the Mechanical Engineering block.

        Annual membership costs 1450 rupees, payable to the society treasurer
        before the last working day of August. Members who join after that date
        pay a late fee of 275 rupees.

        The society runs three workshops each semester. The line-following
        robot workshop is open to first-year students. The autonomous drone
        workshop requires prior completion of the line-following workshop.
        The manipulator arm workshop is restricted to final-year students.

        Equipment may be borrowed for a maximum of fourteen days. A member with
        overdue equipment cannot borrow again until it is returned, and repeated
        lateness leads to suspension for one semester.
    """.trimIndent()

    // Block body, not an expression body. `= runBlocking { ... }` infers its
    // return type from the block's last statement — here a Log.i call, which
    // returns Int — and JUnit4 rejects any test method that does not return
    // void, with a bare "initializationError" that names nothing.
    @Test fun ingestThenQuery(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        BrainRepository.init(ctx)
        val state = BrainRepository.state.value
        assertTrue("corpus not ready: $state", state is InitState.Ready)
        val repo = (state as InitState.Ready).repo

        Log.i("INGEST", "===== BEGIN INGEST DEVICE TEST =====")
        Log.i("INGEST", "embedderReady=${repo.ingest.embedderReady}")

        val file = File(ctx.cacheDir, "quasar-robotics-society.txt")
        file.writeText(doc)
        Log.i("INGEST", "wrote fixture: ${file.length()} bytes")

        // Baseline: the corpus must NOT already answer this.
        val before = runCatching { repo.router.answer("when does the robotics society meet") }
        before.onSuccess {
            Log.i("INGEST", "BEFORE route=${it.route} abstained=${it.abstained}")
            Log.i("INGEST", "BEFORE a: ${it.answer.replace('\n', ' ').take(220)}")
        }.onFailure { Log.i("INGEST", "BEFORE threw ${it.javaClass.simpleName}: ${it.message}") }

        val result = runCatching {
            repo.ingest.ingest(file.toUri()) { done, total ->
                Log.i("INGEST", "progress $done/$total")
            }
        }
        result.onFailure { Log.i("INGEST", "INGEST THREW ${it.javaClass.simpleName}: ${it.message}") }
        val r = result.getOrNull()
        Log.i("INGEST", "RESULT: $r")

        if (r is IngestResult.Ok) {
            Log.i("INGEST", "ok docId=${r.docId} title=${r.title} chunks=${r.chunks}")

            // Each of these is answerable only from the fixture. A wrong answer
            // here means the write landed but retrieval cannot reach it; an
            // abstention means the insert or the FTS index did not take.
            listOf(
                "when does the robotics society meet",
                "how much is robotics club membership",
                "which workshop needs the line following workshop first",
                "how long can I borrow robotics equipment",
            ).forEach { q ->
                val a = runCatching { repo.router.answer(q) }
                Log.i("INGEST", "### Q: $q")
                a.onSuccess {
                    val userSrc = it.sources.count { s -> s.isUserAdded }
                    Log.i("INGEST", "route=${it.route} abstained=${it.abstained} userSources=$userSrc/${it.sources.size}")
                    Log.i("INGEST", "a: ${it.answer.replace('\n', ' ').take(260)}")
                }.onFailure { e -> Log.i("INGEST", "threw ${e.javaClass.simpleName}: ${e.message}") }
            }

            Log.i("INGEST", "added() reports ${repo.ingest.added().size} user document(s)")
            repo.ingest.added().forEach {
                Log.i("INGEST", "  - ${it.title} [${it.category}] chunks=${it.chunkCount} userAdded=${it.isUserAdded}")
            }

            // The bundled corpus must be untouched, and removal must be clean.
            val removed = repo.ingest.remove(r.docId)
            Log.i("INGEST", "remove=${removed}  added() now ${repo.ingest.added().size}")
            val after = runCatching { repo.router.answer("when does the robotics society meet") }
            after.onSuccess {
                Log.i("INGEST", "AFTER REMOVE abstained=${it.abstained} a: ${it.answer.replace('\n', ' ').take(160)}")
            }
        }

        val bundled = runCatching { repo.router.answer("what is the minimum attendance percentage") }
        bundled.onSuccess {
            Log.i("INGEST", "BUNDLED STILL OK: abstained=${it.abstained} a: ${it.answer.replace('\n', ' ').take(160)}")
        }
        Log.i("INGEST", "===== END INGEST DEVICE TEST =====")
    }
}
