package dev.hfps.hoard.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportOutcome
import dev.hfps.hoard.data.legacy.ImportProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the first launch shows while the Capacitor data is imported (ADR 0019). */
sealed interface ImportUiState {
    /** Looking for an earlier import or a Capacitor database (milliseconds). */
    data object Checking : ImportUiState

    data class Running(val progress: ImportProgress?) : ImportUiState

    /** Imported just now: rows per table, shown once before the shell. */
    data class Imported(val counts: Map<String, Int>) : ImportUiState

    /** Nothing written; [retry] or continue (the next launch tries again). */
    data class Failed(val failure: ImportFailure, val message: String) : ImportUiState

    /** Imported earlier, nothing to import, or the user continued: the shell. */
    data object Ready : ImportUiState
}

/** Runs [CapacitorImporter] once per process and turns its outcome into [ImportUiState]. */
class ImportViewModel(private val run: suspend ((ImportProgress) -> Unit) -> ImportOutcome) : ViewModel() {
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Checking)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    init {
        start()
    }

    private fun start() {
        viewModelScope.launch {
            val outcome = run { progress -> _state.value = ImportUiState.Running(progress) }
            _state.value = when (outcome) {
                ImportOutcome.NothingToImport, is ImportOutcome.AlreadyDone -> ImportUiState.Ready
                is ImportOutcome.Imported -> ImportUiState.Imported(countsOf(outcome.record.rowCounts))
                is ImportOutcome.Failed -> ImportUiState.Failed(outcome.failure, outcome.message)
            }
        }
    }

    fun retry() {
        if (_state.value !is ImportUiState.Failed) return
        _state.value = ImportUiState.Running(null)
        start()
    }

    /** Leave the result (or the failure) for the shell. */
    fun finish() {
        _state.value = ImportUiState.Ready
    }

    companion object {
        fun factory(importer: CapacitorImporter): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel { onProgress -> importer.importIfNeeded(onProgress) } }
        }

        fun countsOf(json: String): Map<String, Int> = runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        }.getOrDefault(emptyMap())
    }
}
