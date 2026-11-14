package dev.hfps.hoard.app.ui.shell

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.files.FilesScreen
import dev.hfps.hoard.app.ui.files.FilesViewModel
import dev.hfps.hoard.app.ui.gallery.GalleryScreen
import dev.hfps.hoard.app.ui.settings.LicencesScreen
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsViewModel
import dev.hfps.hoard.designsystem.components.HfpsDestination
import dev.hfps.hoard.designsystem.components.HfpsNavigationBar
import dev.hfps.hoard.designsystem.components.HfpsNavigationRail
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsMotion
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** The two tabs (Gallery, Files), in this order. */
enum class Tab { Gallery, Files }

/**
 * Where the main navigation goes: a bottom bar on phones in portrait, a rail at the start edge on tablets
 * (600dp wide or more) and on short screens (500dp tall or less: a phone in landscape), as Hoard 1.9.1 did with its
 * sidebar rail, so the content keeps the height.
 */
fun usesRail(width: Dp, height: Dp): Boolean = width >= 600.dp || height <= 500.dp

/** The app shell: the tabs and the screens behind them, over the real data in [container]. */
@Composable
fun HoardShell(container: AppContainer) {
    val navController = rememberNavController()
    val files: FilesViewModel = viewModel(factory = FilesViewModel.factory(container))
    val settings: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    ShellLayout(navController) {
        NavHost(
            navController = navController,
            // Files first while the Gallery is a placeholder (it gets content in phase 3).
            startDestination = FilesRoute,
            enterTransition = { fadeIn(HfpsMotion.standard()) },
            exitTransition = { fadeOut(HfpsMotion.fast()) },
            popEnterTransition = { fadeIn(HfpsMotion.standard()) },
            popExitTransition = { fadeOut(HfpsMotion.fast()) },
        ) {
            composable<GalleryRoute> { GalleryScreen(onOpenSettings = { navController.navigate(SettingsRoute) }) }
            composable<FilesRoute> {
                val state by files.state.collectAsStateWithLifecycle()
                FilesScreen(state = state, onOpenSettings = { navController.navigate(SettingsRoute) })
            }
            composable<SettingsRoute> {
                val state by settings.state.collectAsStateWithLifecycle()
                SettingsScreen(
                    state = state,
                    onBack = { navController.popBackStack() },
                    onThemeMode = settings::setThemeMode,
                    onPalette = settings::setPalette,
                    onOpenLicences = { navController.navigate(LicencesRoute) },
                )
            }
            composable<LicencesRoute> { LicencesScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

/** Places the navigation (bar or rail) around [content]; shown only on the tabs. */
@Composable
fun ShellLayout(navController: NavHostController, content: @Composable () -> Unit) {
    val entry by navController.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab = when {
        destination == null -> null
        destination.hasRoute<GalleryRoute>() -> Tab.Gallery
        destination.hasRoute<FilesRoute>() -> Tab.Files
        else -> null
    }
    TabbedLayout(
        selected = tab,
        onSelect = { selected ->
            navController.navigate(if (selected == Tab.Gallery) GalleryRoute else FilesRoute) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        },
        content = content,
    )
}

/** Stateless layout of the shell; [selected] null (a full-screen destination) hides the navigation. */
@Composable
fun TabbedLayout(selected: Tab?, onSelect: (Tab) -> Unit, content: @Composable () -> Unit) {
    val destinations = listOf(
        HfpsDestination(stringResource(R.string.nav_gallery), PhosphorRegular.Images, PhosphorFill.Images),
        HfpsDestination(stringResource(R.string.nav_files), PhosphorRegular.Folders, PhosphorFill.Folders),
    )
    BoxWithConstraints(Modifier.fillMaxSize().background(HfpsTheme.colors.background)) {
        val rail = usesRail(maxWidth, maxHeight)
        when {
            selected == null -> content()
            rail -> Row(Modifier.fillMaxSize()) {
                HfpsNavigationRail(destinations, selected.ordinal, { onSelect(Tab.entries[it]) }, Modifier.fillMaxHeight())
                Column(
                    Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
                ) { content() }
            }
            else -> Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
                ) { content() }
                HfpsNavigationBar(destinations, selected.ordinal, { onSelect(Tab.entries[it]) })
            }
        }
    }
}
