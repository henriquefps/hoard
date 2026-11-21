package dev.hfps.hoard.app.ui.compress

import androidx.compose.foundation.background
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.compress.CompressTargets
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.rememberCollapsingHeaderState
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressInfo
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressOverview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What Settings › Compress videos shows (stateless, for goldens). */
data class CompressSettingsUi(
    val overview: CompressOverview? = null,
    val info: CompressInfo? = null,
    val nas: CompressOffer = CompressOffer.Hidden,
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** This phone's replaces (ADR 0025 A2). */
    val phone: PhoneCompressUi = PhoneCompressUi(),
)

/** Settings › Compress videos (ADR 0025 "UI"): jobs in progress, folders compressed automatically, history. */
@Composable
fun CompressSettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenTrash: () -> Unit) {
    val compress = container.compress
    val info by compress.info.collectAsState()
    val nas by compress.nasOffer.collectAsState()
    val phoneOffer by compress.phoneOffer.collectAsState()
    val phoneRows by container.phoneCompress.rows.collectAsState(emptyList())
    val phoneProgress by container.phoneCompress.progress.collectAsState()
    var asking by remember { mutableStateOf(false) }
    var overview by remember { mutableStateOf<CompressOverview?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var cancelling by remember { mutableStateOf<CompressJob?>(null) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val res = LocalResources.current

    suspend fun load() {
        loading = true
        try {
            overview = compress.overview()
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            failed = true
        } finally {
            loading = false
        }
    }
    LaunchedEffect(Unit) {
        compress.recheckPhone()
        container.phoneCompress.onScreen()
        compress.refresh()
    }
    LaunchedEffect(tick) {
        // Progress moves on the server: read it again while something runs.
        while (true) {
            load()
            if (overview?.running.isNullOrEmpty()) break
            delay(REFRESH_MS)
        }
    }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { if (it is CancellationException) throw it else toaster.show(res.getString(R.string.compress_load_failed)) }
            tick++
        }
    }
    fun allow() {
        if (asking) return
        asking = true
        scope.launch {
            runCatching { container.phoneCompress.allow() }
                .onSuccess { r -> if (r.replaced > 0) toaster.show(res.getQuantityString(R.plurals.compress_phone_replaced_toast, r.replaced, r.replaced.toString())) }
                .onFailure { if (it is CancellationException) throw it else toaster.show(res.getString(R.string.compress_load_failed)) }
            asking = false
        }
    }
    // The Gallery's "ready to replace" banner opens this screen and starts the consent step (ADR 0025 2.8.1).
    val consentAsked by dev.hfps.hoard.app.ui.shell.ShellRequests.compressConsent.collectAsState()
    LaunchedEffect(consentAsked) {
        if (consentAsked) {
            dev.hfps.hoard.app.ui.shell.ShellRequests.compressConsent.value = false
            allow()
        }
    }
    CompressSettingsContent(
        ui = CompressSettingsUi(overview, info, nas, loading, failed, PhoneCompressUi(dev.hfps.hoard.server.compress.PhoneOverview.of(phoneRows), phoneProgress, phoneOffer, asking)),
        onBack = onBack,
        onAllow = ::allow,
        onKeep = { act { container.phoneCompress.keepOriginals(phoneRows.filter { it.error == dev.hfps.hoard.server.compress.PhoneError.PERMISSION }.map { it.id }) } },
        onReplaceNow = { container.phoneCompress.kick() },
        onRefresh = { tick++ },
        onOpenTrash = onOpenTrash,
        onPause = { job -> act { compress.pause(job, !job.paused) } },
        onCancel = { cancelling = it },
        onAutoOff = { job -> act { compress.autoOff(job) } },
    )
    cancelling?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.compress_cancel_confirm_title),
            body = stringResource(R.string.compress_cancel_confirm_body),
            confirmLabel = stringResource(R.string.compress_cancel),
            destructive = true,
            onDismiss = { cancelling = null },
            onConfirm = { act { compress.cancel(job) } },
        )
    }
}

private const val REFRESH_MS = 5_000L

