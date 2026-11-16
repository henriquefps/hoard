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
import dev.hfps.hoard.app.ui.backup.BackupContent
import dev.hfps.hoard.app.ui.backup.BackupUi
import dev.hfps.hoard.app.ui.backup.FreeUpContent
import dev.hfps.hoard.app.ui.backup.FreeUpResult
import dev.hfps.hoard.app.ui.backup.FreeUpStage
import dev.hfps.hoard.app.ui.backup.FreeUpUi
import dev.hfps.hoard.server.backup.BackupKind
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupState
import dev.hfps.hoard.server.backup.FreeShot
import dev.hfps.hoard.server.backup.FreeUpEngine
import dev.hfps.hoard.server.backup.FreeUpExclusion
import dev.hfps.hoard.server.backup.FreeUpPlan
import dev.hfps.hoard.server.backup.FreeUpProgress
import dev.hfps.hoard.server.backup.RevealGate
import dev.hfps.hoard.server.db.BackupCountRow
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.BackupRootEntity
import dev.hfps.hoard.server.db.DeviceDirRow
import dev.hfps.hoard.server.db.FreedTotal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant

/**
 * Goldens of free up space (ADR 0024, phase A3) under `src/test/screenshots/`, copied to docs/testing/backup/: the
 * preview by month with a shot left out (light, dark, tablet), the Reveal gate (on, stale), the check in progress, the
 * result with the shots that stayed, and Settings › Backup with its free-up section and the "Reveal not grouped" hint.
 * Record: `:native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FreeUpScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val now = FixedNow.millis

    private fun shot(name: String, iso: String, size: Long = 4_100_000, video: Boolean = false, raw: Boolean = false): FreeShot {
        val taken = Instant.parse(iso).toEpochMilli()
        fun file(n: String, kind: String, bytes: Long, media: Boolean) =
            BackupFileEntity("r1", "DCIM/Camera/$n", "/storage/emulated/0/DCIM/Camera/$n", if (media) 1 else null, kind, bytes, taken, taken, state = BackupState.STORED)
        val stem = name.substringBeforeLast('.')
        val files = listOfNotNull(
            file(name, if (video) BackupKind.VIDEO else BackupKind.IMAGE, size, true),
            if (raw) file("$stem.dng", BackupKind.RAW, 24_000_000, false) else null,
        )
        return FreeShot("device/DCIM/Camera/${stem.lowercase()}", "r1", "DCIM/Camera", stem, files)
    }

    private val shots = listOf(
        shot("PXL_20260802_101500.jpg", "2026-08-02T10:15:00Z"),
        shot("PXL_20260803_181000.jpg", "2026-08-03T18:10:00Z", raw = true),
        shot("PXL_20260803_181500.mp4", "2026-08-03T18:15:00Z", size = 210_000_000, video = true),
        shot("PXL_20260815_090000.jpg", "2026-08-15T09:00:00Z"),
        shot("PXL_20260912_120000.jpg", "2026-09-12T12:00:00Z"),
        shot("PXL_20260912_120100.jpg", "2026-09-12T12:01:00Z"),
        shot("PXL_20260920_170000.jpg", "2026-09-20T17:00:00Z"),
        shot("PXL_20261001_080000.jpg", "2026-10-01T08:00:00Z"),
        shot("PXL_20261009_210000.mp4", "2026-10-09T21:00:00Z", size = 64_000_000, video = true),
    )

    private val preview = FreeUpUi(
        stage = FreeUpStage.Preview,
        shots = shots,
        excludedCounts = mapOf(FreeUpExclusion.Recent to 412, FreeUpExclusion.Favourite to 8, FreeUpExclusion.NotBackedUp to 3),
        leftOut = setOf("device/DCIM/Camera/pxl_20260912_120100"),
        now = now,
    )

    @Test fun freeUpPreviewPhoneLight() = shoot("freeup_preview_phone_light") { FreeUpContent(preview, onBack = {}) }
    @Test fun freeUpPreviewPhoneDark() = shoot("freeup_preview_phone_dark", dark = true) { FreeUpContent(preview, onBack = {}) }
    @Test fun freeUpPreviewTabletLight() = shoot("freeup_preview_tablet_light", TABLET) { FreeUpContent(preview, onBack = {}) }
    @Test fun freeUpRevealOnPhoneLight() = shoot("freeup_reveal_on_phone_light") { FreeUpContent(preview.copy(gate = RevealGate.On(now - 60_000)), onBack = {}) }
    @Test fun freeUpRevealStalePhoneLight() = shoot("freeup_reveal_stale_phone_light") { FreeUpContent(preview.copy(gate = RevealGate.Stale(now - 3_600_000)), onBack = {}) }
    @Test fun freeUpRevealUngroupedPhoneDark() = shoot("freeup_reveal_ungrouped_phone_dark", dark = true) { FreeUpContent(preview.copy(gate = RevealGate.Ungrouped), onBack = {}) }
    @Test fun freeUpCheckingPhoneLight() = shoot("freeup_checking_phone_light") {
        FreeUpContent(preview.copy(stage = FreeUpStage.Checking, progress = FreeUpProgress(FreeUpEngine.PHASE_VERIFYING, 3, 8)), onBack = {})
    }

    @Test fun freeUpResultPhoneLight() = shoot("freeup_result_phone_light") {
        FreeUpContent(
            preview.copy(
                stage = FreeUpStage.Done,
                result = FreeUpResult(
                    files = 9,
                    bytes = 329_000_000,
                    shots = 7,
                    kept = listOf(shots[4] to "differs", shots[7] to FreeUpPlan.REASON_CHANGED, shots[0] to FreeUpPlan.REASON_REVEAL),
                    declined = 1,
                    keptForReveal = 1,
                ),
            ),
            onBack = {},
        )
    }

    @Test fun freeUpResultDarkNothing() = shoot("freeup_result_none_phone_dark", dark = true) {
        FreeUpContent(preview.copy(stage = FreeUpStage.Done, result = FreeUpResult(declined = 7)), onBack = {})
    }

    private val backup = BackupUi(
        folders = BackupFolders.rows(
            listOf(DeviceDirRow("device", "DCIM/Camera", 12_034, 52_428_800_000)),
            listOf(BackupRootEntity("r1", "primary", "DCIM/Camera", "device", "DCIM/Camera", "image,video", 1, lastPassAt = now - 25 * 60_000)),
            listOf(BackupCountRow("r1", BackupState.STORED, null, 12_000, 52_000_000_000), BackupCountRow("r1", BackupState.QUEUED, null, 34, 428_800_000)),
        ),
        rules = BackupRules(),
        now = now,
        freeUpSupported = true,
        freed = FreedTotal(4_210, 18_400_000_000),
        revealNotGrouped = true,
    )

    @Test fun backupFreeUpPhoneLight() = shoot("backup_freeup_phone_light", qualifiers = "w411dp-h1600dp-port") { BackupContent(backup, onBack = {}) }
    @Test fun backupFreeUpPhoneDark() = shoot("backup_freeup_phone_dark", qualifiers = "w411dp-h1600dp-port", dark = true) {
        BackupContent(backup.copy(freed = FreedTotal(0, 0), revealNotGrouped = false), onBack = {})
    }
}
