package dev.hfps.hoard.app.ui.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.xmp.XmpExport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** One "Export XMP" request: the shots of [paths] (null: every photo of [folderPath]) in [place]. */
data class XmpExportRequest(val place: TransferPlace, val folderPath: String, val paths: List<String>?)

/** "Export XMP" for a screen: [export] runs it, [Dialogs] shows its progress and the read-only fallback. */
class XmpExportController internal constructor(val export: (XmpExportRequest) -> Unit, val Dialogs: @Composable () -> Unit)

/**
 * "Export XMP" (ADR 0011 amendment "2.2.0: export on demand"): writes the sidecars next to the photos; when the
 * location refuses (read-only), asks for a folder (SAF) and writes them there. A summary toast at the end.
 */
@Composable
fun rememberXmpExport(): XmpExportController {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val container = (context.applicationContext as HoardApplication).container
    var running by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var readOnly by remember { mutableStateOf<XmpExportRequest?>(null) }
    var waitingForFolder by remember { mutableStateOf<XmpExportRequest?>(null) }

    fun report(result: XmpExport.Result) {
        val parts = ArrayList<String>()
        parts += res.getQuantityString(R.plurals.xmp_export_done, result.written, result.written.toString())
        if (result.malformed > 0) parts += res.getQuantityString(R.plurals.xmp_export_malformed, result.malformed, result.malformed.toString())
        if (result.failed > 0) parts += res.getQuantityString(R.plurals.xmp_export_failed, result.failed, result.failed.toString())
        toaster.show(parts.joinToString("\n"), long = result.malformed > 0 || result.failed > 0)
    }

    fun run(request: XmpExportRequest, tree: Uri?) {
        if (running?.isActive == true) return
        running = scope.launch {
            try {
                val hidden = container.appSettings.cull.first().xmpHidden
                val result = container.xmpExporter.export(request.place, request.folderPath, request.paths, hidden, tree?.toString())
                if (result.readOnly && tree == null) {
                    readOnly = request
                } else if (result.written == 0 && result.malformed == 0 && result.failed == 0 && !result.readOnly) {
                    toaster.show(res.getString(R.string.xmp_export_nothing))
                } else {
                    report(result)
                    if (result.readOnly) toaster.show(res.getString(R.string.xmp_export_folder_refused), long = true)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                val code = errorCodeOf(e)
                if (code == ErrorCode.ACCESS_DENIED && tree == null) {
                    readOnly = request
                } else if (code != ErrorCode.CANCELLED) {
                    toaster.show(res.getString(R.string.xmp_export_failed_all) + "\n" + res.getString(errorTitle(code)), long = true)
                }
            } finally {
                running = null
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val request = waitingForFolder
        waitingForFolder = null
        if (uri != null && request != null) run(request, uri)
    }

    val dialogs: @Composable () -> Unit = {
        if (running != null) {
            HoardDialog(
                title = stringResource(R.string.xmp_export_running),
                onDismiss = {},
                confirm = {
                    androidx.compose.material3.TextButton({ running?.cancel() }) {
                        Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.primary)
                    }
                },
            ) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).testTag("xmp-export-progress"),
                    color = HfpsTheme.colors.primary,
                    trackColor = HfpsTheme.colors.muted,
                )
            }
        }
        readOnly?.let { request ->
            ConfirmDialog(
                title = stringResource(R.string.xmp_export_read_only_title),
                body = stringResource(R.string.xmp_export_read_only_body),
                confirmLabel = stringResource(R.string.xmp_export_choose_folder),
                onDismiss = { readOnly = null },
                onConfirm = {
                    waitingForFolder = request
                    runCatching { picker.launch(null) }
                },
            )
        }
    }
    return XmpExportController({ run(it, null) }, dialogs)
}
