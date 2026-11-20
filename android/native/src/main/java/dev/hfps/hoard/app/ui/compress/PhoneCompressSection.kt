package dev.hfps.hoard.app.ui.compress

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.api.Compress
import dev.hfps.hoard.server.compress.CompressEngine
import dev.hfps.hoard.server.compress.CompressOffer
import dev.hfps.hoard.server.compress.CompressProgress
import dev.hfps.hoard.server.compress.PhoneError
import dev.hfps.hoard.server.compress.PhoneOverview
import dev.hfps.hoard.server.compress.PhoneReport
import dev.hfps.hoard.server.compress.PhoneState
import dev.hfps.hoard.server.db.CompressPhoneEntity

/** What "This phone" shows (stateless, for goldens). */
data class PhoneCompressUi(
    val overview: PhoneOverview = PhoneOverview(emptyList(), emptyList(), emptyList()),
    val progress: CompressProgress = CompressProgress(),
    val offer: CompressOffer = CompressOffer.Hidden,
    /** The consent step runs (the system dialog may be up). */
    val asking: Boolean = false,
) {
    /** Listed when the phone target is offered (or unusable here) or the journal has rows. */
    val visible: Boolean get() = offer != CompressOffer.Hidden || !overview.isEmpty
}

/**
 * Settings › Compress videos › This phone (ADR 0025 A2): the consent step ("N videos are ready to replace" with Allow
 * and Keep originals), the replaces in progress with the running one's progress, and the recent ones.
 */
