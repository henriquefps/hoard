package dev.hfps.hoard.app.gallery

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.Samples2b
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.ui.gallery.GalleryContent
import dev.hfps.hoard.app.ui.gallery.GalleryUi
import dev.hfps.hoard.app.ui.server.ClaimDialog
import dev.hfps.hoard.app.ui.server.ServerSettingsSection
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsUiState
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.designsystem.theme.ThemeMode
import dev.hfps.hoard.server.api.Candidate
import dev.hfps.hoard.server.api.CandidateDevice
import dev.hfps.hoard.server.db.ServerAccountEntity
import dev.hfps.hoard.server.sync.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Thumbnails drawn in the test: a gradient per shot (no files, no network). */
object FakeThumbs : ThumbSource {
    private val cache = HashMap<String, ImageBitmap>()
    override fun key(entry: GalleryEntry, px: Int) = entry.key
    override fun peek(entry: GalleryEntry, px: Int): ImageBitmap = cache.getOrPut(entry.key) {
        val h = entry.key.hashCode()
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val hue = ((h ushr 1) % 360).toFloat()
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.45f, 0.85f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.6f, 0.45f))
        Canvas(bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 96f, 96f, a, b, Shader.TileMode.CLAMP) })
        bitmap.asImageBitmap()
    }
    override suspend fun load(entry: GalleryEntry, px: Int) = peek(entry, px)
}

