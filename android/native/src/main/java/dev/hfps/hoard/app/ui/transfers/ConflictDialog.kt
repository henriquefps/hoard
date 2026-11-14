package dev.hfps.hoard.app.ui.transfers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.conflictHint
import dev.hfps.hoard.app.ui.common.conflictText
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.transfer.TransferItem
import dev.hfps.hoard.smb.transfer.TransferStates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Jobs whose question the user closed without answering ("Later"); the row's "Choose what to do…" reopens it. */
object DismissedConflicts {
    val ids = MutableStateFlow<Set<String>>(emptySet())
    fun dismiss(id: String) = ids.update { it + id }
    fun askAgain(id: String) = ids.update { it - id }
}

/** The one question for `conflict: ask`, mounted once in the shell: the first job waiting in `conflict`. */
@Composable
fun TransferConflicts() {
    val container = (LocalContext.current.applicationContext as HoardApplication).container
    val transfers by container.transfers.transfers.collectAsState(initial = emptyList())
    val dismissed by DismissedConflicts.ids.collectAsState()
    val waiting = transfers.firstOrNull { it.state == TransferStates.CONFLICT && it.row.id !in dismissed } ?: return
    androidx.compose.runtime.key(waiting.row.id) {
        ConflictDialog(waiting.row.id, onDismiss = { DismissedConflicts.dismiss(waiting.row.id) })
    }
}

/**
 * "“name” already exists": keep both, replace or skip, with "Do the same for the other N files" checked so a
 * conflict storm (a camera card copied again) is one tap (ADR 0007).
 */
@Composable
fun ConflictDialog(transferId: String, onDismiss: () -> Unit) {
    val container = (LocalContext.current.applicationContext as HoardApplication).container
    val service = container.transfers
    var round by remember { mutableIntStateOf(0) }
    var item by remember { mutableStateOf<TransferItem?>(null) }
    var left by remember { mutableIntStateOf(0) }
    var policy by remember { mutableStateOf("rename") }
    var all by remember { mutableStateOf(true) }
    val live by service.live.collectAsState()
    val conflicts = live[transferId]?.conflicts ?: 0
    LaunchedEffect(round, conflicts) {
        val job = service.job(transferId)
        val waiting = job?.let { j -> synchronized(j) { j.items.filter { !it.superseded && it.counts && it.conflict == "ask" && !it.isFinished } } }.orEmpty()
        item = waiting.firstOrNull()
        left = waiting.size
        if (job == null || (waiting.isEmpty() && job.state != TransferStates.CONFLICT)) onDismiss()
    }
    val current = item
    val name = current?.destPath?.substringAfterLast('/') ?: ""
    val folder = current?.destPath?.substringBeforeLast('/', "").orEmpty()
    HoardDialog(
        title = stringResource(R.string.t_transfer_conflict_dialog_title, name),
        onDismiss = onDismiss,
        confirm = {
            PrimaryButton(stringResource(R.string.t_transfer_conflict_dialog_continue), {
                val answered = current ?: return@PrimaryButton
                if (all || left <= 1) {
                    service.resolveConflicts(transferId, policy)
                    onDismiss()
                } else {
                    service.resolveConflicts(transferId, null, mapOf(answered.index to policy))
                    round++
                }
            }, enabled = current != null)
        },
        dismiss = { TextButton(onDismiss) { Text(stringResource(R.string.t_transfer_conflict_dialog_later), color = HfpsTheme.colors.foreground) } },
    ) {
        Text(
            if (folder.isNotEmpty()) stringResource(R.string.t_transfer_conflict_dialog_in_folder, folder) else stringResource(R.string.t_transfer_conflict_dialog_in_root),
            style = HfpsTheme.typography.body,
            color = HfpsTheme.colors.mutedForeground,
        )
        Column(Modifier.padding(top = 8.dp)) {
            listOf("rename", "overwrite", "skip").forEach { option ->
                Row(
                    Modifier.fillMaxWidth().clickable { policy = option }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RadioButton(policy == option, { policy = option }, colors = RadioButtonDefaults.colors(selectedColor = HfpsTheme.colors.primary))
                    Column {
                        Text(stringResource(conflictText(option)), style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
                        Text(stringResource(conflictHint(option)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    }
                }
            }
            if (left > 1) {
                Row(Modifier.fillMaxWidth().clickable { all = !all }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(all, { all = it }, colors = CheckboxDefaults.colors(checkedColor = HfpsTheme.colors.primary))
                    Text(pluralStringResource(R.plurals.t_transfer_conflict_dialog_apply_all, left - 1, (left - 1).toString()), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
                }
            }
        }
    }
}
