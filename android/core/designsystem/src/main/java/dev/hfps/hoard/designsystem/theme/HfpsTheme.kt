package dev.hfps.hoard.designsystem.theme

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** Entry point to the hfps tokens: `HfpsTheme.colors`, `.typography`, `.shapes`. */
object HfpsTheme {
    val colors: HfpsColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHfpsColors.current

    val typography: HfpsTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalHfpsTypography.current

    val shapes: HfpsShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalHfpsShapes.current
}

/**
 * The app theme: the hfps tokens as a Material 3 `ColorScheme` plus [HfpsColors], Inter typography and hfps shapes.
 * Wrap every screen in it. Pass `mode.isDark()` from the user's [ThemeMode] and the user's [HfpsPalette].
 */
@Composable
fun HfpsTheme(darkTheme: Boolean, palette: HfpsPalette = HfpsPalette.Blue, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val wideGamut = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && configuration.isScreenWideColorGamut
    val colors = remember(darkTheme, palette, wideGamut) { hfpsColors(palette, darkTheme, wideGamut) }
    HfpsThemeContent(colors = colors, content = content)
}

/** Shared by [HfpsTheme] and variants that swap the colours (the optional `MediaEditorTheme`). */
@Composable
internal fun HfpsThemeContent(colors: HfpsColors, content: @Composable () -> Unit) {
    val typography = remember { hfpsTypography() }
    val shapes = remember { HfpsShapes() }
    val materialTypography = remember(typography) { typography.toMaterial() }
    CompositionLocalProvider(
        LocalHfpsColors provides colors,
        LocalHfpsTypography provides typography,
        LocalHfpsShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = remember(colors) { colors.toMaterial() },
            typography = materialTypography,
            shapes = MaterialShapes,
            content = content,
        )
    }
}

/** Every Material slot points at an hfps token, so stock components (text fields, menus, dialogs) agree with it. */
private fun HfpsColors.toMaterial(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = primary,
        onPrimary = primaryForeground,
        primaryContainer = accent,
        onPrimaryContainer = accentForeground,
        secondary = secondary,
        onSecondary = secondaryForeground,
        secondaryContainer = secondary,
        onSecondaryContainer = secondaryForeground,
        tertiary = primary,
        onTertiary = primaryForeground,
        background = background,
        onBackground = foreground,
        surface = card,
        onSurface = cardForeground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedForeground,
        surfaceTint = primary,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = destructive,
        onError = primaryForeground,
        errorContainer = muted,
        onErrorContainer = destructive,
        outline = input,
        outlineVariant = border,
        scrim = background,
        surfaceBright = card,
        surfaceDim = background,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = muted,
    )
}
