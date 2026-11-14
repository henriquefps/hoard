@file:Suppress("MagicNumber")

package dev.hfps.hoard.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.hfps.hoard.designsystem.R

/** Inter, bundled as a variable font (OFL, `res/font/inter_variable.ttf`, licence next to the module), one file for every weight. */
// FontVariation is experimental in Compose 1.11 (stable from 1.12, the version after the AGP 9 move, ADR 0019).
@OptIn(ExperimentalTextApi::class)
val InterFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { weight ->
        Font(
            resId = R.font.inter_variable,
            weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
        )
    },
)

private const val TABULAR = "tnum"

/**
 * The hfps type scale in sp. Nothing is below 12sp. Uppercase + tracking only on [eyebrow] (use the `Eyebrow`
 * composable, which also uppercases the text); everything else is sentence case.
 */
@Immutable
data class HfpsTypography(
    /** 12 medium, tracking 0.08em: small section labels only. */
    val eyebrow: TextStyle,
    /** 14: buttons, tabs, labels, menus. */
    val ui: TextStyle,
    /** 14 medium: buttons and active labels. */
    val uiMedium: TextStyle,
    /** 15: body and detail values, a row's primary label. */
    val body: TextStyle,
    /** 16: longer body text. */
    val bodyLarge: TextStyle,
    /** 18 semibold: panel and screen titles. */
    val title: TextStyle,
    /** 20 semibold, tabular: headline numbers. */
    val number: TextStyle,
    /** 24 semibold, tabular. */
    val numberLarge: TextStyle,
    /** 14 tabular: slider values (`+0.70`, `42 %`). */
    val value: TextStyle,
    /** 13 monospace: file names, ids, secondary lines. */
    val mono: TextStyle,
    /** 12 monospace. */
    val monoSmall: TextStyle,
)

@Suppress("CompositionLocalAllowlist")
internal val LocalHfpsTypography = staticCompositionLocalOf<HfpsTypography> {
    error("HfpsTheme is missing: no HfpsTypography provided")
}

internal fun hfpsTypography(): HfpsTypography {
    fun inter(size: Int, line: Int, weight: FontWeight, tabular: Boolean = false, tracking: Float = 0f) = TextStyle(
        fontFamily = InterFamily,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = weight,
        letterSpacing = tracking.em,
        fontFeatureSettings = if (tabular) TABULAR else null,
    )
    return HfpsTypography(
        eyebrow = inter(12, 16, FontWeight.Medium, tracking = 0.08f),
        ui = inter(14, 20, FontWeight.Normal),
        uiMedium = inter(14, 20, FontWeight.Medium),
        body = inter(15, 22, FontWeight.Normal),
        bodyLarge = inter(16, 24, FontWeight.Normal),
        title = inter(18, 24, FontWeight.SemiBold),
        number = inter(20, 28, FontWeight.SemiBold, tabular = true),
        numberLarge = inter(24, 32, FontWeight.SemiBold, tabular = true),
        value = inter(14, 20, FontWeight.Normal, tabular = true),
        mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp),
        monoSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp),
    )
}

/** Material 3 text styles mapped onto the hfps scale so stock components agree with it (no style is under 12sp). */
internal fun HfpsTypography.toMaterial(): Typography = Typography(
    displayLarge = numberLarge,
    displayMedium = numberLarge,
    displaySmall = number,
    headlineLarge = title,
    headlineMedium = title,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = bodyLarge.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = uiMedium,
    bodyLarge = bodyLarge,
    bodyMedium = body,
    bodySmall = ui,
    labelLarge = uiMedium,
    labelMedium = uiMedium,
    labelSmall = eyebrow.copy(letterSpacing = 0.em),
)
