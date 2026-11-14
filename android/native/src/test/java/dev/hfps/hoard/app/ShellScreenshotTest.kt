package dev.hfps.hoard.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.hoard.app.ui.files.FilesScreen
import dev.hfps.hoard.app.ui.gallery.GalleryScreen
import dev.hfps.hoard.app.ui.importer.ImportScreen
import dev.hfps.hoard.app.ui.importer.ImportUiState
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsUiState
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportProgress
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the :native shell (ADR 0019) under `src/test/screenshots/`, copied to docs/testing/native-2a/.
 * Phone portrait (390 x 844dp), phone landscape (844 x 390dp: the short-screen rail), tablet (1280 x 800dp), light
 * and dark. Record: `./gradlew :native:recordRoborazziDebug`; verify: `verifyRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ShellScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String, dark: Boolean, palette: HfpsPalette = HfpsPalette.Blue, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { HfpsTheme(darkTheme = dark, palette = palette) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val files: @Composable () -> Unit = {
        TabbedLayout(selected = Tab.Files, onSelect = {}) { FilesScreen(Samples.files, onOpenSettings = {}) }
    }

    @Test fun filesPhoneLight() = shoot("files_phone_light", PHONE, dark = false, content = files)
    @Test fun filesPhoneDark() = shoot("files_phone_dark", PHONE, dark = true, content = files)
    @Test fun filesLandscapeLight() = shoot("files_landscape_light", LANDSCAPE, dark = false, content = files)
    @Test fun filesLandscapeDark() = shoot("files_landscape_dark", LANDSCAPE, dark = true, content = files)
    @Test fun filesTabletLight() = shoot("files_tablet_light", TABLET, dark = false, content = files)
    @Test fun filesTabletDark() = shoot("files_tablet_dark", TABLET, dark = true, content = files)

    @Test fun filesEmptyPhoneLight() = shoot("files_empty_phone_light", PHONE, dark = false) {
        TabbedLayout(selected = Tab.Files, onSelect = {}) {
            FilesScreen(Samples.files.copy(machines = emptyList(), removable = emptyList(), favorites = emptyList()), {})
        }
    }

    @Test fun galleryPhoneLight() = shoot("gallery_phone_light", PHONE, dark = false) {
        TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryScreen(onOpenSettings = {}) }
    }
    @Test fun galleryLandscapeDark() = shoot("gallery_landscape_dark", LANDSCAPE, dark = true) {
        TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryScreen(onOpenSettings = {}) }
    }

    private val settings: @Composable () -> Unit = {
        SettingsScreen(
            state = SettingsUiState(
                themeMode = ThemeMode.System,
                palette = HfpsPalette.Blue,
                version = "2.0.0-dev",
                legacyImport = LegacyImportEntity("capacitor", "imported", 1, 7, "{}", "2026-11-14T12:00:00.000Z"),
            ),
            onBack = {}, onThemeMode = {}, onPalette = {}, onOpenLicences = {},
        )
    }

    @Test fun settingsPhoneLight() = shoot("settings_phone_light", PHONE, dark = false, content = settings)
    @Test fun settingsPhoneDark() = shoot("settings_phone_dark", PHONE, dark = true, content = settings)
    @Test fun settingsTabletLight() = shoot("settings_tablet_light", TABLET, dark = false, content = settings)

    @Test fun importRunningPhoneLight() = shoot("import_running_phone_light", PHONE, dark = false) {
        ImportScreen(ImportUiState.Running(ImportProgress("cull_decisions", 7, 11)), {}, {})
    }
    @Test fun importDonePhoneLight() = shoot("import_done_phone_light", PHONE, dark = false) {
        ImportScreen(ImportUiState.Imported(Samples.counts), {}, {})
    }
    @Test fun importDonePhoneDark() = shoot("import_done_phone_dark", PHONE, dark = true) {
        ImportScreen(ImportUiState.Imported(Samples.counts), {}, {})
    }
    @Test fun importDoneLandscapeLight() = shoot("import_done_landscape_light", LANDSCAPE, dark = false) {
        ImportScreen(ImportUiState.Imported(Samples.counts), {}, {})
    }
    @Test fun importFailedPhoneDark() = shoot("import_failed_phone_dark", PHONE, dark = true) {
        ImportScreen(ImportUiState.Failed(ImportFailure.TooNew, "database version 8"), {}, {})
    }

    // The other four palettes on the same screen (blue is above).
    @Test fun filesOlive() = shoot("files_phone_light_olive", PHONE, dark = false, palette = HfpsPalette.Olive, content = files)
    @Test fun filesViolet() = shoot("files_phone_dark_violet", PHONE, dark = true, palette = HfpsPalette.Violet, content = files)
    @Test fun filesRose() = shoot("files_phone_light_rose", PHONE, dark = false, palette = HfpsPalette.Rose, content = files)
    @Test fun filesGraphite() = shoot("files_phone_dark_graphite", PHONE, dark = true, palette = HfpsPalette.Graphite, content = files)

    @Test fun filesPhoneLightFont200() {
        RuntimeEnvironment.setFontScale(2f)
        shoot("files_phone_light_font200", PHONE, dark = false, content = files)
    }

    companion object {
        const val PHONE = "w390dp-h844dp-port-xhdpi"
        const val LANDSCAPE = "w844dp-h390dp-land-xhdpi"
        const val TABLET = "w1280dp-h800dp-land-hdpi"
    }
}
