package dev.hfps.hoard.app.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.PHONE
import dev.hfps.hoard.app.ShellScreenshotTest.Companion.TABLET
import dev.hfps.hoard.app.ui.trash.EmptyGroupsBody
import dev.hfps.hoard.app.ui.trash.ServerConfirmBody
import dev.hfps.hoard.app.ui.trash.ServerPart
import dev.hfps.hoard.app.ui.trash.ServerResultBody
import dev.hfps.hoard.app.ui.trash.TrashContent
import dev.hfps.hoard.app.ui.trash.TrashSettingsContent
import dev.hfps.hoard.app.ui.trash.TrashUi
import dev.hfps.hoard.app.ui.trash.emptyLines
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.TrashEntryView
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import dev.hfps.hoard.server.api.TrashPlaceView
import dev.hfps.hoard.server.api.TrashUnitView
import dev.hfps.hoard.server.trash.MovePreview
import dev.hfps.hoard.server.trash.MoveResult
import dev.hfps.hoard.server.trash.ServerTrash
import dev.hfps.hoard.server.trash.ServerTrashListing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Goldens of the server trash (ADR 0023 Amendment (2.10.0)) under `src/test/screenshots/`, copied to
 * docs/testing/server-trash/: the Trash screen with the server's trash next to the phone's own (phone, tablet, light,
 * dark, pt-BR, unreachable), the Move to trash confirmation's server part, the per-item result, the per-group Empty
 * confirmation (and after a 409), and Settings › Trash with the server's auto-empty.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ServerTrashScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, pt: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        if (pt) RuntimeEnvironment.setQualifiers("+pt-rBR")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background).padding(24.dp)) {
            Column(Modifier.background(HfpsTheme.colors.card, HfpsTheme.shapes.card).padding(24.dp)) { content() }
        }
    }

    private fun ui(reachable: Boolean = true) = TrashUi(
        days = TrashGroups.of(TrashSamples.rows.filter { it.locationId != TrashSamples.nas.locationId }, emptySet(), TrashSamples::label, ZoneOffset.UTC),
        loaded = true,
        serverMode = true,
        serverDays = if (reachable) ServerTrash.days(ServerTrashSamples.listing) else emptyList(),
        serverGroups = if (reachable) ServerTrashSamples.listing.groups else emptyMap(),
        serverReachable = reachable,
        waiting = 2,
    )

    @Test fun serverTrashPhoneLight() = shoot("trash_server_phone_light") { TrashContent(ui(), onBack = {}) }
    @Test fun serverTrashPhoneDark() = shoot("trash_server_phone_dark", dark = true) { TrashContent(ui(), onBack = {}) }
    @Test fun serverTrashPhonePt() = shoot("trash_server_phone_pt", pt = true) { TrashContent(ui(), onBack = {}) }
    @Test fun serverTrashTabletLight() = shoot("trash_server_tablet_light", TABLET) { TrashContent(ui(), onBack = {}) }
    @Test fun serverTrashTabletDark() = shoot("trash_server_tablet_dark", TABLET, dark = true) { TrashContent(ui(), onBack = {}) }
    @Test fun serverTrashExpandedPhoneLight() = shoot("trash_server_expanded_phone_light") {
        TrashContent(ui(), onBack = {}, expandedPlaces = setOf("2026-11-26|nas|Videos|2025/Clips", "2026-11-26|nas|Imagens|2024/Trip", "2026-11-25|server|Pixel 8|DCIM/Camera"))
    }
    @Test fun serverTrashUnreachablePhoneLight() = shoot("trash_server_unreachable_phone_light") { TrashContent(ui(reachable = false), onBack = {}) }

    private val preview = MovePreview(
        asked = 1240, nasShots = 1200, nasFiles = 2390, serverItems = 30, size = 41_200_000_000, onlyCopy = setOf("u1", "u2"), edits = setOf("e1"),
        skipped = mapOf("h1" to "held", "b1" to "busy", "p1" to "on_phone", "p2" to "on_phone", "t1" to "trashed"), nasBlocked = null,
    )

    @Test fun serverConfirmPhoneLight() = shoot("trash_server_confirm_phone_light") { Card { ServerConfirmBody(ServerPart(emptyList(), preview), 30) } }
    @Test fun serverConfirmPhoneDark() = shoot("trash_server_confirm_phone_dark", dark = true) { Card { ServerConfirmBody(ServerPart(emptyList(), preview), 30) } }
    @Test fun serverConfirmPhonePt() = shoot("trash_server_confirm_phone_pt", pt = true) { Card { ServerConfirmBody(ServerPart(emptyList(), preview), 30) } }
    @Test fun serverConfirmBlockedPhoneLight() = shoot("trash_server_confirm_blocked_phone_light") {
        Card { ServerConfirmBody(ServerPart(emptyList(), preview.copy(nasBlocked = "write_mount_read_only", serverItems = 0, onlyCopy = emptySet())), 30) }
    }

    private val result = MoveResult(
        moved = (1..1180).map { "m$it" },
        skipped = mapOf("h1" to "held", "p1" to "on_phone"),
        failed = mapOf("f1" to "not_writable", "f2" to "not_writable", "f3" to "changed", "f4" to "unreachable"),
    )

    @Test fun serverResultPhoneLight() = shoot("trash_server_result_phone_light") { Card { ServerResultBody(result) } }
    @Test fun serverResultPhonePt() = shoot("trash_server_result_phone_pt", pt = true) { Card { ServerResultBody(result) } }

    private val lines = emptyLines(ServerTrashSamples.listing.groups, 2, 9_200_000)

    @Test fun emptyGroupsPhoneLight() = shoot("trash_empty_groups_phone_light") {
        Card { EmptyGroupsBody(lines, lines.map { it.key }.toSet() - TrashGroupIds.COMPRESSED, {}, changed = false, held = 1, webOnly = TrashFigures(1, 3_400_000)) }
    }
    @Test fun emptyGroupsPhoneDark() = shoot("trash_empty_groups_phone_dark", dark = true) { Card { EmptyGroupsBody(lines, lines.map { it.key }.toSet(), {}, changed = false, held = 1) } }
    @Test fun emptyGroupsPhonePt() = shoot("trash_empty_groups_phone_pt", pt = true) { Card { EmptyGroupsBody(lines, lines.map { it.key }.toSet(), {}, changed = false, held = 1, webOnly = TrashFigures(1, 3_400_000)) } }
    @Test fun emptyGroupsChangedPhoneLight() = shoot("trash_empty_groups_changed_phone_light") { Card { EmptyGroupsBody(lines, lines.map { it.key }.toSet(), {}, changed = true, held = 0) } }

    @Test fun settingsServerPhoneLight() = shoot("trash_settings_server_phone_light") {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) { Column { TrashSettingsContent(30, onDays = {}, onOpenTrash = {}, serverDays = 30) } }
    }
    @Test fun settingsServerPhonePt() = shoot("trash_settings_server_phone_pt", pt = true) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) { Column { TrashSettingsContent(30, onDays = {}, onOpenTrash = {}, serverDays = 14) } }
    }
}

