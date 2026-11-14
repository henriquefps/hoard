package dev.hfps.hoard.app.ui.save

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.fileKind
import dev.hfps.hoard.app.transfers.INCOMING_LOCATION_ID
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.ui.common.FileTile
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.transfers.DestinationPicker
import dev.hfps.hoard.app.ui.transfers.TransferIntent
import dev.hfps.hoard.app.ui.transfers.rememberStartTransfer
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.share.IncomingShare
import dev.hfps.hoard.smb.share.IncomingShares

/**
 * "Save to Hoard" (ADR 0007, share in): the files another app shared, then where to save them, through the transfer
 * engine (source `incoming`, paths are the items' indexes). "Don't save" forgets them.
 */
@Composable
fun SaveScreen(onBack: () -> Unit, onTransfers: () -> Unit) {
    var share by remember { mutableStateOf(IncomingShares.latest()) }
    DisposableEffect(Unit) {
        val listener: (IncomingShare) -> Unit = { share = it }
        IncomingShares.addListener(listener)
        onDispose { IncomingShares.removeListener(listener) }
    }
    var picking by remember { mutableStateOf(false) }
    val start = rememberStartTransfer(onTransfers)
    val current = share
    HfpsScaffold(topBar = { HfpsTopBar(stringResource(R.string.t_save_title), onBack = onBack) }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            if (current == null || (current.items.isEmpty() && current.text == null && current.rejected == 0)) {
                StateMessage(PhosphorRegular.DownloadSimple, stringResource(R.string.t_save_empty_title), body = stringResource(R.string.t_save_empty_body))
                return@Box
            }
            Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.t_save_description), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
                            if (current.items.isNotEmpty()) {
                                val known = current.items.filter { it.size >= 0 }.sumOf { it.size }
                                Text(
                                    pluralStringResource(R.plurals.t_save_summary, current.items.size, current.items.size.toString(), bytes(known)),
                                    style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                                    color = HfpsTheme.colors.cardForeground,
                                )
                            }
                            if (current.rejected > 0) {
                                Text(
                                    if (current.items.isEmpty()) pluralStringResource(R.plurals.t_save_all_rejected, current.rejected, current.rejected.toString())
                                    else pluralStringResource(R.plurals.t_save_rejected, current.rejected, current.rejected.toString()) + "\n" + stringResource(R.string.t_save_rejected_hint),
                                    style = HfpsTheme.typography.ui,
                                    color = HfpsTheme.colors.warning,
                                )
                            }
                            if (current.items.isEmpty() && current.text != null) {
                                Text(stringResource(R.string.t_save_text_only), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                            }
                        }
                    }
                    items(current.items, key = { it.index }) { item ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            FileTile(fileKind(item.name, false), null, null)
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.size >= 0) bytes(item.size) else stringResource(R.string.t_save_unknown_size), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                            }
                        }
                        HfpsRowDivider(Modifier.padding(start = 68.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(stringResource(R.string.t_save_choose), { picking = true }, icon = PhosphorRegular.FolderSimple, enabled = current.items.isNotEmpty(), modifier = Modifier.fillMaxWidth())
                    GhostButton(stringResource(R.string.t_save_dismiss), {
                        IncomingShares.clear(current.shareId)
                        share = null
                        onBack()
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (picking && current != null) {
        val entries = current.items.map { PickedEntry(it.index.toString(), it.name, false, it.size.coerceAtLeast(0)) }
        DestinationPicker(
            intent = TransferIntent.Save,
            count = entries.size,
            source = null,
            entries = entries,
            onDismiss = { picking = false },
            onPick = { destination, label, conflict ->
                if (start("copy", TransferPlace(INCOMING_LOCATION_ID, shareId = current.shareId), entries, destination, label, conflict)) {
                    picking = false
                    IncomingShares.clear(current.shareId)
                    share = null
                    onBack()
                }
            },
        )
    }
}
