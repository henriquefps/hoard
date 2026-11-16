package dev.hfps.hoard.app.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.gallery.BackupDevice
import dev.hfps.hoard.app.gallery.DeviceFolder
import dev.hfps.hoard.app.gallery.Mirrors
import dev.hfps.hoard.app.gallery.Where
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import java.text.NumberFormat

/** A volume as the owner knows it: internal storage, or the SD card with its UUID. */
@Composable
fun volumeLabel(volume: String): String =
    if (volume == Mirrors.PRIMARY) stringResource(R.string.t_locations_internal) else stringResource(R.string.gallery_devices_sd, volume)

/** "Pixel 8 › SD card 1234-ABCD › DCIM/Camera": what the grid shows of [where]. */
@Composable
fun whereLabel(where: Where): String = listOfNotNull(
    stringResource(R.string.gallery_where_backup, where.deviceName),
    where.volume?.let { volumeLabel(it) },
    where.dir?.ifEmpty { null },
).joinToString(" › ")

/**
 * Gallery › Devices (ADR 0024 A2): every phone with a backup on the server (this one first), its volumes and folders,
 * with how many shots each holds and how many left the phone. Picking one shows that part of the backup in the grid
 * (the "Where" filter); nothing is downloaded.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DevicesSheet(devices: List<BackupDevice>, selected: Where?, onPick: (Where) -> Unit, onDismiss: () -> Unit) {
    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("gallery-devices")) {
        DevicesContent(devices, selected, onPick = {
            onPick(it)
            onDismiss()
        })
    }
}

@Composable
fun DevicesContent(devices: List<BackupDevice>, selected: Where?, onPick: (Where) -> Unit) {
    val numbers = NumberFormat.getIntegerInstance()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.gallery_devices), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
        if (devices.isEmpty()) {
            Text(
                stringResource(R.string.gallery_devices_empty),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.padding(vertical = 16.dp).testTag("gallery-devices-empty"),
            )
        }
        for (device in devices) {
            Eyebrow(if (device.mine) stringResource(R.string.gallery_devices_this) else device.name, Modifier.padding(top = 16.dp, bottom = 4.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(HfpsTheme.colors.background).testTag("gallery-device-${device.physicalId}")) {
                val summary = listOfNotNull(
                    pluralStringResource(R.plurals.gallery_devices_shots, device.shots, numbers.format(device.shots)),
                    device.offPhone.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.gallery_devices_off, it, numbers.format(it)) },
                    stringResource(R.string.gallery_devices_inactive).takeIf { device.active == false },
                ).joinToString(" · ")
                HfpsListRow(
                    title = stringResource(R.string.gallery_devices_all, device.name),
                    subtitle = summary,
                    icon = PhosphorRegular.DeviceMobile,
                    iconTint = if (selected == device.where) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground,
                    onClick = { onPick(device.where) },
                    modifier = Modifier.testTag("gallery-device-all"),
                )
                for (volume in device.volumes) {
                    HfpsRowDivider()
                    val volumeWhere = Where(device.physicalId, device.name, volume.volume)
                    HfpsListRow(
                        title = volumeLabel(volume.volume),
                        subtitle = pluralStringResource(R.plurals.gallery_devices_shots, volume.shots, numbers.format(volume.shots)),
                        icon = if (volume.volume == Mirrors.PRIMARY) PhosphorRegular.HardDrive else PhosphorRegular.SimCard,
                        iconTint = if (selected == volumeWhere) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground,
                        onClick = { onPick(volumeWhere) },
                    )
                    for (folder in volume.folders) {
                        val where = Where(device.physicalId, device.name, volume.volume, folder.dir)
                        FolderRow(folder, selected == where, numbers) { onPick(where) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: DeviceFolder, selected: Boolean, numbers: NumberFormat, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(start = 54.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("gallery-device-folder"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(PhosphorRegular.FolderSimple, null, Modifier.size(18.dp), tint = if (selected) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Text(
                folder.dir.ifEmpty { stringResource(R.string.gallery_devices_whole) },
                style = HfpsTheme.typography.body,
                color = if (selected) HfpsTheme.colors.primary else HfpsTheme.colors.cardForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.gallery_devices_shots, folder.shots, numbers.format(folder.shots)),
                    folder.offPhone.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.gallery_devices_off, it, numbers.format(it)) },
                ).joinToString(" · "),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
    }
}

/** Above the grid while it shows part of a phone's backup: where, and a way back to everything. */
@Composable
fun WhereBar(where: Where, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(HfpsTheme.colors.card).padding(start = 12.dp).testTag("gallery-where"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorRegular.DeviceMobile, null, Modifier.size(16.dp), tint = HfpsTheme.colors.primary)
        Text(
            whereLabel(where),
            style = HfpsTheme.typography.uiMedium,
            color = HfpsTheme.colors.cardForeground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        HfpsIconButton(PhosphorRegular.X, stringResource(R.string.gallery_where_clear), onClear, modifier = Modifier.testTag("gallery-where-clear"))
    }
}
