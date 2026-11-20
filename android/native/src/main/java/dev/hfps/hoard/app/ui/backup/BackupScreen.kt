package dev.hfps.hoard.app.ui.backup

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.backup.BackupFolders
import dev.hfps.hoard.app.backup.FolderLabel
import dev.hfps.hoard.app.backup.FolderRow
import dev.hfps.hoard.app.backup.PhoneBackup
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.fieldColors
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsChip
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.backup.AddRootResult
import dev.hfps.hoard.server.backup.BackupEngine
import dev.hfps.hoard.server.backup.BackupProgress
import dev.hfps.hoard.server.backup.BackupRules
import dev.hfps.hoard.server.backup.BackupState
import dev.hfps.hoard.server.backup.FolderStatus
import dev.hfps.hoard.server.db.BackupFileEntity
import dev.hfps.hoard.server.db.FreedTotal
import dev.hfps.hoard.server.backup.FreeUpRules
import dev.hfps.hoard.app.backup.PhoneFreeUp
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Whether the backup can be used here: paired with a server that has it. */
enum class BackupAvailability { Ready, NotPaired, OldServer }

/** Settings › Backup's state (ADR 0024 "Settings › Backup"). */
data class BackupUi(
    val availability: BackupAvailability = BackupAvailability.Ready,
    val folders: List<FolderRow> = emptyList(),
    val rules: BackupRules = BackupRules(),
    val allFiles: Boolean = false,
    val progress: BackupProgress = BackupProgress(),
    /** A switch being turned (its folder key): disabled until the server answers. */
    val busy: Set<String> = emptySet(),
    /** Now, for "last complete pass 5 minutes ago" (fixed in goldens). */
    val now: Long = System.currentTimeMillis(),
    /** Free up space is offered (Android 11+, ADR 0024 A3). */
    val freeUpSupported: Boolean = false,
    val freeUpRules: FreeUpRules = FreeUpRules(),
    /** What free up has freed so far ("18.4 GB freed"). */
    val freed: FreedTotal = FreedTotal(0, 0),
    /** Reveal is installed here but not grouped with Hoard as this phone (ADR 0024 "When only one app is installed"). */
    val revealNotGrouped: Boolean = false,
)

