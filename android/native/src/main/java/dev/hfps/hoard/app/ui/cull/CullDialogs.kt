package dev.hfps.hoard.app.ui.cull

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.cull.BURST_GAPS_MS
import dev.hfps.hoard.app.cull.CopyFiles
import dev.hfps.hoard.app.cull.CopyFolder
import dev.hfps.hoard.app.cull.CopyPicksOptions
import dev.hfps.hoard.app.cull.CullView
import dev.hfps.hoard.app.cull.CullViewModel
import dev.hfps.hoard.app.cull.Sensitivity
import dev.hfps.hoard.app.cull.copySidecars
import dev.hfps.hoard.app.cull.copyTargetPath
import dev.hfps.hoard.app.cull.files
import dev.hfps.hoard.app.cull.planCopy
import dev.hfps.hoard.app.cull.planDeleteRejects
import dev.hfps.hoard.app.data.CullSettings
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.files.FileService
import dev.hfps.hoard.app.files.LocationKind
import dev.hfps.hoard.app.files.isValidName
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.transfers.FolderPlace
import dev.hfps.hoard.app.transfers.PickedEntry
import dev.hfps.hoard.app.transfers.TREE_LOCATION_ID
import dev.hfps.hoard.app.transfers.TransferPlace
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.fieldColors
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.app.ui.transfers.DestinationPicker
import dev.hfps.hoard.app.ui.transfers.TransferIntent
import dev.hfps.hoard.app.ui.transfers.rememberStartTransfer
import dev.hfps.hoard.data.CullDestinationEntity
import dev.hfps.hoard.data.legacy.isoNow
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import dev.hfps.hoard.designsystem.components.HfpsChip
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

// --- Assist settings ----------------------------------------------------------------------------------------------

/** Blur and eyes sensitivity, the burst gap and the sidecars (the culling page's sheet and Settings › Culling). */
@Composable
fun AssistSettingsFields(container: AppContainer) {
    val settings by container.appSettings.cull.collectAsState(initial = CullSettings())
    val scope = rememberCoroutineScope()
    val s = container.appSettings
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SensitivityField(stringResource(R.string.t_cull_assist_blur_setting), stringResource(R.string.t_cull_assist_blur_hint), settings.blur, "assist-blur") { scope.launch { s.setCullBlur(it) } }
        SensitivityField(stringResource(R.string.t_cull_assist_eyes_setting), stringResource(R.string.t_cull_assist_eyes_hint), settings.eyes, "assist-eyes") { scope.launch { s.setCullEyes(it) } }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.t_cull_assist_burst_gap), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.cardForeground)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BURST_GAPS_MS.forEach { ms ->
                    HfpsChip(
                        label = stringResource(R.string.t_cull_assist_seconds, formatNumber(ms / 1000.0, locale())),
                        selected = settings.burstGapMs == ms,
                        onClick = { scope.launch { s.setCullBurstGap(ms) } },
                    )
                }
            }
            Text(stringResource(R.string.t_cull_assist_burst_gap_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        SwitchField(stringResource(R.string.t_cull_xmp_save), stringResource(R.string.t_cull_xmp_save_hint), settings.saveXmp, "cull-save-xmp") { scope.launch { s.setCullSaveXmp(it) } }
        SwitchField(stringResource(R.string.t_cull_xmp_hidden), stringResource(R.string.t_cull_xmp_hidden_hint), settings.xmpHidden, "cull-xmp-hidden", enabled = settings.saveXmp) { scope.launch { s.setCullXmpHidden(it) } }
    }
}

@Composable
private fun SensitivityField(label: String, hint: String, value: Sensitivity, tag: String, onChange: (Sensitivity) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag(tag)) {
        Text(label, style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.cardForeground)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sensitivity.entries.forEach { s -> HfpsChip(stringResource(sensitivityText(s)), value == s, { onChange(s) }) }
        }
        Text(hint, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
    }
}

