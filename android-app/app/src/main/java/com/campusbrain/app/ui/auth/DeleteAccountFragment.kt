package com.campusbrain.app.ui.auth

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import com.campusbrain.app.R
import com.campusbrain.app.databinding.FragmentDeleteAccountBinding
import kotlinx.coroutines.launch

/**
 * The in-app account-deletion route Google Play requires of any app that lets
 * a user create an account.
 *
 * Reached from the enrolment screen and from nowhere else: enrolment is the
 * only thing in this app that creates an account, so it is the one place a
 * student would look for the way to undo it. It is not a tab, for the reason
 * given in `nav_graph.xml` — the tabs are what a student does with the app,
 * and this is something they do once, to the app.
 *
 * The one thing this screen must not imply, and the reason so much of its copy
 * is about what does NOT happen: deleting the account does not take the
 * answers with it. Retrieval never consults the entitlement, so the corpus,
 * every route, and the student's own imported documents work identically a
 * second after this completes. A deletion screen that let a student believe
 * otherwise would be scaring them out of a right they are entitled to
 * exercise.
 */
class DeleteAccountFragment : Fragment() {

    // Enrolment moves along Z from the header; this moves along Z from
    // enrolment. One idiom for hierarchy, as everywhere else in this app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentDeleteAccountBinding? = null
    private val binding get() = _binding!!

    private val model: DeleteAccountViewModel by lazy {
        ViewModelProvider(this)[DeleteAccountViewModel::class.java]
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentDeleteAccountBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.deleteAction.setOnClickListener { model.arm() }
        binding.deleteConfirmNo.setOnClickListener { model.backToStart() }
        binding.deleteConfirmYes.setOnClickListener { model.confirm() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    private fun render(state: DeleteState) {
        when (state) {
            DeleteState.Explaining -> {
                binding.deleteExplain.visibility = View.VISIBLE
                binding.deleteConfirm.visibility = View.GONE
                binding.deleteResult.visibility = View.GONE
                binding.deleteAction.isEnabled = true
                binding.deleteAction.setText(R.string.delete_action)
            }

            DeleteState.Confirming -> {
                // The explanation stays on screen above the question. The two
                // paragraphs are what the confirmation is confirming, and a
                // student should not have to remember them.
                binding.deleteExplain.visibility = View.VISIBLE
                binding.deleteConfirm.visibility = View.VISIBLE
                binding.deleteResult.visibility = View.GONE
                // The armed button stops being tappable rather than
                // disappearing: a control vanishing from under a thumb that is
                // already moving is how a student ends up tapping whatever
                // slid into its place.
                binding.deleteAction.isEnabled = false
            }

            DeleteState.Working -> {
                binding.deleteExplain.visibility = View.VISIBLE
                binding.deleteConfirm.visibility = View.GONE
                binding.deleteResult.visibility = View.GONE
                binding.deleteAction.isEnabled = false
                binding.deleteAction.setText(R.string.delete_working)
            }

            is DeleteState.Done -> {
                val outcome = DeleteAccountCopy.of(state.result)
                // The explanation goes away only when there is nothing left to
                // explain. On a retryable outcome the account still exists, so
                // what it says is still true and still what the student needs
                // before they tap again.
                binding.deleteExplain.visibility =
                    if (outcome.accountGone) View.GONE else View.VISIBLE
                binding.deleteAction.isEnabled = false
                binding.deleteAction.setText(R.string.delete_action)
                binding.deleteConfirm.visibility = View.GONE
                renderOutcome(outcome)
                binding.deleteResult.visibility = View.VISIBLE
            }
        }
    }

    private fun renderOutcome(outcome: DeleteAccountCopy.Outcome) {
        val c = binding
        c.deleteResultIcon.setImageResource(outcome.iconRes)
        c.deleteResultTitle.text = getString(outcome.titleRes)
        c.deleteResultBody.text = getString(outcome.bodyRes)
        c.deleteResultAction.setText(outcome.actionRes)
        c.deleteResultAction.setOnClickListener {
            if (outcome.returnsToStart) model.backToStart() else findNavController().navigateUp()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
