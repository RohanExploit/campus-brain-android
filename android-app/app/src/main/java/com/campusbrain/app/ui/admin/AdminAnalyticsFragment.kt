package com.campusbrain.app.ui.admin

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.transition.MaterialSharedAxis
import com.campusbrain.app.R
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.Estimates
import com.campusbrain.app.data.InitState
import com.campusbrain.app.data.QueryLog
import com.campusbrain.app.data.Route
import com.campusbrain.app.data.auth.Licensing
import com.campusbrain.app.databinding.FragmentAdminAnalyticsBinding
import com.campusbrain.app.databinding.ItemRouteBarBinding
import com.campusbrain.app.databinding.ItemRouteMinutesBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What this device has actually been used for, counted on this device.
 *
 * Institutional and owner tiers only, and reached only from the licence
 * screen's link -- which is itself hidden below those tiers. Note that the
 * hiding is the whole of the enforcement, and that is on purpose: this screen
 * displays counters that were collected regardless of tier, so there is no
 * secret here to protect, only a control that would mean nothing to a student.
 * Building a second gate would have meant a second thing to get wrong.
 *
 * Nothing on this screen touches the network. The export is the Android share
 * sheet handing the admin a block of text they chose the destination for.
 */
class AdminAnalyticsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentAdminAnalyticsBinding? = null
    private val binding get() = _binding!!

    private var snapshot = QueryLog.Snapshot(emptyMap(), emptyMap(), emptyMap())
    private var minutes: Map<Route, Int> = emptyMap()
    private var catalogue: List<String> = emptyList()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentAdminAnalyticsBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.keepTextSwitch.isChecked = Licensing.keepQueryText()
        binding.keepTextSwitch.setOnCheckedChangeListener { _, on ->
            // Written straight through rather than on leaving the screen. This
            // is a consent toggle: the gap between flicking it off and it
            // taking effect must be zero, and turning it off also deletes the
            // sample already kept -- see AnalyticsStore.setKeepQueryText.
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) { Licensing.setKeepQueryText(on) }
            }
        }

        binding.exportAction.setOnClickListener { share() }

        // The flush happens here, lazily, rather than on every query. See
        // QueryLog's header for the locking argument; the short version is
        // that a route histogram does not need per-event durability and the
        // answer path does not need a third writer.
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val snap = Licensing.analyticsSnapshot()
                val mins = Licensing.minutesAssumption()
                val docs = (BrainRepository.state.value as? InitState.Ready)
                    ?.repo?.docs?.all()?.map { it.docId }.orEmpty()
                Triple(snap, mins, docs)
            }
            if (_binding == null) return@launch
            snapshot = loaded.first
            minutes = loaded.second
            catalogue = loaded.third
            renderAll()
        }
    }

    private fun renderAll() {
        renderCounts()
        renderHistogram()
        renderUnused()
        renderMinutesRows()
        renderEstimate()
    }

    private fun renderCounts() {
        binding.statQuestions.label.setText(R.string.analytics_stat_questions)
        binding.statQuestions.value.text = snapshot.total.toString()
        binding.statAbstained.label.setText(R.string.analytics_stat_abstained)
        binding.statAbstained.value.text = snapshot.abstained.toString()
        binding.statRate.label.setText(R.string.analytics_stat_abstain_rate)
        // One decimal place. Two would imply a precision that 30 questions
        // cannot support, and none would round 4.9% to 5%.
        binding.statRate.value.text =
            String.format("%.1f%%", snapshot.abstentionRate * 100.0)
    }

    /**
     * The histogram, drawn as bars whose colours are the app's existing route
     * colours. Rows are inflated from the enum, so a fifth route appears the
     * day the router grows one rather than the day someone remembers this file.
     */
    private fun renderHistogram() {
        val container: LinearLayout = binding.routeHistogram
        container.removeAllViews()
        val bars = snapshot.histogram()
        val max = bars.maxOfOrNull { it.second } ?: 0
        bars.forEach { (route, count) ->
            val row = ItemRouteBarBinding.inflate(layoutInflater, container, false)
            row.routeLabel.text = route.name
            row.routeCount.text = count.toString()
            row.routeBar.setBackgroundColor(
                ContextCompat.getColor(requireContext(), colorFor(route))
            )
            // scaleX on a match_parent bar, not a computed pixel width: the
            // row has not been measured at this point, so its width is 0 and
            // any arithmetic on it would produce four invisible bars. Pivot at
            // the left edge so every bar grows from the same baseline.
            val fraction = if (max <= 0) 0f else count.toFloat() / max
            // A zero-count route keeps a hairline of colour rather than
            // vanishing: an invisible bar reads as a missing route, and "this
            // capability was never used" is the finding, not the absence.
            row.routeBar.scaleX = if (fraction <= 0f) 0.01f else fraction
            row.routeBar.pivotX = 0f
            container.addView(row.root)
        }
    }

    private fun colorFor(route: Route): Int = when (route) {
        Route.TABULAR -> R.color.route_tabular
        Route.FACT -> R.color.route_fact
        Route.LOCAL -> R.color.route_local
        Route.GLOBAL -> R.color.route_global
    }

    /**
     * Documents nothing has ever cited.
     *
     * The interesting figure for an institution is not which documents get
     * read but which never do -- an uncited handbook is either badly written,
     * badly named, or about something nobody has to ask.
     */
    private fun renderUnused() {
        val unused = snapshot.neverRetrieved(catalogue)
        if (catalogue.isEmpty() || unused.isEmpty()) {
            binding.unusedSummary.setText(R.string.analytics_unused_empty)
            binding.unusedList.visibility = View.GONE
            return
        }
        binding.unusedSummary.text =
            getString(R.string.analytics_unused_fmt, unused.size, catalogue.size)
        binding.unusedList.text = unused.joinToString("\n")
        binding.unusedList.visibility = View.VISIBLE
    }

    /** The editable assumption, one row per route, above the estimate. */
    private fun renderMinutesRows() {
        val container: LinearLayout = binding.minutesRows
        container.removeAllViews()
        Route.entries.forEach { route ->
            val row = ItemRouteMinutesBinding.inflate(layoutInflater, container, false)
            row.minutesLabel.text = route.name
            row.minutesValue.setText((minutes[route] ?: 0).toString())
            row.minutesValue.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString()?.toIntOrNull() ?: return
                    minutes = minutes + (route to v)
                    // Re-rendered live, so the reader watches the estimate move
                    // as they change the assumption. That is the fastest way to
                    // learn that the figure is a product of an assumption, and
                    // it is a lesson this screen wants taught.
                    renderEstimate()
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            Licensing.setMinutesAssumption(route, v)
                        }
                    }
                }
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            container.addView(row.root)
        }
    }

    private fun renderEstimate() {
        if (_binding == null) return
        val estimate = Estimates.of(snapshot, minutes)
        binding.estimateValue.text =
            getString(R.string.analytics_estimate_fmt, String.format("%.1f", estimate.hours))
    }

    /**
     * The share sheet, with the caveat inside the exported text.
     *
     * `ACTION_SEND` with `text/plain` rather than a file: the admin picks where
     * it goes, the app never writes it to shared storage, and no permission is
     * needed. The export carries the assumption on every line that used it --
     * see [UsageExport], where that rule is enforced rather than remembered.
     */
    private fun share() {
        val body = UsageExport.text(
            snapshot = snapshot,
            minutes = minutes,
            catalogue = catalogue,
            caveat = getString(R.string.analytics_estimate_caveat),
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.analytics_export_subject))
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, getString(R.string.analytics_export_chooser)))
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

