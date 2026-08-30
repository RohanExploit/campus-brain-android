package com.kriet.campusbrain.ui.result

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.kriet.campusbrain.Grades
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.InitState
import com.kriet.campusbrain.data.StudentRow
import com.kriet.campusbrain.data.SubjectRow
import com.kriet.campusbrain.databinding.FragmentResultBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The TABULAR route with a form instead of free text.
 *
 * Calls the same [com.kriet.campusbrain.retrieval.TabularQueries] the router
 * calls, so a bug cannot exist on one screen and not the other.
 */
class ResultFragment : Fragment() {

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
            textSize = 13f
            setPadding(8, 16, 8, 16)
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
        badge.setBackgroundColor(
            ContextCompat.getColor(
                requireContext(),
                if (s.result.equals("FAIL", true)) R.color.grade_fail else R.color.grade_pass
            )
        )

        val tiles = header.findViewById<LinearLayout>(R.id.tiles)
        s.sgpa?.let { tiles.addView(tile("SGPA", "%.2f".format(it))) }
        s.estimatedSgpa?.let { tiles.addView(tile("Est. SGPA", "%.2f".format(it))) }
        s.totalMarks?.let { tiles.addView(tile("Total", it.toString())) }
        val backlogs = subjects.count { Grades.isFail(it.grade) }
        tiles.addView(tile("Backlogs", backlogs.toString()))

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
        header.findViewById<TextView>(R.id.verify).text = buildString {
            append("stored SGPA ").append(s.sgpa?.let { "%.4f".format(it) } ?: "-")
            append("   recomputed ").append(recomputed?.let { "%.4f".format(it) } ?: "-")
            if (s.sgpa != null && recomputed != null && kotlin.math.abs(s.sgpa - recomputed) > 0.005) {
                append("   MISMATCH")
            }
        }
        binding.content.addView(header)

        val spacer = View(requireContext())
        spacer.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
        binding.content.addView(spacer)

        subjects.forEach { sub ->
            val row = inflater.inflate(R.layout.item_subject_row, binding.content, false)
            row.findViewById<TextView>(R.id.code).text = sub.subjectCode
            row.findViewById<TextView>(R.id.credit).text = "${sub.credit} cr"
            row.findViewById<TextView>(R.id.points).text = "%.1f".format(sub.gradePoint)
            val g = row.findViewById<TextView>(R.id.grade)
            g.text = sub.grade ?: "-"
            // AB is a PASS at 8.5, not an absence. AU is an audit, never a fail.
            val c = when {
                Grades.isAudit(sub.grade) -> R.color.grade_audit
                Grades.isFail(sub.grade) -> R.color.grade_fail
                else -> R.color.grade_pass
            }
            g.setBackgroundColor(ContextCompat.getColor(requireContext(), c))
            g.setTextColor(Color.WHITE)
            binding.content.addView(row)
        }
    }

    private fun tile(label: String, value: String): View {
        val ll = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 28, 0)
        }
        ll.addView(TextView(requireContext()).apply {
            text = value; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        ll.addView(TextView(requireContext()).apply {
            text = label; textSize = 10f; alpha = 0.6f
        })
        return ll
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
