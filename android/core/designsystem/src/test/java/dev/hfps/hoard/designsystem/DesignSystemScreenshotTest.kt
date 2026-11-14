package dev.hfps.hoard.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsChip
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the design system under `src/test/screenshots/designsystem/`, phone size, light / dark / 200 % font.
 * Record: `./gradlew :core:designsystem:recordRoborazziDebug`. Verify (CI, `qa`): `verifyRoborazziDebug`.
 * Literal strings are fine here: this is a test fixture, not UI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class DesignSystemScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun light() = shoot("light", dark = false)

    @Test fun dark() = shoot("dark", dark = true)

    @Test fun lightFontScale200() = shoot("light_font200", dark = false, fontScale = 2f)

    private fun shoot(name: String, dark: Boolean, fontScale: Float = 1f) {
        composeRule.setContent {
            HfpsTheme(darkTheme = dark) {
                Scaled(fontScale) { Sample() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/designsystem/phone_$name.png")
    }
}

@Composable
private fun Sample() {
    HfpsScaffold(
        topBar = {
            HfpsTopBar(title = "Settings", onBack = {}) {
                HfpsIconButton(PhosphorRegular.MagnifyingGlass, contentDescription = "Search", onClick = {})
            }
        },
    ) {
        Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Account", Modifier.padding(horizontal = 16.dp))
            HfpsListRow(title = "Storage", subtitle = "1.2 GB of 4 GB", icon = PhosphorRegular.Gear, onClick = {})
            HfpsRowDivider()
            HfpsListRow(title = "About", onClick = {})
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HfpsChip(label = "All", selected = true, onClick = {})
                HfpsChip(label = "Recent", selected = false, onClick = {})
            }
            FlowRow(
                Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(text = "Save", onClick = {})
                SecondaryButton(text = "Share", onClick = {})
                GhostButton(text = "Cancel", onClick = {})
            }
        }
    }
}

@Composable
private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}