@Composable
fun SwitchField(label: String, hint: String?, checked: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled) { onChange(!checked) }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = HfpsTheme.typography.uiMedium, color = if (enabled) HfpsTheme.colors.cardForeground else HfpsTheme.colors.mutedForeground)
            if (hint != null) Text(hint, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary, checkedThumbColor = HfpsTheme.colors.primaryForeground),
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AssistSettingsSheet(container: AppContainer, onDismiss: () -> Unit) {
    HfpsBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.t_cull_assist_settings_title), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
            Text(stringResource(R.string.t_cull_assist_settings_hint), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            AssistSettingsFields(container)
            PrimaryButton(stringResource(R.string.t_common_done), onDismiss, Modifier.fillMaxWidth())
        }
    }
}

// --- Copy picks ---------------------------------------------------------------------------------------------------

/** A chosen destination of "Copy picks" (remembered per source location in `cull_destinations`). */
private data class CopyDestination(val place: TransferPlace, val path: String, val label: String)

/**
 * "Copy picks to…" (ADR 0008, CopyPicksDialog.tsx): where (any location, through the transfer engine's destination
 * picker; the last destination of this source is remembered), which files of a pair, and the folder name. With "Save
 * ratings next to photos" on, each copied shot's sidecar carries its stars and pick at the destination (ADR 0011).
 * The copy is one job of the one transfer engine (copyPicks.ts): its progress, conflicts and retries are the engine's.
 */
