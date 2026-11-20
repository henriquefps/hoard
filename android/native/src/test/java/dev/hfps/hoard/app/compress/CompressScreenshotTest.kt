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
import dev.hfps.hoard.app.trash.CompressedOriginal
import dev.hfps.hoard.app.trash.CompressedTrash
import dev.hfps.hoard.app.trash.HeldTrash
import dev.hfps.hoard.app.trash.TrashCompressionState
import dev.hfps.hoard.app.trash.TrashGroups
import dev.hfps.hoard.app.trash.TrashPlace
import dev.hfps.hoard.app.trash.TrashSamples
import dev.hfps.hoard.app.ui.compress.CompressSettingsContent
import dev.hfps.hoard.app.ui.compress.CompressSettingsUi
import dev.hfps.hoard.app.ui.compress.CompressSheetContent
import dev.hfps.hoard.app.ui.trash.CompressedTrashRow
import dev.hfps.hoard.app.ui.trash.TrashContent
import dev.hfps.hoard.app.ui.trash.TrashUi
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressAttention
import dev.hfps.hoard.server.api.CompressCurrent
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.CompressJobBytes
import dev.hfps.hoard.server.api.CompressProgress
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressOverview
import dev.hfps.hoard.server.compress.CompressSheetState
import dev.hfps.hoard.server.compress.EstimateSummary
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Goldens of video compression (ADR 0025, phase A1) under `src/test/screenshots/`, copied to docs/testing/compression/:
 * the sheet (estimate, a target the server cannot use, the automatic switch), Settings › Compress videos, and the Trash
 * screen with compressed originals and held entries. Phone and tablet, light and dark. Record: `:native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CompressScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** The sheet's body as a card at the bottom (a bottom sheet opens its own window, which the root capture misses). */
    @Composable
    private fun Sheet(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().background(HfpsTheme.colors.card, HfpsTheme.shapes.card).padding(top = 24.dp)) { content() }
        }
    }

    private val profiles = listOf(Compress.PROFILE_SAME, Compress.PROFILE_SMALLER)
    private val estimate = EstimateSummary(
        videos = 312, encode = 129, size = 29_100_000_000, estSize = 5_600_000_000, estMs = 2_100_000,
        skipped = listOf("older_compression" to 180, "efficient" to 2, "unreadable" to 1),
        warnings = setOf(EstimateSummary.WARN_HDR10PLUS, EstimateSummary.WARN_META), pending = 0, subfolders = 4,
    )
    private val sheet = CompressSheetState(Compress.TARGET_NAS, profiles).withEstimate(estimate)

    @Composable
    private fun EstimateSheet() = Sheet { CompressSheetContent(sheet, listOf(Compress.TARGET_NAS, Compress.TARGET_BACKUP), leftOut = 3, trashDays = 30, unavailable = null) }

    @Test fun sheetPhoneLight() = shoot("compress_sheet_phone_light") { EstimateSheet() }
    @Test fun sheetPhoneDark() = shoot("compress_sheet_phone_dark", dark = true) { EstimateSheet() }
    @Test fun sheetTabletLight() = shoot("compress_sheet_tablet_light", TABLET) { EstimateSheet() }
    @Test fun sheetTabletDark() = shoot("compress_sheet_tablet_dark", TABLET, dark = true) { EstimateSheet() }

    @Test fun sheetEstimatingPhoneLight() = shoot("compress_sheet_estimating_phone_light") {
        Sheet { CompressSheetContent(CompressSheetState(Compress.TARGET_NAS, profiles + Compress.PROFILE_AV1, profile = Compress.PROFILE_AV1), listOf(Compress.TARGET_NAS), 0, 30, null) }
    }

    @Test fun sheetUnavailablePhoneLight() = shoot("compress_sheet_unavailable_phone_light") {
        Sheet { CompressSheetContent(CompressSheetState(Compress.TARGET_NAS, profiles, loading = false), listOf(Compress.TARGET_NAS), 0, 30, unavailable = "write_mount_read_only") }
    }

    @Test fun sheetAutoPhoneDark() = shoot("compress_sheet_auto_phone_dark", dark = true) {
        val auto = CompressSheetState(Compress.TARGET_NAS, listOf(Compress.PROFILE_SAME), auto = true).withEstimate(estimate).copy(alsoExisting = true)
        Sheet { CompressSheetContent(auto, listOf(Compress.TARGET_NAS), 0, 30, null, autoFolder = "videos/Drone/2024") }
    }

    // --- Settings › Compress videos ---

    private val json = Json { ignoreUnknownKeys = true }

    private fun job(id: String, name: String, auto: Boolean = false, state: String = CompressJob.STATE_ACTIVE, done: Int, toDo: Int, saved: Long = 0, inTrash: Long = 0, replaced: Int = 0, paused: Boolean = false) = CompressJob(
        id = id, name = name, auto = auto, state = state, paused = paused, createdAt = 1_763_500_000_000,
        counts = mapOf("replaced" to replaced, "queued" to toDo),
        skipped = if (toDo == 0) mapOf("older_compression" to 4) else emptyMap(),
        failed = if (state == CompressJob.STATE_DONE) mapOf("verify_quality" to 1) else emptyMap(),
        bytes = CompressJobBytes(saved = saved, inTrash = inTrash),
        progress = CompressProgress(done, toDo, if (toDo > 0) 1_260_000 else null, if (toDo > 0 && !paused) CompressCurrent("12", "i", "encoding", 42) else null),
        select = if (auto) json.parseToJsonElement("""{"folders":[{"folderId":"f","path":"Drone/2024","recursive":true}]}""") else null,
    )

    private val overview = CompressOverview.of(
        listOf(
            job("1", "videos/Drone/2023", done = 41, toDo = 88, inTrash = 8_200_000_000, replaced = 41),
            job("2", "Gallery selection", done = 3, toDo = 9, paused = true, state = CompressJob.STATE_PAUSED),
            job("3", "videos/Drone/2024", auto = true, done = 6, toDo = 0, inTrash = 1_100_000_000, replaced = 6),
            job("4", "videos/Pocket", state = CompressJob.STATE_DONE, done = 29, toDo = 0, saved = 21_400_000_000, replaced = 0),
        ),
    )
    private val info = CompressInfo(available = true, gpu = "NVIDIA GeForce RTX 4080", trashDays = 30, attention = CompressAttention(stalled = 1))
    private val settings = CompressSettingsUi(overview, info, CompressOffer.Ready, loading = false)

    @Test fun settingsPhoneLight() = shoot("compress_settings_phone_light") { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsPhoneDark() = shoot("compress_settings_phone_dark", dark = true) { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsTabletLight() = shoot("compress_settings_tablet_light", TABLET) { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsTabletDark() = shoot("compress_settings_tablet_dark", TABLET, dark = true) { CompressSettingsContent(settings, onBack = {}) }
    @Test fun settingsUnavailablePhoneLight() = shoot("compress_settings_unavailable_phone_light") {
        CompressSettingsContent(CompressSettingsUi(CompressOverview(emptyList(), emptyList(), emptyList()), info.copy(attention = null), CompressOffer.Unavailable("no_gpu"), loading = false), onBack = {})
    }

    @Test fun settingsUnknownReasonPhoneLight() = shoot("compress_settings_unknown_reason_phone_light") {
        CompressSettingsContent(CompressSettingsUi(CompressOverview(emptyList(), emptyList(), emptyList()), info.copy(attention = null), CompressOffer.Unavailable("quantum_flux"), loading = false), onBack = {})
    }

    // --- The Trash screen with compressed originals and held entries ---

    private val nas = TrashSamples.nas

    private fun trashUi(): TrashUi {
        val day = "2026-11-18"
        val rows = listOf(
            TrashSamples.row(nas, "Videos", day, "Videos/Drone/2024/DJI_0042.MP4", 1_420_000_000),
            TrashSamples.row(nas, "Videos", day, "Videos/Drone/2024/DJI_0043.MP4", 980_000_000),
            TrashSamples.row(nas, "Videos", day, "Videos/Drone/2024/DJI_0044.MP4", 412_000_000),
        ) + TrashSamples.rows
        fun original(id: String, path: String, size: Long, held: Boolean) = CompressedOriginal(
            id, path.substringAfterLast('/'), size, "out-$id", held, 1,
            listOf(TrashPlace(nas.locationId, nas.share) to "Videos/.hoard-trash/$day/${path.removePrefix("Videos/")}"),
        )
        val originals = listOf(
            original("a", "Videos/Drone/2024/DJI_0042.MP4", 1_420_000_000, held = false),
            original("b", "Videos/Drone/2024/DJI_0043.MP4", 980_000_000, held = true),
        )
        val state = TrashCompressionState(HeldTrash(listOf(originals[1].smbPaths.single()), setOf("b")), originals)
        val server = listOf(
            CompressedTrashRow(CompressedOriginal("k1", "PXL_20241002_181500.mp4", 612_000_000, "o1", false, 2, emptyList()), null),
            CompressedTrashRow(CompressedOriginal("k2", "PXL_20241003_090000.mp4", 233_000_000, "o2", true, 1, emptyList()), null),
        )
        return TrashUi(TrashGroups.of(rows, emptySet(), TrashSamples::label, ZoneOffset.UTC, compression = state), loaded = true, compressed = server)
    }

    @Test fun trashPhoneLight() = shoot("compress_trash_phone_light") { TrashContent(trashUi(), onBack = {}) }
    @Test fun trashPhoneDark() = shoot("compress_trash_phone_dark", dark = true) { TrashContent(trashUi(), onBack = {}) }
    @Test fun trashTabletLight() = shoot("compress_trash_tablet_light", TABLET) { TrashContent(trashUi(), onBack = {}) }
    @Test fun trashTabletDark() = shoot("compress_trash_tablet_dark", TABLET, dark = true) { TrashContent(trashUi(), onBack = {}) }

    @Test fun trashEmptyConfirmHeldPhoneLight() = shoot("compress_trash_empty_confirm_phone_light") {
        val ui = trashUi()
        Sheet {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
                androidx.compose.material3.Text(
                    androidx.compose.ui.res.pluralStringResource(dev.hfps.hoard.app.R.plurals.trash_delete_confirm_body, ui.reachableFiles, ui.reachableFiles.toString(), dev.hfps.hoard.app.ui.common.bytes(ui.reachableBytes)),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.mutedForeground,
                )
                dev.hfps.hoard.app.ui.trash.EmptyUnreachableNote(ui)
            }
        }
    }

    @Test
    fun theTrashCountsLeaveHeldEntriesOut() {
        val ui = trashUi()
        // Held: one share row and one server-kept original; neither is in the count Empty confirms.
        org.junit.Assert.assertEquals(2, ui.held)
        val all = ui.days.sumOf { d -> d.folders.sumOf { it.rows.size } } + ui.compressed.size
        org.junit.Assert.assertEquals(all - 2, ui.reachableFiles)
        org.junit.Assert.assertTrue(CompressedTrash.byRow(emptyList()).isEmpty())
    }
}
