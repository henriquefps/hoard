package dev.hfps.hoard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.hfps.hoard.app.ui.common.LocalNow
import dev.hfps.hoard.designsystem.theme.HfpsPalette
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import java.time.Instant

/**
 * The "now" of every screen test: the samples are dated 2026-11-14 10:00 UTC, so relative dates ("Today", "Oct 2")
 * read the same whatever day the goldens are verified on (`verifyRoborazziDebug`).
 */
object FixedNow {
    val millis: Long = Instant.parse("2026-11-14T15:00:00Z").toEpochMilli()
}

/** [HfpsTheme] with [LocalNow] fixed at [FixedNow]. */
@Composable
fun FixedNowTheme(darkTheme: Boolean, palette: HfpsPalette = HfpsPalette.Blue, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNow provides { FixedNow.millis }) {
        HfpsTheme(darkTheme = darkTheme, palette = palette, content = content)
    }
}
