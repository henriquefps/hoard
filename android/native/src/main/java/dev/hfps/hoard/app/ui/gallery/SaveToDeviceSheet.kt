package dev.hfps.hoard.app.ui.gallery

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.gallery.GalleryEntry
import dev.hfps.hoard.app.gallery.SaveToDevice
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.DEVICE_LOCATION_ID
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.VOLUME_PREFIX
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.transfers.DestinationPicker
import dev.hfps.hoard.app.ui.transfers.TransferIntent
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.smb.location.StorageAccess
import kotlinx.coroutines.launch

/**
 * "Save to this device" (ADR 0024 A2): copies whole shots of server items into a folder of this device through the
 * transfer engine (progress, retries, Cancel and conflicts in Transfers, ADR 0007). The folder is chosen once (kept in
 * settings) and defaults to `Pictures/From <device name>/`, never Hoard's copies for Reveal.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SaveToDeviceSheet(container: AppContainer, entries: List<GalleryEntry>, onDismiss: () -> Unit, onSaved: () -> Unit = {}) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val saves = container.serverSaves
    val settings by container.appSettings.files.collectAsState(initial = null)
    var items by remember { mutableStateOf<List<Item>?>(null) }
    var chosen by remember { mutableStateOf<FolderPlace?>(null) }
    var chosenLabel by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var allFiles by remember { mutableStateOf(StorageAccess.granted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { allFiles = StorageAccess.granted(context) }

    LaunchedEffect(entries) {
        items = saves.items(entries)
        chosen = saves.chosen()
        chosenLabel = chosen?.path
    }

    val list = items
    val devices = list.orEmpty().map { SaveToDevice.deviceOf(it) }.distinct()
    val account by remember { container.gallery.account }.collectAsState(initial = null)
    val defaultName = account?.serverName
    val folderText = when {
        chosen != null -> chosen!!.path.ifEmpty { chosenLabel ?: "/" }
        devices.size <= 1 -> SaveToDevice.defaultFolder(devices.firstOrNull() ?: defaultName).path
        else -> stringResource(R.string.save_device_default_many)
    }
    val local = chosen?.place?.locationId.let { it == null || it == DEVICE_LOCATION_ID || it.startsWith(VOLUME_PREFIX) }

    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("save-device-sheet")) {
        SaveToDeviceContent(
            shots = list?.size,
            folder = folderText,
            chosen = chosen != null,
            needsAllFiles = local && !allFiles,
            problem = problem,
            busy = busy,
            onChange = { picking = true },
            onReset = {
                chosen = null
                scope.launch { saves.choose(null, "") }
            },
            onAllow = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val forApp = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    runCatching { context.startActivity(forApp) }.onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
                }
            },
            onCancel = onDismiss,
            onSave = save@{
                val shots = list ?: return@save
                busy = true
                scope.launch {
                    try {
                        val files = saves.save(shots, chosen, settings?.defaultConflict ?: "ask")
                        toaster.show(res.getQuantityString(R.plurals.save_device_started, files, files.toString(), folderText))
                        onSaved()
                        onDismiss()
                    } catch (e: Throwable) {
                        problem = res.getString(R.string.save_device_failed, res.getString(errorTitle(errorCodeOf(e))))
                    } finally {
                        busy = false
                    }
                }
            },
        )
    }
    if (picking) {
        DestinationPicker(
            intent = TransferIntent.Save,
            count = list?.size ?: 0,
            source = null,
            entries = emptyList(),
            onDismiss = { picking = false },
            onPick = { folder, _, label ->
                if (SaveToDevice.refused(folder.path)) {
                    problem = res.getString(R.string.save_device_refused)
                } else {
                    problem = null
                    chosen = folder
                    chosenLabel = label
                    saves.choose(folder, label)
                }
                picking = false
            },
            titleText = stringResource(R.string.save_device_folder),
            actionText = stringResource(R.string.t_cull_copy_use_folder),
        )
    }
}

/** The sheet's content (stateless, for goldens): what is saved, where, and Save. */
@Composable
fun SaveToDeviceContent(
    shots: Int?,
    folder: String,
    chosen: Boolean,
    needsAllFiles: Boolean,
    problem: String?,
    busy: Boolean,
    onChange: () -> Unit,
    onReset: () -> Unit,
    onAllow: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Text(stringResource(R.string.save_device), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
        when {
            shots == null -> Unit
            shots == 0 -> Text(stringResource(R.string.save_device_nothing), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 8.dp))
            else -> Text(
                pluralStringResource(R.plurals.save_device_shots, shots, shots.toString()),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Eyebrow(stringResource(R.string.save_device_folder), Modifier.padding(top = 16.dp, bottom = 4.dp))
        HfpsListRow(title = folder, icon = PhosphorRegular.FolderSimple, modifier = Modifier.testTag("save-device-folder"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton(stringResource(R.string.save_device_change), onChange, modifier = Modifier.testTag("save-device-change"))
            if (chosen) GhostButton(stringResource(R.string.save_device_reset), onReset)
        }
        if (needsAllFiles) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.save_device_all_files), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning, modifier = Modifier.weight(1f).padding(end = 8.dp))
                SecondaryButton(stringResource(R.string.save_device_allow), onAllow)
            }
        }
        problem?.let { Text(it, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GhostButton(stringResource(android.R.string.cancel), onCancel)
            PrimaryButton(
                stringResource(R.string.save_device_action),
                onSave,
                enabled = !busy && (shots ?: 0) > 0 && !needsAllFiles,
                modifier = Modifier.testTag("save-device-save"),
            )
        }
    }
}
