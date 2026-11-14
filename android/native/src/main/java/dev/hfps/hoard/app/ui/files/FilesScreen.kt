package dev.hfps.hoard.app.ui.files

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.route.RouteState
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.RouteLine
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** What the Files tab's rows do (the shell's navigation and the stores); defaults do nothing (previews, tests). */
class FilesActions(
    val openLocation: (locationId: String, share: String?, path: String) -> Unit = { _, _, _ -> },
    val addMachine: () -> Unit = {},
    val editMachine: (String) -> Unit = {},
    val recheck: (String) -> Unit = {},
    val deleteMachine: (MachineItem) -> Unit = {},
    val transfers: () -> Unit = {},
    val storageAccess: () -> Unit = {},
    val renameFavorite: (FavoriteItem) -> Unit = {},
    val removeFavorite: (FavoriteItem) -> Unit = {},
    val moveFavorite: (FavoriteItem, Int) -> Unit = { _, _ -> },
    /** A drive Hoard cannot read by path: pick a folder on it (SAF). */
    val grantVolume: (VolumeItem) -> Unit = {},
    val storageSettings: () -> Unit = {},
)

/**
 * The Files tab (Hoard 1's home, ADR 0005): this phone (internal storage, USB drives, SD cards), the favourites, then
 * the SMB machines with the route each one uses right now.
 */
@Composable
fun FilesScreen(state: FilesUiState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier, actions: FilesActions = FilesActions()) {
    HfpsScaffold(
        modifier = modifier,
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(title = stringResource(R.string.files_title)) {
                Box {
                    HfpsIconButton(PhosphorRegular.ArrowsDownUp, stringResource(R.string.t_nav_transfers), actions.transfers, active = state.activeTransfers > 0)
                    if (state.activeTransfers > 0) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 8.dp).size(16.dp).clip(CircleShape).background(HfpsTheme.colors.primary),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(state.activeTransfers.toString(), style = HfpsTheme.typography.eyebrow, color = HfpsTheme.colors.primaryForeground)
                        }
                    }
                }
                HfpsIconButton(PhosphorRegular.Gear, stringResource(R.string.action_settings), onOpenSettings)
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("files-list"),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                section(R.string.files_on_this_phone)
                if (!state.storageGranted) {
                    item(key = "access") { AccessCard(actions.storageAccess) }
                }
                state.phone?.let { phone ->
                    item(key = "v-phone") { VolumeRow(phone, actions) }
                }
                state.removable.forEach { volume ->
                    item(key = "v-" + volume.uuid) {
                        HfpsRowDivider(Modifier.padding(start = 68.dp))
                        VolumeRow(volume, actions)
                    }
                }
                if (state.removable.isEmpty() && !state.loading) note(R.string.t_locations_connect_hint)

                if (state.favorites.isNotEmpty()) {
                    section(R.string.files_favorites)
                    state.favorites.forEachIndexed { index, favorite ->
                        item(key = "f-" + favorite.id) {
                            if (index > 0) HfpsRowDivider(Modifier.padding(start = 68.dp))
                            FavoriteRow(favorite, state.routes[favorite.machineId], index, state.favorites.size, actions)
                        }
                    }
                }

                section(R.string.files_machines)
                if (state.machines.isEmpty() && !state.loading) {
                    item(key = "onboarding") { Onboarding(actions.addMachine) }
                } else {
                    state.machines.forEachIndexed { index, machine ->
                        item(key = "m-" + machine.id) {
                            if (index > 0) HfpsRowDivider(Modifier.padding(start = 68.dp))
                            MachineRow(machine, state.routes[machine.id] ?: RouteState.IDLE, actions)
                        }
                    }
                    item(key = "add-machine") {
                        Box(Modifier.padding(16.dp)) { SecondaryButton(stringResource(R.string.t_machines_add), actions.addMachine, icon = PhosphorRegular.Plus) }
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
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun Tile(icon: ImageVector) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
    }
}

@Composable
private fun RowShell(onClick: (() -> Unit)?, menu: (@Composable () -> Unit)?, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        menu?.invoke()
    }
}