@Composable
fun CompressSettingsContent(
    ui: CompressSettingsUi,
    onBack: () -> Unit,
    onRefresh: () -> Unit = {},
    onOpenTrash: () -> Unit = {},
    onPause: (CompressJob) -> Unit = {},
    onCancel: (CompressJob) -> Unit = {},
    onAutoOff: (CompressJob) -> Unit = {},
    onAllow: () -> Unit = {},
    onKeep: () -> Unit = {},
    onReplaceNow: () -> Unit = {},
) {
    HfpsScaffold(
        header = rememberCollapsingHeaderState(),
        topBar = {
            HfpsTopBar(title = stringResource(R.string.compress_title), onBack = onBack) {
                HfpsIconButton(PhosphorRegular.ArrowsClockwise, stringResource(R.string.t_browse_refresh), onRefresh, enabled = !ui.loading)
                HfpsIconButton(PhosphorRegular.Trash, stringResource(R.string.trash_title), onOpenTrash)
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 840.dp).fillMaxSize().testTag("compress-settings")) {
                if (ui.loading && ui.overview == null) item("loading") { LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted) }
                if (ui.phone.ready > 0) {
                    item("ready") { ReplaceReadyBanner(ui.phone.ready, onAllow, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), enabled = !ui.phone.asking) }
                }
                item("status") { StatusBlock(ui) }
                if (ui.phone.visible) item("phone") { PhoneCompressSection(ui.phone, onAllow, onKeep, onReplaceNow) }
                val o = ui.overview
                if (o != null) {
                    if (o.running.isNotEmpty()) {
                        item("running-h") { Header(stringResource(R.string.compress_running_title)) }
                        items(o.running, key = { "run-" + it.id }) { job ->
                            Column(Modifier.background(HfpsTheme.colors.card)) {
                                if (job != o.running.first()) HfpsRowDivider(Modifier.padding(start = 16.dp))
                                RunningJob(job, onPause, onCancel)
                            }
                        }
                    }
                    item("auto-h") { Header(stringResource(R.string.compress_auto_folders_title)) }
                    if (o.automatic.isEmpty()) {
                        item("auto-none") { Hint(stringResource(R.string.compress_auto_none)) }
                    } else {
                        items(o.automatic, key = { "auto-" + it.id }) { job ->
                            Column(Modifier.background(HfpsTheme.colors.card)) {
                                if (job != o.automatic.first()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                                HfpsListRow(
                                    title = job.name?.ifEmpty { null } ?: CompressTargets.folderLabel(job) ?: job.id,
                                    subtitle = jobLine(job),
                                    icon = PhosphorRegular.FolderSimple,
                                    modifier = Modifier.testTag("compress-auto-row"),
                                    trailing = { Switch(true, { onAutoOff(job) }, colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary)) },
                                )
                            }
                        }
                    }
                    item("history-h") { Header(stringResource(R.string.compress_history_title)) }
                    if (o.history.isEmpty() && o.running.isEmpty() && o.automatic.isEmpty()) {
                        item("none") { Hint(stringResource(R.string.compress_no_jobs)) }
                    }
                    items(o.history, key = { "h-" + it.id }) { job ->
                        Column(Modifier.background(HfpsTheme.colors.card)) {
                            if (job != o.history.first()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                            HfpsListRow(
                                title = jobTitle(job),
                                subtitle = jobLine(job),
                                icon = if (job.state == CompressJob.STATE_CANCELLED) PhosphorRegular.XCircle else PhosphorRegular.CheckCircle,
                                modifier = Modifier.testTag("compress-history-row"),
                            )
                        }
                    }
                    item("restore-hint") { Hint(stringResource(R.string.compress_restore_desc)) }
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(ui: CompressSettingsUi) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val n = ui.nas) {
            is CompressOffer.Unavailable -> {
                // With this phone's own replace working, the library's reason is said as the library's (ADR 0025 A2).
                val reason = unavailableText(n.reason)
                val shown = if (ui.phone.offer == CompressOffer.Ready) reason.copy(text = stringResource(R.string.compress_unavailable_library, reason.text)) else reason
                ReasonLine(shown, HfpsTheme.typography.ui, HfpsTheme.colors.warning, Modifier.testTag("compress-unavailable"))
            }
            else -> Unit
        }
        ui.info?.attention?.stalled?.takeIf { it > 0 }?.let { n ->
            Text(pluralStringResource(R.plurals.compress_attention, n, formatNumber(n, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning)
        }
        ui.overview?.let { o ->
            Text(
                stringResource(R.string.compress_totals, bytes(o.saved), bytes(o.inTrash)),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.foreground,
                modifier = Modifier.testTag("compress-totals"),
            )
        }
        ui.info?.gpu?.let { Text(stringResource(R.string.compress_gpu, it), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground) }
        ui.info?.let { i ->
            Text(stringResource(if (i.offerAv1) R.string.compress_av1_offered else R.string.compress_av1_hidden), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            Text(stringResource(R.string.compress_night_window), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        if (ui.failed) Text(stringResource(R.string.compress_load_failed), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive)
    }
}

@Composable
private fun Header(text: String) {
    Eyebrow(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun jobTitle(job: CompressJob): String {
    val date = DateFormat.getDateInstance(DateFormat.MEDIUM, locale()).format(Date(job.createdAt))
    return job.name?.ifEmpty { null }?.let { "$it · $date" } ?: date
}

/** Counts of a job: done of all, saved and in the trash, skipped and failed by reason. */
@Composable
private fun jobLine(job: CompressJob): String {
    val res = LocalResources.current
    val total = job.progress.done + job.progress.toDo
    val parts = ArrayList<String>()
    if (total > 0) parts += pluralStringResource(R.plurals.compress_progress, total, formatNumber(job.progress.done, locale()), formatNumber(total, locale()))
    if (job.bytes.saved > 0) parts += stringResource(R.string.compress_saved, bytes(job.bytes.saved))
    val inTrash = (job.counts["replaced"] ?: 0) + (job.counts["restored"] ?: 0)
    if (inTrash > 0) parts += pluralStringResource(R.plurals.compress_in_trash, inTrash, formatNumber(inTrash, locale())) + " (" + bytes(job.bytes.inTrash) + ")"
    val skipped = job.skipped.values.sum()
    if (skipped > 0) parts += pluralStringResource(R.plurals.compress_skipped_count, skipped, formatNumber(skipped, locale()))
    for ((reason, n) in job.failed.entries.sortedByDescending { it.value }) {
        if (n > 0) parts += pluralStringResource(R.plurals.compress_failed_count, n, formatNumber(n, locale()), CompressReasonTexts.inline(res, CompressReasonTexts.failed(res, reason)))
    }
    when {
        job.auto -> parts.add(0, stringResource(R.string.compress_job_auto))
        job.state == CompressJob.STATE_CANCELLED -> parts.add(0, stringResource(R.string.compress_job_cancelled))
        job.paused -> parts.add(0, stringResource(R.string.compress_job_paused))
        job.state == CompressJob.STATE_DONE -> parts.add(0, stringResource(R.string.compress_job_done))
    }
    if (job.target == Compress.TARGET_BACKUP) parts += stringResource(R.string.compress_target_backup)
    if (job.target == Compress.TARGET_PHONE) parts += stringResource(R.string.compress_job_phone)
    return parts.joinToString(" · ")
}

@Composable
private fun RunningJob(job: CompressJob, onPause: (CompressJob) -> Unit, onCancel: (CompressJob) -> Unit) {
    val total = (job.progress.done + job.progress.toDo).coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).testTag("compress-running-row")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(PhosphorRegular.ArrowsIn, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
            Column(Modifier.weight(1f)) {
                Text(jobTitle(job), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, maxLines = 1)
                Text(jobLine(job), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 3)
                val eta = job.progress.etaMs?.takeIf { it > 0 }?.let { stringResource(R.string.compress_eta, minutesText(it)) }
                val current = job.progress.current?.percent?.let { stringResource(R.string.compress_job_current, it.toString()) }
                listOfNotNull(current, eta).takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground) }
            }
        }
        LinearProgressIndicator(
            progress = { job.progress.done.toFloat() / total },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            color = HfpsTheme.colors.primary,
            trackColor = HfpsTheme.colors.muted,
        )
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GhostButton(stringResource(if (job.paused) R.string.compress_resume else R.string.compress_pause), { onPause(job) }, modifier = Modifier.testTag("compress-pause"))
            GhostButton(stringResource(R.string.compress_cancel), { onCancel(job) }, modifier = Modifier.testTag("compress-cancel"))
        }
    }
}

/** Settings › Photo server's "Compress videos" row: shown with the feature (or while the server reports compression). */
@Composable
fun CompressSettingsRow(container: AppContainer, onOpen: () -> Unit) {
    val visible by container.compress.settingsVisible.collectAsState()
    if (!visible) return
    HfpsRowDivider(Modifier.padding(start = 54.dp))
    HfpsListRow(
        title = stringResource(R.string.compress_title),
        subtitle = stringResource(R.string.compress_settings_summary),
        icon = PhosphorRegular.ArrowsIn,
        onClick = onOpen,
        modifier = Modifier.testTag("settings-compress"),
        subtitleMaxLines = Int.MAX_VALUE,
    )
}
