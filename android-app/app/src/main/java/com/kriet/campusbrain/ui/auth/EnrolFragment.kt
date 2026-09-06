package com.kriet.campusbrain.ui.auth

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import com.kriet.campusbrain.R
import com.kriet.campusbrain.databinding.FragmentEnrolBinding
import kotlinx.coroutines.launch

/**
 * The only caller of [com.kriet.campusbrain.data.auth.Identity.enrol] in the
 * app, and the reason the auth layer stopped being invisible.
 *
 * Reached by tapping the header's status pill. Never navigated to
 * automatically, never presented at launch, never put in front of a question.
 * That is not politeness -- it is the product. Every answer this app gives is
 * read from documents already on the phone, and it gives them to a student who
 * never opens this screen exactly as readily as to one who does. A licence
 * confirmation that interrupted an answer would be advertising a promise while
 * breaking it.
 *
 * Nothing here consults the entitlement to decide anything. It writes one, and
 * reads back only what it just wrote in order to name the institution on the
 * success card.
 */
class EnrolFragment : Fragment() {

    // Enrolment is a step down from the header into a detail, so it moves
    // along Z like DocDetailFragment does -- the page recedes, this comes
    // forward, and back reverses it. Sibling tabs cross-fade and hierarchy
    // moves on Z; there is no third idiom in this app and this does not add
    // one.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentEnrolBinding? = null
    private val binding get() = _binding!!

    private val model: EnrolViewModel by lazy {
        // Fragment-scoped and explicit, matching DocsFragment's reasoning for
        // not using the fragment-ktx delegate: this keeps the dependency on
        // what the build file actually declares.
        ViewModelProvider(this)[EnrolViewModel::class.java]
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentEnrolBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = refreshForm()
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
        }
        binding.enrolEmail.addTextChangedListener(watcher)
        binding.enrolPassword.addTextChangedListener(watcher)
        binding.enrolCode.addTextChangedListener(watcher)

        binding.enrolSubmit.setOnClickListener { submit() }
        binding.enrolCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }

        refreshForm()

        // viewLifecycleOwner explicitly, matching DocsFragment: unqualified,
        // `repeatOnLifecycle` would bind to the Fragment's own lifecycle,
        // which outlives the views this collector writes into.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { render(it) }
            }
        }
    }

    /**
     * Enables the action and, when something is wrong, says what under the
     * field it is wrong about.
     *
     * The correction REPLACES that field's help line rather than appearing
     * beneath it. Two reasons, and the second is the real one: the form does
     * not change height as the student types, so nothing jumps under a thumb
     * that is already reaching for the next field; and a help line that turns
     * into a correction is read as the same sentence being refined, where a
     * new red row below it is read as a telling-off.
     *
     * Only the first problem is shown. A student filling three fields top to
     * bottom wants the next thing to fix, not an audit.
     */
    private fun refreshForm() {
        if (_binding == null) return
        val email = binding.enrolEmail.text?.toString().orEmpty()
        val password = binding.enrolPassword.text?.toString().orEmpty()
        val code = binding.enrolCode.text?.toString().orEmpty()

        binding.enrolEmailHelp.setText(R.string.enrol_email_help)
        binding.enrolPasswordHelp.setText(R.string.enrol_password_help)
        binding.enrolCodeHelp.setText(R.string.enrol_code_help)

        val problem = EnrolForm.problem(email, password, code)
        if (problem != null) {
            // An empty form is not yet wrong. A correction appears only once
            // the student has typed something into the field it is about: a
            // screen that opens by telling you your password is too short is
            // scolding you for not having started. The missing code never gets
            // one at all -- the action being unavailable already says the form
            // is incomplete, and the field's own help line explains where a
            // code comes from, which is the more useful sentence.
            val show = when (problem.field) {
                EnrolForm.Field.EMAIL -> true
                EnrolForm.Field.PASSWORD -> password.isNotEmpty()
                EnrolForm.Field.CODE -> false
            }
            if (show) {
                val line = when (problem.field) {
                    EnrolForm.Field.EMAIL -> binding.enrolEmailHelp
                    EnrolForm.Field.PASSWORD -> binding.enrolPasswordHelp
                    EnrolForm.Field.CODE -> binding.enrolCodeHelp
                }
                line.setText(problem.messageRes)
            }
        }
        binding.enrolSubmit.isEnabled = problem == null
    }

    private fun submit() {
        if (_binding == null) return
        val email = binding.enrolEmail.text?.toString().orEmpty()
        val password = binding.enrolPassword.text?.toString().orEmpty()
        val code = binding.enrolCode.text?.toString().orEmpty()
        if (!EnrolForm.submittable(email, password, code)) { refreshForm(); return }
        model.submit(email, password, code)
    }

    private fun render(state: EnrolState) {
        when (state) {
            EnrolState.Editing -> {
                binding.enrolForm.visibility = View.VISIBLE
                binding.enrolResult.visibility = View.GONE
                binding.enrolSubmit.setText(R.string.enrol_submit)
                setFormEnabled(true)
                refreshForm()
            }

            EnrolState.Working -> {
                // The form stays on screen and stays legible; only its
                // controls go inert. Up to five calls at eight seconds each is
                // a long time to look at a spinner, and a student who suspects
                // they mistyped the code should be able to read what they sent
                // rather than being shown an empty screen with a wheel on it.
                binding.enrolForm.visibility = View.VISIBLE
                binding.enrolResult.visibility = View.GONE
                binding.enrolSubmit.setText(R.string.enrol_working)
                setFormEnabled(false)
            }

            is EnrolState.Done -> {
                binding.enrolForm.visibility = View.GONE
                renderOutcome(EnrolCopy.of(state.result))
                binding.enrolResult.visibility = View.VISIBLE
            }
        }
    }

    private fun setFormEnabled(enabled: Boolean) {
        binding.enrolEmail.isEnabled = enabled
        binding.enrolPassword.isEnabled = enabled
        binding.enrolCode.isEnabled = enabled
        binding.enrolSubmit.isEnabled = enabled
    }

    private fun renderOutcome(outcome: EnrolCopy.Outcome) {
        val c = binding
        c.enrolResultIcon.setImageResource(outcome.iconRes)

        if (outcome.succeeded) {
            // The window, pluralised properly: "1 day" and "45 days" are the
            // same sentence and a bare "%d day(s)" is the mark of a form that
            // did not care which one it was saying.
            val days = resources.getQuantityString(
                R.plurals.enrol_days, outcome.offlineDays, outcome.offlineDays
            )
            c.enrolResultTitle.text = getString(outcome.titleRes, outcome.institution)
            c.enrolResultBody.text = getString(outcome.bodyRes, days)
            // The code has been redeemed and has no further use, so it stops
            // existing on screen. Same for the password: a success card left
            // sitting above a filled-in credentials form is a screenshot
            // waiting to be taken.
            c.enrolCode.setText("")
            c.enrolPassword.setText("")
        } else {
            c.enrolResultTitle.text = getString(outcome.titleRes)
            c.enrolResultBody.text = getString(outcome.bodyRes)
        }

        c.enrolResultAction.setText(outcome.actionRes)
        c.enrolResultAction.setOnClickListener {
            if (outcome.returnsToForm) model.backToForm() else findNavController().navigateUp()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