@Composable
private fun AccessCard(onAllow: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .background(HfpsTheme.colors.primary.copy(alpha = 0.06f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.t_locations_access_card_title), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
        Text(stringResource(R.string.t_locations_access_card_body), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        PrimaryButton(stringResource(R.string.t_locations_access_card_action), onAllow, icon = PhosphorRegular.LockSimple)
    }
}

@Composable
private fun VolumeRow(volume: VolumeItem, actions: FilesActions) {
    val readable = volume.state == "mounted" || volume.state == "mountedReadOnly"
    val needsGrant = readable && volume.uuid != null && volume.access == "none"
    var menu by remember { mutableStateOf(false) }
    val label = if (volume.uuid == null) stringResource(R.string.t_locations_internal) else volume.label
    RowShell(
        onClick = when {
            needsGrant -> ({ actions.grantVolume(volume) })
            readable -> ({ actions.openLocation(volume.locationId, null, "") })
            else -> null
        },
        menu = {
            Box {
                HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_locations_actions_for, label), { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                    if (needsGrant) MenuItem(stringResource(R.string.t_locations_permission_picker), PhosphorRegular.FolderSimple, { menu = false; actions.grantVolume(volume) })
                    MenuItem(
                        stringResource(if (volume.uuid != null) R.string.t_locations_eject_in_settings else R.string.t_locations_storage_settings),
                        if (volume.uuid != null) PhosphorRegular.Eject else PhosphorRegular.Gear,
                        { menu = false; actions.storageSettings() },
                    )
                }
            }
        },
    ) {
        Tile(
            when {
                volume.uuid == null -> PhosphorRegular.DeviceMobile
                volume.kind == "usb" -> PhosphorRegular.Usb
                else -> PhosphorRegular.HardDrive
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (needsGrant) stringResource(R.string.t_locations_permission_picker) else volumeSubtitle(volume),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                maxLines = 2,
            )
            val total = volume.totalBytes
            val free = volume.freeBytes
            if (readable && total != null && free != null && total > 0) {
                val used = ((total - free).toFloat() / total).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { used },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = when {
                        used > 0.95f -> HfpsTheme.colors.destructive
                        used > 0.85f -> HfpsTheme.colors.warning
                        else -> HfpsTheme.colors.primary
                    },
                    trackColor = HfpsTheme.colors.muted,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun FavoriteRow(favorite: FavoriteItem, route: RouteState?, index: Int, count: Int, actions: FilesActions) {
    var menu by remember { mutableStateOf(false) }
    val connected = favorite.place !is Place.Drive || (favorite.place as Place.Drive).label != null
    RowShell(
        onClick = { actions.openLocation(favorite.locationId, favorite.share.ifEmpty { null }, favorite.path) },
        menu = {
            Box {
                HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_favorites_actions_for, favorite.label), { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                    MenuItem(stringResource(R.string.t_favorites_rename), PhosphorRegular.PencilSimple, { menu = false; actions.renameFavorite(favorite) })
                    MenuItem(stringResource(R.string.t_common_move_up_short), PhosphorRegular.ArrowUp, { menu = false; actions.moveFavorite(favorite, -1) }, enabled = index > 0)
                    MenuItem(stringResource(R.string.t_common_move_down_short), PhosphorRegular.ArrowDown, { menu = false; actions.moveFavorite(favorite, 1) }, enabled = index < count - 1)
                    HorizontalDivider(color = HfpsTheme.colors.hairline)
                    MenuItem(stringResource(R.string.t_favorites_remove), PhosphorRegular.Trash, { menu = false; actions.removeFavorite(favorite) }, destructive = true)
                }
            }
        },
    ) {
        Tile(PhosphorRegular.FolderSimpleStar)
        Column(Modifier.weight(1f)) {
            Text(favorite.label, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(favoriteSubtitle(favorite), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                route != null -> RouteLine(route, hideHost = true)
                !connected -> Text(stringResource(R.string.t_locations_not_connected), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
    }
}

@Composable
private fun MachineRow(machine: MachineItem, route: RouteState, actions: FilesActions) {
    var menu by remember { mutableStateOf(false) }
    RowShell(
        onClick = { actions.openLocation(machine.id, null, "") },
        menu = {
            Box {
                HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_machines_actions_for, machine.name), { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                    MenuItem(stringResource(R.string.t_common_edit), PhosphorRegular.PencilSimple, { menu = false; actions.editMachine(machine.id) })
                    MenuItem(stringResource(R.string.t_route_recheck), PhosphorRegular.ArrowsClockwise, { menu = false; actions.recheck(machine.id) })
                    HorizontalDivider(color = HfpsTheme.colors.hairline)
                    MenuItem(stringResource(R.string.t_common_delete), PhosphorRegular.Trash, { menu = false; actions.deleteMachine(machine) }, destructive = true)
                }
            }
        },
    ) {
        Tile(machineIcon(machine.icon))
        Column(Modifier.weight(1f)) {
            Text(machine.name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (route.status == dev.hfps.hoard.app.route.RouteStatus.Idle) {
                Text(machineSubtitle(machine.hosts), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                RouteLine(route)
            }
        }
    }
}

/** No machine yet: what SMB machines do in three steps, and one button (Hoard 1's onboarding). */
@Composable
private fun Onboarding(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.t_onboarding_title), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
        Text(stringResource(R.string.t_onboarding_body), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        listOf(
            Triple(PhosphorRegular.HardDrives, R.string.t_onboarding_step1_title, R.string.t_onboarding_step1_body),
            Triple(PhosphorRegular.ShieldCheck, R.string.t_onboarding_step2_title, R.string.t_onboarding_step2_body),
            Triple(PhosphorRegular.ArrowsDownUp, R.string.t_onboarding_step3_title, R.string.t_onboarding_step3_body),
        ).forEach { (icon, title, body) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(HfpsTheme.colors.muted.copy(alpha = 0.5f)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(icon, null, Modifier.size(22.dp), tint = HfpsTheme.colors.primary)
                Column {
                    Text(stringResource(title), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
                    Text(stringResource(body), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        PrimaryButton(stringResource(R.string.t_onboarding_cta), onAdd, icon = PhosphorRegular.Plus)
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

/** Machine icon ids Hoard 1 offers, in its order. */
val MACHINE_ICONS = listOf("desktop-tower", "desktop", "laptop", "hard-drives", "cloud", "house", "monitor-play", "cpu")

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
    // As Hoard 1 says it: the machine, its share, then the folder ("NAS · photos · 2026/Lisboa").
    val where = listOf(favorite.share, favorite.path).filter { it.isNotEmpty() }
    return listOf(place).plus(where.ifEmpty { listOf(stringResource(R.string.files_root)) }).joinToString(" · ")
}
