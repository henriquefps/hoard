package dev.hfps.hoard.app

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.test.core.app.ApplicationProvider
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.files.ViewOptions
import dev.hfps.hoard.app.files.viewEntries
import dev.hfps.hoard.app.route.ActiveRoute
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.app.route.RouteState
import dev.hfps.hoard.app.route.RouteStatus
import dev.hfps.hoard.app.ui.browse.BrowseNav
import dev.hfps.hoard.app.ui.browse.BrowseScreen
import dev.hfps.hoard.app.ui.browse.BrowseUiState
import dev.hfps.hoard.app.ui.browse.BrowseViewModel
import dev.hfps.hoard.app.ui.browse.Listing
import dev.hfps.hoard.app.ui.browse.ShareItem
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.rememberToaster
import dev.hfps.hoard.data.MachineAddressEntity
import dev.hfps.hoard.data.MachineEntity
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import java.util.Locale

/** Sample data of phase 2b's screens (synthetic names, no real data). */
object Samples2b {
    val container: AppContainer get() = (ApplicationProvider.getApplicationContext<HoardApplication>()).container

    val machine = MachineWithAddresses(
        MachineEntity("m1", "Test Samba", "desktop-tower", "blue", "hoard", null, "guid", 0, "2026-11-14T00:00:00Z"),
        listOf(
            MachineAddressEntity("a1", "m1", "192.168.1.155", 445, "local", null, 0),
            MachineAddressEntity("a2", "m1", "100.101.12.34", 445, "tailscale", null, 1),
        ),
    )

    val location = BrowseLocation("m1", LocationKind.Smb, "Test Samba", machine = machine, share = "media")

    val online = RouteState(RouteStatus.Online, route = ActiveRoute("192.168.1.155", 445, AddressKind.Local, 4))

    private const val DAY = "2026-11-14T10:00:00Z"
    val entries = listOf(
        SmbEntry("Lisboa", "Photos/Lisboa", true, 0, DAY, false),
        SmbEntry("Raw", "Photos/Raw", true, 0, "2026-10-02T10:00:00Z", false),
        SmbEntry("DSC0001.JPG", "Photos/DSC0001.JPG", false, 6_412_331, DAY, false),
        SmbEntry("DSC0001.ARW", "Photos/DSC0001.ARW", false, 24_903_441, DAY, false),
        SmbEntry("a very long file name from the family trip to Sintra in the spring.jpg", "Photos/long.jpg", false, 3_100_000, "2025-04-20T10:00:00Z", false),
        SmbEntry("clip.mp4", "Photos/clip.mp4", false, 104_432_771, DAY, false),
        SmbEntry("notes.md", "Photos/notes.md", false, 1_204, DAY, false),
        SmbEntry("report.pdf", "Photos/report.pdf", false, 820_100, DAY, false),
        SmbEntry("backup.zip", "Photos/backup.zip", false, 9_820_100_000, DAY, false),
        SmbEntry(".DS_Store", "Photos/.DS_Store", false, 6_148, DAY, true),
    )

    fun folder(selected: Set<String> = emptySet(), readOnly: Boolean = false) = BrowseUiState(
        location = location,
        share = "media",
        path = "Photos",
        route = online,
        listing = Listing(entries = entries, access = if (readOnly) SmbAccess.READ else SmbAccess.READ_WRITE, loading = false),
        visible = viewEntries(entries, ViewOptions(sort = SortOrder.DEFAULT), Locale.ENGLISH),
        selected = selected,
        selectionMode = selected.isNotEmpty(),
    )

    val shares = BrowseUiState(
        location = location.copy(share = null),
        share = null,
        route = online,
        listing = Listing(
            shares = listOf(
                ShareItem("media", "Media files", false, SmbAccess.READ_WRITE, false),
                ShareItem("photos", null, false, SmbAccess.READ, false),
                ShareItem("archive", null, false, null, true),
            ),
            loading = false,
        ),
    )

    val nav = BrowseNav({}, { _, _, _ -> }, {}, {}, { _, _ -> }, { _, _, _ -> }, {})

    @Composable
    fun Toasts(content: @Composable () -> Unit) {
        val host = remember { SnackbarHostState() }
        CompositionLocalProvider(LocalToaster provides rememberToaster(host)) { content() }
    }

    @Composable
    fun Browse(state: BrowseUiState, onViewModel: (BrowseViewModel) -> Unit = {}) {
        val vm = remember { BrowseViewModel(container, "m1", state.share, state.path, "Internal storage", "Drive") }
        onViewModel(vm)
        Toasts { BrowseScreen(vm, state, remember { FileActions(container) }, nav, container.clipboard) }
    }
}