/** Settings › Backup bound to the app. */
@Composable
fun BackupScreen(container: AppContainer, onBack: () -> Unit, onOpenFolder: (String) -> Unit, onFreeUp: () -> Unit = {}) {
    val backup = container.backup
    val db = container.gallery.db
    val account by db.account().observe().collectAsState(initial = null)
    val folders by remember { combine(db.device().observeDirs(), db.backup().observeRoots(), db.backup().observeCounts()) { d, r, c -> BackupFolders.rows(d, r, c) } }.collectAsState(initial = emptyList())
    val rules by backup.observeRules.collectAsState(initial = BackupRules())
    val progress by backup.engine.progress.collectAsState()
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    var allFiles by remember { mutableStateOf(PhoneBackup.allFilesAccess(context)) }
    var busy by remember { mutableStateOf(emptySet<String>()) }
    var adding by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        val now = PhoneBackup.allFilesAccess(context)
        // All files access granted or revoked: the roots' types follow at the next run (ADR 0024).
        if (now != allFiles && folders.any { it.on }) backup.now()
        allFiles = now
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) { container.gallery.deviceIndex.scan() }
    val freeUpRules by container.freeUp.observeRules.collectAsState(initial = FreeUpRules())
    val freed by db.backup().observeFreedTotal().collectAsState(initial = FreedTotal(0, 0))
    var revealNotGrouped by remember { mutableStateOf(false) }
    LaunchedEffect(account?.serverId) {
        if (account == null) return@LaunchedEffect
        val installed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { container.reveal.isAvailable() }.getOrDefault(false) }
        if (!installed) return@LaunchedEffect
        val view = runCatching { container.gallery.sync.deviceView() }.getOrNull() ?: return@LaunchedEffect
        revealNotGrouped = BackupFolders.revealNotGrouped(installed, view.physicalDevice?.devices.orEmpty(), System.currentTimeMillis())
    }
    val a = account
    val availability = when {
        a == null -> BackupAvailability.NotPaired
        !a.has(dev.hfps.hoard.server.api.Features.BACKUP) -> BackupAvailability.OldServer
        else -> BackupAvailability.Ready
    }
    fun toggle(row: FolderRow, on: Boolean) {
        if (row.key in busy) return
        busy = busy + row.key
        scope.launch {
            try {
                if (on) {
                    val result = backup.add(row.locationId, row.folder)
                    message(result)?.let { toaster.show(res.getString(it.first, *it.second)) }
                } else {
                    row.rootId?.let { backup.remove(it) }
                }
            } finally {
                busy = busy - row.key
            }
        }
    }
    BackupContent(
        ui = BackupUi(
            availability, folders, rules, allFiles, progress, busy,
            freeUpSupported = PhoneFreeUp.supported,
            freeUpRules = freeUpRules,
            freed = freed,
            revealNotGrouped = revealNotGrouped,
        ),
        onBack = onBack,
        onToggle = ::toggle,
        onOpenFolder = onOpenFolder,
        onAddFolder = { adding = true },
        onRules = { r -> scope.launch { backup.setRules(r) } },
        onNow = {
            backup.now()
            toaster.show(res.getString(R.string.backup_now_started))
        },
        onAllFiles = { openAllFilesAccess(context) },
        onFreeUp = onFreeUp,
        onFreeUpRules = { r -> scope.launch { container.freeUp.setRules(r) } },
        onThisPhone = onBack,
    )
    if (adding) {
        var path by remember { mutableStateOf("") }
        HoardDialog(
            title = stringResource(R.string.backup_add_folder),
            onDismiss = { adding = false },
            confirm = {
                PrimaryButton(stringResource(R.string.backup_add_folder), {
                    val parsed = BackupFolders.parse(path, android.os.Environment.getExternalStorageDirectory().absolutePath)
                    adding = false
                    if (parsed != null) {
                        val (location, folder) = parsed
                        toggle(FolderRow(location, folder, BackupFolders.label(folder), null, 0, 0), true)
                    }
                }, modifier = Modifier.testTag("backup-add-confirm"))
            },
            dismiss = { GhostButton(stringResource(android.R.string.cancel), { adding = false }) },
        ) {
            Text(stringResource(R.string.backup_add_folder_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(path, { path = it }, singleLine = true, label = { Text(stringResource(R.string.backup_add_folder_field)) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth().testTag("backup-add-path"))
        }
    }
}

/** The toast for turning a folder on, when it did not simply work. */
private fun message(result: AddRootResult): Pair<Int, Array<Any>>? = when (result) {
    is AddRootResult.Added -> null
    is AddRootResult.Covered -> R.string.backup_covered to emptyArray()
    AddRootResult.NotConfigured -> R.string.backup_not_configured to emptyArray()
    AddRootResult.Unsupported -> R.string.backup_unavailable_old to emptyArray()
    AddRootResult.Offline -> R.string.backup_offline to emptyArray()
    is AddRootResult.Failed -> R.string.backup_add_failed to arrayOf(result.message)
}

/** Android's screen for All files access (RAWs and sidecars, ADR 0024). */
fun openAllFilesAccess(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 30) return
    val forApp = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(forApp) }.onFailure {
        runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** Settings › Backup itself (goldens in `BackupScreenshotTest`). */
@Composable
fun BackupContent(
    ui: BackupUi,
    onBack: () -> Unit,
    onToggle: (FolderRow, Boolean) -> Unit = { _, _ -> },
    onOpenFolder: (String) -> Unit = {},
    onAddFolder: () -> Unit = {},
    onRules: (BackupRules) -> Unit = {},
    onNow: () -> Unit = {},
    onAllFiles: () -> Unit = {},
    onFreeUp: () -> Unit = {},
    onFreeUpRules: (FreeUpRules) -> Unit = {},
    onThisPhone: () -> Unit = {},
) {
    HfpsScaffold(
        header = rememberCollapsingHeaderState(),
        topBar = { HfpsTopBar(title = stringResource(R.string.backup_title), onBack = onBack) },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("backup-list")) {
                if (ui.availability != BackupAvailability.Ready) {
                    item("unavailable") {
                        Text(
                            stringResource(if (ui.availability == BackupAvailability.NotPaired) R.string.backup_unavailable_pair else R.string.backup_unavailable_old),
                            style = HfpsTheme.typography.body,
                            color = HfpsTheme.colors.mutedForeground,
                            modifier = Modifier.padding(16.dp).testTag("backup-unavailable"),
                        )
                    }
                    return@LazyColumn
                }
                item("summary") { Summary(ui, onNow) }
                if (ui.revealNotGrouped) item("reveal") { RevealNotGrouped(onThisPhone) }
                item("folders-head") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) {
                        Eyebrow(stringResource(R.string.backup_folders))
                        Text(stringResource(R.string.backup_folders_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    }
                }
                items(ui.folders, key = { it.key }) { row ->
                    Column(Modifier.background(HfpsTheme.colors.card)) {
                        if (row != ui.folders.first()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                        FolderItem(row, ui.now, busy = row.key in ui.busy, onToggle = onToggle, onOpen = onOpenFolder)
                    }
                }
                item("add") {
                    Column(Modifier.background(HfpsTheme.colors.card)) {
                        if (ui.folders.isNotEmpty()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                        HfpsListRow(title = stringResource(R.string.backup_add_folder), icon = PhosphorRegular.FolderPlus, onClick = onAddFolder, modifier = Modifier.testTag("backup-add"))
                    }
                }
                item("raw") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(if (ui.allFiles) R.string.backup_raw_on else R.string.backup_raw_hint),
                            style = HfpsTheme.typography.ui,
                            color = HfpsTheme.colors.mutedForeground,
                            modifier = Modifier.weight(1f).testTag("backup-raw"),
                        )
                        if (!ui.allFiles) SecondaryButton(stringResource(R.string.backup_raw_allow), onAllFiles, modifier = Modifier.padding(start = 12.dp))
                    }
                }
                item("rules") { Rules(ui.rules, onRules) }
                if (ui.freeUpSupported && ui.folders.any { it.on }) item("freeup") { FreeUpSection(ui, onFreeUp, onFreeUpRules) }
            }
        }
    }
}

