package dev.hfps.hoard.app.ui.reveal

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import dev.hfps.hoard.app.files.formatBytes
import dev.hfps.hoard.app.transfers.LiveTransfer
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.transfers.NotificationsPermission
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.share.RevealHandoff
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Batches above this many files ask first (Reveal lists them all in one hand-off). */
const val REVEAL_CONFIRM_ABOVE = 200

/** Ask before sending big batches (since 2.2.0 nothing streams through Hoard: no note for SMB any more). */
fun needsRevealConfirm(count: Int) = count > REVEAL_CONFIRM_ABOVE

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
    var available by remember { mutableStateOf(actions.revealAvailable()) }
    var pending by remember { mutableStateOf<RevealRequest?>(null) }
    // The copy before a hand-off (ADR 0010 "2.2.0: local copies"): its progress, and the job to cancel.
    var copying by remember { mutableStateOf<LiveTransfer?>(null) }
    var sending by remember { mutableStateOf<Job?>(null) }
    // A hand-off prepared while Hoard was in the background with notifications off: offered when Hoard returns.
    var ready by remember { mutableStateOf<RevealHandoff.Prepared?>(null) }
    // Installing or removing Reveal shows or hides the action when Hoard comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        available = actions.revealAvailable()
    }
    LaunchedEffect(Unit) {
        available = actions.revealAvailable()
    }

    fun send(request: RevealRequest) {
        val activity = context.findActivity() ?: return
        if (request.location.isSmb) NotificationsPermission.askOnce()
        if (sending?.isActive == true) return
        sending = scope.launch {
            try {
                actions.editInReveal(
                    activity, request.location, request.folderPath, request.paths, request.title, request.initialPath,
                    onCopyProgress = { copying = it },
                    deliver = { prepared ->
                        withContext(Dispatchers.Main) {
                            copying = null
                            // A long copy may end after the user left Hoard: Android 10+ blocks starting Reveal then.
                            val visible = (activity as? LifecycleOwner)?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) != false
                            when {
                                visible -> container.reveal.start(activity, prepared)
                                !RevealReady.post(context, prepared, request.title) -> ready = prepared
                            }
                        }
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                when (val code = errorCodeOf(e)) {
                    ErrorCode.CANCELLED -> Unit
                    ErrorCode.NO_APP -> {
                        available = false
                        toaster.show(res.getString(R.string.t_reveal_not_installed))
                    }
                    else -> toaster.show(res.getString(R.string.t_reveal_failed) + "\n" + res.getString(errorTitle(code)))
                }
            } finally {
                copying = null
            }
        }
    }

    val edit: (BrowseLocation, String, List<String>, String?, String?) -> Unit = { location, folderPath, paths, title, initial ->
        if (paths.isNotEmpty()) {
            val request = RevealRequest(location, folderPath, paths, title, initial)
            if (needsRevealConfirm(paths.size)) pending = request else send(request)
        }
    }

    val dialogs: @Composable () -> Unit = {
        val request = pending
        if (request != null) {
            val count = request.paths.size
            ConfirmDialog(
                title = pluralStringResource(R.plurals.t_reveal_confirm_title, count, count.toString()),
                body = stringResource(R.string.t_reveal_confirm_many, count.toString()),
                confirmLabel = stringResource(R.string.t_reveal_confirm_action),
                onDismiss = { pending = null },
                onConfirm = { send(request) },
            )
        }
        copying?.let { progress -> RevealCopyDialog(progress) { sending?.cancel() } }
        ready?.let { prepared ->
            ConfirmDialog(
                title = stringResource(R.string.reveal_ready_title),
                body = stringResource(R.string.reveal_ready_dialog_body),
                confirmLabel = stringResource(R.string.reveal_ready_open),
                onDismiss = { ready = null },
                onConfirm = {
                    val activity = context.findActivity()
                    if (activity != null) {
                        try {
                            container.reveal.start(activity, prepared)
                        } catch (e: Throwable) {
                            toaster.show(res.getString(R.string.t_reveal_failed) + "\n" + res.getString(errorTitle(errorCodeOf(e))))
                        }
                    }
                },
            )
        }
    }
    return RevealController(available, edit, dialogs)
}

/** "Copying for Reveal": the copy job's progress (files and bytes) and Cancel, which deletes the partial copies. */
@Composable
private fun RevealCopyDialog(progress: LiveTransfer, onCancel: () -> Unit) {
    val fraction = if (progress.totalBytes > 0) (progress.bytesDone.toFloat() / progress.totalBytes).coerceIn(0f, 1f) else 0f
    HoardDialog(
        title = stringResource(R.string.reveal_copy_title),
        onDismiss = {},
        confirm = {
            TextButton(onCancel, modifier = Modifier.testTag("reveal-copy-cancel")) {
                Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.primary)
            }
        },
    ) {
        Text(stringResource(R.string.reveal_copy_body), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).testTag("reveal-copy-progress"),
            color = HfpsTheme.colors.primary,
            trackColor = HfpsTheme.colors.muted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.reveal_copy_progress,
                (progress.itemsDone + progress.itemsSkipped).toString(),
                progress.itemsTotal.toString(),
                formatBytes(progress.bytesDone),
                formatBytes(progress.totalBytes),
            ),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
        )
    }
}
