package com.campusbrain.app.ui.license

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import com.campusbrain.app.R
import com.campusbrain.app.data.BrainRepository
import com.campusbrain.app.data.InitState
import com.campusbrain.app.data.auth.License
import com.campusbrain.app.data.auth.LicenseKey
import com.campusbrain.app.data.auth.Licensing
import com.campusbrain.app.data.auth.Tier
import com.campusbrain.app.databinding.FragmentLicenseBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The only caller of [Licensing.apply] in the app.
 *
 * Reached by long-pressing the header's status pill. Never navigated to
 * automatically, never presented at launch, never put in front of a question
 * -- the same three rules [com.campusbrain.app.ui.auth.EnrolFragment] lives
 * by, and for the same reason. Everything this app answers, it answers from
 * documents already on the phone, at every tier, and it answers them for a
 * student who never opens this screen exactly as readily as for one who does.
 *
 * Nothing here consults a licence to decide whether something works. It reads
 * one to describe it, and writes one when an admin pastes a key. The single
 * conditional on tier in this file hides a link to the usage screen, which is
 * a screen and not a capability.
 */
class LicenseFragment : Fragment() {

    // A step down from the header into a detail, so it moves along Z like
    // DocDetailFragment and EnrolFragment. Sibling tabs cross-fade, hierarchy
    // moves on Z; there is no third idiom in this app and this does not add one.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    private var _binding: FragmentLicenseBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        _binding = FragmentLicenseBinding.inflate(i, c, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.licenseKey.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                // Enabled on "there is something to try", not on "this looks
                // like a key". A shape check here would have to agree with
                // LicenseKey.decode forever, and the moment it disagreed a
                // valid key would be un-submittable with no explanation.
                binding.licenseApply.isEnabled = !s?.toString()?.trim().isNullOrEmpty()
            }
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        binding.licenseApply.setOnClickListener { apply() }
        binding.licenseRemove.setOnClickListener {
            Licensing.removeLicense()
            binding.licenseKeyHelp.setText(R.string.license_key_help)
        }

        // The install id, behind a long-press on the tier label. Follows
        // MainActivity's `binding.header.setOnLongClickListener` precedent: a
        // support affordance nobody discovers by accident.
        binding.licenseTier.setOnLongClickListener {
            binding.licenseInstallId.text = Licensing.installId
                ?.let { id -> getString(R.string.license_install_id_fmt, id) }
                ?: getString(R.string.license_install_id_missing)
            binding.licenseInstallId.visibility = View.VISIBLE
            true
        }

        binding.licenseAnalytics.setOnClickListener {
            findNavController().navigate(R.id.adminAnalyticsFragment)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                Licensing.license.collect { render(it) }
            }
        }
    }

    /**
     * Applies the pasted key.
     *
     * The key is cleared from the field on success and LEFT THERE on failure:
     * an admin who mistyped one character needs to see what they typed, and an
     * admin who succeeded has a bearer credential sitting on a screen with no
     * further use for it.
     *
     * The string is never logged, never put in a Toast, and never echoed back
     * into any message below. A licence key in logcat is a licence key
     * published to every app on the phone that can read logs.
     */
    private fun apply() {
        val typed = binding.licenseKey.text?.toString().orEmpty()
        when (val result = Licensing.apply(typed)) {
            is Licensing.ApplyResult.Accepted -> {
                binding.licenseKey.setText("")
                binding.licenseKeyHelp.text = getString(
                    R.string.license_applied_fmt, result.license.tenantDisplayName
                )
            }
            is Licensing.ApplyResult.Refused -> {
                // The correction replaces the help line rather than appearing
                // under it -- EnrolFragment's idiom, and its reasoning: the
                // screen does not change height, so nothing jumps under a
                // thumb, and a help line that turns into a correction reads as
                // the same sentence being refined.
                binding.licenseKeyHelp.text = buildString {
                    append(getString(LicenseCopy.messageFor(result.reason)))
                    // Only said when there is something to protect. On a free
                    // device it would be a reassurance about nothing.
                    if (result.prior != null) {
                        append(" ")
                        append(getString(R.string.license_unchanged_note))
                    }
                }
            }
            is Licensing.ApplyResult.NotStored ->
                binding.licenseKeyHelp.setText(R.string.license_refused_storage)
        }
    }

    private fun render(license: License?) {
        if (_binding == null) return
        val now = System.currentTimeMillis()
        val caps = Licensing.capsFor(license, now)

        binding.licenseTier.setText(LicenseCopy.tierNameFor(caps.tier))
        binding.licenseBody.text = describe(license, now)

        binding.licenseRemove.visibility = if (license == null) View.GONE else View.VISIBLE
        // A screen, not a capability. Hidden rather than shown-and-refusing:
        // a control a student cannot use is better absent than present and
        // saying no, and this app does not advertise at people.
        binding.licenseAnalytics.visibility =
            if (caps.tier.seesAnalytics) View.VISIBLE else View.GONE

        // The allowance, read off the corpus rather than remembered. Off the
        // main thread because it is two COUNT/SUM queries on a file an import
        // may currently hold a lock on.
        viewLifecycleOwner.lifecycleScope.launch {
            val used = withContext(Dispatchers.IO) {
                (BrainRepository.state.value as? InitState.Ready)?.repo?.ingest?.usage()
                    ?: (0 to 0L)
            }
            if (_binding == null) return@launch
            binding.licenseDocsTile.label.setText(R.string.license_usage_docs)
            binding.licenseDocsTile.value.text =
                getString(R.string.license_usage_fmt, used.first, caps.maxDocs)
            binding.licenseKbTile.label.setText(R.string.license_usage_kb)
            binding.licenseKbTile.value.text = getString(
                R.string.license_usage_fmt, (used.second / 1024L).toInt(), caps.maxTotalKb
            )
        }
    }

    /** The sentence under the tier. Free gets its own, which is not a pitch. */
    private fun describe(license: License?, nowMs: Long): CharSequence {
        if (license == null) return getString(R.string.license_free_body)
        val expiry = license.expiresAtMs
        return buildString {
            append(getString(R.string.license_institution_label))
            append(": ")
            append(license.tenantDisplayName)
            append("\n")
            append(
                when {
                    expiry == null -> getString(R.string.license_expires_never)
                    nowMs >= expiry -> getString(R.string.license_expired_fmt, date(expiry))
                    else -> getString(R.string.license_expires_fmt, date(expiry))
                }
            )
        }
    }

    /** The device's own locale and zone: this date is read by one person on
     * one phone, and an ISO timestamp would be worse for all of them. */
    private fun date(epochMs: Long): String = runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs))
    }.getOrDefault("")

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}