@Composable
private fun Summary(ui: BackupUi, onNow: () -> Unit) {
    val total = BackupFolders.total(ui.folders)
    val on = ui.folders.count { it.on }
    Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp).testTag("backup-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (on == 0) stringResource(R.string.backup_settings_off) else summaryText(on, total),
            style = HfpsTheme.typography.body,
            color = HfpsTheme.colors.cardForeground,
        )
        if (ui.progress.running) {
            Text(progressText(ui.progress), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.testTag("backup-progress"))
            val fraction = if (ui.progress.size > 0) ui.progress.sent.toFloat() / ui.progress.size else 0f
            if (ui.progress.phase == BackupEngine.PHASE_UPLOADING) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
            }
        }
        PrimaryButton(stringResource(R.string.backup_now), onNow, enabled = on > 0 && !ui.progress.running, modifier = Modifier.testTag("backup-now"))
    }
}

/** "2 folders · 11,050 of 12,034 backed up". */
@Composable
fun summaryText(folders: Int, total: FolderStatus): String {
    val l = locale()
    return pluralStringResource(R.plurals.backup_settings_summary, folders, formatNumber(folders, l), formatNumber(total.done, l), formatNumber(total.files, l))
}

@Composable
private fun progressText(p: BackupProgress): String {
    val l = locale()
    val line = when (p.phase) {
        BackupEngine.PHASE_UPLOADING -> stringResource(R.string.backup_running_uploading, p.name.orEmpty(), if (p.size > 0) (p.sent * 100 / p.size).toInt() else 0)
        BackupEngine.PHASE_HASHING -> stringResource(R.string.backup_running_hashing, p.name.orEmpty())
        else -> stringResource(R.string.backup_running_listing)
    }
    return if (p.toUpload > 0) line + "\n" + stringResource(R.string.backup_running_count, formatNumber(p.uploaded, l), formatNumber(p.toUpload, l)) else line
}

