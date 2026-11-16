package dev.hfps.hoard.app.ui.transfers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.testTag
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.formatDuration
import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.app.transfers.INCOMING_LOCATION_ID
import dev.hfps.hoard.app.transfers.TREE_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferService
import dev.hfps.hoard.app.transfers.TransferView
import dev.hfps.hoard.app.transfers.VOLUME_PREFIX
import dev.hfps.hoard.app.ui.common.MenuItem
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.relativeDate
import dev.hfps.hoard.app.ui.common.problemText
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.transfer.TransferStates
import kotlinx.coroutines.launch

/**
 * Transfers (Hoard 1's TransfersPage): the jobs running now with their progress, speed and time left, then the
 * history; cancel, retry (resumes partial files), answer conflicts, the failed files, remove, clear finished.
 */
@Composable
fun TransfersScreen(onBack: () -> Unit, onOpenDestination: (TransferView) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val service = container.transfers
    val scope = rememberCoroutineScope()
    val list by service.transfers.collectAsState(initial = null)
    val machines by container.database.machines().observeAll().collectAsState(initial = emptyList())
    var answering by remember { mutableStateOf<String?>(null) }
    val finished = list.orEmpty().filter { !it.active }
    HfpsScaffold(
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(stringResource(R.string.t_transfers_title), onBack = onBack) {
                if (finished.isNotEmpty()) {
                    HfpsIconButton(PhosphorRegular.Trash, stringResource(R.string.t_transfers_clear_finished), { scope.launch { service.clearFinished() } })
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                transferItems(list, machines, service, limitFinished = null, onAnswer = { answering = it }, onOpenDestination = onOpenDestination)
            }
        }
    }
    answering?.let { id -> ConflictDialog(id, onDismiss = { answering = null }) }
}

/**
 * The queue as list items, for the screen and the phone sheet (TransfersList.tsx): the active jobs, then the history
 * ([limitFinished] of it in the sheet, "N more on the Transfers page"), "Clear finished".
 */
