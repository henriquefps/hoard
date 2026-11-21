package dev.hfps.hoard.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.ui.files.FilesScreen
import dev.hfps.hoard.app.ui.importer.ImportScreen
import dev.hfps.hoard.app.ui.importer.ImportUiState
import dev.hfps.hoard.app.ui.settings.AboutSettingsScreen
import dev.hfps.hoard.app.ui.settings.AppearanceSettingsScreen
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import dev.hfps.hoard.app.ui.settings.SettingsUiState
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import dev.hfps.hoard.app.ui.shell.usesRail
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.designsystem.theme.ThemeMode
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Compose UI tests of the shell's screens (JVM, Robolectric). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-port-xhdpi")
class ShellBehaviourTest {
    @get:Rule val compose = createComposeRule()

    @Test fun theTabsSwitch() {
        val picked = mutableListOf<Tab>()
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                TabbedLayout(selected = Tab.Files, onSelect = { picked += it }) { FilesScreen(Samples.files, {}) }
            }
        }
        compose.onNodeWithText("Gallery").performClick()
        assertThat(picked).containsExactly(Tab.Gallery)
    }

    @Test fun filesListsMachinesVolumesAndFavorites() {
        var settings = 0
        compose.setContent { HfpsTheme(darkTheme = false) { FilesScreen(Samples.files, onOpenSettings = { settings++ }) } }
        compose.onNodeWithText("NAS").assertIsDisplayed()
        compose.onNodeWithText("192.168.1.10 and 2 more addresses").assertIsDisplayed()
        compose.onNodeWithText("No address").assertIsDisplayed()
        compose.onNodeWithText("Internal storage").assertIsDisplayed()
        compose.onNodeWithText("SanDisk SD card").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Lisboa"))
        compose.onNodeWithText("NAS · photos · 2026/Lisboa").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").performClick()
        assertThat(settings).isEqualTo(1)
    }

    @Test fun anEmptyFilesTabSaysWhy() {
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                FilesScreen(Samples.files.copy(machines = emptyList(), removable = emptyList(), favorites = emptyList()), {})
            }
        }
        compose.onNodeWithText("Plug in a USB drive or an SD card reader and it shows up here.").assertIsDisplayed()
        compose.onAllNodesWithText("Favorites").assertCountEquals(0)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add your first machine"))
        compose.onNodeWithText("Add your first machine").assertIsDisplayed()
    }

    @Test fun aFailedImportOffersRetryAndContinue() {
        var retried = 0
        var continued = 0
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                ImportScreen(ImportUiState.Failed(ImportFailure.Unreadable, "x"), { continued++ }, { retried++ })
            }
        }
        compose.onNodeWithText("The Hoard 1 database could not be read.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Continue without importing").performClick()
        assertThat(retried to continued).isEqualTo(1 to 1)
    }

    @Test fun theImportSummaryCountsWhatCameOver() {
        var continued = 0
        compose.setContent { HfpsTheme(darkTheme = false) { ImportScreen(ImportUiState.Imported(Samples.counts), { continued++ }, {}) } }
        compose.onNodeWithText("3 machines").assertIsDisplayed()
        compose.onNodeWithText("4 favorites").assertIsDisplayed()
        compose.onNodeWithText("5 culling decisions").assertIsDisplayed()
        compose.onNodeWithText("Continue").performClick()
        assertThat(continued).isEqualTo(1)
    }

    @Test fun settingsChangeThemeAndPalette() {
        val modes = mutableListOf<ThemeMode>()
        val palettes = mutableListOf<HfpsPalette>()
        var licences = 0
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                Column {
                    Box(Modifier.weight(1f)) { AppearanceSettingsScreen(SettingsUiState(), {}, { modes += it }, { palettes += it }) }
                    Box(Modifier.weight(1f)) { AboutSettingsScreen(SettingsUiState(), {}, { licences++ }) }
                }
            }
        }
        compose.onNodeWithTag("settings-theme").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithTag("settings-palette").performClick()
        compose.onNodeWithText("Olive").performClick()
        compose.onNodeWithText("Not imported").assertExists()
        compose.onNodeWithTag("settings-licences").performScrollTo().performClick()
        assertThat(modes).containsExactly(ThemeMode.Dark)
        assertThat(palettes).containsExactly(HfpsPalette.Olive)
        assertThat(licences).isEqualTo(1)
    }

    @Test fun theRailIsForTabletsAndShortScreens() {
        assertThat(usesRail(390.dp, 844.dp)).isFalse()
        assertThat(usesRail(844.dp, 390.dp)).isTrue()
        assertThat(usesRail(1280.dp, 800.dp)).isTrue()
        assertThat(usesRail(600.dp, 960.dp)).isTrue()
    }
}
