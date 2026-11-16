package dev.hfps.hoard.app.gallery

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.gallery.GallerySamples.fp
import dev.hfps.hoard.app.gallery.GallerySamples.server
import dev.hfps.hoard.app.ui.gallery.DevicesContent
import dev.hfps.hoard.app.ui.gallery.GalleryContent
import dev.hfps.hoard.app.ui.gallery.GalleryUi
import dev.hfps.hoard.app.ui.gallery.SaveToDeviceContent
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.BackupRoot
import dev.hfps.hoard.server.db.ServerAccountEntity
import dev.hfps.hoard.server.db.ServerItemMirrorEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Other devices see backups (ADR 0024 A2): Gallery › Devices, the grid showing one phone's backup with the "Deleted
 * from the phone" badge, and the "Save to this device" sheet. Goldens in `src/test/screenshots/` (copied to
 * docs/testing/backup-a2/); record with `./gradlew :native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryDevicesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val pixel = "phys-pixel"
    private val tablet = "phys-tab"

    private val account = ServerAccountEntity(
        url = "http://192.168.1.20:3071", serverId = "srv", serverName = "Hoard server", serverVersion = "0.12.0", deviceId = "dev",
        physicalDeviceId = tablet, app = "hoard", features = "items,previews,culling,device-identity,edits,backup", cursor = "x:i9", caughtUp = true,
        pairedAt = 1, lastSyncAt = System.currentTimeMillis() - 120_000,
    )

    private fun mirror(item: String, n: Int, path: String, onDevice: Boolean = true, gone: String? = null, physical: String = pixel) =
        ServerItemMirrorEntity(item, fp(n), physical, if (physical == pixel) "Pixel 8" else "Tab S9", "r-$physical", "primary", path, 1000, 1, onDevice, gone)

    private val entries: List<GalleryEntry> by lazy {
        val rows = (1..14).map { i -> server("px$i", "PXL_202611%02d_%04d.jpg".format(i, i), "2026-11-%02dT10:00:00.000".format(i), folder = "Pixel 8/DCIM/Camera", main = 7000 + i, make = "Google", model = "Pixel 8") } +
            listOf(
                server("wa1", "IMG-20261101-WA0001.jpg", "2026-11-01T12:00:00.000", folder = "Pixel 8/WhatsApp Images", main = 7100, make = null, model = null),
                server("tab1", "Screenshot_20261104.png", "2026-11-04T09:00:00.000", folder = "Tab S9/Pictures/Screenshots", main = 7200, make = null, model = null),
            )
        val mirrors = (1..14).map { i -> mirror("px$i", 7000 + i, "DCIM/Camera/PXL_202611%02d_%04d.jpg".format(i, i), onDevice = i % 4 != 0, gone = if (i % 4 == 0) "deleted" else null) } +
            listOf(
                mirror("wa1", 7100, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG-20261101-WA0001.jpg", onDevice = false, gone = "deleted"),
                mirror("tab1", 7200, "Pictures/Screenshots/Screenshot_20261104.png", physical = tablet),
            )
        GalleryModel.entries(GalleryInput(rows, emptyList(), emptyMap(), emptyMap(), emptyMap(), mirrors = mirrors.groupBy { it.itemId }, physicalId = tablet))
    }

    private val roots = listOf(
        BackupRoot("r-$pixel", pixel, "Pixel 8", volume = "PRIMARY", path = "DCIM/Camera", active = true),
        BackupRoot("r-$pixel-wa", pixel, "Pixel 8", volume = "PRIMARY", path = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", active = true),
        BackupRoot("r-$pixel-sd", pixel, "Pixel 8", volume = "1234-ABCD", path = "DCIM", active = true),
        BackupRoot("r-$tablet", tablet, "Tab S9", mine = true, volume = "PRIMARY", path = "Pictures", active = true),
    )

    private val devices by lazy { DeviceTree.build(roots, entries, tablet) }

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

    private fun sheet(content: @Composable () -> Unit): @Composable () -> Unit = {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.card)) { content() }
    }

    private fun backupGrid(where: Where): @Composable () -> Unit = {
        val filter = GalleryFilter(where = where)
        val ui = GalleryUi(layout(entries.filter(filter::matches), byMonth = false), filter, 4, true, account, total = entries.size, devices = devices)
        TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryContent(ui, FakeThumbs) }
    }

    private fun save(dark: Boolean, needsAllFiles: Boolean = false): @Composable () -> Unit = sheet {
        SaveToDeviceContent(3, "Pictures/From Pixel 8", chosen = false, needsAllFiles = needsAllFiles, problem = null, busy = false, onChange = {}, onReset = {}, onAllow = {}, onCancel = {}, onSave = {})
    }

    @Test fun devicesPhoneLight() = shoot("gallery_devices_phone_light", ShellScreenshotTest.PHONE, false, sheet { DevicesContent(devices, null, onPick = {}) })
    @Test fun devicesPhoneDark() = shoot("gallery_devices_phone_dark", ShellScreenshotTest.PHONE, true, sheet { DevicesContent(devices, Where(pixel, "Pixel 8", "PRIMARY", "DCIM/Camera"), onPick = {}) })
    @Test fun devicesEmpty() = shoot("gallery_devices_empty_phone_light", ShellScreenshotTest.PHONE, false, sheet { DevicesContent(emptyList(), null, onPick = {}) })
    @Test fun backupGridPhoneLight() = shoot("gallery_backup_grid_phone_light", ShellScreenshotTest.PHONE, false, backupGrid(Where(pixel, "Pixel 8")))
    @Test fun backupGridPhoneDark() = shoot("gallery_backup_grid_phone_dark", ShellScreenshotTest.PHONE, true, backupGrid(Where(pixel, "Pixel 8", "PRIMARY", "DCIM/Camera")))
    @Test fun backupGridTablet() = shoot("gallery_backup_grid_tablet_light", ShellScreenshotTest.TABLET, false, backupGrid(Where(pixel, "Pixel 8")))
    @Test fun saveSheetLight() = shoot("gallery_save_device_phone_light", ShellScreenshotTest.PHONE, false, save(false))
    @Test fun saveSheetDark() = shoot("gallery_save_device_phone_dark", ShellScreenshotTest.PHONE, true, save(true, needsAllFiles = true))

    @Test
    fun theBackupGridMarksShotsThePhoneDeletedAndClearsBackToEverything() {
        var cleared = false
        var devicesOpened = false
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                val filter = GalleryFilter(where = Where(pixel, "Pixel 8"))
                val ui = GalleryUi(layout(entries.filter(filter::matches), byMonth = false), filter, 4, true, account, total = entries.size, devices = devices)
                GalleryContent(ui, FakeThumbs, onClearWhere = { cleared = true }, onDevices = { devicesOpened = true })
            }
        }
        settle()
        // Every 4th camera shot and the WhatsApp image were deleted on the phone (the grid composes only what is on screen).
        assertThat(compose.onAllNodesWithTag("badge-off-phone", useUnmergedTree = true).fetchSemanticsNodes()).isNotEmpty()
        compose.onAllNodesWithTag("badge-both", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("gallery-where").assertExists()
        compose.onNodeWithTag("gallery-devices-open").performClick()
        compose.onNodeWithTag("gallery-where-clear").performClick()
        assertThat(devicesOpened).isTrue()
        assertThat(cleared).isTrue()
    }

    @Test
    fun devicesPickAFolder() {
        var picked: Where? = null
        compose.setContent { FixedNowTheme(darkTheme = false) { DevicesContent(devices, null, onPick = { picked = it }) } }
        settle()
        compose.onAllNodesWithTag("gallery-device-folder").onFirst().performClick()
        assertThat(picked!!.physicalId).isEqualTo(tablet)
        assertThat(picked!!.dir).isEqualTo("Pictures")
    }

    @Test
    fun saveWaitsForAllFilesAccess() {
        compose.setContent { FixedNowTheme(darkTheme = false) { save(false, needsAllFiles = true)() } }
        settle()
        compose.onNodeWithTag("save-device-save").assertIsNotEnabled()
    }
}
