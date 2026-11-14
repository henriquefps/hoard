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
import dev.hfps.hoard.app.ui.settings.SettingsScreen
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
        compose.onNodeWithText("This phone").assertIsDisplayed()
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
        compose.onNodeWithText("No USB drive or SD card connected.").assertIsDisplayed()
        compose.onAllNodesWithText("Favorites").assertCountEquals(0)
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
                SettingsScreen(SettingsUiState(), {}, { modes += it }, { palettes += it }, { licences++ })
            }
        }
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithText("Olive").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Open-source licences"))
        compose.onNodeWithText("Not imported").assertIsDisplayed()
        compose.onNodeWithText("Open-source licences").performClick()
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