@Composable
fun folderTitle(row: FolderRow): String {
    val name = when (row.label) {
        FolderLabel.Camera -> stringResource(R.string.backup_folder_camera)
        FolderLabel.Screenshots -> stringResource(R.string.backup_folder_screenshots)
        FolderLabel.WhatsAppImages -> stringResource(R.string.backup_folder_whatsapp_images)
        FolderLabel.WhatsAppVideos -> stringResource(R.string.backup_folder_whatsapp_videos)
        FolderLabel.Other -> row.folder.substringAfterLast('/').ifEmpty { row.folder }
    }
    return if (row.locationId == "device") name else stringResource(R.string.backup_folder_sd, name)
}

/** "11,050 of 12,034 backed up · 950 already on the server · 34 waiting for Wi-Fi", then bytes and the last pass. */
@Composable
fun statusText(row: FolderRow, now: Long): String {
    val l = locale()
    val s = row.status ?: return pluralStringResource(R.plurals.backup_folder_off, row.files, formatNumber(row.files, l), bytes(row.bytes))
    val parts = ArrayList<String>()
    parts += pluralStringResource(R.plurals.backup_status_done, s.files, formatNumber(s.backedUp, l), formatNumber(s.files, l))
    if (s.onServer > 0) parts += pluralStringResource(R.plurals.backup_status_server, s.onServer, formatNumber(s.onServer, l))
    if (s.waitingWifi > 0) parts += pluralStringResource(R.plurals.backup_status_wifi, s.waitingWifi, formatNumber(s.waitingWifi, l))
    if (s.waitingCharging > 0) parts += pluralStringResource(R.plurals.backup_status_charging, s.waitingCharging, formatNumber(s.waitingCharging, l))
    if (s.queued > 0) parts += pluralStringResource(R.plurals.backup_status_queued, s.queued, formatNumber(s.queued, l))
    if (s.failed > 0) parts += pluralStringResource(R.plurals.backup_status_failed, s.failed, formatNumber(s.failed, l))
    if (s.refused > 0) parts += pluralStringResource(R.plurals.backup_status_refused, s.refused, formatNumber(s.refused, l))
    val second = listOf(
        stringResource(R.string.backup_status_bytes, bytes(s.backedUpBytes), bytes(s.bytes)),
        row.lastPassAt?.let { stringResource(R.string.backup_status_last_pass, DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()) }
            ?: stringResource(R.string.backup_status_never),
    )
    return parts.joinToString(" · ") + "\n" + second.joinToString(" · ")
}

