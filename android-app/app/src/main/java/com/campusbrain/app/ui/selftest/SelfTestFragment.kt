package com.campusbrain.app.ui.selftest

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.campusbrain.app.RouterSmoke
import com.campusbrain.app.SelfTest
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import com.campusbrain.app.databinding.FragmentSelfTestBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reachable by long-pressing the app bar title.
 *
 * Its real job is the 30 seconds before going on stage: open it, confirm every
 * line is green, then present. The checks it runs are the ones whose failures
 * are otherwise silent -- FTS5 absent, vectors decoded wrong, percentages that
 * stop summing to 100.
 */
class SelfTestFragment : Fragment() {

    private var _binding: FragmentSelfTestBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentSelfTestBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.status.text = "running..."
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                when (val s = BrainRepository.state.value) {
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
            }
            binding.status.text = text
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