fun LazyListScope.transferItems(
    list: List<TransferView>?,
    machines: List<MachineWithAddresses>,
    service: TransferService,
    limitFinished: Int?,
    onAnswer: (String) -> Unit,
    onOpenDestination: (TransferView) -> Unit,
) {
    val active = list.orEmpty().filter { it.active }
    val finished = list.orEmpty().filter { !it.active }
    val shown = if (limitFinished != null) finished.take(limitFinished) else finished
    when {
        list == null -> Unit
        list.isEmpty() -> item {
            StateMessage(PhosphorRegular.ArrowsDownUp, stringResource(R.string.t_transfers_empty_title), body = stringResource(R.string.t_transfers_empty_body))
        }
        else -> {
            if (active.isNotEmpty()) {
                item { Eyebrow(pluralStringResource(R.plurals.t_transfers_active, active.size, active.size.toString()), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                items(active, key = { it.row.id }) { t ->
                    TransferRow(t, machines, service, onAnswer = { onAnswer(t.row.id) }, onOpenDestination = onOpenDestination)
                    HfpsRowDivider(Modifier.padding(start = 16.dp))
                }
            }
            if (finished.isNotEmpty()) {
                item { Eyebrow(stringResource(R.string.t_transfers_finished), Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                items(shown, key = { it.row.id }) { t ->
                    TransferRow(t, machines, service, onAnswer = {}, onOpenDestination = onOpenDestination)
                    HfpsRowDivider(Modifier.padding(start = 16.dp))
                }
                if (shown.size < finished.size) {
                    item {
                        Text(
                            pluralStringResource(R.plurals.t_transfers_more, finished.size - shown.size, (finished.size - shown.size).toString()),
                            style = HfpsTheme.typography.ui,
                            color = HfpsTheme.colors.mutedForeground,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
                item {
                    val scope = rememberCoroutineScope()
                    Box(Modifier.padding(16.dp)) { GhostButton(stringResource(R.string.t_transfers_clear_finished), { scope.launch { service.clearFinished() } }, icon = PhosphorRegular.Trash) }
                }
            }
        }
    }
}

/**
 * Phones: the queue in a bottom sheet from the transfer indicator (Hoard 1's TransfersDrawer): the summary, the active
 * jobs and the last five finished, "Open all transfers" for the screen. Tablets open the screen instead.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TransfersSheet(onDismiss: () -> Unit, onOpenAll: () -> Unit, onOpenDestination: (TransferView) -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val service = container.transfers
    val list by service.transfers.collectAsState(initial = null)
    val machines by container.database.machines().observeAll().collectAsState(initial = emptyList())
    var answering by remember { mutableStateOf<String?>(null) }
    val indicator = dev.hfps.hoard.app.transfers.TransfersIndicator.of(list.orEmpty())
    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("transfers-sheet")) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            Text(stringResource(R.string.t_transfers_title), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
            Text(
                if (indicator.count > 0) {
                    pluralStringResource(R.plurals.t_transfers_active_summary, indicator.count, indicator.count.toString(), indicator.percent.toInt().toString())
                } else {
                    stringResource(R.string.t_transfers_description)
                },
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 560.dp)) {
            transferItems(list, machines, service, limitFinished = 5, onAnswer = { answering = it }, onOpenDestination = { onDismiss(); onOpenDestination(it) })
        }
        HorizontalDivider(color = HfpsTheme.colors.hairline)
        SecondaryButton(
            stringResource(R.string.t_transfers_open_page),
            { onDismiss(); onOpenAll() },
            modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("transfers-open-all"),
        )
    }
    answering?.let { id -> ConflictDialog(id, onDismiss = { answering = null }) }
}

@Composable
private fun placeName(locationId: String, share: String, machines: List<MachineWithAddresses>): String = when {
    locationId == DEVICE_LOCATION_ID -> stringResource(R.string.t_locations_internal)
    locationId.startsWith(VOLUME_PREFIX) -> stringResource(R.string.t_locations_drive)
    locationId == TREE_LOCATION_ID -> stringResource(R.string.t_transfer_android_folder)
    locationId == INCOMING_LOCATION_ID -> stringResource(R.string.t_transfers_from_other_app)
    locationId == dev.hfps.hoard.app.transfers.SERVER_LOCATION_ID -> stringResource(R.string.save_device_from_server)
    else -> (machines.firstOrNull { it.machine.id == locationId }?.machine?.name ?: stringResource(R.string.t_transfers_unknown_machine)) +
        if (share.isNotEmpty()) " / $share" else ""
}

@Composable
private fun TransferRow(t: TransferView, machines: List<MachineWithAddresses>, service: TransferService, onAnswer: () -> Unit, onOpenDestination: (TransferView) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var showFailed by remember { mutableStateOf(false) }
    val row = t.row
    val count = TransferService.sourcePaths(row).size
    val name = if (count > 1) pluralStringResource(R.plurals.t_transfers_name_more, count - 1, row.name, (count - 1).toString()) else row.name
    val stateText = when (t.state) {
        TransferStates.RUNNING -> stringResource(if (row.op == "move") R.string.t_transfers_states_running_move else R.string.t_transfers_states_running_copy)
        TransferStates.QUEUED -> stringResource(R.string.t_transfers_states_queued)
        TransferStates.SCANNING -> stringResource(R.string.t_transfers_states_scanning)
        TransferStates.CONFLICT -> stringResource(R.string.t_transfers_states_conflict)
        TransferStates.RETRYING -> stringResource(R.string.t_transfers_states_retrying)
        TransferStates.COMPLETED -> stringResource(R.string.t_transfers_states_completed)
        TransferStates.FAILED -> stringResource(R.string.t_transfers_states_failed)
        TransferStates.CANCELLED -> stringResource(R.string.t_transfers_states_cancelled)
        else -> stringResource(R.string.t_transfers_states_skipped)
    }
    val from = placeName(row.sourceLocationId, row.sourceShare.takeUnless { row.sourceLocationId == INCOMING_LOCATION_ID }.orEmpty(), machines)
    val to = placeName(row.destLocationId, row.destShare, machines) + if (row.destPath.isNotEmpty()) " / ${row.destPath}" else ""
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(stateTint(t.state).copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(if (row.op == "move") PhosphorRegular.Scissors else PhosphorRegular.Copy, null, Modifier.size(18.dp), tint = stateTint(t.state))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$from → $to",
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                HfpsIconButton(PhosphorRegular.DotsThreeVertical, stringResource(R.string.t_common_more), { menu = true })
                DropdownMenu(menu, { menu = false }, containerColor = HfpsTheme.colors.card) {
                    if (t.active) {
                        MenuItem(stringResource(R.string.t_transfers_cancel, row.name), PhosphorRegular.X, { menu = false; service.cancel(row.id) }, destructive = true)
                    } else {
                        if (t.state == TransferStates.FAILED || t.state == TransferStates.CANCELLED) {
                            MenuItem(stringResource(R.string.t_transfers_retry, row.name), PhosphorRegular.ArrowsClockwise, {
                                menu = false
                                scope.launch { runCatching { NotificationsPermission.askOnce(); service.retry(row) } }
                            })
                        }
                        MenuItem(stringResource(R.string.t_transfers_open_destination), PhosphorRegular.FolderSimple, { menu = false; onOpenDestination(t) }, enabled = row.destLocationId != TREE_LOCATION_ID)
                        MenuItem(stringResource(R.string.t_transfers_remove), PhosphorRegular.Trash, { menu = false; scope.launch { service.remove(row.id) } })
                    }
                }
            }
        }
        if (t.active) {
            val progress = if (t.total > 0) (t.bytesDone.toFloat() / t.total).coerceIn(0f, 1f) else 0f
            if (t.state == TransferStates.SCANNING || t.state == TransferStates.QUEUED) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
            } else {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = stateTint(t.state),
                    trackColor = HfpsTheme.colors.muted,
                    drawStopIndicator = {},
                )
            }
            val speed = t.live?.bytesPerSecond
            val parts = buildList {
                add(stateText)
                if (t.state == TransferStates.SCANNING) {
                    add(pluralStringResource(R.plurals.t_transfers_scanning, t.itemsTotal, t.itemsTotal.toString()))
                } else {
                    add(stringResource(R.string.t_transfers_progress, bytes(t.bytesDone), bytes(t.total)))
                    if (t.itemsTotal > 1) add(pluralStringResource(R.plurals.t_transfers_items_progress, t.itemsTotal, t.itemsDone.toString(), t.itemsTotal.toString()))
                }
                if (speed != null && speed > 0) {
                    add(bytes(speed) + "/s")
                    if (t.total > t.bytesDone) add(stringResource(R.string.t_transfers_left, formatDuration((t.total - t.bytesDone).toDouble() / speed)))
                }
                t.live?.activeHost?.let { add(it) }
            }
            Text(parts.joinToString(" · "), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 2)
            t.live?.currentName?.takeIf { t.state == TransferStates.RUNNING }?.let {
                Text(stringResource(R.string.t_transfers_current, it), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t.state == TransferStates.RETRYING) Text(stringResource(R.string.t_transfers_retrying_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning)
            if (t.state == TransferStates.CONFLICT) {
                Text(pluralStringResource(R.plurals.t_transfers_conflicts, t.conflicts.coerceAtLeast(1), t.conflicts.coerceAtLeast(1).toString()), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning)
                SecondaryButton(stringResource(R.string.t_transfers_answer), onAnswer)
            }
        } else {
            val detail = buildList {
                add(stateText)
                add(bytes(t.total))
                if (t.itemsTotal > 1) add(pluralStringResource(R.plurals.t_transfers_items_done, t.itemsTotal, t.itemsDone.toString(), t.itemsTotal.toString()))
                add(relativeDate(context, row.finishedAt ?: row.createdAt))
            }
            Text(detail.joinToString(" · "), style = HfpsTheme.typography.ui, color = stateTint(t.state).takeIf { t.state == TransferStates.FAILED } ?: HfpsTheme.colors.mutedForeground)
            when {
                row.error == dev.hfps.hoard.app.transfers.INTERRUPTED -> Text(stringResource(R.string.t_transfers_interrupted), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                t.state == TransferStates.FAILED && t.errorCode != null ->
                    Text(stringResource(problemText(ErrorCode.fromName(t.errorCode))), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive)
            }
            if (t.itemsFailed > 0) {
                if (t.state == TransferStates.COMPLETED) {
                    Text(pluralStringResource(R.plurals.t_transfers_some_failed, t.itemsFailed, t.itemsFailed.toString()), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning)
                }
                GhostButton(
                    if (showFailed) stringResource(R.string.t_transfers_hide_failed) else pluralStringResource(R.plurals.t_transfers_show_failed, t.itemsFailed, t.itemsFailed.toString()),
                    { showFailed = !showFailed },
                )
                if (showFailed) FailedItems(service, row.id)
            }
        }
    }
}

@Composable
private fun FailedItems(service: TransferService, id: String) {
    val job = remember(id) { service.job(id) }
    if (job == null) {
        Text(stringResource(R.string.t_transfers_details_gone), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        return
    }
    val failed = remember(job) { synchronized(job) { job.items.filter { !it.superseded && it.state == TransferStates.FAILED }.map { it.sourcePath to it.errorCode } } }
    Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        failed.take(20).forEach { (path, code) ->
            Text(
                path.substringAfterLast('/') + " · " + stringResource(problemText(code ?: ErrorCode.UNKNOWN)),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (failed.size > 20) Text(pluralStringResource(R.plurals.t_transfers_more_failed, failed.size - 20, (failed.size - 20).toString()), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
    }
}

@Composable
private fun stateTint(state: String) = when (state) {
    TransferStates.FAILED -> HfpsTheme.colors.destructive
    TransferStates.CONFLICT, TransferStates.RETRYING -> HfpsTheme.colors.warning
    TransferStates.CANCELLED, TransferStates.SKIPPED -> HfpsTheme.colors.mutedForeground
    else -> HfpsTheme.colors.primary
}
