package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.ThemeMode

/** Settings of phase 2a: appearance (theme, palette), about (version, the Hoard 1 import, licences). */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (HfpsPalette) -> Unit,
    onOpenLicences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HfpsScaffold(
        modifier = modifier,
        header = rememberCollapsingHeaderState(),
        topBar = { HfpsTopBar(title = stringResource(R.string.settings_title), onBack = onBack) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Section(R.string.settings_theme)
                Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
                    listOf(
                        ThemeMode.System to R.string.theme_system,
                        ThemeMode.Light to R.string.theme_light,
                        ThemeMode.Dark to R.string.theme_dark,
                    ).forEach { (mode, label) ->
                        HfpsChoiceRow(stringResource(label), state.themeMode == mode, { onThemeMode(mode) })
                    }
                }
                HfpsRowDivider(Modifier.padding(top = 8.dp))
                Section(R.string.settings_palette)
                Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
                    HfpsPalette.entries.forEach { palette ->
                        HfpsChoiceRow(stringResource(paletteName(palette)), state.palette == palette, { onPalette(palette) })
                    }
                }
                HfpsRowDivider(Modifier.padding(top = 8.dp))
                Section(R.string.settings_about)
                HfpsListRow(
                    title = stringResource(R.string.settings_version),
                    subtitle = state.version,
                    icon = PhosphorRegular.Info,
                )
                HfpsRowDivider(Modifier.padding(start = 54.dp))
                HfpsListRow(
                    title = stringResource(R.string.settings_import),
                    subtitle = importSubtitle(state.legacyImport),
                    icon = PhosphorRegular.Database,
                )
                HfpsRowDivider(Modifier.padding(start = 54.dp))
                HfpsListRow(
                    title = stringResource(R.string.settings_licences),
                    subtitle = stringResource(R.string.settings_licences_subtitle),
                    icon = PhosphorRegular.Scroll,
                    onClick = onOpenLicences,
                )
            }
        }
    }
}

@Composable
private fun Section(title: Int) {
    Eyebrow(stringResource(title), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun importSubtitle(record: LegacyImportEntity?): String = when (record?.status) {
    null -> stringResource(R.string.settings_import_pending)
    "none" -> stringResource(R.string.settings_import_none)
    else -> stringResource(R.string.settings_import_done, record.importedAt.take(10))
}

fun paletteName(palette: HfpsPalette): Int = when (palette) {
    HfpsPalette.Blue -> R.string.palette_blue
    HfpsPalette.Olive -> R.string.palette_olive
    HfpsPalette.Violet -> R.string.palette_violet
    HfpsPalette.Rose -> R.string.palette_rose
    HfpsPalette.Graphite -> R.string.palette_graphite
}
