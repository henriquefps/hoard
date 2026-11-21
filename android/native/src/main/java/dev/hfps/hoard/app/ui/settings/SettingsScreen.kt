package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.formatIsoDay
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsSelect
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.designsystem.theme.ThemeMode

/**
 * The parts of Settings (Hoard 2.9.0, docs/design/settings-2026-11): the Settings screen lists them, each opens its
 * own screen. [id] is the navigation argument (`SettingsPageRoute`).
 */
enum class SettingsPage(val id: String, val title: Int, val summary: Int?, val icon: () -> ImageVector) {
    Server("server", R.string.server_section, R.string.settings_server_summary, { PhosphorRegular.LockSimple }),
    BackupFolders("backup", R.string.settings_backup_title, R.string.settings_backup_summary, { PhosphorRegular.UploadSimple }),
    Storage("storage", R.string.settings_storage_title, R.string.settings_storage_summary, { PhosphorRegular.Database }),
    Appearance("appearance", R.string.settings_appearance_title, R.string.settings_appearance_summary, { PhosphorRegular.Palette }),
    Browsing("browsing", R.string.settings_browsing_title, R.string.settings_browsing_summary, { PhosphorRegular.FolderSimple }),
    Trash("trash", R.string.trash_title, null, { PhosphorRegular.Trash }),
    Culling("culling", R.string.t_settings_culling, R.string.settings_culling_summary, { PhosphorRegular.Images }),
    About("about", R.string.settings_about, R.string.settings_about_summary, { PhosphorRegular.ShieldCheck }),
    ;

    companion object {
        fun of(id: String): SettingsPage = entries.firstOrNull { it.id == id } ?: Server
    }
}

/** Settings: the list of its parts, each opening its own screen ([onOpen]). */
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpen: (SettingsPage) -> Unit, modifier: Modifier = Modifier) {
    SettingsPageScaffold(stringResource(R.string.settings_title), onBack, modifier) {
        SettingsPage.entries.forEachIndexed { i, page ->
            if (i > 0) HfpsRowDivider(Modifier.padding(start = 54.dp))
            HfpsListRow(
                title = stringResource(page.title),
                subtitle = page.summary?.let { stringResource(it) },
                icon = page.icon(),
                onClick = { onOpen(page) },
                modifier = Modifier.testTag("settings-page-${page.id}"),
                trailing = { Icon(PhosphorRegular.CaretRight, null, Modifier.size(18.dp), tint = HfpsTheme.colors.mutedForeground) },
            )
        }
    }
}

/** One Settings screen: the collapsing top bar with back, and a scrolling column at most 840 dp wide. */
@Composable
fun SettingsPageScaffold(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    HfpsScaffold(
        modifier = modifier,
        header = rememberCollapsingHeaderState(),
        topBar = { HfpsTopBar(title = title, onBack = onBack) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 24.dp),
                content = content,
            )
        }
    }
}

/** A section's eyebrow inside a Settings screen. */
@Composable
fun SettingsSection(title: Int, modifier: Modifier = Modifier) {
    Eyebrow(stringResource(title), modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** A muted help line under a setting. */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Settings › Appearance and language: theme, palette and the in-app language, each a drop-down list. */
@Composable
fun AppearanceSettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onPalette: (HfpsPalette) -> Unit,
    /** The in-app language (null = the system's), and its setter; the language shows when [onLanguage] is given. */
    language: String? = null,
    onLanguage: ((String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    SettingsPageScaffold(stringResource(R.string.settings_appearance_title), onBack, modifier) {
        SettingsSection(R.string.settings_theme)
        HfpsSelect(
            options = listOf(ThemeMode.System, ThemeMode.Light, ThemeMode.Dark),
            selected = state.themeMode,
            optionLabel = { stringResource(themeName(it)) },
            onSelect = onThemeMode,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("settings-theme"),
        )
        SettingsSection(R.string.settings_palette)
        HfpsSelect(
            options = HfpsPalette.entries,
            selected = state.palette,
            optionLabel = { stringResource(paletteName(it)) },
            onSelect = onPalette,
            modifier = Modifier.padding(horizontal = 16.dp).testTag("settings-palette"),
        )
        if (onLanguage != null) {
            SettingsSection(R.string.t_settings_language)
            HfpsSelect(
                options = listOf(null, "en", "pt-BR"),
                selected = language,
                optionLabel = {
                    stringResource(
                        when (it) {
                            null -> R.string.language_system
                            "en" -> R.string.t_language_en
                            else -> R.string.t_language_pt_br
                        },
                    )
                },
                onSelect = onLanguage,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("settings-language"),
            )
        }
    }
}

/** Settings › About: the version, the Hoard 1 import, the licence and its links, the open source licences. */
@Composable
fun AboutSettingsScreen(state: SettingsUiState, onBack: () -> Unit, onOpenLicences: () -> Unit, modifier: Modifier = Modifier) {
    SettingsPageScaffold(stringResource(R.string.settings_about), onBack, modifier) {
        SettingsSection(R.string.settings_about)
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
        Text(
            stringResource(R.string.t_settings_about_body),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        HfpsListRow(
            title = stringResource(R.string.t_settings_source_code),
            subtitle = REPOSITORY.removePrefix("https://"),
            icon = PhosphorRegular.ArrowSquareOut,
            onClick = { runCatching { uriHandler.openUri(REPOSITORY) } },
        )
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        HfpsListRow(
            title = stringResource(R.string.t_settings_license),
            icon = PhosphorRegular.ArrowSquareOut,
            onClick = { runCatching { uriHandler.openUri("$REPOSITORY/blob/main/LICENSE") } },
        )
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        HfpsListRow(
            title = stringResource(R.string.settings_licences),
            subtitle = stringResource(R.string.settings_licences_subtitle),
            icon = PhosphorRegular.Scroll,
            onClick = onOpenLicences,
            modifier = Modifier.testTag("settings-licences"),
            trailing = { Icon(PhosphorRegular.CaretRight, null, Modifier.size(18.dp), tint = HfpsTheme.colors.mutedForeground) },
        )
    }
}

/** Hoard's repository (Hoard 1's Settings › About links). */
const val REPOSITORY = "https://github.com/henriquefps/hoard"

@Composable
private fun importSubtitle(record: LegacyImportEntity?): String = when (record?.status) {
    null -> stringResource(R.string.settings_import_pending)
    "none" -> stringResource(R.string.settings_import_none)
    else -> stringResource(R.string.settings_import_done, formatIsoDay(record.importedAt, locale()))
}

fun themeName(mode: ThemeMode): Int = when (mode) {
    ThemeMode.System -> R.string.theme_system
    ThemeMode.Light -> R.string.theme_light
    ThemeMode.Dark -> R.string.theme_dark
}

fun paletteName(palette: HfpsPalette): Int = when (palette) {
    HfpsPalette.Blue -> R.string.palette_blue
    HfpsPalette.Olive -> R.string.palette_olive
    HfpsPalette.Violet -> R.string.palette_violet
    HfpsPalette.Rose -> R.string.palette_rose
    HfpsPalette.Graphite -> R.string.palette_graphite
}
