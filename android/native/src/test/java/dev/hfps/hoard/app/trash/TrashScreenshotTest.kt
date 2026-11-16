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
}
