package com.kriet.campusbrain.ui.docs

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kriet.campusbrain.R
import com.kriet.campusbrain.data.BrainRepository
import com.kriet.campusbrain.data.IngestResult
import com.kriet.campusbrain.data.InitState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Where one import has got to. */
sealed interface ImportState {
    data object Idle : ImportState

    /**
     * [total] is 0 while the file is being read and split, which is the
     * backend's own signal that the chunk count is not knowable yet. The UI
     * maps that straight onto the progress bar's indeterminate mode rather
     * than inventing a fraction to fill the gap.
     */
    data class Working(val done: Int, val total: Int) : ImportState

    /**
     * Terminal. [keywordOnly] is read at the moment the document was written,
     * not at render time: it describes what happened to *this* document, and
     * an embedder that becomes ready later does not retroactively vectorise it.
     */
    data class Done(val result: IngestResult, val keywordOnly: Boolean) : ImportState
}

/**
 * Owns the import so that neither the fragment nor the Activity does.
 *
 * Scoped to the Activity, for three reasons that all showed up at once:
 *
 *  - **Rotation.** Embedding 200 passages takes tens of seconds and
 *    [com.kriet.campusbrain.data.DocumentIngest] checks for cancellation on
 *    every chunk. Run from `viewLifecycleOwner.lifecycleScope`, turning the
 *    phone sideways would silently abandon the whole document. `viewModelScope`
 *    outlives the view, so it does not.
 *  - **The share sheet.** The Uri arrives at MainActivity but the progress and
 *    the outcome belong on the Documents tab. An Activity-scoped ViewModel is
 *    the handoff; the alternative is passing a Uri through nav arguments and
 *    hoping the read grant is still alive on the other side.
 *  - **One at a time.** Two concurrent ingests would race on the same SQLite
 *    writer. [start] refuses rather than queueing, because queueing means
 *    holding a Uri whose transient read permission this app cannot renew.
 */
class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    private var job: Job? = null

    /**
     * Begins an import. Returns false when one is already running, in which
     * case nothing was started and the caller should say so.
     */
    fun start(uri: Uri): Boolean {
        if (job?.isActive == true) return false
        job = viewModelScope.launch {
            _state.value = ImportState.Working(0, 0)

            // A share can arrive before the corpus has finished opening, on a
            // cold start where the app was launched *by* the share. Waiting on
            // the state flow is what makes that case work instead of failing
            // with "still starting up" the one time a demo does it.
            val ready = BrainRepository.state.first { it !is InitState.Loading }
            val repo = (ready as? InitState.Ready)?.repo
            if (repo == null) {
                _state.value = ImportState.Done(
                    IngestResult.Failed(getApplication<Application>().getString(R.string.import_no_repo)),
                    keywordOnly = false,
                )
                return@launch
            }

            val ingest = repo.ingest
            // onProgress is invoked on this coroutine's context, which is Main.
            // Assigning a StateFlow is safe from anywhere, so no post() and no
            // second dispatch; the collector on the Documents tab renders it.
            val result = ingest.ingest(uri) { done, total ->
                _state.value = ImportState.Working(done, total)
            }
            _state.value = ImportState.Done(result, keywordOnly = !ingest.embedderReady)
        }
        return true
    }

    /** True while a document is being read or embedded. */
    val isBusy: Boolean get() = job?.isActive == true

    /**
     * Clears a terminal result. Called when the student dismisses the card or
     * acts on it — never automatically on a timer, because three of the four
     * outcomes carry an instruction the student has to be able to re-read.
     */
    fun dismiss() {
        if (_state.value is ImportState.Done) _state.value = ImportState.Idle
    }
}
