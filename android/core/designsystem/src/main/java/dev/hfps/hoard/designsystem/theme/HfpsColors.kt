package dev.hfps.hoard.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The hfps tokens by their hfps names, including the ones Material 3's `ColorScheme` has no slot for (`warning`,
 * `mutedForeground`, `border`, `ring`). Read with [HfpsTheme.colors]. Every value comes from the generated
 * `HfpsTokens<Palette>` objects (through [hfpsColors]); never put a raw colour in a screen.
 */
@Immutable
data class HfpsColors(
    /** Chrome behind the top bar and around the card (`--background`). */
    val background: Color,
    val foreground: Color,
    /** The one raised content surface (`--card`). */
    val card: Color,
    val cardForeground: Color,
    /** The accent: active state, primary button, slider fill, toggles. Never large areas. */
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    /** Secondary text; passes WCAG AA on background, card and muted. */
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    /** Failed / destructive status. */
    val destructive: Color,
    /** Attention status: high usage, paused, needs a look. */
    val warning: Color,
    /** The hfps hairline border (`--border`). */
    val border: Color,
    /** Same value as [border]; the name to use for a 1dp card outline or divider. */
    val hairline: Color,
    /** Field and unselected-chip outline (`--input`). */
    val input: Color,
    /** Focus ring. */
    val ring: Color,
    val isDark: Boolean,
)

@Suppress("CompositionLocalAllowlist")
internal val LocalHfpsColors = staticCompositionLocalOf<HfpsColors> {
    error("HfpsTheme is missing: no HfpsColors provided")
}