@Composable
private fun FolderItem(row: FolderRow, now: Long, busy: Boolean, onToggle: (FolderRow, Boolean) -> Unit, onOpen: (String) -> Unit) {
    val icon: ImageVector = when (row.label) {
        FolderLabel.Camera -> PhosphorRegular.Camera
        FolderLabel.Screenshots -> PhosphorRegular.DeviceMobile
        FolderLabel.WhatsAppImages -> PhosphorRegular.Images
        FolderLabel.WhatsAppVideos -> PhosphorRegular.FilmStrip
        FolderLabel.Other -> PhosphorRegular.FolderSimple
    }
    val problems = (row.status?.failed ?: 0) + (row.status?.refused ?: 0) > 0
    Row(
        Modifier.fillMaxWidth().let { m -> if (row.rootId != null) m.clickable { onOpen(row.rootId) } else m }
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .testTag("backup-folder"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = if (problems) HfpsTheme.colors.warning else if (row.on) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground)
        Column(Modifier.weight(1f)) {
            Text(folderTitle(row), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
            Text(row.folder.ifEmpty { "/" }, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            Text(statusText(row, now), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.testTag("backup-folder-status"))
        }
        Switch(
            row.on,
            { v -> onToggle(row, v) },
            enabled = !busy,
            colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary),
            modifier = Modifier.testTag(if (row.on) "backup-switch-on" else "backup-switch-off"),
        )
    }
}

@Composable
private fun Rules(rules: BackupRules, onRules: (BackupRules) -> Unit) {
    Column(Modifier.testTag("backup-rules")) {
        Eyebrow(stringResource(R.string.backup_rules), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        Column(Modifier.background(HfpsTheme.colors.card)) {
            RuleRow(R.string.backup_rule_unmetered, PhosphorRegular.WifiHigh, rules.photosUnmetered, "backup-rule-unmetered") { onRules(rules.copy(photosUnmetered = it)) }
            HfpsRowDivider(Modifier.padding(start = 54.dp))
            RuleRow(R.string.backup_rule_charging, PhosphorRegular.Lightning, rules.photosCharging, "backup-rule-charging") { onRules(rules.copy(photosCharging = it)) }
            HfpsRowDivider(Modifier.padding(start = 54.dp))
            RuleRow(R.string.backup_rule_videos, PhosphorRegular.VideoCamera, rules.videosCharging, "backup-rule-videos") { onRules(rules.copy(videosCharging = it)) }
        }
        Text(stringResource(R.string.backup_rule_battery), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp))
    }
}

/** "Reveal on this phone is not grouped with Hoard" (ADR 0024: no delegation, both apps may upload), with a way to "This phone". */
@Composable
private fun RevealNotGrouped(onThisPhone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(HfpsTheme.colors.card).padding(12.dp).testTag("backup-reveal-not-grouped"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorRegular.Warning, null, Modifier.size(20.dp), tint = HfpsTheme.colors.warning)
        Text(stringResource(R.string.backup_reveal_not_grouped), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.cardForeground, modifier = Modifier.weight(1f))
        GhostButton(stringResource(R.string.server_this_phone), onThisPhone)
    }
}

