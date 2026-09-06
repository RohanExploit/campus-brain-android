package com.kriet.campusbrain.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kriet.campusbrain.data.auth.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where one enrolment attempt has got to. */
sealed interface EnrolState {
    /** The form, filled in or not. */
    data object Editing : EnrolState

    /** In flight. The form is disabled but still on screen and still legible;
     * a student who mistyped a code should be able to see what they sent. */
    data object Working : EnrolState

    /** Terminal, and rendered by [EnrolCopy.of]. */
    data class Done(val result: Identity.EnrolResult) : EnrolState
}

/**
 * Owns the network call so the fragment does not.
 *
 * The reasoning is [com.kriet.campusbrain.ui.docs.ImportViewModel]'s, almost
 * word for word, and it applies harder here.
 * [Identity.enrol] is up to five sequential HTTP calls -- sign-up, sign-in,
 * redeem, memberships, tenants -- each with an eight-second timeout, so on a
 * captive portal this runs for the better part of a minute. Launched from
 * `viewLifecycleOwner.lifecycleScope`, turning the phone sideways halfway
 * through would abandon it. Worse than abandoning an import: a rotation
 * landing after `redeem` returned means the account was created and the code
 * was consumed with nothing on screen to say so.
 *
 * Fragment-scoped rather than activity-scoped, unlike the import: nothing
 * outside this screen needs the result, and a ViewModel on the fragment
 * already survives a configuration change. Re-running after a lost result is
 * safe in any case -- the second attempt redeems the same code, the server
 * answers 23505, and `AlreadyEnrolled` recovers the grant.
 */
class EnrolViewModel : ViewModel() {

    private val _state = MutableStateFlow<EnrolState>(EnrolState.Editing)
    val state: StateFlow<EnrolState> = _state

    private var job: Job? = null

    /**
     * Starts one attempt. Returns false when one is already running, in which
     * case nothing was started.
     *
     * The three arguments are the only place in this class that a password or
     * a code exists. Neither is stored on the instance, neither is logged, and
     * neither survives the call: [EnrolState.Done] carries the outcome, not
     * the input. The fragment clears the fields on success for the same
     * reason -- a redeemed code has no further use and a screen still showing
     * it is a screenshot waiting to happen.
     */
    fun submit(email: String, password: String, code: String): Boolean {
        if (job?.isActive == true) return false
        _state.value = EnrolState.Working
        job = viewModelScope.launch {
            // Identity.enrol's callees each move to Dispatchers.IO themselves,
            // so this is belt and braces rather than a fix -- but it makes the
            // one function on this screen that touches a socket say so at the
            // call site, which is worth four words.
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    Identity.enrol(EnrolForm.addressFor(email), password, code)
                }.getOrElse {
                    // enrol does not throw by design; every failure path
                    // returns a result. If one ever does, an unreachable
                    // institution is the honest reading and it is the branch
                    // that changes nothing locally.
                    Identity.EnrolResult.Unavailable
                }
            }
            _state.value = EnrolState.Done(result)
        }
        return true
    }

    /** Back to the form after a retryable outcome, with what was typed intact. */
    fun backToForm() {
        if (_state.value is EnrolState.Done) _state.value = EnrolState.Editing
    }
}