/**
 * The export, as a pure function over the numbers.
 *
 * No Android in it, so the one property that matters can be unit-tested: a
 * saving figure never appears without the assumption that produced it and the
 * sentence saying it is an estimate. A CSV of hours with no methodology is
 * exactly the artefact that ends up on a slide, and once a buyer discovers
 * that a headline figure was a constant times a count, every measured number
 * beside it is suspect too.
 *
 * Question text is never exported, at any setting. The local sample exists so
 * an admin can READ what students ask on the phone in front of them; putting
 * it through a share sheet is the one action that would take it off the device,
 * which is the thing the opt-in copy promises never happens.
 */
object UsageExport {

    fun text(
        snapshot: QueryLog.Snapshot,
        minutes: Map<Route, Int>,
        catalogue: Collection<String>,
        caveat: String,
    ): String {
        val estimate = Estimates.of(snapshot, minutes)
        val unused = snapshot.neverRetrieved(catalogue)
        return buildString {
            appendLine("Campus Brain — usage on this device")
            appendLine("All figures counted on this phone. Nothing was transmitted.")
            appendLine()
            appendLine("MEASURED")
            appendLine("questions,${snapshot.total}")
            appendLine("abstentions,${snapshot.abstained}")
            appendLine("abstention_rate,${String.format("%.3f", snapshot.abstentionRate)}")
            appendLine()
            appendLine("route,questions,abstentions")
            snapshot.histogram().forEach { (route, count) ->
                appendLine("$route,$count,${snapshot.abstentions[route] ?: 0}")
            }
            appendLine()
            appendLine("documents_in_corpus,${catalogue.size}")
            appendLine("documents_never_cited,${unused.size}")
            unused.forEach { appendLine("never_cited,$it") }
            appendLine()
            // The heading says ESTIMATED and every row carries its own
            // assumption, so no line of this block can be lifted out of
            // context and read as a measurement.
            appendLine("ESTIMATED")
            appendLine(caveat)
            appendLine("route,questions_measured,minutes_assumed_each,minutes_estimated")
            estimate.lines.forEach { line ->
                appendLine("${line.route},${line.queries},${line.minutesEach},${line.minutes}")
            }
            appendLine("total_minutes_estimated,${estimate.totalMinutes}")
            appendLine("total_hours_estimated,${String.format("%.1f", estimate.hours)}")
        }
    }
}
