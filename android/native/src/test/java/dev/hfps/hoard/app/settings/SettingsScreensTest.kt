package dev.hfps.hoard.app.settings

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.Samples2b
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.data.FilesSettings
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.ui.settings.AboutSettingsScreen
import dev.hfps.hoard.app.ui.settings.AppearanceSettingsScreen
import dev.hfps.hoard.app.ui.settings.BackupFoldersSettingsScreen
import dev.hfps.hoard.app.ui.settings.BrowsingSettingsContent
import dev.hfps.hoard.app.ui.settings.CullingSettingsScreen
import dev.hfps.hoard.app.ui.settings.ServerSettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsPage
import dev.hfps.hoard.app.ui.settings.SettingsPageScaffold
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsUiState
import dev.hfps.hoard.app.ui.settings.StorageSettingsScreen
import dev.hfps.hoard.app.ui.settings.TrashSettingsScreen
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.ThemeMode
import dev.hfps.hoard.server.db.ServerAccountEntity
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings reorganized (Hoard 2.9.0, docs/design/settings-2026-11): the Settings list and each of its screens, with
 * their key states (not paired, an http server, a sync error, "Compress videos" shown and hidden). Goldens under
 * `src/test/screenshots/settings_*`, in pt-BR like the owner's wireframes, copied to
 * docs/design/settings-2026-11/app/. Record with `./gradlew :native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsScreensTest {
    @get:Rule val compose = createComposeRule()

    private val https = ServerAccountEntity(
        url = "https://hoard.example.ts.net", serverId = "srv", serverName = "hoard-server", serverVersion = "0.13.0", deviceId = "dev",
        physicalDeviceId = "dev", app = "hoard", features = "items,previews,culling,device-identity,edits,backup", cursor = "x:i9", caughtUp = true,
        pairedAt = 1, lastSyncAt = System.currentTimeMillis() - 120_000,
    )

    private val state = SettingsUiState(
        themeMode = ThemeMode.System,
        palette = HfpsPalette.Blue,
        version = "2.9.0",
        legacyImport = LegacyImportEntity("capacitor", "none", 1, 7, "{}", "2026-11-14T12:00:00.000Z"),
    )

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun pair(account: ServerAccountEntity?) = runBlocking {
        val db = Samples2b.container.gallery.db.account()
        if (account == null) db.clear() else db.put(account)
    }

    private fun shoot(name: String, dark: Boolean, ptBr: Boolean = true, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        if (ptBr) RuntimeEnvironment.setQualifiers("+pt-rBR")
        compose.setContent { FixedNowTheme(darkTheme = dark) { Samples2b.Toasts { content() } } }
        settle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    // --- The list ---

    @Test fun listLight() = shoot("settings_list_phone_light", dark = false) { SettingsScreen({}, {}) }
    @Test fun listDark() = shoot("settings_list_phone_dark", dark = true) { SettingsScreen({}, {}) }
    @Test fun listEnglish() = shoot("settings_list_en_phone_light", dark = false, ptBr = false) { SettingsScreen({}, {}) }

    // --- Photo server ---

    private val server: @Composable () -> Unit = { ServerSettingsScreen(Samples2b.container, {}, {}, {}) }

    @Test fun serverLight() { pair(https); shoot("settings_server_phone_light", dark = false, content = server) }
    @Test fun serverDark() { pair(https); shoot("settings_server_phone_dark", dark = true, content = server) }
    @Test fun serverUnpairedLight() { pair(null); shoot("settings_server_unpaired_phone_light", dark = false, content = server) }
    @Test fun serverUnpairedDark() { pair(null); shoot("settings_server_unpaired_phone_dark", dark = true, content = server) }
    @Test fun serverHttpLight() {
        pair(https.copy(url = "http://192.168.1.20:3071"))
        shoot("settings_server_http_phone_light", dark = false, content = server)
    }
    @Test fun serverSyncErrorDark() {
        pair(https.copy(lastError = SyncEngine.ERROR_OFFLINE))
        shoot("settings_server_sync_error_phone_dark", dark = true, content = server)
    }
    @Test fun serverCompressLight() {
        pair(https.copy(features = https.features + ",compress"))
        shoot("settings_server_compress_phone_light", dark = false, content = server)
    }

    // --- Backup and phone folders ---

    private val backup: @Composable () -> Unit = { BackupFoldersSettingsScreen(Samples2b.container, {}, {}) }

    @Test fun backupLight() { pair(https); shoot("settings_backup_phone_light", dark = false, content = backup) }
    @Test fun backupDark() { pair(https); shoot("settings_backup_phone_dark", dark = true, content = backup) }
    @Test fun backupUnpairedLight() { pair(null); shoot("settings_backup_unpaired_phone_light", dark = false, content = backup) }

    // --- Cache and storage ---

    private val storage: @Composable () -> Unit = { StorageSettingsScreen(Samples2b.container, {}) }

    @Test fun storageLight() { pair(https); shoot("settings_storage_phone_light", dark = false, content = storage) }
    @Test fun storageDark() { pair(https); shoot("settings_storage_phone_dark", dark = true, content = storage) }
    @Test fun storageUnpairedLight() { pair(null); shoot("settings_storage_unpaired_phone_light", dark = false, content = storage) }

    // --- Appearance and language ---

    private val appearance: @Composable () -> Unit = { AppearanceSettingsScreen(state, {}, {}, {}, language = null, onLanguage = {}) }

    @Test fun appearanceLight() = shoot("settings_appearance_phone_light", dark = false, content = appearance)
    @Test fun appearanceDark() = shoot("settings_appearance_phone_dark", dark = true, content = appearance)

    // --- Browsing and transfers ---

    private val browsing: @Composable () -> Unit = {
        SettingsPageScaffold("Navegação e transferências", {}) { BrowsingSettingsContent(FilesSettings(), null) }
    }

    @Test fun browsingLight() = shoot("settings_browsing_phone_light", dark = false, content = browsing)
    @Test fun browsingDark() = shoot("settings_browsing_phone_dark", dark = true, content = browsing)

    // --- Trash ---

    private val trash: @Composable () -> Unit = { TrashSettingsScreen(Samples2b.container, {}, {}) }

    @Test fun trashLight() = shoot("settings_trash_phone_light", dark = false, content = trash)
    @Test fun trashDark() = shoot("settings_trash_phone_dark", dark = true, content = trash)

    // --- Culling ---

    private val culling: @Composable () -> Unit = { CullingSettingsScreen(Samples2b.container, {}) }

    @Test fun cullingLight() = shoot("settings_culling_phone_light", dark = false, content = culling)
    @Test fun cullingDark() = shoot("settings_culling_phone_dark", dark = true, content = culling)

    // --- About ---

    private val about: @Composable () -> Unit = { AboutSettingsScreen(state, {}, {}) }

    @Test fun aboutLight() = shoot("settings_about_phone_light", dark = false, content = about)
    @Test fun aboutDark() = shoot("settings_about_phone_dark", dark = true, content = about)

    // --- Behaviour ---

    @Test fun theListOpensEachScreen() {
        val opened = mutableListOf<SettingsPage>()
        compose.setContent { FixedNowTheme(darkTheme = false) { SettingsScreen({}, { opened += it }) } }
        SettingsPage.entries.forEach { page ->
            compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("settings-page-${page.id}"))
            compose.onNodeWithTag("settings-page-${page.id}").performClick()
        }
        assertThat(opened).containsExactlyElementsIn(SettingsPage.entries).inOrder()
    }

    @Test fun theCompressRowNeedsTheFeature() {
        pair(https)
        compose.setContent { FixedNowTheme(darkTheme = false) { Samples2b.Toasts { server() } } }
        settle()
        compose.onNodeWithTag("server-sync-now").assertIsDisplayed()
        compose.onNodeWithTag("settings-compress").assertDoesNotExist()
        pair(https.copy(features = https.features + ",compress"))
        settle()
        compose.onNodeWithTag("settings-compress").assertExists()
    }

    @Test fun unpairedServerOffersOnlyPairing() {
        pair(null)
        var paired = 0
        compose.setContent { FixedNowTheme(darkTheme = false) { Samples2b.Toasts { ServerSettingsScreen(Samples2b.container, {}, { paired++ }, {}) } } }
        settle()
        compose.onNodeWithTag("server-sync-now").assertDoesNotExist()
        compose.onNodeWithTag("server-unpair").assertDoesNotExist()
        compose.onNodeWithTag("server-pair").performClick()
        assertThat(paired).isEqualTo(1)
    }

    @Test fun theHttpWarningAndTheSyncErrorAreShownInFull() {
        pair(https.copy(url = "http://192.168.1.20:3071", lastError = SyncEngine.ERROR_UNAUTHORIZED))
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        compose.setContent { FixedNowTheme(darkTheme = false) { Samples2b.Toasts { server() } } }
        settle()
        compose.onNodeWithText("plain HTTP", substring = true, ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithText("no longer accepts this phone", substring = true, ignoreCase = true).assertIsDisplayed()
    }

    @Test fun theCachesNeedAPairedServer() {
        pair(null)
        compose.setContent { FixedNowTheme(darkTheme = false) { Samples2b.Toasts { storage() } } }
        settle()
        compose.onNodeWithTag("server-cache").assertDoesNotExist()
        compose.onNodeWithTag("settings-reveal-copies").assertExists()
        pair(https)
        settle()
        compose.onNodeWithTag("server-cache").assertExists()
        compose.onNodeWithTag("server-offline-thumbs").assertExists()
    }

    @Test fun theSelectsChangeTheSettings() {
        val modes = mutableListOf<ThemeMode>()
        val palettes = mutableListOf<HfpsPalette>()
        val languages = mutableListOf<String?>()
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                AppearanceSettingsScreen(state, {}, { modes += it }, { palettes += it }, language = null, onLanguage = { languages += it })
            }
        }
        compose.onNodeWithTag("settings-theme").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.onNodeWithTag("settings-palette").performClick()
        compose.onNodeWithText("Olive").performClick()
        compose.onNodeWithTag("settings-language").performClick()
        compose.onNodeWithText("Português (Brasil)").performClick()
        assertThat(modes).containsExactly(ThemeMode.Dark)
        assertThat(palettes).containsExactly(HfpsPalette.Olive)
        assertThat(languages).containsExactly("pt-BR")
    }

    @Test fun browsingShowsTheChosenValues() {
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                SettingsPageScaffold("Browsing", {}) {
                    BrowsingSettingsContent(FilesSettings(showHiddenFiles = true, defaultSort = SortOrder(SortKey.Modified, false), defaultConflict = "rename"), null)
                }
            }
        }
        compose.onNodeWithText("Modified").assertExists()
        compose.onNodeWithText("Newest first").assertExists()
    }
}
