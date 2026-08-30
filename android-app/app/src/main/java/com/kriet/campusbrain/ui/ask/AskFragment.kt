package com.kriet.campusbrain.ui.ask

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
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
        SUGGESTIONS.forEach { q ->
            val chip = layoutInflater.inflate(R.layout.item_suggestion, binding.chipRow, false)
            (chip as android.widget.TextView).text = q
            chip.setOnClickListener { submit(q) }
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
        scrollToEnd()

        viewLifecycleOwner.lifecycleScope.launch {
            val result: AnswerResult? = withContext(Dispatchers.IO) {
                when (val s = BrainRepository.state.value) {
                    is InitState.Ready -> runCatching { s.repo.router.answer(query) }.getOrNull()
                    else -> null
                }
            }
            if (result == null) {
                adapter.addError("The corpus is not available on this device.")
            } else {
                adapter.addAnswer(result)
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
        /** Deliberately one per route: FACT, GLOBAL, LOCAL, TABULAR, plus a miss. */
        val SUGGESTIONS = listOf(
            "What is the minimum attendance percentage?",
            "What scholarships are available?",
            "Who handles hostel allotment?",
            "What is the pass percentage?",
            "Which subject has the most failures?",
        )
    }
}
