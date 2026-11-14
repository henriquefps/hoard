package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** One destination of the app's main navigation: [icon] (regular) and [selectedIcon] (fill) with a [label]. */
data class HfpsDestination(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * The bottom navigation of a phone in portrait, on `background` like the top bar (chrome), the active destination
 * on the accent with its fill icon. Applies the navigation-bar inset itself.
 */
@Composable
fun HfpsNavigationBar(
    destinations: List<HfpsDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HfpsTheme.colors
    NavigationBar(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.mutedForeground,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
    ) {
        destinations.forEachIndexed { index, destination ->
            val active = index == selected
            NavigationBarItem(
                selected = active,
                onClick = { onSelect(index) },
                icon = { Icon(if (active) destination.selectedIcon else destination.icon, contentDescription = null) },
                label = { Text(destination.label, style = HfpsTheme.typography.uiMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.foreground,
                    indicatorColor = colors.accent,
                    unselectedIconColor = colors.mutedForeground,
                    unselectedTextColor = colors.mutedForeground,
                ),
            )
        }
    }
}

/**
 * The same navigation as a rail at the start edge: tablets, and short screens (a phone in landscape, Hoard 1.9.1's
 * `short` rule: viewport 500dp tall or less), where a bottom bar would take height the content needs.
 */
@Composable
fun HfpsNavigationRail(
    destinations: List<HfpsDestination>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HfpsTheme.colors
    NavigationRail(
        modifier = modifier,
        containerColor = colors.background,
        contentColor = colors.mutedForeground,
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
    ) {
        destinations.forEachIndexed { index, destination ->
            val active = index == selected
            NavigationRailItem(
                selected = active,
                onClick = { onSelect(index) },
                icon = { Icon(if (active) destination.selectedIcon else destination.icon, contentDescription = null) },
                label = { Text(destination.label, style = HfpsTheme.typography.uiMedium) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = colors.primary,
                    selectedTextColor = colors.foreground,
                    indicatorColor = colors.accent,
                    unselectedIconColor = colors.mutedForeground,
                    unselectedTextColor = colors.mutedForeground,
                ),
            )
        }
    }
}
