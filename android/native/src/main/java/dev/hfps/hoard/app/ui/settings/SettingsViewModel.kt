package dev.hfps.hoard.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.BuildConfig
import dev.hfps.hoard.app.ui.id
import dev.hfps.hoard.app.ui.themeModeOf
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.data.legacy.CapacitorImporter
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val palette: HfpsPalette = HfpsPalette.Blue,
    val version: String = BuildConfig.VERSION_NAME,
    /** The Capacitor import's record; null while nothing was recorded (not run yet, or it failed). */
    val legacyImport: LegacyImportEntity? = null,
)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val state: StateFlow<SettingsUiState> = combine(
        settings.observeString(SettingsStore.THEME_MODE),
        settings.observeString(SettingsStore.PALETTE),
        container.database.legacyImports().observe(CapacitorImporter.SOURCE),
    ) { mode, palette, record ->
        SettingsUiState(themeModeOf(mode), HfpsPalette.fromId(palette), legacyImport = record)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.putString(SettingsStore.THEME_MODE, mode.id) }
    }

    fun setPalette(palette: HfpsPalette) {
        viewModelScope.launch { settings.putString(SettingsStore.PALETTE, palette.id) }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