@Composable
fun CopyPicksDialog(container: AppContainer, vm: CullViewModel, view: CullView, location: BrowseLocation, onDismiss: () -> Unit, onTransfers: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val start = rememberStartTransfer(onTransfers)
    val sourcePath = vm.key.path
    var destination by remember { mutableStateOf<CopyDestination?>(null) }
    var conflict by remember { mutableStateOf<String?>(null) }
    var options by remember { mutableStateOf(CopyPicksOptions()) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val row = container.database.cull().destination(vm.key.locationId, vm.key.share) ?: return@LaunchedEffect
        val saved = runCatching { JSONObject(row.options) }.getOrNull() ?: JSONObject()
        destination = CopyDestination(TransferPlace(row.locationId, row.share.ifEmpty { null }, saved.optString("treeUri").ifEmpty { null }), row.path, row.label ?: row.path)
        conflict = saved.optString("conflict").ifEmpty { null }
        options = CopyPicksOptions(
            files = CopyFiles.entries.firstOrNull { it.id == saved.optString("files") } ?: CopyFiles.Both,
            folder = CopyFolder.entries.firstOrNull { it.id == saved.optString("folder") } ?: CopyFolder.Keep,
            subfolder = saved.optString("subfolder").ifEmpty { null },
        )
    }

    val plan = remember(view.core.shots, view.core.decisions, options.files) { planCopy(view.core.shots, view.core.decisions, options.files) }
    val isTree = destination?.place?.locationId == TREE_LOCATION_ID
    val effective = if (isTree) options.copy(folder = CopyFolder.None) else options
    val targetPath = destination?.let { copyTargetPath(it.path, sourcePath, effective) }.orEmpty()
    val sourceName = sourcePath.split('/').lastOrNull { it.isNotEmpty() }
    val subfolder = when (effective.folder) {
        CopyFolder.Keep -> sourceName
        CopyFolder.New -> effective.subfolder?.trim()
        CopyFolder.None -> null
    }
    val subfolderInvalid = effective.folder == CopyFolder.New && !isValidName(options.subfolder.orEmpty().trim())

    fun run() {
        val dest = destination ?: return
        busy = true
        scope.launch {
            try {
                val policy = conflict ?: container.appSettings.files.first().defaultConflict
                container.database.cull().putDestination(
                    CullDestinationEntity(
                        sourceLocationId = vm.key.locationId,
                        sourceShare = vm.key.share,
                        locationId = dest.place.locationId,
                        share = dest.place.share.orEmpty(),
                        path = dest.path,
                        label = dest.label,
                        options = JSONObject().apply {
                            put("files", options.files.id)
                            put("folder", options.folder.id)
                            options.subfolder?.let { put("subfolder", it) }
                            dest.place.treeUri?.let { put("treeUri", it) }
                            put("conflict", policy)
                        }.toString(),
                        updatedAt = isoNow(),
                    ),
                )
                var path = dest.path
                val sub = subfolder?.trim()?.takeIf { it.isNotEmpty() }
                if (sub != null && !isTree) {
                    // The engine creates the folders it copies, not the one it copies into.
                    val target = destinationLocation(container, dest.place) ?: throw dev.hfps.hoard.smb.HoardSmbException(ErrorCode.NOT_FOUND, "Destination not found")
                    try {
                        container.files.run(target) { fs, _ -> fs.createFolder(dest.path, sub) }
                    } catch (e: Throwable) {
                        if (errorCodeOf(e) != ErrorCode.ALREADY_EXISTS) throw e
                    }
                    path = if (dest.path.isEmpty()) sub else "${dest.path}/$sub"
                }
                val settings = container.appSettings.cull.first()
                val sidecars = copySidecars(plan, view.core.shots, view.core.decisions, settings.saveXmp)
                val entries = plan.items.map { PickedEntry(it.file.path, it.file.name, false, it.file.size) }
                val label = if (sub != null) "${dest.label} › $sub" else dest.label
                val sourcePlace = TransferPlace(vm.key.locationId, vm.key.share.ifEmpty { null })
                if (start("copy", sourcePlace, entries, FolderPlace(dest.place, path), label, policy, sidecars, settings.saveXmp && settings.xmpHidden)) onDismiss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.show(res.getString(R.string.t_transfer_start_failed) + "\n" + res.getString(errorTitle(errorCodeOf(e))), long = true)
            } finally {
                busy = false
            }
        }
    }

    if (picking) {
        DestinationPicker(
            intent = TransferIntent.Copy,
            count = plan.items.size,
            source = FolderPlace(TransferPlace(vm.key.locationId, vm.key.share.ifEmpty { null }), sourcePath),
            entries = plan.items.map { PickedEntry(it.file.path, it.file.name, false, it.file.size) },
            onDismiss = { picking = false },
            onPick = { place, label, policy ->
                destination = CopyDestination(place.place, place.path, label)
                conflict = policy
                picking = false
            },
            titleText = stringResource(R.string.t_cull_copy_destination),
            actionText = stringResource(R.string.t_cull_copy_use_folder),
        )
        return
    }

    val loc = locale()
    HoardDialog(
        title = stringResource(R.string.t_cull_copy_title),
        onDismiss = { if (!busy) onDismiss() },
        confirm = {
            PrimaryButton(
                pluralStringResource(R.plurals.t_cull_copy_start, plan.items.size, plan.items.size.toString()),
                ::run,
                Modifier.testTag("copy-start"),
                enabled = destination != null && plan.items.isNotEmpty() && !subfolderInvalid && !busy,
            )
        },
        dismiss = { SecondaryButton(stringResource(R.string.t_common_cancel), onDismiss, enabled = !busy) },
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                pluralStringResource(R.plurals.t_cull_copy_summary, plan.items.size, formatNumber(plan.shots, loc), formatNumber(plan.items.size, loc), bytes(plan.bytes)),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
            Eyebrow(stringResource(R.string.t_cull_copy_destination))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { picking = true }.padding(vertical = 8.dp).testTag("copy-destination"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(PhosphorRegular.FolderSimple, null, Modifier.size(22.dp), tint = HfpsTheme.colors.mutedForeground)
                Column(Modifier.weight(1f)) {
                    Text(destination?.label ?: stringResource(R.string.t_cull_copy_choose_destination), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.cardForeground)
                    if (destination != null) Text("/$targetPath", style = HfpsTheme.typography.monoSmall, color = HfpsTheme.colors.mutedForeground)
                }
                Text(stringResource(R.string.t_cull_copy_change), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.primary)
            }
            Eyebrow(stringResource(R.string.t_cull_copy_files))
            Column {
                CopyFiles.entries.forEach { f ->
                    HfpsChoiceRow(
                        stringResource(
                            when (f) {
                                CopyFiles.Both -> R.string.t_cull_copy_files_both
                                CopyFiles.Raw -> R.string.t_cull_copy_files_raw
                                CopyFiles.Jpeg -> R.string.t_cull_copy_files_jpeg
                            },
                        ),
                        options.files == f,
                        { options = options.copy(files = f) },
                    )
                }
                if (plan.fallbacks > 0) {
                    Text(
                        pluralStringResource(if (options.files == CopyFiles.Raw) R.plurals.t_cull_copy_fallback_raw else R.plurals.t_cull_copy_fallback_jpeg, plan.fallbacks, plan.fallbacks.toString()),
                        style = HfpsTheme.typography.ui,
                        color = HfpsTheme.colors.mutedForeground,
                    )
                }
            }
            Eyebrow(stringResource(R.string.t_cull_copy_folder))
            Column {
                CopyFolder.entries.forEach { f ->
                    HfpsChoiceRow(
                        when (f) {
                            CopyFolder.Keep -> stringResource(R.string.t_cull_copy_folder_keep, sourceName ?: "/")
                            CopyFolder.New -> stringResource(R.string.t_cull_copy_folder_new)
                            CopyFolder.None -> stringResource(R.string.t_cull_copy_folder_none)
                        },
                        effective.folder == f,
                        { if (!isTree) options = options.copy(folder = f) },
                    )
                }
                if (options.folder == CopyFolder.New && !isTree) {
                    OutlinedTextField(
                        value = options.subfolder.orEmpty(),
                        onValueChange = { options = options.copy(subfolder = it) },
                        label = { Text(stringResource(R.string.t_cull_copy_subfolder_name)) },
                        singleLine = true,
                        isError = subfolderInvalid && options.subfolder.orEmpty().isNotEmpty(),
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val xmp by container.appSettings.cull.collectAsState(initial = CullSettings())
            if (xmp.saveXmp) Text(stringResource(R.string.t_cull_copy_xmp_note), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** The location a destination place names (for making the subfolder). */
private suspend fun destinationLocation(container: AppContainer, place: TransferPlace): BrowseLocation? {
    val machines = container.database.machines().observeAll().first()
    val volumes = container.volumes.observe().first()
    return FileService.resolve(place.locationId, place.share, machines, volumes, "", "")
}

// --- Delete rejects -----------------------------------------------------------------------------------------------

/**
 * Deletes every file of every rejected shot after a confirmation that says how many, how much and where, and that it
 * is permanent (shares and drives have no trash). Stops at the first refusal and says why.
 */
@Composable
fun DeleteRejectsDialog(vm: CullViewModel, view: CullView, location: BrowseLocation, onDismiss: () -> Unit) {
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val plan = remember(view.core.shots, view.core.decisions) { planDeleteRejects(view.core.shots, view.core.decisions) }
    val loc = locale()
    val where = when (location.kind) {
        LocationKind.Smb -> stringResource(R.string.t_cull_delete_where_smb, location.name)
        LocationKind.Volume -> stringResource(R.string.t_cull_delete_where_drive, location.name)
        LocationKind.Device -> stringResource(R.string.t_cull_delete_where_phone)
    }
    ConfirmDialog(
        title = pluralStringResource(R.plurals.t_cull_delete_title, plan.shots.size, plan.shots.size.toString()),
        confirmLabel = pluralStringResource(R.plurals.t_cull_delete_confirm, plan.files.size, plan.files.size.toString()),
        destructive = true,
        onDismiss = onDismiss,
        body = pluralStringResource(R.plurals.t_cull_delete_body, plan.files.size, formatNumber(plan.shots.size, loc), formatNumber(plan.files.size, loc), bytes(plan.bytes), where),
        onConfirm = {
            val deleted = HashSet<String>()
            var error: Throwable? = null
            for (file in plan.files) {
                try {
                    vm.deleteFile(file.path)
                    deleted += file.path
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (errorCodeOf(e) == ErrorCode.NOT_FOUND) {
                        deleted += file.path
                        continue
                    }
                    error = e
                    break
                }
            }
            // A shot is gone when every one of its files is (a pair half-deleted stays, with what is left).
            val gone = plan.shots.filter { id -> view.byId[id]?.files?.all { it.path in deleted } == true }
            vm.removeShots(gone)
            if (error != null) {
                val code = errorCodeOf(error)
                toaster.show(
                    (if (code == ErrorCode.ACCESS_DENIED) res.getString(R.string.t_cull_delete_refused) else res.getString(errorTitle(code))) + "\n" +
                        res.getQuantityString(R.plurals.t_cull_delete_partial, gone.size, gone.size.toString()),
                    long = true,
                )
            } else {
                toaster.show(res.getQuantityString(R.plurals.t_cull_delete_done, gone.size, gone.size.toString()))
            }
        },
    ) {
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.t_cull_delete_permanent), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.destructive)
    }
}
