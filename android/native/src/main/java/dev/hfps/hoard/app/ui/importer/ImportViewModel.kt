package dev.hfps.hoard.app.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.importer.BridgeOffer
import dev.hfps.hoard.app.importer.BridgeSource
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

    /** Side-by-side build: Hoard 1 is on the phone, "Import from Hoard" or start fresh. */
    data class Offer(val offer: BridgeOffer) : ImportUiState

    data class Running(val progress: ImportProgress?) : ImportUiState

    /** Imported just now: rows per table, shown once before the shell; [passwords] machines still need theirs. */
    data class Imported(val counts: Map<String, Int>, val passwords: Int = 0) : ImportUiState

    /** Nothing written; [retry] or continue (the next launch tries again). */
    data class Failed(val failure: ImportFailure, val message: String) : ImportUiState

    /** After a bridge import: each machine's password, asked once. */
    data object Passwords : ImportUiState

    /** Imported earlier, nothing to import, or the user continued: the shell. */
    data object Ready : ImportUiState
}

/**
 * Runs [CapacitorImporter] once per process and turns its outcome into [ImportUiState]. With a [bridge] (the
 * side-by-side build, ADR 0019), Hoard 1's import bridge is offered first: the user imports from Hoard or starts fresh.
 */
class ImportViewModel(
    private val bridge: BridgeSource? = null,
    private val run: suspend ((ImportProgress) -> Unit) -> ImportOutcome,
) : ViewModel() {
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Checking)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** The last run came through the bridge: "Try again" repeats it. */
    private var viaBridge = false

    init {
        viewModelScope.launch {
            val offer = runCatching { bridge?.offer() }.getOrNull()
            if (offer != null) _state.value = ImportUiState.Offer(offer) else start()
        }
    }

    private fun start() {
        viaBridge = false
        viewModelScope.launch { settle(run { progress -> _state.value = ImportUiState.Running(progress) }) }
    }

    private suspend fun settle(outcome: ImportOutcome) {
        _state.value = when (outcome) {
            ImportOutcome.NothingToImport, is ImportOutcome.AlreadyDone -> ImportUiState.Ready
            is ImportOutcome.Imported -> ImportUiState.Imported(
                countsOf(outcome.record.rowCounts),
                if (viaBridge) runCatching { bridge?.machinesWithoutPassword()?.size }.getOrNull() ?: 0 else 0,
            )
            is ImportOutcome.Failed -> ImportUiState.Failed(outcome.failure, outcome.message)
        }
    }

    /** "Import from Hoard": copy Hoard 1's snapshot through the bridge and import it. */
    fun importFromHoard() {
        val source = bridge ?: return
        viaBridge = true
        _state.value = ImportUiState.Running(null)
        viewModelScope.launch { settle(source.import { progress -> _state.value = ImportUiState.Running(progress) }) }
    }

    /** "Check again" after updating Hoard 1. */
    fun checkAgain() {
        viewModelScope.launch {
            val offer = runCatching { bridge?.offer() }.getOrNull()
            if (offer != null) _state.value = ImportUiState.Offer(offer) else start()
        }
    }

    /** "Start without importing": recorded as a fresh install (the importer finds no file of its own). */
    fun startFresh() {
        _state.value = ImportUiState.Running(null)
        start()
    }

    fun retry() {
        if (_state.value !is ImportUiState.Failed) return
        if (viaBridge) importFromHoard() else {
            _state.value = ImportUiState.Running(null)
            start()
        }
    }

    /** Leave the result (or the failure) for the shell; after a bridge import, the passwords come first. */
    fun finish() {
        val current = _state.value
        _state.value = if (current is ImportUiState.Imported && current.passwords > 0) ImportUiState.Passwords else ImportUiState.Ready
    }

    /** The Passwords step is done (or left for later). */
    fun passwordsDone() {
        _state.value = ImportUiState.Ready
    }

    companion object {
        fun factory(importer: CapacitorImporter, bridge: BridgeSource? = null): ViewModelProvider.Factory = viewModelFactory {
            initializer { ImportViewModel(bridge) { onProgress -> importer.importIfNeeded(onProgress) } }
        }

        fun countsOf(json: String): Map<String, Int> = runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getInt(it) }
        }.getOrDefault(emptyMap())
    }
}