/** Settings › Backup › Free up space (ADR 0024 A3): open the preview, what was freed, and the two rules (owner's decision 3). */
@Composable
private fun FreeUpSection(ui: BackupUi, onFreeUp: () -> Unit, onRules: (FreeUpRules) -> Unit) {
    val l = locale()
    Column(Modifier.testTag("backup-freeup")) {
        Eyebrow(stringResource(R.string.freeup_title), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        Column(Modifier.background(HfpsTheme.colors.card)) {
            HfpsListRow(
                title = stringResource(R.string.freeup_open),
                subtitle = if (ui.freed.files > 0) {
                    pluralStringResource(R.plurals.freeup_freed_so_far, ui.freed.files, bytes(ui.freed.bytes), formatNumber(ui.freed.files, l))
                } else {
                    stringResource(R.string.freeup_open_hint)
                },
                icon = PhosphorRegular.Broom,
                onClick = onFreeUp,
                modifier = Modifier.testTag("backup-freeup-open"),
            )
            HfpsRowDivider(Modifier.padding(start = 54.dp))
            RuleRow(R.string.freeup_rule_recent, PhosphorRegular.CalendarBlank, ui.freeUpRules.keepRecent, "freeup-rule-recent") { onRules(ui.freeUpRules.copy(keepRecent = it)) }
            HfpsRowDivider(Modifier.padding(start = 54.dp))
            RuleRow(R.string.freeup_rule_favourites, PhosphorRegular.Star, ui.freeUpRules.keepFavourites, "freeup-rule-favourites") { onRules(ui.freeUpRules.copy(keepFavourites = it)) }
        }
        Text(stringResource(R.string.freeup_rules_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun RuleRow(title: Int, icon: ImageVector, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    HfpsListRow(
        title = stringResource(title),
        icon = icon,
        onClick = { onChange(!on) },
        modifier = Modifier.testTag(tag),
        trailing = { Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary)) },
    )
}

// --- A folder's files ------------------------------------------------------------------------------------------------

/** The file list's filter (tapping a folder opens its files filtered by state). */
enum class FileFilter { All, Open, Done, Problems;

    fun accepts(f: BackupFileEntity): Boolean = when (this) {
        All -> true
        Open -> f.state !in BackupState.DONE
        Done -> f.state in BackupState.DONE
        Problems -> f.state == BackupState.FAILED || f.state == BackupState.REFUSED || f.state == BackupState.CHANGED
    }
}

@Composable
fun BackupFolderScreen(container: AppContainer, rootId: String, onBack: () -> Unit) {
    val db = container.gallery.db
    val root by remember(rootId) { db.backup().observeRoots() }.collectAsState(initial = null)
    val files by remember(rootId) { db.backup().observeFiles(rootId) }.collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf(FileFilter.All) }
    val r = root?.firstOrNull { it.id == rootId }
    val title = r?.let { folderTitle(FolderRow(it.locationId, it.folder, BackupFolders.label(it.folder), it.id, 0, 0)) } ?: stringResource(R.string.backup_title)
    // ADR 0025 A2: this phone's videos of the folder, replaced in place (offered with the server's phone target).
    val phoneOffer by container.compress.phoneOffer.collectAsState()
    var compressing by remember { mutableStateOf<dev.hfps.hoard.app.ui.compress.CompressRequest?>(null) }
    LaunchedEffect(Unit) { container.compress.recheckPhone() }
    val folder = r?.let { container.compress.phoneFolder(it.id, "", title) }
    BackupFilesContent(
        title, files, filter, onFilter = { filter = it }, onBack = onBack,
        onCompress = folder?.takeIf { phoneOffer != dev.hfps.hoard.server.compress.CompressOffer.Hidden }?.let { f -> { compressing = dev.hfps.hoard.app.ui.compress.CompressRequest(listOf(f.part)) } },
        onCompressAuto = folder?.takeIf { phoneOffer != dev.hfps.hoard.server.compress.CompressOffer.Hidden }?.let { f -> { compressing = dev.hfps.hoard.app.ui.compress.CompressRequest(listOf(f.part), autoFolder = f) } },
    )
    compressing?.let { request -> dev.hfps.hoard.app.ui.compress.CompressSheet(container, request, onDismiss = { compressing = null }) }
}

/** A folder's files with their states (goldens in `BackupScreenshotTest`). */
@Composable
fun BackupFilesContent(
    title: String,
    files: List<BackupFileEntity>,
    filter: FileFilter,
    onFilter: (FileFilter) -> Unit,
    onBack: () -> Unit,
    /** "Compress videos in this folder" and "Compress automatically" (ADR 0025 A2); null hides them. */
    onCompress: (() -> Unit)? = null,
    onCompressAuto: (() -> Unit)? = null,
) {
    HfpsScaffold(header = rememberCollapsingHeaderState(), topBar = { HfpsTopBar(title = title, onBack = onBack) }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            val shown = files.filter(filter::accepts)
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("backup-files")) {
                if (onCompress != null || onCompressAuto != null) {
                    item("compress") {
                        Column(Modifier.padding(top = 8.dp).background(HfpsTheme.colors.card)) {
                            onCompress?.let {
                                HfpsListRow(stringResource(R.string.compress_action_folder), icon = PhosphorRegular.ArrowsIn, onClick = it, modifier = Modifier.testTag("backup-compress"))
                            }
                            onCompressAuto?.let {
                                if (onCompress != null) HfpsRowDivider(Modifier.padding(start = 54.dp))
                                HfpsListRow(stringResource(R.string.compress_auto_title), subtitle = stringResource(R.string.compress_auto_phone_desc), icon = PhosphorRegular.ArrowsClockwise, onClick = it, modifier = Modifier.testTag("backup-compress-auto"))
                            }
                        }
                    }
                }
                item("filters") {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (f in FileFilter.entries) {
                            val label = when (f) {
                                FileFilter.All -> R.string.backup_filter_all
                                FileFilter.Open -> R.string.backup_filter_open
                                FileFilter.Done -> R.string.backup_filter_done
                                FileFilter.Problems -> R.string.backup_filter_problems
                            }
                            HfpsChip(stringResource(label) + " · " + formatNumber(files.count(f::accepts), locale()), f == filter, { onFilter(f) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    item("empty") { Text(stringResource(R.string.backup_files_empty), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(16.dp)) }
                }
                items(shown, key = { it.path }) { f ->
                    Column(Modifier.background(HfpsTheme.colors.card)) {
                        if (f != shown.first()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                        val (icon, tint) = stateIcon(f.state)
                        HfpsListRow(
                            title = f.path.substringAfterLast('/'),
                            subtitle = stateText(f) + " · " + bytes(f.size),
                            icon = icon,
                            iconTint = tint,
                            modifier = Modifier.testTag("backup-file"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun stateIcon(state: String): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when (state) {
    BackupState.STORED, BackupState.VERIFIED -> PhosphorRegular.CheckCircle to HfpsTheme.colors.primary
    BackupState.NAS -> PhosphorRegular.CloudCheck to HfpsTheme.colors.primary
    BackupState.FAILED, BackupState.REFUSED -> PhosphorRegular.WarningCircle to HfpsTheme.colors.warning
    BackupState.UPLOADING, BackupState.HASHING -> PhosphorRegular.UploadSimple to HfpsTheme.colors.primary
    BackupState.PAUSED -> PhosphorRegular.ClockCounterClockwise to HfpsTheme.colors.mutedForeground
    else -> PhosphorRegular.Circle to HfpsTheme.colors.mutedForeground
}

/** A file's state in words (ADR 0024: waiting, queued, hashing, uploading, backed up, on the server, verified, paused, failed). */
@Composable
fun stateText(f: BackupFileEntity): String = when (f.state) {
    BackupState.WAITING -> stringResource(R.string.backup_state_waiting)
    BackupState.HASHING -> stringResource(R.string.backup_state_hashing)
    BackupState.UPLOADING -> stringResource(R.string.backup_state_uploading, if (f.size > 0) (f.uploadOffset * 100 / f.size).toInt() else 0)
    BackupState.STORED -> stringResource(R.string.backup_state_stored)
    BackupState.NAS -> stringResource(R.string.backup_state_nas)
    BackupState.VERIFIED -> stringResource(R.string.backup_state_verified)
    BackupState.PAUSED -> stringResource(if (f.error == BackupState.REASON_CHARGING) R.string.backup_state_paused_charging else R.string.backup_state_paused_wifi)
    BackupState.CHANGED -> stringResource(R.string.backup_state_changed)
    BackupState.REFUSED -> stringResource(
        R.string.backup_state_refused,
        when (f.error) {
            "unsupported_type" -> stringResource(R.string.backup_refused_format)
            "invalid_path", "outside_root", "reserved_prefix", BackupEngine.REFUSED_INVALID -> stringResource(R.string.backup_refused_path)
            else -> f.error.orEmpty()
        },
    )
    BackupState.FAILED -> stringResource(
        when (f.error) {
            BackupState.REASON_UNREADABLE -> R.string.backup_state_failed_unreadable
            BackupState.REASON_SERVER_FULL -> R.string.backup_state_failed_full
            BackupState.REASON_TOO_LARGE -> R.string.backup_state_failed_large
            else -> R.string.backup_state_failed_server
        },
    )
    else -> stringResource(R.string.backup_state_queued)
}

/** Settings' row for the backup (in the photo server's section): its summary, opening Settings › Backup. */
@Composable
fun BackupSettingsRow(container: AppContainer, onOpen: () -> Unit) {
    val db = container.gallery.db
    val folders by remember { combine(db.device().observeDirs(), db.backup().observeRoots(), db.backup().observeCounts()) { d, r, c -> BackupFolders.rows(d, r, c) } }.collectAsState(initial = emptyList())
    val on = folders.count { it.on }
    val freed by remember { db.backup().observeFreedTotal() }.collectAsState(initial = FreedTotal(0, 0))
    val summary = if (on == 0) stringResource(R.string.backup_settings_off) else summaryText(on, BackupFolders.total(folders))
    HfpsListRow(
        title = stringResource(R.string.backup_title),
        subtitle = if (freed.files > 0) summary + "\n" + pluralStringResource(R.plurals.freeup_freed_so_far, freed.files, bytes(freed.bytes), formatNumber(freed.files, locale())) else summary,
        icon = PhosphorRegular.UploadSimple,
        onClick = onOpen,
        modifier = Modifier.testTag("settings-backup"),
    )
}
