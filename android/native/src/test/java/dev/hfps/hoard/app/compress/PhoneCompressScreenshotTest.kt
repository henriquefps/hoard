package dev.hfps.hoard.app.compress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.PHONE
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.TABLET
import dev.hfps.hoard.app.ui.compress.CompressSettingsContent
import dev.hfps.hoard.app.ui.compress.CompressSettingsUi
import dev.hfps.hoard.app.ui.compress.CompressSheetContent
import dev.hfps.hoard.app.ui.compress.PhoneCompressUi
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressJobBytes
import dev.hfps.hoard.server.api.CompressProgress as JobProgress
import dev.hfps.hoard.server.compress.CompressEngine
import dev.hfps.hoard.server.compress.CompressGate
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressOverview
import dev.hfps.hoard.server.compress.CompressProgress
import dev.hfps.hoard.server.compress.CompressSheetState
import dev.hfps.hoard.server.compress.EstimateSummary
import dev.hfps.hoard.server.compress.PhoneError
import dev.hfps.hoard.server.compress.PhoneOverview
import dev.hfps.hoard.server.compress.PhoneReport
import dev.hfps.hoard.server.compress.PhoneState
import dev.hfps.hoard.server.db.CompressPhoneEntity
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the phone replace (ADR 0025 A2) under `src/test/screenshots/`, copied to docs/testing/compression/: the
 * sheet for this phone's videos (and on a phone without All files access), and Settings › Compress videos with "This
 * phone" (the consent step, a download in progress, recent replaces). Record: `:native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class PhoneCompressScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().background(HfpsTheme.colors.card, HfpsTheme.shapes.card).padding(top = 24.dp)) { content() }
        }
    }

    private val profiles = listOf(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER)
    private val estimate = EstimateSummary(
        videos = 730, encode = 129, size = 29_100_000_000, estSize = 5_600_000_000, estMs = 2_100_000,
        skipped = listOf("older_compression" to 550, "small_saving" to 49, "unreadable" to 1, "sync_artifact" to 1),
        warnings = setOf(EstimateSummary.WARN_META, EstimateSummary.WARN_PCM), pending = 0,
    )

    @Composable
    private fun PhoneSheet() = Sheet {
        CompressSheetContent(CompressSheetState(Compress.TARGET_PHONE, profiles).withEstimate(estimate), listOf(Compress.TARGET_PHONE), leftOut = 2, trashDays = 30, unavailable = null)
    }

    @Test fun sheetPhoneLight() = shoot("compress_phone_sheet_phone_light") { PhoneSheet() }
    @Test fun sheetPhoneDark() = shoot("compress_phone_sheet_phone_dark", dark = true) { PhoneSheet() }
    @Test fun sheetTabletLight() = shoot("compress_phone_sheet_tablet_light", TABLET) { PhoneSheet() }

    @Test fun sheetNoAllFilesPhoneLight() = shoot("compress_phone_sheet_no_all_files_phone_light") {
        Sheet { CompressSheetContent(CompressSheetState(Compress.TARGET_PHONE, profiles, loading = false), listOf(Compress.TARGET_PHONE), 0, 30, unavailable = CompressGate.REASON_PHONE_ALL_FILES) }
    }

    // --- Settings › Compress videos › This phone ---

    private fun row(id: String, name: String, state: String, error: String? = null, from: Long = 1_480_000_000, to: Long = 210_000_000, updated: Long = 0, report: String = PhoneReport.REPLACED, abandon: String? = null, direction: String = Compress.DIRECTION_COMPRESS) =
        CompressPhoneEntity(
            id = id, volume = "primary", path = "DCIM/Camera/$name", absolute = "/storage/emulated/0/DCIM/Camera/$name", direction = direction,
            sourceFingerprint = "v1:a", sourceSha256 = "a", sourceSize = from, targetFingerprint = "v1:b", targetSha256 = "b", targetSize = to,
            state = state, error = error, report = report, abandonReason = abandon, updatedAt = updated,
        )

    private val phone = PhoneCompressUi(
        overview = PhoneOverview.of(
            listOf(
                row("1", "DJI_0412.MP4", PhoneState.VERIFIED, PhoneError.PERMISSION),
                row("2", "DJI_0413.MP4", PhoneState.VERIFIED, PhoneError.PERMISSION, from = 2_100_000_000, to = 380_000_000),
                row("6", "PXL_20241020_101500.mp4", PhoneState.VERIFIED, PhoneError.PERMISSION, from = 260_000_000, to = 912_000_000, report = PhoneReport.RESTORED, direction = Compress.DIRECTION_RESTORE),
                row("3", "PXL_20241102_181233.mp4", PhoneState.DOWNLOADING, from = 912_000_000, to = 260_000_000),
                row("4", "PXL_20241101_090102.mp4", PhoneState.REPORTED, updated = 3),
                row("5", "VID_20241030_1210.mp4", PhoneState.ABANDONED, PhoneError.CHANGED, updated = 2, abandon = PhoneError.CHANGED),
            ),
        ),
        progress = CompressProgress(running = true, phase = CompressEngine.PHASE_DOWNLOADING, name = "PXL_20241102_181233.mp4", done = 96_000_000, size = 260_000_000),
        offer = CompressOffer.Ready,
    )
    private val phoneJob = CompressJob(
        id = "p1", target = Compress.TARGET_PHONE, name = "DCIM/Camera", createdAt = 1_763_500_000_000, counts = mapOf("ready" to 3, "replaced" to 1),
        bytes = CompressJobBytes(inTrash = 1_480_000_000), progress = JobProgress(done = 2, toDo = 3),
    )
    private val settings = CompressSettingsUi(
        CompressOverview.of(listOf(phoneJob)),
        CompressInfo(available = true, gpu = "NVIDIA GeForce RTX 4080", trashDays = 30),
        CompressOffer.Ready,
        loading = false,
        phone = phone,
    )

    @Test fun settingsPhoneLight() = shoot("compress_phone_settings_phone_light") { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsPhoneDark() = shoot("compress_phone_settings_phone_dark", dark = true) { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsTabletLight() = shoot("compress_phone_settings_tablet_light", TABLET) { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsTabletDark() = shoot("compress_phone_settings_tablet_dark", TABLET, dark = true) { CompressSettingsContent(settings, onBack = {}) }

    @Test fun settingsNoAllFilesPhoneLight() = shoot("compress_phone_settings_no_all_files_phone_light") {
        CompressSettingsContent(
            settings.copy(overview = CompressOverview(emptyList(), emptyList(), emptyList()), phone = PhoneCompressUi(offer = CompressOffer.Unavailable(CompressGate.REASON_PHONE_ALL_FILES))),
            onBack = {},
        )
    }

    // --- ADR 0025 Amendment (2.8.1): "ready to replace" and the warning while a write is in progress ---

    @Test fun settingsWritingPhoneLight() = shoot("compress_phone_settings_writing_phone_light") {
        CompressSettingsContent(
            settings.copy(
                phone = PhoneCompressUi(
                    PhoneOverview.of(listOf(row("3", "PXL_20241102_181233.mp4", PhoneState.WRITING).copy(writeStarted = true))),
                    CompressProgress(running = true, phase = CompressEngine.PHASE_WRITING, name = "PXL_20241102_181233.mp4", size = 260_000_000),
                    CompressOffer.Ready,
                ),
            ),
            onBack = {},
        )
    }

    @Test
    fun theSettingsBannerStartsTheConsentStepAndTheWarningFollowsAWrite() {
        var allowed = 0
        var ui by androidx.compose.runtime.mutableStateOf(settings)
        compose.setContent { FixedNowTheme(darkTheme = false) { CompressSettingsContent(ui, onBack = {}, onAllow = { allowed++ }) } }
        compose.onNodeWithText("3 compressed videos are ready to replace on this phone").assertExists()
        compose.onNodeWithTag("compress-ready-continue").performClick()
        assertThat(allowed).isEqualTo(1)
        compose.onNodeWithTag("compress-phone-writing-warning").assertDoesNotExist()
        // A write in progress (or a started one waiting to be redone): the uninstall / clear storage warning.
        ui = settings.copy(phone = PhoneCompressUi(PhoneOverview.of(listOf(row("3", "PXL_1.mp4", PhoneState.WRITING).copy(writeStarted = true, nextAttemptAt = 9, error = PhoneError.SPACE))), offer = CompressOffer.Ready))
        compose.waitForIdle()
        compose.onNodeWithTag("compress-ready-banner").assertDoesNotExist()
        compose.onNodeWithTag("compress-phone-writing-warning").assertExists()
    }
}
