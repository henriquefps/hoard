package dev.hfps.hoard.app.ui.reveal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileActions
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.ui.browse.findActivity
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.transfers.NotificationsPermission
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.launch

/** Batches above this many files ask first (Reveal lists them all in one hand-off). */
const val REVEAL_CONFIRM_ABOVE = 200

/** Ask before sending: big batches, and SMB once (the photos are read through Hoard while the machine is reachable). */
fun needsRevealConfirm(count: Int, smb: Boolean) = smb || count > REVEAL_CONFIRM_ABOVE

private data class RevealRequest(val location: BrowseLocation, val folderPath: String, val paths: List<String>, val title: String?, val initialPath: String?)

/** "Edit in Reveal" for a screen (RevealProvider.tsx): availability, the one confirmation, the hand-off. */
class RevealController internal constructor(val available: Boolean, val edit: (BrowseLocation, String, List<String>, String?, String?) -> Unit, val Dialogs: @Composable () -> Unit)

@Composable
fun rememberReveal(actions: FileActions): RevealController {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as HoardApplication).container
    val settings by container.appSettings.files.collectAsState(initial = null)
    var available by remember { mutableStateOf(actions.revealAvailable()) }
    var pending by remember { mutableStateOf<RevealRequest?>(null) }
    // Installing or removing Reveal shows or hides the action when Hoard comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { available = actions.revealAvailable() }
    LaunchedEffect(Unit) { available = actions.revealAvailable() }

    fun send(request: RevealRequest) {
        val activity = context.findActivity() ?: return
        if (request.location.isSmb) NotificationsPermission.askOnce()
        scope.launch {
            try {
                actions.editInReveal(activity, request.location, request.folderPath, request.paths, request.title, request.initialPath)
            } catch (e: Throwable) {
                when (val code = errorCodeOf(e)) {
                    ErrorCode.CANCELLED -> Unit
                    ErrorCode.NO_APP -> {
                        available = false
                        toaster.show(res.getString(R.string.t_reveal_not_installed))
                    }
                    else -> toaster.show(res.getString(R.string.t_reveal_failed) + "\n" + res.getString(errorTitle(code)))
                }
            }
        }
    }

    val edit: (BrowseLocation, String, List<String>, String?, String?) -> Unit = { location, folderPath, paths, title, initial ->
        if (paths.isNotEmpty()) {
            val request = RevealRequest(location, folderPath, paths, title, initial)
            val smb = location.isSmb && settings?.revealStreamNoteSeen != true
            if (needsRevealConfirm(paths.size, smb)) pending = request else send(request)
        }
    }

    val dialogs: @Composable () -> Unit = {
        val request = pending
        if (request != null) {
            var dontExplain by remember { mutableStateOf(false) }
            val count = request.paths.size
            val smb = request.location.isSmb && settings?.revealStreamNoteSeen != true
            ConfirmDialog(
                title = pluralStringResource(R.plurals.t_reveal_confirm_title, count, count.toString()),
                body = listOfNotNull(
                    if (count > REVEAL_CONFIRM_ABOVE) stringResource(R.string.t_reveal_confirm_many, count.toString()) else null,
                    if (smb) stringResource(R.string.t_reveal_confirm_stream) else null,
                ).joinToString("\n\n"),
                confirmLabel = stringResource(R.string.t_reveal_confirm_action),
                onDismiss = { pending = null },
                onConfirm = {
                    if (smb && dontExplain) container.appSettings.setRevealStreamNoteSeen(true)
                    send(request)
                },
            ) {
                if (smb) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Checkbox(dontExplain, { dontExplain = it }, colors = CheckboxDefaults.colors(checkedColor = HfpsTheme.colors.primary))
                        Text(stringResource(R.string.t_reveal_confirm_dont_explain), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
                    }
                }
            }
        }
    }
    return RevealController(available, edit, dialogs)
}