/**
 * Rejection reason to finished copy, as a pure lookup with no Android in it.
 *
 * Split out for the reason [com.campusbrain.app.ui.auth.EnrolCopy] was: a
 * `when` inside a fragment is a `when` no unit test can see, and the property
 * that matters here -- that every rejection reason has a distinct sentence
 * telling the admin what to do next -- is exactly the sort of thing that rots
 * silently when a new reason is added.
 */
object LicenseCopy {

    /**
     * Exhaustive over [LicenseKey.Rejection] with no `else`, deliberately: a
     * reason added later fails to compile here rather than falling through to
     * a generic "invalid key" that helps nobody.
     */
    fun messageFor(reason: LicenseKey.Rejection): Int = when (reason) {
        LicenseKey.Rejection.MALFORMED -> R.string.license_refused_malformed
        LicenseKey.Rejection.TRUNCATED -> R.string.license_refused_truncated
        LicenseKey.Rejection.BAD_SIGNATURE -> R.string.license_refused_signature
        LicenseKey.Rejection.UNKNOWN_SCHEMA -> R.string.license_refused_schema
        LicenseKey.Rejection.BAD_FIELD -> R.string.license_refused_field
        LicenseKey.Rejection.EXPIRED -> R.string.license_refused_expired
        LicenseKey.Rejection.WRONG_DEVICE -> R.string.license_refused_device
        // Not the admin's fault and the copy must not imply it is. It asks for
        // the one thing that actually helps -- the app version -- rather than
        // sending them to re-read a key that was probably fine.
        LicenseKey.Rejection.INTERNAL -> R.string.license_refused_internal
    }

    fun tierNameFor(tier: Tier): Int = when (tier) {
        Tier.FREE -> R.string.license_tier_free
        Tier.INSTITUTIONAL -> R.string.license_tier_institutional
        Tier.OWNER -> R.string.license_tier_owner
    }
}
