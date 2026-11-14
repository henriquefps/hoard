package dev.hfps.hoard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.ui.importer.ImportScreen
import dev.hfps.hoard.app.ui.importer.ImportUiState
import dev.hfps.hoard.app.ui.importer.ImportViewModel
import dev.hfps.hoard.app.ui.shell.HoardShell
import dev.hfps.hoard.data.SettingsStore
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.designsystem.theme.ThemeMode
import dev.hfps.hoard.designsystem.theme.isDark

/**
 * The app: the user's theme and palette, then the first-launch import of the Capacitor data (ADR 0019) until it has
 * settled, then the shell. [systemBars] lets the activity match the bar icons to the theme.
 */
@Composable
fun HoardApp(container: AppContainer, systemBars: @Composable (dark: Boolean) -> Unit = {}) {
    val mode by container.settings.observeString(SettingsStore.THEME_MODE).collectAsStateWithLifecycle(null)
    val paletteId by container.settings.observeString(SettingsStore.PALETTE).collectAsStateWithLifecycle(null)
    val dark = themeModeOf(mode).isDark()
    systemBars(dark)
    HfpsTheme(darkTheme = dark, palette = HfpsPalette.fromId(paletteId)) {
        val importer: ImportViewModel = viewModel(factory = ImportViewModel.factory(container.importer))
        val state by importer.state.collectAsStateWithLifecycle()
        when (val current = state) {
            ImportUiState.Ready -> HoardShell(container)
            // A fresh install or an earlier import: decided in a few milliseconds, show the window background.
            ImportUiState.Checking -> Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background))
            else -> ImportScreen(
                state = current,
                onContinue = importer::finish,
                onRetry = importer::retry,
            )
        }
    }
}

fun themeModeOf(id: String?): ThemeMode = when (id) {
    "light" -> ThemeMode.Light
    "dark" -> ThemeMode.Dark
    else -> ThemeMode.System
}

val ThemeMode.id: String get() = name.lowercase()
