package dev.hfps.hoard.app.backup

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.hoard.app.FixedNow
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.PHONE
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.TABLET
import dev.hfps.hoard.app.ui.backup.BackupAvailability
import dev.hfps.hoard.app.ui.backup.BackupContent
import dev.hfps.hoard.app.ui.backup.BackupFilesContent
import dev.hfps.hoard.app.ui.backup.BackupUi
import dev.hfps.hoard.app.ui.backup.FileFilter
import dev.hfps.hoard.server.backup.BackupEngine
import dev.hfps.hoard.server.backup.BackupProgress
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupState
import dev.hfps.hoard.server.db.BackupCountRow
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.DeviceDirRow
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of Settings › Backup (ADR 0024, phase A1) under `src/test/screenshots/`, copied to docs/testing/backup/: the
 * folders with their status (light, dark, tablet), a run in progress, a server without the backup, and a folder's
 * files with their states. Record: `:native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BackupScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val now = FixedNow.millis

    private val folders = BackupFolders.rows(
        listOf(
            DeviceDirRow("device", "DCIM/Camera", 12_034, 52_428_800_000),
            DeviceDirRow("device", "Pictures/Screenshots", 812, 640_000_000),
            DeviceDirRow("device", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", 3_410, 410_000_000),
            DeviceDirRow("device", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", 220, 2_900_000_000),
            DeviceDirRow("device", "Download", 41, 90_000_000),
        ),
        listOf(
            BackupRootEntity("r1", "primary", "DCIM/Camera", "device", "DCIM/Camera", "image,video", 1, lastPassAt = now - 25 * 60_000),
            BackupRootEntity("r2", "primary", "Pictures/Screenshots", "device", "Pictures/Screenshots", "image,video", 1, lastPassAt = null),
        ),
        listOf(
            BackupCountRow("r1", BackupState.STORED, null, 11_050, 48_000_000_000),
            BackupCountRow("r1", BackupState.NAS, null, 950, 4_000_000_000),
            BackupCountRow("r1", BackupState.PAUSED, BackupState.REASON_WIFI, 34, 428_800_000),
            BackupCountRow("r2", BackupState.QUEUED, null, 800, 630_000_000),
            BackupCountRow("r2", BackupState.STORED, null, 11, 10_000_000),
            BackupCountRow("r2", BackupState.FAILED, BackupState.REASON_UNREADABLE, 1, 100_000),
        ),
    )

    private val ui = BackupUi(folders = folders, rules = BackupRules(), allFiles = false, now = now)

    @Test fun backupPhoneLight() = shoot("backup_phone_light") { BackupContent(ui, onBack = {}) }
    @Test fun backupPhoneDark() = shoot("backup_phone_dark", dark = true) { BackupContent(ui.copy(allFiles = true), onBack = {}) }
    @Test fun backupTabletLight() = shoot("backup_tablet_light", TABLET) { BackupContent(ui, onBack = {}) }
    @Test fun backupRunningPhoneLight() = shoot("backup_running_phone_light") {
        BackupContent(ui.copy(progress = BackupProgress(true, BackupEngine.PHASE_UPLOADING, "PXL_20261115_101500.jpg", 2_000_000, 4_100_000, 12, 34, 52_000_000)), onBack = {})
    }
    @Test fun backupOffPhoneLight() = shoot("backup_off_phone_light") {
        BackupContent(BackupUi(folders = BackupFolders.rows(listOf(DeviceDirRow("device", "DCIM/Camera", 120, 400_000_000)), emptyList(), emptyList()), now = now), onBack = {})
    }
    @Test fun backupOldServerPhoneLight() = shoot("backup_old_server_phone_light") { BackupContent(BackupUi(availability = BackupAvailability.OldServer, now = now), onBack = {}) }

    private fun file(name: String, state: String, error: String? = null, size: Long = 4_100_000, offset: Long = 0) =
        BackupFileEntity("r1", "DCIM/Camera/$name", "/storage/emulated/0/DCIM/Camera/$name", 1, if (name.endsWith(".mp4")) "video" else "image", size, 1, 1, state = state, error = error, uploadOffset = offset)

    private val files = listOf(
        file("PXL_20261115_101500.jpg", BackupState.UPLOADING, offset = 2_050_000),
        file("PXL_20261115_101200.jpg", BackupState.HASHING),
        file("PXL_20261115_100900.mp4", BackupState.PAUSED, BackupState.REASON_CHARGING, size = 380_000_000),
        file("PXL_20261114_181500.jpg", BackupState.PAUSED, BackupState.REASON_WIFI),
        file("PXL_20261114_120000.jpg", BackupState.FAILED, BackupState.REASON_UNREADABLE),
        file("PXL_20261113_090000.jpg", BackupState.STORED),
        file("PXL_20261112_090000.jpg", BackupState.VERIFIED),
        file("IMG-20261101-WA0001.jpg", BackupState.NAS),
        file("PXL_20261110_090000.jpg", BackupState.QUEUED),
        file("Screenshot.avif", BackupState.REFUSED, "unsupported_type"),
    )

    @Test fun backupFilesPhoneLight() = shoot("backup_files_phone_light") { BackupFilesContent("Camera", files, FileFilter.All, onFilter = {}, onBack = {}) }
    @Test fun backupFilesPhoneDark() = shoot("backup_files_phone_dark", dark = true) { BackupFilesContent("Camera", files, FileFilter.Problems, onFilter = {}, onBack = {}) }
}
