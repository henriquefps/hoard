package dev.hfps.hoard.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours for what sits on a photo or a video (grid badges, the viewer's chrome): the picture decides the background,
 * so these do not follow the theme or the palette. [scrim] is the badge background; the rest are readable on it.
 */
object HfpsMedia {
    /** The full-screen viewer's background and the badge/pill background over a thumbnail. */
    val black = Color.Black
    val scrim = Color(0x99000000)
    val playScrim = Color(0x88000000)
    val chromeScrim = Color.Black.copy(alpha = 0.55f)

    /** Icons and text on a scrim. */
    val onScrim = Color.White
    val onScrimMuted = Color(0xFFCBD5E1)
    val onScrimFaint = Color(0x33FFFFFF)

    /** Status on a scrim: caution (backup failed, off the phone, a rating), positive (picked), negative (rejected). */
    val caution = Color(0xFFFFC53D)
    val positive = Color(0xFF7DD3A8)
    val negative = Color(0xFFFF8A80)
}