@Composable
fun PhoneCompressSection(
    ui: PhoneCompressUi,
    onAllow: () -> Unit = {},
    onKeep: () -> Unit = {},
    onReplaceNow: () -> Unit = {},
) {
    if (!ui.visible) return
    val o = ui.overview
    Column(Modifier.fillMaxWidth().testTag("compress-phone")) {
        Eyebrow(stringResource(R.string.compress_phone_title), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        (ui.offer as? CompressOffer.Unavailable)?.let { u ->
            ReasonLine(unavailableText(u.reason), HfpsTheme.typography.ui, HfpsTheme.colors.warning, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("compress-phone-unavailable"))
        }
        if (o.waiting.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp).testTag("compress-phone-consent")) {
                val (back, replace) = o.waiting.partition { it.direction == Compress.DIRECTION_RESTORE || it.report == PhoneReport.NONE }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(PhosphorRegular.ShieldCheck, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
                    Column(Modifier.weight(1f)) {
                        if (replace.isNotEmpty()) {
                            Text(
                                pluralStringResource(R.plurals.compress_phone_consent, replace.size, formatNumber(replace.size, locale()), bytes(replace.sumOf { (it.sourceSize - it.targetSize).coerceAtLeast(0) })),
                                style = HfpsTheme.typography.body,
                                color = HfpsTheme.colors.cardForeground,
                            )
                        }
                        if (back.isNotEmpty()) {
                            Text(
                                pluralStringResource(R.plurals.compress_phone_consent_restore, back.size, formatNumber(back.size, locale()), bytes(back.sumOf { (it.targetSize - it.sourceSize).coerceAtLeast(0) })),
                                style = HfpsTheme.typography.body,
                                color = HfpsTheme.colors.cardForeground,
                            )
                        }
                    }
                }
                Text(stringResource(R.string.compress_phone_consent_why), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    GhostButton(stringResource(R.string.compress_phone_keep), onKeep, enabled = !ui.asking, modifier = Modifier.testTag("compress-phone-keep"))
                    PrimaryButton(stringResource(R.string.compress_phone_allow), onAllow, enabled = !ui.asking, modifier = Modifier.testTag("compress-phone-allow"))
                }
            }
        }
        val running = ui.progress.takeIf { it.running && it.phase != null && it.phase != CompressEngine.PHASE_LISTING }
        if (running != null) {
            Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(horizontal = 16.dp, vertical = 10.dp).testTag("compress-phone-progress")) {
                Text(progressText(running), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.cardForeground, maxLines = 2)
                if (running.size > 0) {
                    LinearProgressIndicator(
                        progress = { (running.done.toFloat() / running.size).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        color = HfpsTheme.colors.primary,
                        trackColor = HfpsTheme.colors.muted,
                    )
                }
            }
        }
        val rows = o.active + o.recent
        for ((i, row) in rows.withIndex()) {
            Column(Modifier.background(HfpsTheme.colors.card)) {
                if (i > 0 || running != null || o.waiting.isNotEmpty()) HfpsRowDivider(Modifier.padding(start = 54.dp))
                HfpsListRow(
                    title = row.path.substringAfterLast('/'),
                    subtitle = rowText(row),
                    icon = rowIcon(row),
                    iconTint = if (row.state == PhoneState.ABANDONED) HfpsTheme.colors.warning else HfpsTheme.colors.mutedForeground,
                    modifier = Modifier.testTag("compress-phone-row"),
                )
            }
        }
        if (o.isEmpty && ui.offer == CompressOffer.Ready) {
            Text(stringResource(R.string.compress_phone_none), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        if (o.saved > 0) {
            Text(stringResource(R.string.compress_phone_saved, bytes(o.saved)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("compress-phone-saved"))
        }
        if (o.active.isNotEmpty() && running == null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.End) {
                GhostButton(stringResource(R.string.compress_phone_replace_now), onReplaceNow, modifier = Modifier.testTag("compress-phone-now"))
            }
        }
    }
}

@Composable
private fun progressText(p: CompressProgress): String {
    val name = p.name.orEmpty()
    return when (p.phase) {
        CompressEngine.PHASE_DOWNLOADING -> stringResource(R.string.compress_phone_progress_downloading, name, bytes(p.done), bytes(p.size))
        CompressEngine.PHASE_VERIFYING -> stringResource(R.string.compress_phone_progress_verifying, name)
        CompressEngine.PHASE_WRITING -> stringResource(R.string.compress_phone_progress_writing, name)
        else -> stringResource(R.string.compress_phone_progress_reporting, name)
    }
}

private fun rowIcon(row: CompressPhoneEntity) = when (row.state) {
    PhoneState.REPORTED -> PhosphorRegular.CheckCircle
    PhoneState.ABANDONED -> PhosphorRegular.WarningCircle
    PhoneState.DOWNLOADING -> PhosphorRegular.DownloadSimple
    else -> PhosphorRegular.ArrowsIn
}

/** A row's state in the owner's words ("Downloading", "Replaced · 412 MB saved", "Not replaced: changed on the phone"). */
@Composable
fun rowText(row: CompressPhoneEntity): String {
    val restore = row.direction == Compress.DIRECTION_RESTORE || row.report == PhoneReport.NONE
    val sizes = stringResource(R.string.compress_phone_sizes, bytes(row.sourceSize), bytes(row.targetSize))
    val state = when (row.state) {
        PhoneState.DOWNLOADING -> if (row.nextAttemptAt != null && row.error != null) stringResource(R.string.compress_phone_state_retry) else stringResource(R.string.compress_phone_state_downloading)
        PhoneState.VERIFYING -> stringResource(R.string.compress_phone_state_verifying)
        PhoneState.VERIFIED -> when {
            row.error == PhoneError.PERMISSION && !row.writeStarted -> stringResource(R.string.compress_phone_waiting_permission)
            row.nextAttemptAt != null && row.error != null -> stringResource(R.string.compress_phone_state_waiting, waitText(row.error))
            else -> stringResource(R.string.compress_phone_state_verified)
        }
        // A write that backs off says why (no room, a failed write, MediaStore): Amendment (A2), "Review of #81".
        PhoneState.WRITING -> if (row.nextAttemptAt != null && row.error != null) {
            stringResource(R.string.compress_phone_state_waiting, waitText(row.error))
        } else {
            stringResource(if (restore) R.string.compress_phone_state_restoring else R.string.compress_phone_state_writing)
        }
        PhoneState.WRITTEN -> stringResource(R.string.compress_phone_state_written)
        PhoneState.REPORTED -> stringResource(if (restore) R.string.compress_phone_state_restored else R.string.compress_phone_state_reported)
        PhoneState.ABANDON, PhoneState.ABANDONED -> stringResource(R.string.compress_phone_state_abandoned, abandonText(row.abandonReason ?: row.error))
        else -> row.state
    }
    return if (row.state == PhoneState.ABANDONED || row.state == PhoneState.ABANDON) state else "$state · $sizes"
}

@Composable
private fun waitText(reason: String?): String = when (reason) {
    PhoneError.SPACE -> stringResource(R.string.compress_phone_wait_space)
    PhoneError.WRITE -> stringResource(R.string.compress_phone_wait_write)
    PhoneError.PERMISSION -> stringResource(R.string.compress_phone_wait_permission)
    PhoneError.MEDIA -> stringResource(R.string.compress_phone_wait_media)
    PhoneError.VERIFY -> stringResource(R.string.compress_phone_wait_verify)
    else -> stringResource(R.string.compress_phone_reason_other)
}

@Composable
private fun abandonText(reason: String?): String = when (reason) {
    PhoneError.CHANGED -> stringResource(R.string.compress_phone_reason_changed)
    PhoneError.GONE -> stringResource(R.string.compress_phone_reason_gone)
    PhoneError.VERIFY -> stringResource(R.string.compress_phone_reason_verify)
    PhoneError.REFUSED -> stringResource(R.string.compress_phone_reason_refused)
    "not_found" -> stringResource(R.string.compress_phone_reason_cancelled)
    else -> stringResource(R.string.compress_phone_reason_other)
}
