package dev.hfps.hoard.app.ui.files

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/**
 * The Files tab of phase 2a (ADR 0019): the saved machines, this phone's storage, the USB drives and SD cards
 * attached now, and the favorites, read-only. Browsing comes screen by screen in phase 2b.
 */
@Composable
fun FilesScreen(state: FilesUiState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    HfpsScaffold(
        modifier = modifier,
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(title = stringResource(R.string.files_title)) {
                HfpsIconButton(PhosphorRegular.Gear, stringResource(R.string.action_settings), onOpenSettings)
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                section(R.string.files_machines)
                if (state.machines.isEmpty() && !state.loading) {
                    note(R.string.files_machines_empty)
                }
                rows(state.machines, key = { "m-" + it.id }) { machine ->
                    HfpsListRow(
                        title = machine.name,
                        subtitle = machineSubtitle(machine.hosts),
                        icon = machineIcon(machine.icon),
                        iconTint = HfpsTheme.colors.primary,
                    )
                }

                section(R.string.files_on_this_phone)
                if (state.removable.isEmpty() && state.phone == null && !state.loading) {
                    note(R.string.files_no_removable)
                }
                rows(listOfNotNull(state.phone) + state.removable, key = { "v-" + it.uuid }) { volume ->
                    HfpsListRow(
                        title = if (volume.uuid == null) stringResource(R.string.files_this_phone) else volume.label,
                        subtitle = volumeSubtitle(volume),
                        icon = when {
                            volume.uuid == null -> PhosphorRegular.DeviceMobile
                            volume.kind == "usb" -> PhosphorRegular.Usb
                            else -> PhosphorRegular.HardDrive
                        },
                    )
                }
                if (state.removable.isEmpty() && state.phone != null && !state.loading) {
                    note(R.string.files_no_removable)
                }

                if (state.favorites.isNotEmpty()) {
                    section(R.string.files_favorites)
                    rows(state.favorites, key = { "f-" + it.id }) { favorite ->
                        HfpsListRow(
                            title = favorite.label,
                            subtitle = favoriteSubtitle(favorite),
                            icon = PhosphorRegular.Star,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: Int) {
    item(key = "section-$title", contentType = "section") {
        Eyebrow(stringResource(title), Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}

private fun LazyListScope.note(text: Int) {
    item(key = "note-$text", contentType = "note") {
        Text(
            text = stringResource(text),
            style = HfpsTheme.typography.body,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private fun <T> LazyListScope.rows(list: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    items(list.size, key = { key(list[it]) }, contentType = { "row" }) { index ->
        if (index > 0) HfpsRowDivider(Modifier.padding(start = 54.dp))
        row(list[index])
    }
}

/** The Phosphor icon a machine was given in Hoard 1 (`machines.icon`, src/components/MachineIcon.tsx). */
fun machineIcon(id: String?): ImageVector = when (id) {
    "desktop-tower" -> PhosphorRegular.DesktopTower
    "desktop" -> PhosphorRegular.Desktop
    "laptop" -> PhosphorRegular.Laptop
    "cloud" -> PhosphorRegular.Cloud
    "house" -> PhosphorRegular.House
    "monitor-play" -> PhosphorRegular.MonitorPlay
    "cpu" -> PhosphorRegular.Cpu
    else -> PhosphorRegular.HardDrives
}

@Composable
private fun machineSubtitle(hosts: List<String>): String = when (hosts.size) {
    0 -> stringResource(R.string.files_machine_no_address)
    1 -> hosts.first()
    else -> pluralStringResource(R.plurals.files_machine_more_addresses, hosts.size - 1, hosts.first(), hosts.size - 1)
}

@Composable
private fun volumeSubtitle(volume: VolumeItem): String {
    val context = LocalContext.current
    val kind = when (volume.kind) {
        "internal" -> null
        "usb" -> stringResource(R.string.files_volume_usb)
        "sd" -> stringResource(R.string.files_volume_sd)
        else -> stringResource(R.string.files_volume_other)
    }
    val detail = when {
        volume.state != "mounted" && volume.state != "mountedReadOnly" -> stringResource(R.string.files_volume_unavailable)
        volume.freeBytes != null && volume.totalBytes != null -> stringResource(
            R.string.files_volume_free,
            Formatter.formatShortFileSize(context, volume.freeBytes),
            Formatter.formatShortFileSize(context, volume.totalBytes),
        ) + if (volume.state == "mountedReadOnly") " · " + stringResource(R.string.files_volume_read_only) else ""
        else -> null
    }
    // "USB drive" is often the volume's own label already: say it once.
    return listOfNotNull(kind?.takeUnless { it.equals(volume.label, ignoreCase = true) }, detail).joinToString(" · ")
}

@Composable
private fun favoriteSubtitle(favorite: FavoriteItem): String {
    val place = when (val p = favorite.place) {
        is Place.Machine -> p.name
        Place.Phone -> stringResource(R.string.files_this_phone)
        is Place.Drive -> p.label ?: stringResource(R.string.files_volume_unknown, p.uuid)
    }
    val path = favorite.path.ifEmpty { stringResource(R.string.files_root) }
    return "$place · $path"
}

