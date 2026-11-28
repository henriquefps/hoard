package dev.hfps.hoard.app.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import dev.hfps.hoard.server.trash.ServerTrashDay
import dev.hfps.hoard.server.trash.ServerTrashLine
import dev.hfps.hoard.server.trash.ServerTrashPlace

/*
 * The Trash screen's server part (ADR 0023 Amendment (2.10.0), hoard-server 0.15.0 `trash-actions`): the server's trash
 * by day and place, Restore and Delete permanently through the server, and Empty with the web's per-group confirmation.
 */

/** A line of the Empty dialog: a server group (`web`, `hoard`, `compressed`) or Hoard's own trash ([LOCAL]). */
data class EmptyLine(val key: String, val count: Int, val size: Long) {
    companion object {
        const val LOCAL = "local"
    }
}

/** The Empty dialog's lines: each server group with something in it, then Hoard's own trash (files) when it has any. */
fun emptyLines(groups: Map<String, TrashFigures>, localFiles: Int, localBytes: Long): List<EmptyLine> =
    TrashGroupIds.ALL.mapNotNull { g -> groups[g]?.takeIf { it.count > 0 }?.let { EmptyLine(g, it.count, it.size) } } +
        listOfNotNull(EmptyLine(EmptyLine.LOCAL, localFiles, localBytes).takeIf { localFiles > 0 })

@Composable
private fun groupLabel(key: String): String = when (key) {
    TrashGroupIds.WEB -> stringResource(R.string.trash_group_web)
    TrashGroupIds.HOARD -> stringResource(R.string.trash_group_hoard)
    TrashGroupIds.COMPRESSED -> stringResource(R.string.trash_group_compressed)
    else -> stringResource(R.string.trash_group_local)
}

/**
 * Empty trash with the web's per-group confirmation (the owner's rule): one line per group with its count, size and a
 * checkbox (all on); [changed]: the server's 409 said the trash changed, these are its figures now.
 */
@Composable
fun EmptyGroupsDialog(
    lines: List<EmptyLine>,
    checked: Set<String>,
    onChecked: (Set<String>) -> Unit,
    changed: Boolean,
    held: Int,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    webOnly: TrashFigures? = null,
) {
    HoardDialog(
        title = stringResource(R.string.trash_empty_confirm_title),
        onDismiss = { if (!busy) onDismiss() },
        confirm = {
            TextButton(onConfirm, enabled = !busy && lines.any { it.key in checked }, modifier = Modifier.testTag("trash-empty-confirm")) {
                Text(stringResource(R.string.trash_empty_action), color = HfpsTheme.colors.destructive, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
            }
        },
        dismiss = { TextButton({ onDismiss() }, enabled = !busy) { Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
    ) {
        EmptyGroupsBody(lines, checked, onChecked, changed, held, busy, webOnly)
    }
}

@Composable
fun EmptyGroupsBody(lines: List<EmptyLine>, checked: Set<String>, onChecked: (Set<String>) -> Unit, changed: Boolean, held: Int, busy: Boolean = false, webOnly: TrashFigures? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (changed) {
            Text(stringResource(R.string.trash_empty_changed), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.warning, modifier = Modifier.testTag("trash-empty-changed"))
        }
        Text(stringResource(R.string.trash_empty_groups_body), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        for (line in lines) {
            val on = line.key in checked
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !busy) { onChecked(if (on) checked - line.key else checked + line.key) }.padding(vertical = 2.dp).testTag("trash-empty-group-${line.key}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(on, { onChecked(if (it) checked + line.key else checked - line.key) }, enabled = !busy, colors = CheckboxDefaults.colors(checkedColor = HfpsTheme.colors.primary))
                Column(Modifier.weight(1f)) {
                    Text(groupLabel(line.key), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
                    Text(
                        if (line.key == EmptyLine.LOCAL) {
                            pluralStringResource(R.plurals.trash_group_summary, line.count, formatNumber(line.count, locale()), bytes(line.size))
                        } else {
                            pluralStringResource(R.plurals.trash_group_shots, line.count, formatNumber(line.count, locale()), bytes(line.size))
                        },
                        style = HfpsTheme.typography.ui,
                        color = HfpsTheme.colors.mutedForeground,
                    )
                }
            }
        }
        if (webOnly != null && webOnly.count > 0) {
            Text(
                pluralStringResource(R.plurals.trash_empty_web_only, webOnly.count, formatNumber(webOnly.count, locale()), bytes(webOnly.size)),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = 4.dp).testTag("trash-empty-web-only"),
            )
        }
        if (held > 0) {
            Text(pluralStringResource(R.plurals.compress_trash_held_note, held, formatNumber(held, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 4.dp))
        }
        Text(stringResource(R.string.t_cull_delete_permanent), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.destructive, modifier = Modifier.padding(top = 4.dp))
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
    }
}

/** The server section's header: its eyebrow, the server's auto-empty, and why it is not listed when unreachable. */
@Composable
fun ServerSectionHeader(reachable: Boolean, serverDays: Int, waiting: Int) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).testTag("trash-server-header")) {
        Eyebrow(stringResource(R.string.trash_server_section))
        Text(
            if (reachable) stringResource(R.string.trash_server_auto_note, serverDays) else stringResource(R.string.trash_server_unreachable),
            style = HfpsTheme.typography.ui,
            color = if (reachable) HfpsTheme.colors.mutedForeground else HfpsTheme.colors.destructive,
        )
        if (waiting > 0) {
            Text(pluralStringResource(R.plurals.trash_server_waiting, waiting, formatNumber(waiting, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.testTag("trash-server-waiting"))
        }
    }
}

/** Hoard's own trash (the phone, drives, shares outside the server), when the server's is listed above it. */
@Composable
fun LocalSectionHeader() {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 0.dp)) {
        Eyebrow(stringResource(R.string.trash_local_section))
    }
}

