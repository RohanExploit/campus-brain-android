package com.campusbrain.app.ui.result

import android.content.res.ColorStateList
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.transition.MaterialFadeThrough
import androidx.lifecycle.lifecycleScope
import com.campusbrain.app.Grades
import com.campusbrain.app.R
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import com.campusbrain.app.data.StudentRow
import com.campusbrain.app.data.SubjectRow
import com.campusbrain.app.databinding.FragmentResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TABULAR route with a form instead of free text.
 *
 * Calls the same [com.campusbrain.app.retrieval.TabularQueries] the router
 * calls, so a bug cannot exist on one screen and not the other.
 */
class ResultFragment : Fragment() {
    // Sibling tabs are peers, so they cross-fade rather than slide: a
    // directional transition would imply a hierarchy the bottom bar does not
    // have. MaterialFadeThrough carries Material's own easing and duration,
    // which is why it is used instead of a hand-rolled alpha animation.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }


    private var _binding: FragmentResultBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentResultBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.lookup.setOnClickListener { lookup(binding.rollInput.text?.toString().orEmpty()) }
        binding.rollInput.setOnEditorActionListener { _, _, _ ->
            lookup(binding.rollInput.text?.toString().orEmpty()); true
        }
    }

    private fun lookup(raw: String) {
        val term = raw.trim()
        if (term.isEmpty()) return
        binding.content.removeAllViews()

        viewLifecycleOwner.lifecycleScope.launch {
            val repo = (BrainRepository.state.value as? InitState.Ready)?.repo
            if (repo == null) { showMessage("The corpus is not available."); return@launch }

            val payload = withContext(Dispatchers.IO) {
                val exact = repo.tabular.studentByRoll(term)
                if (exact != null) {
                    listOf(exact to repo.tabular.subjectsFor(exact.rollNo))
                } else {
                    repo.tabular.studentsByName(term).take(20).map { s ->
                        s to repo.tabular.subjectsFor(s.rollNo)
                    }
                }
            }

            when {
                payload.isEmpty() -> showMessage(getString(R.string.no_student))
                payload.size == 1 -> render(payload[0].first, payload[0].second)
                else -> {
                    showMessage("${payload.size} students match \"$term\" — showing each:")
                    payload.forEach { (s, subs) -> render(s, subs) }
                }
            }
        }
    }

    private fun showMessage(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            // setPadding takes pixels. The old call passed 8 and 16 straight
            // through, which on this screen's density is under 3dp and 6dp —
            // the "no student found" line was sitting almost flush.
            val v = resources.getDimensionPixelSize(R.dimen.space_4)
            setPadding(0, v, 0, v)
            setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.type_body_sm)
            )
        }
        binding.content.addView(tv)
    }

    private fun render(s: StudentRow, subjects: List<SubjectRow>) {
        val inflater = layoutInflater
        val header = inflater.inflate(R.layout.view_student_header, binding.content, false)

        header.findViewById<TextView>(R.id.name).text = s.name ?: "(name not recorded)"
        header.findViewById<TextView>(R.id.roll).text = s.rollNo

        val badge = header.findViewById<TextView>(R.id.resultBadge)
        // students.result verbatim. The exporter enforces NOT NULL on it so
        // downstream code can trust it; deriving pass/fail here instead would
        // be a second opinion nobody asked for.
        badge.text = s.result
        // Tint rather than setBackgroundColor: the latter throws away the
        // rounded shape drawable for a flat ColorDrawable and squares the
        // badge off. Same trap as the route badge in MessageAdapter.
        badge.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (s.result.equals("FAIL", true)) R.color.grade_fail else R.color.grade_pass
            )
        )

        val tiles = header.findViewById<LinearLayout>(R.id.tiles)
        s.sgpa?.let { tiles.addView(tile(tiles, "SGPA", "%.2f".format(it))) }
        s.estimatedSgpa?.let { tiles.addView(tile(tiles, "Est. SGPA", "%.2f".format(it))) }
        s.totalMarks?.let { tiles.addView(tile(tiles, "Total", it.toString())) }
        val backlogs = subjects.count { Grades.isFail(it.grade) }
        tiles.addView(tile(tiles, "Backlogs", backlogs.toString()))

        val flags = header.findViewById<TextView>(R.id.flags)
        val notes = buildList {
            if (s.isSupply) add("Appeared for supplementary examination")
            if (s.seatCancelled) add("Seat cancelled")
            val audits = subjects.count { Grades.isAudit(it.grade) }
            if (audits > 0) add("$audits audit subject(s), excluded from SGPA credits")
        }
        if (notes.isNotEmpty()) {
            flags.visibility = View.VISIBLE
            flags.text = notes.joinToString(" · ")
        }

        // grade_point is already base_point x credit, so the recomputation
        // divides by credits and does NOT multiply again. Shown beside the
        // stored value rather than instead of it; if they disagree, both are
        // visible rather than one silently winning.
        val recomputed = Grades.recomputeSgpa(
            subjects.map { it.grade to (it.credit to it.gradePoint) }
        )
        val mismatch = s.sgpa != null && recomputed != null &&
            kotlin.math.abs(s.sgpa - recomputed) > 0.005
        val verify = header.findViewById<TextView>(R.id.verify)
        verify.text = buildString {
            append("stored SGPA ").append(s.sgpa?.let { "%.4f".format(it) } ?: "-")
            append("   recomputed ").append(recomputed?.let { "%.4f".format(it) } ?: "-")
            if (mismatch) append("   MISMATCH")
        }
        // A disagreement between the stored and recomputed SGPA is the single
        // most important thing this card can say, and it was saying it in the
        // quietest colour on the screen.
        verify.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (mismatch) R.color.grade_fail else R.color.text_tertiary
            )
        )
        binding.content.addView(header)

        val spacer = View(requireContext())
        spacer.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            // Pixels, not dp: the old literal 12 was four device pixels of gap.
            resources.getDimensionPixelSize(R.dimen.space_5)
        )
        binding.content.addView(spacer)

        subjects.forEach { sub ->
            val row = inflater.inflate(R.layout.item_subject_row, binding.content, false)
            row.findViewById<TextView>(R.id.code).text = sub.subjectCode
            row.findViewById<TextView>(R.id.credit).text = "${sub.credit} cr"
            row.findViewById<TextView>(R.id.points).text = "%.1f".format(sub.gradePoint)
            val g = row.findViewById<TextView>(R.id.grade)
            g.text = sub.grade ?: "-"
            // AB is a PASS at 8.5, not an absence. AU is an audit, never a fail.
            if (Grades.isAudit(sub.grade)) {
                // An audit carries no verdict, so it does not wear one. Its
                // old filled badge had to be a light grey to take dark ink,
                // which put it a hair off text_tertiary and made a subject
                // nobody was graded on the loudest thing in the column.
                g.setBackgroundResource(R.drawable.bg_chip)
                g.backgroundTintList = null
                g.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            } else {
                // Tint rather than setBackgroundColor, so the badge keeps its
                // corners. The ink is the near-black from the layout because
                // the white this used to set came to 2:1 on grade_pass and
                // 3.1:1 on grade_fail; the near-black is 9.6:1 and 6.2:1.
                g.setBackgroundResource(R.drawable.bg_route_badge)
                g.backgroundTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(
                        requireContext(),
                        if (Grades.isFail(sub.grade)) R.color.grade_fail else R.color.grade_pass
                    )
                )
                g.setTextColor(ContextCompat.getColor(requireContext(), R.color.on_accent))
            }
            binding.content.addView(row)
        }
    }

    /**
     * Inflated rather than assembled in code. The hand-built version used raw
     * pixel padding, float text sizes and an alpha step for the label, so the
     * three figures on the card answered to nothing in the type scale and
     * changing the scale left them behind.
     */
    private fun tile(parent: ViewGroup, label: String, value: String): View {
        val tile = layoutInflater.inflate(R.layout.view_stat_tile, parent, false)
        tile.findViewById<TextView>(R.id.value).text = value
        tile.findViewById<TextView>(R.id.label).text = label
        return tile
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
