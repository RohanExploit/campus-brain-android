package com.campusbrain.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import com.campusbrain.app.data.auth.Identity
import com.campusbrain.app.data.auth.Licensing
import com.campusbrain.app.databinding.ActivityMainBinding
import com.campusbrain.app.ui.auth.IdentityPill
import com.campusbrain.app.ui.docs.ImportViewModel
import com.campusbrain.app.ui.welcome.FirstRunGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val imports: ImportViewModel by lazy {
        ViewModelProvider(this)[ImportViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHost = supportFragmentManager
            .findFragmentById(R.id.navHost) as NavHostFragment
        val navController = navHost.navController
        binding.bottomNav.setupWithNavController(navController)

        // The self test is deliberately not a tab: it is a pre-demo check, not
        // a feature. Long-press the title to reach it.
        binding.header.setOnLongClickListener {
            navController.navigate(R.id.selfTestFragment); true
        }

        // The second affordance, and the only way to enrolment. A separate
        // gesture on a separate view rather than a second meaning for the
        // long-press above: one gesture with two destinations is how a demo
        // arrives on the wrong screen with an audience watching.
        //
        // Note what this listener does NOT read. It navigates unconditionally
        // — enrolled, lapsed or never enrolled all reach the same screen,
        // because a tap that sometimes does nothing is worse than no tap at
        // all, and because deciding here would mean consulting the entitlement
        // in a fourth place.
        binding.statusPill.setOnClickListener {
            navController.navigate(R.id.enrolFragment)
        }

        // The third gesture, and the third destination. Long-press the pill
        // for the licence screen; the title's long-press still goes to the
        // self test, and the pill's tap still goes to enrolment. Each gesture
        // has exactly one destination, for the reason given above.
        //
        // Like the tap, this navigates unconditionally. It does not read the
        // tier to decide whether to go: free, institutional and owner all
        // reach the same screen, and that screen is where the difference is
        // described. Branching here would mean the tier deciding whether a
        // gesture works, which is one step from the tier deciding whether
        // something else works.
        binding.statusPill.setOnLongClickListener {
            navController.navigate(R.id.licenseFragment); true
        }

        lifecycleScope.launch {
            // Identity first, and deliberately BEFORE the corpus: it is two
            // local SQLite reads with no network in them, so it costs nothing,
            // and the entitlement has to be readable while the 86MB embedder is
            // still loading. Nothing below waits on it and nothing about
            // answering a question consults it — see Identity's header comment.
            withContext(Dispatchers.IO) { Identity.init(applicationContext) }
            // The commercial layer, on the same terms and for the same
            // reasons: two local SQLite reads, no network on any path, and
            // nothing below waits on it. It governs one thing — whether a NEW
            // document may be imported — and appears nowhere on the way to an
            // answer. See Licensing's header.
            withContext(Dispatchers.IO) { Licensing.init(applicationContext) }
            withContext(Dispatchers.IO) { BrainRepository.init(applicationContext) }
            // Detached, and never awaited by anything on screen. On a device
            // with no config.json, no session, or a grant read in the last
            // day it returns before opening a socket, so airplane mode pays
            // nothing for it; when it does run and fails, it writes nothing
            // and the existing grace window is left exactly where it was.
            launch(Dispatchers.IO) { runCatching { Identity.refreshIfDue() } }

            when (val s = BrainRepository.state.value) {
                is InitState.Failed -> {
                    binding.statusPillState.text = s.message.lineSequence().first()
                    Toast.makeText(this@MainActivity, s.message, Toast.LENGTH_LONG).show()
                }
                is InitState.Ready -> {
                    val m = s.repo.db.meta
                    val docs = m["document_count"]
                        ?: withContext(Dispatchers.IO) {
                            runCatching { s.repo.docs.all().size.toString() }.getOrNull()
                        }
                    if (docs != null) {
                        // Without the embedder, retrieval finds the words the
                        // student typed rather than what they meant, and every
                        // document they import is indexed the same degraded
                        // way. Stated here rather than left silent, but in the
                        // tertiary ramp beside the count: it is a caveat on the
                        // corpus, not an alarm, and the offline claim next to
                        // the live dot is the one thing the header must not
                        // give up.
                        binding.statusPillDocs.text =
                            if (s.repo.vectorReady) "$docs documents"
                            else "$docs documents · keyword only"
                        binding.statusPillDocs.visibility = View.VISIBLE
                    }
                    if (!s.repo.fts.available) {
                        // Not a crash, but the user should know retrieval is
                        // running on the degraded keyword path.
                        binding.statusPillState.text =
                            "FTS5 unavailable — using LIKE fallback (results will be weaker)"
                    }
                }
                InitState.Loading -> Unit
            }
            binding.header.visibility = View.VISIBLE
        }

        // The licence slot, followed rather than sampled.
        //
        // It used to be read once from disk, with a comment saying a banner
        // that materialises mid-answer is worse than one a day late. That
        // reasoning was about a WARNING appearing unbidden, and it still
        // holds — but it now also has to cover the case it was written before:
        // a student who taps the pill, enrols, and comes back to a header that
        // has not noticed. Confirming an action the student just took is the
        // opposite of an interruption, and it is the only way this screen ever
        // shows a grant on the run it was created in.
        //
        // The cost is real and accepted: the once-a-day refreshIfDue above can
        // change the STATE word under a student mid-session. It is one word in
        // the tertiary slot beside a name they put there themselves, it never
        // becomes a dialog, and nothing about answering changes with it.
        //
        // This is the third and last thing MainActivity does with Identity —
        // init, refresh, display — and none of the three is consulted by
        // anything on the way to an answer.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Identity.entitlement.collect { grant ->
                    val text = IdentityPill.text(
                        grant, getString(R.string.entitlement_active_short)
                    )
                    // Null means an un-enrolled device, and an un-enrolled
                    // device gets the header it has always had: the view stays
                    // GONE, which is the state the layout ships in.
                    binding.statusPillChunks.text = text.orEmpty()
                    binding.statusPillChunks.visibility =
                        if (text == null) View.GONE else View.VISIBLE
                }
            }
        }

        // Only on a genuinely new launch. After process death the saved state
        // comes back with the original Intent still attached, and the transient
        // read grant on its Uri is long gone — re-running it would produce a
        // baffling failure card for a file the student shared yesterday.
        if (savedInstanceState == null) {
            val hadSharedDocument = handleSharedDocument(intent)
            if (!hadSharedDocument) maybeWelcome(navController)
        }
    }

    /**
     * The four claims, on the first launch only.
     *
     * Three things this is careful not to be. It is not a gate: the welcome
     * pops back to a fully working Ask tab, the bottom bar stays live
     * underneath it, and the corpus is already loaded behind it. It is not a
     * funnel: it ends on the Ask tab and never on the enrolment screen, so
     * nothing a new student is told here is made conditional on an account.
     * And it is not shown to a phone that has already seen it, including a
     * phone whose store cannot be read — see [FirstRun.shouldShow], where
     * "cannot tell" resolves to "do not show" precisely so a broken store
     * cannot produce an onboarding screen that will not stay shut.
     *
     * Skipped entirely when the launch came from a share, because that student
     * asked for something specific and four panes of introduction is not it.
     */
    private fun maybeWelcome(navController: androidx.navigation.NavController) {
        lifecycleScope.launch {
            val show = withContext(Dispatchers.IO) {
                FirstRunGate.shouldShow(applicationContext)
            }
            if (!show) return@launch
            // Marked as seen when it is SHOWN, not when it is dismissed: the
            // bottom bar is live underneath, so a student can leave by a route
            // that is neither Skip nor Start, and a flag written only on the
            // two deliberate exits would bring this back on the next launch.
            withContext(Dispatchers.IO) { FirstRunGate.markSeen(applicationContext) }
            // The activity can have been torn down while that ran.
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            navController.navigate(R.id.welcomeFragment)
        }
    }

    /**
     * The one lifecycle boundary the usage counters are written down at.
     *
     * Not per query, deliberately. `user_corpus.db` runs on a rollback journal
     * where a writer holds an EXCLUSIVE lock, a document import holds one for
     * up to fifty embeddings, and the search path reads the same file. A write
     * on the answer path would put a third writer into that contention for a
     * route histogram that needs no per-event durability. See QueryLog's
     * header for the whole argument.
     *
     * On a background thread and never awaited: if the process dies before it
     * lands, a session's counts are lost and nothing in the app behaves
     * differently for it.
     */
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch(Dispatchers.IO) { runCatching { Licensing.flushAnalytics() } }
    }

    /**
     * The share-sheet entry point. Reached only because the Activity is
     * `singleTask`: with the default launch mode the system would build a
     * second MainActivity for every share and this would never run.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Replace the stored Intent before reading it, so the consume below
        // clears the one a later getIntent() will return.
        setIntent(intent)
        handleSharedDocument(intent)
    }

    /**
     * Turns "share this file with Campus Brain" into an import.
     *
     * Two shapes to unpack. ACTION_SEND carries the file in EXTRA_STREAM;
     * ACTION_VIEW ("open with") carries it in the Intent's own data. Both are
     * a content:// Uri with a read grant scoped to this task, which is why
     * nothing is persisted and takePersistableUriPermission is never called —
     * the bytes are read once, now, and the corpus keeps the text, not the
     * file.
     *
     * Returns true when this launch was a share, which is the one case the
     * first-run welcome stands down for: someone who shared a timetable asked
     * for something specific, and four panes of introduction in front of it
     * would be the app talking over them.
     */
    private fun handleSharedDocument(intent: Intent?): Boolean {
        intent ?: return false
        val uri: Uri? = when (intent.action) {
            // A plain-text share from a browser or notes app also arrives as
            // ACTION_SEND text/plain, but with EXTRA_TEXT and no stream. There
            // is no file to ingest, so the app just opens normally rather than
            // showing a failure for something the student did not ask for.
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri == null) return false

        // Consume it. A rotation re-delivers the same Intent through onCreate,
        // and DocumentIngest's uniqueDocId would cheerfully index the file a
        // second time as "timetable (2)".
        intent.action = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)

        if (!imports.start(uri)) {
            Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show()
            // Still true: the launch WAS a share, it just could not be served
            // yet, and a welcome screen on top of that Toast helps nobody.
            return true
        }
        // The progress and the outcome live on the Documents tab, so go there.
        // Posted because the bottom bar has only just been wired to the
        // NavController and the graph may not have restored yet.
        binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.docsFragment }
        return true
    }
}
