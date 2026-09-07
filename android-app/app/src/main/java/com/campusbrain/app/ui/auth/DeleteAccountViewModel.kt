package com.campusbrain.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusbrain.app.data.auth.AccountDeletion
import com.campusbrain.app.data.auth.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where one deletion has got to. Four states, and the middle one is the
 * confirmation -- it is a state rather than a dialog so that a rotation
 * cannot silently drop a student back to the un-confirmed screen, and so that
 * the two paragraphs saying what is lost stay on screen while they decide. */
sealed interface DeleteState {
    /** The explanation, with the destructive action offered but not armed. */
    data object Explaining : DeleteState

    /** Armed: the question, and the two answers to it. */
    data object Confirming : DeleteState

    /** In flight. */
    data object Working : DeleteState

    /** Terminal, and rendered by [DeleteAccountCopy.of]. */
    data class Done(val result: AccountDeletion.Result) : DeleteState
}

/**
 * Owns the deletion call so the fragment does not.
 *
 * [EnrolViewModel]'s reasoning, and it matters more here. The call is a token
 * refresh followed by an RPC, each with an eight-second timeout, and the
 * request is not idempotent from the student's point of view -- turning the
 * phone sideways after the server has deleted the account, with the work
 * launched from a view lifecycle, would leave a deleted account and a screen
 * that never said so. A ViewModel survives the rotation and the result lands.
 */
class DeleteAccountViewModel : ViewModel() {

    private val _state = MutableStateFlow<DeleteState>(DeleteState.Explaining)
    val state: StateFlow<DeleteState> = _state

    private var job: Job? = null

    /** Arms the confirmation. Nothing is sent, and nothing is deleted. */
    fun arm() {
        if (_state.value is DeleteState.Explaining) _state.value = DeleteState.Confirming
    }

    /** Backs out of the confirmation, or off a retryable outcome. */
    fun backToStart() {
        if (job?.isActive == true) return
        _state.value = DeleteState.Explaining
    }

    /**
     * Sends the one request. Returns false when one is already running, in
     * which case nothing was started -- a second delete in flight would race
     * its own token refresh for no gain.
     *
     * Only callable from [DeleteState.Confirming]: the confirmation is a
     * precondition in code, not just a screen a student happened to see.
     */
    fun confirm(): Boolean {
        if (_state.value !is DeleteState.Confirming) return false
        if (job?.isActive == true) return false
        _state.value = DeleteState.Working
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { Identity.deleteAccount() }.getOrElse {
                    // deleteAccount does not throw by design. If it ever does,
                    // an unreachable institution is the honest reading and it
                    // is the branch that changes nothing, locally or remotely.
                    AccountDeletion.Result.Unavailable
                }
            }
            _state.value = DeleteState.Done(result)
        }
        return true
    }
}