/** A server trash for the goldens: two days, two NAS folders, a phone backup, a held compression, web and Hoard groups. */
object ServerTrashSamples {
    private fun unit(folder: String?, day: String, dir: String, size: Long, group: String = TrashGroupIds.HOARD, held: Boolean = false, by: String? = null, device: String? = null) = TrashUnitView(
        key = if (folder != null) "nas:$folder:.hoard-trash/$day" else "server", source = if (folder != null) "nas" else "server", day = day,
        folderId = folder, folderName = folder?.let { if (it == "f1") "Imagens" else "Videos" }, dir = dir, device = device,
        files = 2, size = size, held = held, byServer = by != null, by = by, group = group,
    )

    private fun entry(id: String, name: String, vararg units: TrashUnitView) =
        TrashEntryView(id, "photo", name, units.sumOf { it.size }, places = listOf(TrashPlaceView("nas", trash = ".hoard-trash/${units.first().day}/$name")), units = units.toList())

    val listing = ServerTrashListing(
        listOf(
            entry("a", "DSC01001.ARW", unit("f1", "2026-11-26", "2024/Trip", 30_900_000, by = "dev-1")),
            entry("b", "DSC01002.ARW", unit("f1", "2026-11-26", "2024/Trip", 24_700_000, by = "dev-1")),
            entry("c", "DSC01007.ARW", unit("f1", "2026-11-26", "2024/Trip", 25_100_000, group = TrashGroupIds.WEB, by = "admin")),
            entry("v", "C0042.MP4", unit("f2", "2026-11-26", "2025/Clips", 1_800_000_000, group = TrashGroupIds.COMPRESSED, held = true)),
            entry("w", "C0031.MP4", unit("f2", "2026-11-26", "2025/Clips", 2_400_000_000, group = TrashGroupIds.COMPRESSED)),
            entry("p", "IMG_2041.HEIC", unit(null, "2026-11-25", "DCIM/Camera", 3_400_000, group = TrashGroupIds.WEB, by = "admin", device = "Pixel 8")),
            entry("o", "DSC09912.ARW", unit("f1", "2026-10-20", "2023/Wedding", 25_000_000)),
        ),
        mapOf(
            TrashGroupIds.WEB to TrashFigures(2, 28_500_000),
            TrashGroupIds.HOARD to TrashFigures(3, 80_600_000),
            TrashGroupIds.COMPRESSED to TrashFigures(1, 2_400_000_000),
        ),
    )
}
