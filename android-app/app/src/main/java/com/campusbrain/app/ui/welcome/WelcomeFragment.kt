package com.campusbrain.app.ui.welcome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialFadeThrough
import com.google.android.material.transition.MaterialSharedAxis
import com.campusbrain.app.R
import com.campusbrain.app.databinding.FragmentWelcomeBinding

/**
 * Four claims, once, and then out of the way forever.
 *
 * What this screen must not become is the thing it most easily could: a funnel.
 * There is no account step at the end of it, no "sign in to continue", and no
 * navigation anywhere except back to the Ask tab. Enrolment is reachable from
 * the header pill and from nowhere else, and a student who dismisses this lands
 * on a fully working app -- the corpus is in the APK and belongs to whoever
 * installed it.
 *
 * The seen-flag is written by the caller at the moment this is navigated to,
 * not here on dismissal. See [FirstRunStore.markSeen] for why that is the
 * safer moment.
 */
class WelcomeFragment : Fragment() {

    // Same Z that DocDetailFragment and EnrolFragment use. This is not a
    // sibling of the Ask tab, so it does not cross-fade with one.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentWelcomeBinding? = null
    private val binding get() = _binding!!

    private var index = 0

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentWelcomeBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        index = savedInstanceState?.getInt(KEY_INDEX) ?: 0

        // Built once and re-tinted, rather than rebuilt on every advance: four
        // views appearing and disappearing inside a container the fade-through
        // is not running on would flicker against a transition it is not part
        // of.
        repeat(FirstRun.PANES.size) {
            val dot = View(requireContext())
            // wrap_content, and the 6dp comes from the shape's own <size>:
            // a ShapeDrawable reports an intrinsic size and View's suggested
            // minimum picks it up, so the dot needs no dimen of its own and
            // the drawable stays the single place its size is stated.
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.marginEnd = resources.getDimensionPixelSize(R.dimen.space_2)
            dot.layoutParams = lp
            dot.setBackgroundResource(R.drawable.dot_step)
            binding.welcomeDots.addView(dot)
        }

        binding.welcomeSkip.setOnClickListener { finish() }
        binding.welcomeAction.setOnClickListener {
            if (FirstRun.isLast(index)) finish() else advance()
        }

        render(animate = false)
    }

    private fun advance() {
        index += 1
        render(animate = true)
    }

    private fun render(animate: Boolean) {
        if (animate) {
            // The panes are peers -- four claims, no hierarchy between them --
            // so they cross-fade exactly as the bottom-bar tabs do. The screen
            // ITSELF arrived on Z, which is the hierarchy move; using Z again
            // between panes would say each claim is a level below the last.
            TransitionManager.beginDelayedTransition(
                binding.root, MaterialFadeThrough()
            )
        }
        val pane = FirstRun.paneAt(index)
        binding.welcomeTitle.setText(pane.titleRes)
        binding.welcomeBody.setText(pane.bodyRes)

        for (i in 0 until binding.welcomeDots.childCount) {
            binding.welcomeDots.getChildAt(i).alpha = if (i == index) 1f else DOT_DIM
        }

        val last = FirstRun.isLast(index)
        binding.welcomeAction.setText(if (last) R.string.welcome_start else R.string.welcome_next)
        // The step count goes on the action rather than on the dots: TalkBack
        // reads the control the user is about to press, and "Next, step 2 of
        // 4" is the whole of what the dots convey.
        binding.welcomeAction.contentDescription = getString(
            R.string.welcome_step_description, index + 1, FirstRun.PANES.size
        ) + ", " + getString(if (last) R.string.welcome_start else R.string.welcome_next)
    }

    /**
     * Leaves, by either exit, to the tab that was already underneath.
     *
     * `navigateUp` rather than a navigate to the Ask tab: this was pushed onto
     * the back stack over whatever the start destination is, so popping it
     * restores that rather than asserting a second opinion about where the
     * student should be.
     */
    private fun finish() {
        findNavController().navigateUp()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_INDEX, index)
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    private companion object {
        const val KEY_INDEX = "welcome_index"
        /** Visible as an index, not competing with the pane it indexes. */
        const val DOT_DIM = 0.28f
    }
}
