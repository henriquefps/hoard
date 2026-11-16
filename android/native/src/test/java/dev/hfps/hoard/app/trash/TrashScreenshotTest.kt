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
import dev.hfps.hoard.app.ui.trash.TrashConfirmBody
import dev.hfps.hoard.app.ui.trash.TrashContent
import dev.hfps.hoard.app.ui.trash.TrashProgressBody
import dev.hfps.hoard.app.ui.trash.TrashSettingsContent
import dev.hfps.hoard.app.ui.trash.TrashUi
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Goldens of the trash (ADR 0023, phase A2) under `src/test/screenshots/`, copied to docs/testing/trash/: the Trash
 * screen (light, dark, a drive not reachable, empty, tablet), the confirmation's body (with the old-server warning and
 * a refusal), the progress, and Settings › Trash. Record: `:native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TrashScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String = PHONE, dark: Boolean = false, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun ui(unreachable: Set<TrashPlace> = emptySet(), rows: List<dev.hfps.hoard.data.TrashEntryEntity> = TrashSamples.rows) =
        TrashUi(TrashGroups.of(rows, unreachable, TrashSamples::label, ZoneOffset.UTC), loaded = true)

    @Test fun trashPhoneLight() = shoot("trash_phone_light") { TrashContent(ui(), onBack = {}) }
    @Test fun trashPhoneDark() = shoot("trash_phone_dark", dark = true) { TrashContent(ui(), onBack = {}) }
    @Test fun trashUnreachablePhoneLight() = shoot("trash_unreachable_phone_light") { TrashContent(ui(setOf(TrashSamples.usb)), onBack = {}) }
    @Test fun trashEmptyPhoneLight() = shoot("trash_empty_phone_light") { TrashContent(ui(rows = emptyList()).copy(autoDays = 0), onBack = {}) }
    @Test fun trashTabletDark() = shoot("trash_tablet_dark", TABLET, dark = true) { TrashContent(ui(), onBack = {}) }

    /** The dialog's body as a card (an AlertDialog opens its own window, which the root capture does not include). */
    @Composable
    private fun Card(content: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background).padding(24.dp)) {
            Column(Modifier.background(HfpsTheme.colors.card, HfpsTheme.shapes.card).padding(24.dp)) { content() }
        }
    }

    private val plan: TrashPlan get() {
        val listing = listOf(
            TrashFile("Imagens/2024/Trip/DSC01001.ARW", 24_800_000, 1), TrashFile("Imagens/2024/Trip/DSC01001.JPG", 6_100_000, 1),
            TrashFile("Imagens/2024/Trip/DSC01001.xmp", 4_000, 1), TrashFile("Imagens/2024/Trip/DSC01002.ARW", 24_700_000, 1),
            TrashFile("Imagens/2024/Trip/.hoard-trash", 0, 1, isDirectory = true),
        )
        val units = TrashPlanner.units("Imagens/2024/Trip", listing, listOf("Imagens/2024/Trip/DSC01001.ARW", "Imagens/2024/Trip/DSC01002.ARW"))
        val p = TrashPlanner.plan(TrashSamples.nas, units, RootChoice.Root("Imagens", "folder-1", ""), serverHasTrash = false)
        return p.copy(refused = listOf(RefusedUnit(units.first(), TrashRefusal.SERVER_UNREACHABLE)))
    }

    @Test fun trashConfirmPhoneLight() = shoot("trash_confirm_phone_light") { Card { TrashConfirmBody(plan, "NAS › photos › Imagens", 30) } }
    @Test fun trashConfirmPhoneDark() = shoot("trash_confirm_phone_dark", dark = true) { Card { TrashConfirmBody(plan, "NAS › photos › Imagens", 0) } }
    @Test fun trashProgressPhoneLight() = shoot("trash_progress_phone_light") {
        Card { TrashProgressBody(TrashProgress(42, 17, 126, 51, 1_200_000_000, 480_000_000, "DSC01051.ARW")) }
    }
    @Test fun trashSettingsPhoneLight() = shoot("trash_settings_phone_light") {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) { Column { TrashSettingsContent(30, onDays = {}, onOpenTrash = {}) } }
    }
    @Test fun trashSettingsPhoneDark() = shoot("trash_settings_phone_dark", dark = true) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) { Column { TrashSettingsContent(0, onDays = {}, onOpenTrash = {}) } }
    }

    // --- Review of PR #59: the changed dialogs ---

    private fun phoneUnits() = TrashPlanner.units(
        "DCIM/Camera",
        listOf(TrashFile("DCIM/Camera/IMG_1.JPG", 3_400_000, 1), TrashFile("DCIM/Camera/IMG_1.DNG", 24_000_000, 1), TrashFile("DCIM/Camera/IMG_2.JPG", 3_100_000, 1)),
        listOf("DCIM/Camera/IMG_1.JPG", "DCIM/Camera/IMG_2.JPG"),
        wholeShots = false,
    )

    @Test fun trashPermanentFilesPhoneLight() = shoot("trash_permanent_files_phone_light") {
        Card { dev.hfps.hoard.app.ui.trash.PermanentDeleteBody(phoneUnits(), "Phone", phone = true, reason = TrashRefusal.PHONE_TRASH_UNAVAILABLE, shots = false) }
    }

    @Test fun trashPermanentShotsPhoneDark() = shoot("trash_permanent_shots_phone_dark", dark = true) {
        Card { dev.hfps.hoard.app.ui.trash.PermanentDeleteBody(plan.units.map { it.unit }, "NAS › rootro", phone = false, reason = TrashRefusal.ROOT_NOT_WRITABLE, shots = true) }
    }

    @Test fun trashShortenPhoneLight() = shoot("trash_shorten_phone_light") {
        Card { dev.hfps.hoard.app.ui.trash.ShortenBody(7, dev.hfps.hoard.app.trash.TrashService.AutoEmptyPreview(126, 1_240_000_000, 2, othersIncluded = true)) }
    }

    @Test fun trashEmptyConfirmPhoneLight() = shoot("trash_empty_confirm_phone_light") {
        val unreachable = ui(setOf(TrashSamples.usb))
        Card {
            androidx.compose.material3.Text(
                androidx.compose.ui.res.pluralStringResource(dev.hfps.hoard.app.R.plurals.trash_delete_confirm_body, unreachable.reachableFiles, unreachable.reachableFiles.toString(), dev.hfps.hoard.app.ui.common.bytes(unreachable.reachableBytes)),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
            dev.hfps.hoard.app.ui.trash.EmptyUnreachableNote(unreachable)
        }
    }

    // --- Phase A3: the phone's MediaStore trash ---

    private val phoneRows = listOf("IMG_0001.JPG", "IMG_0001.xmp", "IMG_0002.JPG").map { name ->
        TrashSamples.row(TrashPlace("device"), "", "", "DCIM/Camera/$name", if (name.endsWith("xmp")) 2_000 else 3_400_000).copy(
            trashedPath = "",
            mediaUri = "content://media/external_primary/images/media/1",
            trashedAt = java.time.LocalDate.of(2026, 11, 14).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            expiresAt = java.time.LocalDate.of(2026, 12, 14).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
    }

    private fun phoneUi() = TrashUi(
        TrashGroups.of(phoneRows + TrashSamples.rows, emptySet(), { if (it.locationId == "device") "Internal storage" else TrashSamples.label(it) }, ZoneOffset.UTC),
        loaded = true,
    )

    @Test fun trashPhonePhoneLight() = shoot("trash_phone_entries_phone_light") { TrashContent(phoneUi(), onBack = {}) }

    @Test fun trashMediaAskPhoneLight() = shoot("trash_media_ask_phone_light") {
        Card {
            val files = listOf(TrashFile("DCIM/Camera/IMG_0001.JPG", 6_084, 1), TrashFile("DCIM/Camera/IMG_0001.xmp", 38, 1))
            val p = TrashPlanner.plan(TrashPlace("device"), TrashPlanner.units("DCIM/Camera", files, listOf("DCIM/Camera/IMG_0001.JPG"), wholeShots = false), RootChoice.Root(""), false)
            TrashConfirmBody(p, "Internal storage", 30)
            dev.hfps.hoard.app.ui.trash.MediaManagementAsk {}
        }
    }

    @Test fun trashPermanentFolderPhoneLight() = shoot("trash_permanent_folder_phone_light") {
        val folder = TrashPlanner.units("DCIM", listOf(TrashFile("DCIM/OldStuff", 0, 1, isDirectory = true)), listOf("DCIM/OldStuff"))
        Card { dev.hfps.hoard.app.ui.trash.PermanentDeleteBody(folder, "Internal storage", phone = true, reason = TrashRefusal.FOLDER_NOT_TRASHABLE, shots = false) }
    }

    @Test fun trashSettingsMediaAllowedPhoneDark() = shoot("trash_settings_media_allowed_phone_dark", dark = true) {
        Box(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) {
            Column { TrashSettingsContent(30, onDays = {}, onOpenTrash = {}, media = dev.hfps.hoard.app.ui.trash.MediaManagement.Allowed) }
        }
    }

    // --- Phase A4: the Gallery's trash ---

    @Test fun trashServerSectionPhoneLight() = shoot("trash_server_section_phone_light") {
        val items = listOf("a", "b").map { id ->
            dev.hfps.hoard.server.db.ServerItemEntity(
                id = "srv-$id", rev = 1, kind = "photo", mainFingerprint = "fp-$id", sortTime = 1_700_000_000_000L, captureLocal = null,
                make = null, model = null, lens = null, width = 2400, height = 1600, orientation = null, durationMs = null,
                name = "DSC0100${if (id == "a") 1 else 2}.ARW", folder = "photos/2024/Wedding", search = "", hasRaw = true, editPrimary = null,
                editCount = 0, editedAt = null, json = "{}", trashedSince = 1_700_000_100_000L,
            )
        }
        TrashContent(ui().copy(server = dev.hfps.hoard.app.ui.trash.serverTrashRows(items)), onBack = {}, thumbs = dev.hfps.hoard.app.gallery.FakeThumbs)
    }

    @Test fun trashGalleryConfirmPhoneLight() = shoot("trash_gallery_confirm_phone_light") {
        Card {
            TrashConfirmBody(plan.copy(refused = emptyList()), "Internal storage, NAS › photos › Imagens", 30)
            androidx.compose.material3.Text(
                "1 server photo is left out: Hoard does not know its share yet. Open its folder in Files once.\n" +
                    "1 photo exists only as an upload on the server and is left out (Reveal's \"Delete from server\" removes it).",
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
    }
}
