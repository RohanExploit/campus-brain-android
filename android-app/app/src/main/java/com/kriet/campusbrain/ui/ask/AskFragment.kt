package com.kriet.campusbrain.ui.ask

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialFadeThrough
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.AnswerResult
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.InitState
import com.kriet.campusbrain.databinding.FragmentAskBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The router-driven surface: a question in, a routed and cited answer out.
 *
 * Every answer shows which of the four routes handled it and can expand the
 * decision trace. The point of the system is that it classifies before it
 * answers; a bubble that only showed prose would hide the part worth seeing.
 */
class AskFragment : Fragment() {
    // Sibling tabs are peers, so they cross-fade rather than slide: a
    // directional transition would imply a hierarchy the bottom bar does not
    // have. MaterialFadeThrough carries Material's own easing and duration,
    // which is why it is used instead of a hand-rolled alpha animation.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }


    private var _binding: FragmentAskBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: MessageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MessageAdapter { docId ->
            findNavController().navigate(
                R.id.docDetailFragment, Bundle().apply { putString("docId", docId) })
        }
        binding.messages.layoutManager = LinearLayoutManager(requireContext())
        binding.messages.adapter = adapter

        // One suggestion per route, so a demo can exercise all four in four taps.
        SUGGESTIONS.forEach { (label, query) ->
            val chip = layoutInflater.inflate(R.layout.item_suggestion, binding.chipRow, false)
            (chip as android.widget.TextView).text = label
            chip.setOnClickListener { submit(query) }
            binding.chipRow.addView(chip)
        }

        binding.input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Blank input is disabled at the button, not just guarded in the
                // router: it is the one query that could print the roster.
                binding.send.isEnabled = !s.isNullOrBlank()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.send.setOnClickListener { submit(binding.input.text?.toString().orEmpty()) }
        binding.input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submit(binding.input.text?.toString().orEmpty()); true
            } else false
        }
    }

    private fun submit(query: String) {
        if (query.isBlank()) return
        binding.input.setText("")
        binding.emptyHint.visibility = View.GONE
        adapter.addUser(query)
        // Retrieval, embedding and composition take one to three seconds on
        // the phone. Until now the screen showed the question and then
        // nothing, which on a device with no network activity to point at
        // looks exactly like an app that has hung.
        adapter.showPending(getString(R.string.ask_working))
        scrollToEnd()

        viewLifecycleOwner.lifecycleScope.launch {
            val ready = BrainRepository.state.value as? InitState.Ready
            val result: AnswerResult? = ready?.let {
                withContext(Dispatchers.IO) { runCatching { it.repo.router.answer(query) }.getOrNull() }
            }
            adapter.clearPending()
            when {
                // Two different failures that used to share one sentence. The
                // corpus being absent is an install problem the student can
                // act on; a query that threw is not.
                ready == null -> adapter.addError(
                    getString(R.string.error_no_corpus_title),
                    getString(R.string.error_no_corpus_body),
                )
                result == null -> adapter.addError(
                    getString(R.string.error_answer_title),
                    getString(R.string.error_answer_body),
                )
                else -> adapter.addAnswer(result)
            }
            scrollToEnd()
        }
    }

    private fun scrollToEnd() {
        binding.messages.post { binding.messages.scrollToPosition(adapter.itemCount - 1) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Deliberately one per route: FACT, GLOBAL, LOCAL, TABULAR, plus a miss.
         *
         * Label and query are separate because the chip used to be captioned
         * with the question it sent. The first of those is wider than the
         * screen, so a student saw one pill spanning both edges and no sign
         * that four more sat off to the right. The label is what fits on a
         * chip; the query is still the full sentence the router is given, so
         * the routes each one exercises are unchanged.
         */
        val SUGGESTIONS = listOf(
            "Minimum attendance" to "What is the minimum attendance percentage?",
            "Scholarships" to "What scholarships are available?",
            "Hostel allotment" to "Who handles hostel allotment?",
            "Pass percentage" to "What is the pass percentage?",
            "Most failures" to "Which subject has the most failures?",
        )
    }
}
