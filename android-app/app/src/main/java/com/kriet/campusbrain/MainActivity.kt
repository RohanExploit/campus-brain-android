package com.kriet.campusbrain

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.InitState
import com.kriet.campusbrain.databinding.ActivityMainBinding
import com.kriet.campusbrain.ui.docs.ImportViewModel
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

        lifecycleScope.launch {
            withContext(Dispatchers.IO) { BrainRepository.init(applicationContext) }
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

        // Only on a genuinely new launch. After process death the saved state
        // comes back with the original Intent still attached, and the transient
        // read grant on its Uri is long gone — re-running it would produce a
        // baffling failure card for a file the student shared yesterday.
        if (savedInstanceState == null) handleSharedDocument(intent)
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
     */
    private fun handleSharedDocument(intent: Intent?) {
        intent ?: return
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
        if (uri == null) return

        // Consume it. A rotation re-delivers the same Intent through onCreate,
        // and DocumentIngest's uniqueDocId would cheerfully index the file a
        // second time as "timetable (2)".
        intent.action = null
        intent.data = null
        intent.removeExtra(Intent.EXTRA_STREAM)

        if (!imports.start(uri)) {
            Toast.makeText(this, R.string.import_busy, Toast.LENGTH_LONG).show()
            return
        }
        // The progress and the outcome live on the Documents tab, so go there.
        // Posted because the bottom bar has only just been wired to the
        // NavController and the graph may not have restored yet.
        binding.bottomNav.post { binding.bottomNav.selectedItemId = R.id.docsFragment }
    }
}
