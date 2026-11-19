package dev.hfps.hoard.app.ui.compress

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import dev.hfps.hoard.app.compress.CompressFolder
import dev.hfps.hoard.app.compress.CompressPart
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import dev.hfps.hoard.designsystem.components.HfpsCheckRow
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.api.CompressJob
import dev.hfps.hoard.server.api.ServerException
import dev.hfps.hoard.server.compress.CompressGate
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressSheetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * What a compression sheet is about: a selection or a folder ([parts], one per target), or a folder's "Compress
 * automatically" ([autoFolder]: the switch's sheet, with "Also compress the N videos already there").
 */
data class CompressRequest(
    val parts: List<CompressPart>,
    /** Videos of the selection no target takes (on this phone only, uploads). */
    val leftOut: Int = 0,
    val autoFolder: CompressFolder? = null,
)

/**
 * "Compress videos" (ADR 0025 "UI"): what will be compressed and where, the profile, the server's estimate ("129 of 312
 * videos · 29.1 GB → about 5.6 GB · about 35 min"), skipped by reason, warnings, the trash note, then Start. The
 * automatic variant turns a folder's standing job on (or off).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CompressSheet(container: AppContainer, request: CompressRequest, onDismiss: () -> Unit, onStarted: () -> Unit = {}) {
    val compress = container.compress
    val info by compress.info.collectAsState()
    val nas by compress.nasOffer.collectAsState()
    val backup by compress.backupOffer.collectAsState()
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val auto = request.autoFolder != null
    var state by remember { mutableStateOf(CompressSheetState(request.parts.firstOrNull()?.target ?: Compress.TARGET_NAS, CompressGate.profiles(info, auto), auto = auto)) }
    var autoJob by remember { mutableStateOf<CompressJob?>(null) }
    var autoChecked by remember { mutableStateOf(!auto) }

    LaunchedEffect(Unit) { compress.refresh() }
    if (auto) {
        LaunchedEffect(request.autoFolder) {
            autoJob = runCatching { compress.automaticJob(request.autoFolder!!) }.getOrNull()
            autoChecked = true
        }
    }
    // The offer of every part's target: a part whose target is not usable shows the server's reason instead.
    val blocked = request.parts.mapNotNull { p ->
        when (val o = if (p.target == Compress.TARGET_BACKUP) backup else nas) {
            is CompressOffer.Unavailable -> o.reason
            CompressOffer.Hidden -> CompressGate.REASON_UNKNOWN
            CompressOffer.Ready -> null
        }
    }.firstOrNull()

    LaunchedEffect(state.profile, blocked) {
        if (blocked != null || request.parts.isEmpty()) {
            state = state.copy(loading = false)
            return@LaunchedEffect
        }
        state = state.copy(loading = true, estimate = null, error = null)
        try {
            compress.estimate(request.parts, state.profile) { summary -> state = state.withEstimate(summary) }
            state = state.copy(loading = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state = state.failed((e as? ServerException)?.let { it.reason ?: it.code } ?: "offline")
        }
    }

    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("compress-sheet")) {
        CompressSheetContent(
            state = state,
            targets = request.parts.map { it.target }.distinct(),
            leftOut = request.leftOut,
            trashDays = info?.trashDays ?: 30,
            unavailable = blocked,
            autoFolder = request.autoFolder?.label,
            autoOn = autoJob,
            autoKnown = autoChecked,
            onProfile = { state = state.withProfile(it) },
            onAlsoExisting = { state = state.copy(alsoExisting = it) },
            onCancel = onDismiss,
            onTurnOff = {
                val job = autoJob ?: return@CompressSheetContent
                scope.launch {
                    runCatching { compress.autoOff(job) }
                        .onSuccess { toaster.show(res.getString(R.string.compress_auto_off)); onDismiss() }
                        .onFailure { toaster.show(res.getString(R.string.compress_load_failed)) }
                }
            },
            onStart = {
                state = state.copy(starting = true)
                scope.launch {
                    try {
                        var queued = 0
                        if (auto) {
                            compress.start(request.parts, state.profile, auto = true, name = request.autoFolder?.label)
                            if (state.alsoExisting) queued += compress.start(request.parts, state.profile).sumOf { it.queued ?: 0 }
                            toaster.show(res.getString(R.string.compress_auto_on))
                        } else {
                            queued = compress.start(request.parts, state.profile).sumOf { it.queued ?: 0 }
                            toaster.show(res.getQuantityString(R.plurals.compress_started, queued, formatNumberPlain(queued)))
                        }
                        onStarted()
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        val s = e as? ServerException
                        val text = when (s?.reason) {
                            "exists" -> res.getString(R.string.compress_exists)
                            "busy" -> res.getString(R.string.compress_busy)
                            else -> res.getString(R.string.compress_start_failed, s?.reason ?: s?.code ?: res.getString(R.string.compress_load_failed))
                        }
                        toaster.show(text)
                        state = state.copy(starting = false)
                    }
                }
            },
        )
    }
}

private fun formatNumberPlain(n: Int): String = java.text.NumberFormat.getIntegerInstance().format(n)

/** The sheet without its data (goldens). */
@Composable
fun CompressSheetContent(
    state: CompressSheetState,
    targets: List<String>,
    leftOut: Int,
    trashDays: Int,
    unavailable: String?,
    autoFolder: String? = null,
    autoOn: CompressJob? = null,
    autoKnown: Boolean = true,
    onProfile: (String) -> Unit = {},
    onAlsoExisting: (Boolean) -> Unit = {},
    onCancel: () -> Unit = {},
    onStart: () -> Unit = {},
    onTurnOff: () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(PhosphorRegular.ArrowsIn, null, Modifier.size(22.dp), tint = HfpsTheme.colors.primary)
            Text(
                if (autoFolder != null) stringResource(R.string.compress_auto_title) else stringResource(R.string.compress_title),
                style = HfpsTheme.typography.title,
                color = HfpsTheme.colors.cardForeground,
            )
        }
        autoFolder?.let { Text(it, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 2.dp)) }
        if (autoFolder != null) {
            Text(stringResource(R.string.compress_auto_desc), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 8.dp))
        } else {
            for (t in targets) {
                Text(
                    stringResource(if (t == Compress.TARGET_BACKUP) R.string.compress_target_backup else R.string.compress_target_nas),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.mutedForeground,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        if (leftOut > 0) {
            Text(pluralStringResource(R.plurals.compress_left_out, leftOut, formatNumber(leftOut, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 4.dp))
        }
        if (unavailable != null) {
            Text(unavailableText(unavailable), style = HfpsTheme.typography.body, color = HfpsTheme.colors.warning, modifier = Modifier.padding(top = 12.dp).testTag("compress-unavailable"))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) { GhostButton(stringResource(R.string.t_common_close), onCancel) }
            return@Column
        }
        if (targets.isEmpty()) {
            Text(stringResource(R.string.compress_none_in_selection), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) { GhostButton(stringResource(R.string.t_common_close), onCancel) }
            return@Column
        }
        if (autoOn != null) {
            // The folder is compressed automatically already: the sheet turns it off.
            Text(stringResource(R.string.compress_auto_on), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground, modifier = Modifier.padding(top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                GhostButton(stringResource(R.string.t_common_close), onCancel)
                PrimaryButton(stringResource(R.string.compress_auto_turn_off), onTurnOff, modifier = Modifier.testTag("compress-auto-off"))
            }
            return@Column
        }
        if (state.profiles.size > 1) {
            Eyebrow(stringResource(R.string.compress_profile_label), Modifier.padding(top = 16.dp, bottom = 4.dp))
            Column(Modifier.selectableGroup()) {
                for (id in state.profiles) {
                    val (name, desc) = profileText(id)
                    HfpsChoiceRow(name, state.profile == id, { onProfile(id) }, detail = desc, modifier = Modifier.testTag("compress-profile-$id"))
                }
            }
        }
        Eyebrow(stringResource(R.string.compress_estimate_title), Modifier.padding(top = 16.dp, bottom = 4.dp))
        val e = state.estimate
        when {
            state.error != null -> Text(stringResource(R.string.compress_start_failed, state.error.orEmpty()), style = HfpsTheme.typography.body, color = HfpsTheme.colors.destructive)
            e == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), color = HfpsTheme.colors.primary, strokeWidth = 2.dp)
                Text(stringResource(R.string.compress_estimating), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            }
            else -> Column(Modifier.testTag("compress-estimate")) {
                if (e.videos == 0) {
                    Text(stringResource(R.string.compress_estimate_nothing), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
                } else {
                    Text(
                        pluralStringResource(R.plurals.compress_estimate_summary, e.videos, formatNumber(e.encode, locale()), formatNumber(e.videos, locale())),
                        style = HfpsTheme.typography.body,
                        color = HfpsTheme.colors.cardForeground,
                    )
                    if (e.encode > 0) {
                        Text(
                            stringResource(R.string.compress_estimate_sizes, bytes(e.size), bytes(e.estSize), minutesText(e.estMs)),
                            style = HfpsTheme.typography.body,
                            color = HfpsTheme.colors.cardForeground,
                        )
                    }
                }
                for ((reason, n) in e.skipped) {
                    Text(skipText(reason, n), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                }
                if (e.pending > 0) {
                    Text(stringResource(R.string.compress_estimate_pending), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                }
                if (e.hdr10Plus) Text(stringResource(R.string.compress_warn_hdr10plus), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning, modifier = Modifier.padding(top = 4.dp))
                if (e.metaSaved) Text(stringResource(R.string.compress_meta_saved), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 4.dp))
                if (autoFolder != null && e.encode > 0) {
                    HfpsCheckRow(
                        pluralStringResource(R.plurals.compress_auto_existing, e.encode, formatNumber(e.encode, locale())),
                        state.alsoExisting,
                        onAlsoExisting,
                        modifier = Modifier.padding(top = 8.dp).testTag("compress-also-existing"),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.compress_originals_to_trash, trashDays.toString()),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.padding(top = 12.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            GhostButton(stringResource(android.R.string.cancel), onCancel)
            PrimaryButton(
                if (autoFolder != null) stringResource(R.string.compress_auto_switch) else stringResource(R.string.compress_start),
                onStart,
                enabled = state.canStart && autoKnown,
                modifier = Modifier.testTag("compress-start"),
            )
        }
    }
}