@Composable
fun ServerDayHeader(day: ServerTrashDay, label: String, onRestore: (List<ServerTrashLine>) -> Unit, onDelete: (List<ServerTrashLine>) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Eyebrow(label)
            Text(pluralStringResource(R.plurals.trash_group_shots, day.shots, formatNumber(day.shots, locale()), bytes(day.bytes)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        ServerMenu(stringResource(R.string.t_locations_actions_for, label), day.free.isNotEmpty(), { onRestore(day.free) }, day.deletable.isNotEmpty()) { onDelete(day.deletable) }
    }
}

/** A place of a day; tapping it shows its photos, each with its own Restore and Delete permanently ([expanded]). */
@Composable
fun ServerPlaceRow(place: ServerTrashPlace, onRestore: (List<ServerTrashLine>) -> Unit, onDelete: (List<ServerTrashLine>) -> Unit, expanded: Boolean = false, onToggle: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(HfpsTheme.colors.card).clickable(onClick = onToggle).padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp).testTag("trash-server-place"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (place.source == "nas") PhosphorRegular.FolderSimple else PhosphorRegular.DeviceMobile, null, Modifier.size(20.dp), tint = HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Eyebrow(if (place.source == "nas") place.name else stringResource(R.string.trash_server_backup_of, place.name))
            Text(place.dir.ifEmpty { "/" }, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, maxLines = 2)
            Text(pluralStringResource(R.plurals.trash_group_shots, place.shots, formatNumber(place.shots, locale()), bytes(place.bytes)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            val held = place.heldLines.size
            if (held > 0) {
                Text(pluralStringResource(R.plurals.trash_server_held, held, formatNumber(held, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.testTag("trash-server-held"))
            }
        }
        ServerMenu(stringResource(R.string.t_locations_actions_for, place.dir.ifEmpty { place.name }), place.free.isNotEmpty(), { onRestore(place.free) }, place.deletable.isNotEmpty()) { onDelete(place.deletable) }
    }
    if (expanded) {
        for (line in place.lines) ServerLineRow(line, onRestore, onDelete)
    }
}

/** One photo of a place: its name and size, its own menu, or "Held by a compression in progress" (no actions). */
@Composable
fun ServerLineRow(line: ServerTrashLine, onRestore: (List<ServerTrashLine>) -> Unit, onDelete: (List<ServerTrashLine>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(start = 48.dp, end = 4.dp, bottom = 4.dp).testTag("trash-server-line"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(if (line.entry.kind == "video") PhosphorRegular.FileVideo else PhosphorRegular.Image, null, Modifier.size(16.dp), tint = HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Text(line.entry.name, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.cardForeground, maxLines = 1)
            Text(
                when {
                    line.held -> stringResource(R.string.compress_trash_held)
                    line.webOnly -> bytes(line.unit.size) + " · " + stringResource(R.string.trash_web_only_label)
                    else -> bytes(line.unit.size)
                },
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                maxLines = 1,
            )
        }
        if (line.held) Box(Modifier.size(48.dp)) else ServerMenu(stringResource(R.string.t_locations_actions_for, line.entry.name), true, { onRestore(listOf(line)) }, !line.webOnly) { onDelete(listOf(line)) }
    }
}

@Composable
private fun ServerMenu(description: String, enabled: Boolean, onRestore: () -> Unit, canDelete: Boolean = true, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HfpsIconButton(PhosphorRegular.DotsThreeVertical, description, { open = true }, enabled = enabled, modifier = Modifier.testTag("trash-server-menu"))
        DropdownMenu(open, { open = false }, containerColor = HfpsTheme.colors.card) {
            MenuItem(stringResource(R.string.trash_restore), PhosphorRegular.ArrowCounterClockwise, { open = false; onRestore() })
            // Backup-only photos: a device may restore them, only the web gallery deletes them (server-0150b).
            if (canDelete) MenuItem(stringResource(R.string.trash_delete_permanently), PhosphorRegular.Trash, { open = false; onDelete() }, destructive = true)
        }
    }
}
