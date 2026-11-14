package dev.hfps.hoard.app.ui.transfers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.app.transfers.RecentDestination
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.transfers.rememberDestination
import dev.hfps.hoard.app.transfers.transferRequest
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.Toaster
import dev.hfps.hoard.app.ui.common.problemText
import kotlinx.coroutines.flow.first

/**
 * Starts a transfer from the UI (picker, clipboard, Save to…): records the row and starts the job through the one
 * engine, remembers the destination as recent, and says so with a "View" action (useStartTransfer.ts). Returns false
 * after its own error message, so callers can stay open.
 */
class StartTransfer(private val container: AppContainer, private val context: Context, private val toaster: Toaster, private val onView: () -> Unit) {
    private val res get() = context.resources

    suspend operator fun invoke(
        op: String,
        source: TransferPlace,
        entries: List<PickedEntry>,
        destination: FolderPlace,
        label: String,
        conflict: String,
        sidecars: Map<String, dev.hfps.hoard.xmp.XmpFields> = emptyMap(),
        hiddenSidecars: Boolean = false,
    ): Boolean {
        NotificationsPermission.askOnce()
        try {
            container.transfers.transfer(transferRequest(op, source, entries, destination, conflict).copy(sidecars = sidecars, hiddenSidecars = hiddenSidecars))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            toaster.show(res.getString(R.string.t_transfer_start_failed) + "\n" + res.getString(problemText(errorCodeOf(e))), long = true)
            return false
        }
        runCatching {
            val recent = container.appSettings.files.first().recentDestinations
            container.appSettings.setRecentDestinations(
                rememberDestination(recent, RecentDestination(destination.place.locationId, destination.place.share, destination.path, label, destination.place.treeUri)),
            )
        }
        val text = res.getQuantityString(
            if (op == "move") R.plurals.t_transfer_started_move else R.plurals.t_transfer_started_copy,
            entries.size,
            entries.size.toString(),
            label,
        )
        toaster.show(text, action = res.getString(R.string.t_transfers_view), onAction = onView)
        return true
    }
}

@Composable
fun rememberStartTransfer(onView: () -> Unit): StartTransfer {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val container = (context.applicationContext as HoardApplication).container
    return remember(container, toaster) { StartTransfer(container, context, toaster, onView) }
}

/**
 * Android 13+'s notification permission, asked once per app run before work that runs as a foreground service
 * (transfers, streams), as Hoard 1 did. The work runs whatever the answer: without it there is no notification.
 */
object NotificationsPermission {
    @Volatile
    var ask: (() -> Unit)? = null
    private var asked = false

    fun askOnce() {
        if (asked) return
        asked = true
        ask?.invoke()
    }
}
