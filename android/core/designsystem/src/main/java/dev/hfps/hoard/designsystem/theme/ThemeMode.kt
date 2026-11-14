package dev.hfps.hoard.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** The user's theme choice (Light, Dark, System). Default [System]; persist it in the app (DataStore). */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** Whether this mode renders dark, given the system setting. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun ThemeMode.isDark(): Boolean = isDark(systemDark = isSystemInDarkTheme())
