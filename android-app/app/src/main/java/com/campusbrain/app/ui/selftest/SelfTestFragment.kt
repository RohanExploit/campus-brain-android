package com.campusbrain.app.ui.selftest

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.campusbrain.app.R
import com.campusbrain.app.RouterSmoke
import com.campusbrain.app.SelfTest
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import com.campusbrain.app.databinding.FragmentSelfTestBinding
import com.campusbrain.app.diag.CrashLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reachable by long-pressing the app bar title.
 *
 * Its real job is the 30 seconds before going on stage: open it, confirm every
 * line is green, then present. The checks it runs are the ones whose failures
 * are otherwise silent -- FTS5 absent, vectors decoded wrong, percentages that
 * stop summing to 100.
 *
 * It now also shows the crash log, because this is already the screen someone
 * is told to open when the app misbehaves and a second diagnostic screen would
 * be one nobody could find. The log is read from disk here and nowhere else;
 * see [CrashLog] for why it is a file rather than a reporting SDK, and for
 * what is and is not allowed into it.
 */
class SelfTestFragment : Fragment() {

    private var _binding: FragmentSelfTestBinding? = null
    private val binding get() = _binding!!

    private val crashFile: File by lazy { CrashLog.fileIn(requireContext()) }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSelfTestBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.status.text = "running..."
        binding.crashShare.setOnClickListener { share() }
        binding.crashClear.setOnClickListener {
            CrashLog.clear(crashFile)
            refresh()
        }
        refresh()
    }

    /**
     * Runs the checks and re-reads the log, both off the main thread.
     *
     * The file read is small but it is still a file read, and this screen is
     * opened on a phone that may be busy opening an 86 MB model at the time.
     */
    private fun refresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            val checks = withContext(Dispatchers.IO) { checksText() }
            val log = withContext(Dispatchers.IO) { CrashLog.read(crashFile) }
            if (_binding == null) return@launch
            binding.status.text = checks + "\n" + CrashLog.section(log)
            // The actions appear only when there is something to act on. A
            // phone that has never crashed shows this screen exactly as it
            // always looked.
            binding.crashActions.visibility =
                if (log.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun checksText(): String = when (val s = BrainRepository.state.value) {
        is InitState.Ready -> {
            val checks = SelfTest.run(s.repo.db) +
                SelfTest.embedderChecks(s.repo.db, s.repo.embedder) +
                RouterSmoke.run(s.repo)
            val failed = checks.count { !it.ok }
            buildString {
                append(if (failed == 0) "ALL ${checks.size} CHECKS PASSED\n\n"
                       else "$failed of ${checks.size} CHECKS FAILED\n\n")
                checks.forEach {
                    append(if (it.ok) "[PASS] " else "[FAIL] ")
                    append(it.name).append('\n')
                    append("       ")
                    append(it.detail.replace("\n", "\n       "))
                    append("\n\n")
                }
            }
        }
        is InitState.Failed -> "CORPUS UNAVAILABLE\n\n${s.message}"
        InitState.Loading -> "still loading"
    }

    /**
     * The share sheet, carrying the log as text.
     *
     * `ACTION_SEND` with `EXTRA_TEXT` rather than a file, following
     * [com.campusbrain.app.ui.admin.AdminAnalyticsFragment]'s export: no
     * FileProvider, no manifest surface, nothing written to shared storage,
     * and -- the reason that matters here -- the person sees the text in
     * whatever app they picked before it is sent anywhere. An attachment they
     * cannot read is the wrong shape for a product whose claim is that data
     * stays on the phone.
     *
     * Only the log goes, never the check results: those name documents in the
     * corpus.
     */
    private fun share() {
        val log = CrashLog.read(crashFile)
        if (log.isBlank()) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.selftest_crash_subject))
            putExtra(Intent.EXTRA_TEXT, log)
        }
        startActivity(Intent.createChooser(send, getString(R.string.selftest_crash_chooser)))
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