/**
 * The Gallery tab (ADR 0021): behaviour under Robolectric and goldens under `src/test/screenshots/` (copied to
 * docs/testing/gallery/): the grid with every badge, month headers at the densest zoom, filters and search, empty and
 * permission states, the banners, Settings › Photo server and the "same phone" question; phone portrait and
 * landscape, tablet, light and dark. Record with `./gradlew :native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryScreenTest {
    @get:Rule val compose = createComposeRule()

    private val account = ServerAccountEntity(
        url = "http://192.168.1.20:3071", serverId = "srv", serverName = "Hoard server", serverVersion = "0.9.0", deviceId = "dev",
        physicalDeviceId = "dev", app = "hoard", features = "items,previews,culling,device-identity,edits", cursor = "x:i9", caughtUp = true,
        pairedAt = 1, lastSyncAt = System.currentTimeMillis() - 120_000,
    )

    private fun ui(entries: List<GalleryEntry> = GallerySamples.entries(), columns: Int = 4, filter: GalleryFilter = GalleryFilter(), account: ServerAccountEntity? = this.account, status: SyncStatus = SyncStatus()) =
        GalleryUi(layout(entries.filter(filter::matches), byMonth = columns >= 7), filter, columns, true, account, status, entries.size)

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun shoot(name: String, qualifiers: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        settle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun grid(ui: GalleryUi): @Composable () -> Unit = { TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryContent(ui, FakeThumbs) } }

    @Test fun gridPhoneLight() = shoot("gallery_grid_phone_light", ShellScreenshotTest.PHONE, false, grid(ui()))
    @Test fun gridPhoneDark() = shoot("gallery_grid_phone_dark", ShellScreenshotTest.PHONE, true, grid(ui()))
    @Test fun gridLandscapeLight() = shoot("gallery_grid_landscape_light", ShellScreenshotTest.LANDSCAPE, false, grid(ui()))
    @Test fun gridLandscapeDark() = shoot("gallery_grid_landscape_dark", ShellScreenshotTest.LANDSCAPE, true, grid(ui()))
    @Test fun gridTabletLight() = shoot("gallery_grid_tablet_light", ShellScreenshotTest.TABLET, false, grid(ui()))
    @Test fun gridTabletDark() = shoot("gallery_grid_tablet_dark", ShellScreenshotTest.TABLET, true, grid(ui()))
    @Test fun gridBigPhoneTwoColumns() = shoot("gallery_grid_phone_2col_light", ShellScreenshotTest.PHONE, false, grid(ui(columns = 2)))
    @Test fun gridDenseMonths() = shoot("gallery_grid_phone_months_dark", ShellScreenshotTest.PHONE, true, grid(ui(GallerySamples.big(2000), columns = 8)))
    @Test fun gridFiltered() = shoot("gallery_grid_filtered_phone_light", ShellScreenshotTest.PHONE, false, grid(ui(filter = GalleryFilter(kind = KindFilter.RawPairs, minRating = 4, query = "trip"))))
    @Test fun noMatch() = shoot("gallery_no_match_phone_light", ShellScreenshotTest.PHONE, false, grid(ui(filter = GalleryFilter(camera = "Leica M11"))))
    @Test fun unpaired() = shoot("gallery_unpaired_phone_light", ShellScreenshotTest.PHONE, false, grid(ui(account = null)))
    @Test fun syncing() = shoot("gallery_syncing_phone_dark", ShellScreenshotTest.PHONE, true, grid(ui(status = SyncStatus(running = true))))
    @Test fun permission() = shoot("gallery_permission_phone_light", ShellScreenshotTest.PHONE, false) {
        TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryContent(GalleryUi(layout(emptyList(), false), permission = false, account = null), FakeThumbs) }
    }

    @Test fun claimQuestion() = shoot("gallery_claim_phone_light", ShellScreenshotTest.PHONE, false) {
        ClaimDialog(listOf(Candidate("phys", "Pixel 8", "model", listOf(CandidateDevice("phys", "Pixel 8", "reveal", "android", "Google", "Pixel 8"))))) {}
    }

    private fun settings(paired: Boolean): @Composable () -> Unit {
        val container = Samples2b.container
        runBlocking { if (paired) container.gallery.db.account().put(account) else container.gallery.db.account().clear() }
        return {
            Samples2b.Toasts { SettingsScreen(
                state = SettingsUiState(themeMode = ThemeMode.System, palette = HfpsPalette.Blue, version = "2.0.0-dev", legacyImport = null),
                onBack = {}, onThemeMode = {}, onPalette = {}, onOpenLicences = {},
                server = { ServerSettingsSection(container, onPair = {}) },
            ) }
        }
    }

    @Test fun settingsUnpaired() = shoot("gallery_settings_unpaired_phone_light", ShellScreenshotTest.PHONE, false, settings(false))
    @Test fun settingsPaired() = shoot("gallery_settings_paired_phone_dark", ShellScreenshotTest.PHONE, true, settings(true))

    @Test
    fun badgesShowWhereAndWhatEachShotIs() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.TABLET}")
        var opened: String? = null
        compose.setContent { FixedNowTheme(darkTheme = false) { GalleryContent(ui(), FakeThumbs, onOpen = { opened = it }) } }
        settle()
        // The pair on the phone and the server shows once, backed up.
        compose.onAllNodesWithTag("badge-both", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("badge-pair", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("badge-video", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("badge-motion", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("badge-edited", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("badge-pick", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithTag("badge-reject", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithTag("badge-rating", useUnmergedTree = true).assertCountEquals(3)
        // The grid is described by a string resource (en / pt-BR), not a hard-coded word.
        val described = RuntimeEnvironment.getApplication().getString(dev.hfps.hoard.app.R.string.gallery_grid_description)
        assertThat(described).isEqualTo("Photo grid")
        compose.onNodeWithContentDescription(described).assertExists()
        compose.onAllNodesWithTag("gallery-cell").onFirst().performClick()
        assertThat(opened).isEqualTo("d:device/Pictures/Screenshots/screenshot_1")
    }

    @Test
    fun theUnpairedBannerOffersSetup() {
        var paired = false
        compose.setContent { FixedNowTheme(darkTheme = false) { GalleryContent(ui(account = null), FakeThumbs, onPair = { paired = true }) } }
        settle()
        compose.onNodeWithTag("gallery-banner-unpaired").assertExists()
        compose.onNodeWithTag("gallery-search").performClick()
        compose.onNodeWithTag("gallery-search-field").assertExists()
        assertThat(paired).isFalse()
    }
}
