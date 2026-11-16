package dev.hfps.hoard.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.BrowseLocation
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.trash.TrashOutcome
import dev.hfps.hoard.app.trash.TrashPlan
import dev.hfps.hoard.app.trash.TrashProgress
import dev.hfps.hoard.app.trash.TrashRefusal
import dev.hfps.hoard.app.trash.TrashUnit
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.errorTitle
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What a trash dialog moves: [paths] of [folder] in [location] (the trash adds each shot's other files and sidecars). */
data class TrashRequest(val location: BrowseLocation, val folder: String, val paths: List<String>)

/** What left the folder: units moved to the trash entirely, and units deleted permanently instead. */
data class TrashDone(val trashed: List<TrashUnit>, val deleted: List<TrashUnit>) {
    val gone: List<TrashUnit> get() = trashed + deleted
}

/** Refusals for which the dialog offers today's permanent delete instead (ADR 0023 "When the trash is not possible"). */
val PERMANENT_OFFER: Set<TrashRefusal> = setOf(
    TrashRefusal.ROOT_NOT_WRITABLE,
    TrashRefusal.NOT_SAME_DEVICE,
    TrashRefusal.MOVE_UNSUPPORTED,
    TrashRefusal.ALREADY_IN_TRASH,
    TrashRefusal.PHONE_TRASH_UNAVAILABLE,
)

fun reasonText(reason: TrashRefusal): Int = when (reason) {
    TrashRefusal.ALREADY_IN_TRASH -> R.string.trash_reason_already_in_trash
    TrashRefusal.CONTAINS_TRASH -> R.string.trash_reason_contains_trash
    TrashRefusal.SERVER_UNREACHABLE -> R.string.trash_reason_server_unreachable
    TrashRefusal.ROOT_NOT_WRITABLE -> R.string.trash_reason_root_not_writable
    TrashRefusal.NOT_SAME_DEVICE -> R.string.trash_reason_not_same_device
    TrashRefusal.MOVE_UNSUPPORTED -> R.string.trash_reason_move_unsupported
    TrashRefusal.UNREACHABLE -> R.string.trash_reason_unreachable
    TrashRefusal.NOT_FOUND -> R.string.trash_reason_not_found
    TrashRefusal.UNKNOWN_PLACE -> R.string.trash_reason_unknown_place
    TrashRefusal.PHONE_TRASH_UNAVAILABLE -> R.string.trash_reason_phone
    TrashRefusal.FAILED -> R.string.trash_reason_failed
}

/** Where a plan's trash is, for the dialog: `machine › share › root` (or the drive's name). */
fun trashWhere(location: BrowseLocation, plan: TrashPlan): String {
    val roots = plan.roots.ifEmpty { listOf("") }
    return roots.joinToString(", ") { root ->
        listOfNotNull(location.name, location.share?.takeIf { location.isSmb }, root.ifEmpty { null }).joinToString(" › ")
    }
}

private sealed interface Step {
    data object Preparing : Step
    data class PrepareFailed(val message: Int) : Step
    data class Confirm(val plan: TrashPlan) : Step
    data object Running : Step

    /** Units the trash refused that can be deleted permanently instead ([trashed] already went to the trash). */
    data class Offer(val units: List<TrashUnit>, val reason: TrashRefusal, val trashed: List<TrashUnit>) : Step
}

/**
 * "Move to trash" (ADR 0023), for culling's rejects and the Files delete (the owner's decision 1): plans on a fresh
 * listing (the trash root may need the photo server), confirms with the counts, size and where, warns when the server
 * is too old to keep Reveal's edits, runs with progress and Cancel, and offers the permanent delete only for what the
 * trash refused for a reason that permanent delete solves.
 */
@Composable
fun MoveToTrashDialog(
    container: AppContainer,
    request: TrashRequest,
    onDismiss: () -> Unit,
    onDone: (TrashDone) -> Unit,
    /** Culling (true): whole shots. Files (false): the selected files, sidecars only with a shot's last member. */
    shots: Boolean = true,
) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Preparing) }
    val progress by container.trash.progress.collectAsState()
    val autoDays by container.appSettings.trashAutoEmptyDays.collectAsState(initial = dev.hfps.hoard.app.data.AppSettings.TRASH_AUTO_EMPTY_DEFAULT)

    LaunchedEffect(request) {
        step = try {
            val plan = container.trash.prepareFolder(request.location, request.folder, request.paths, wholeShots = shots)
            val offer = plan.refused.filter { it.reason in PERMANENT_OFFER }
            if (plan.units.isEmpty() && offer.isNotEmpty()) Step.Offer(offer.map { it.unit }, offer.first().reason, emptyList()) else Step.Confirm(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Step.PrepareFailed(errorTitle(errorCodeOf(e)))
        }
    }

    fun finish(trashed: List<TrashUnit>, deleted: List<TrashUnit>) {
        onDone(TrashDone(trashed, deleted))
        onDismiss()
    }

    fun run(plan: TrashPlan) {
        step = Step.Running
        scope.launch {
            val report = try {
                container.trash.start(plan).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.show(res.getString(errorTitle(errorCodeOf(e))))
                finish(emptyList(), emptyList())
                return@launch
            }
            val trashed = report.trashed.map { it.unit }
            val messages = buildList {
                if (trashed.isNotEmpty()) add(res.getQuantityString(R.plurals.trash_moved, trashed.size, trashed.size.toString()))
                if (report.partial.isNotEmpty()) add(res.getQuantityString(R.plurals.trash_partial, report.partial.size, report.partial.size.toString()))
                if (report.cancelled.isNotEmpty()) add(res.getString(R.string.trash_cancelled))
                val other = report.refused.filter { it.reason !in PERMANENT_OFFER }
                if (other.isNotEmpty()) {
                    add(res.getQuantityString(R.plurals.trash_refused_count, other.size, other.size.toString(), res.getString(reasonText(other.first().reason))))
                }
            }
            if (messages.isNotEmpty()) toaster.show(messages.joinToString("\n"), long = messages.size > 1)
            val offer = report.refused.filter { it.reason in PERMANENT_OFFER }
            if (offer.isNotEmpty()) {
                step = Step.Offer(offer.map { it.unit }, offer.first().reason, trashed)
            } else {
                finish(trashed, emptyList())
            }
        }
    }

    when (val s = step) {
        Step.Preparing -> HoardDialog(
            title = stringResource(R.string.trash_title),
            onDismiss = onDismiss,
            confirm = {},
            dismiss = { TextButton(onDismiss) { Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
        ) {
            Text(stringResource(R.string.trash_preparing), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
        }
        is Step.PrepareFailed -> HoardDialog(
            title = stringResource(R.string.trash_title),
            onDismiss = onDismiss,
            confirm = { TextButton(onDismiss) { Text(stringResource(R.string.t_common_close), color = HfpsTheme.colors.primary) } },
        ) {
            Text(stringResource(s.message), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        }
        is Step.Confirm -> {
            val plan = s.plan
            val allShots = shots && plan.units.all { it.unit.isShot }
            val count = plan.units.size
            HoardDialog(
                title = if (count == 0) stringResource(R.string.trash_title)
                else if (allShots) pluralStringResource(R.plurals.trash_confirm_title_shots, count, formatNumber(count, locale()))
                else pluralStringResource(R.plurals.trash_confirm_title_items, count, formatNumber(count, locale())),
                onDismiss = onDismiss,
                confirm = {
                    if (count > 0) {
                        TextButton({ run(plan) }, Modifier.testTag("trash-confirm")) {
                            Text(stringResource(R.string.trash_confirm_action), color = HfpsTheme.colors.primary, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                },
                dismiss = { TextButton(onDismiss) { Text(stringResource(if (count == 0) R.string.t_common_close else R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
            ) {
                TrashConfirmBody(plan, trashWhere(request.location, plan), autoDays)
            }
        }
        Step.Running -> HoardDialog(
            title = stringResource(R.string.trash_running),
            onDismiss = {},
            confirm = {},
            dismiss = { TextButton({ container.trash.cancel() }) { Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
        ) {
            TrashProgressBody(progress)
        }
        is Step.Offer -> {
            val count = s.units.size
            var busy by remember { mutableStateOf(false) }
            HoardDialog(
                title = pluralStringResource(R.plurals.trash_permanent_title, count, formatNumber(count, locale())),
                onDismiss = { if (!busy) finish(s.trashed, emptyList()) },
                confirm = {
                    TextButton(
                        {
                            busy = true
                            scope.launch {
                                val deleted = container.trash.deletePermanently(request.location, s.units)
                                toaster.show(res.getQuantityString(R.plurals.trash_deleted_permanently, deleted.size, deleted.size.toString()))
                                finish(s.trashed, deleted)
                            }
                        },
                        enabled = !busy,
                    ) {
                        Text(stringResource(R.string.trash_delete_permanently), color = HfpsTheme.colors.destructive, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                    }
                },
                dismiss = { TextButton({ finish(s.trashed, emptyList()) }, enabled = !busy) { Text(stringResource(R.string.trash_keep), color = HfpsTheme.colors.foreground) } },
            ) {
                Text(
                    pluralStringResource(R.plurals.trash_permanent_body, count, formatNumber(count, locale()), stringResource(reasonText(s.reason))),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.mutedForeground,
                )
            }
        }
    }
}

/** The confirmation's body: how many files, how much, where, the old-server warning and what cannot go. */
@Composable
fun TrashConfirmBody(plan: TrashPlan, where: String, autoDays: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.units.isNotEmpty()) {
            Text(
                pluralStringResource(R.plurals.trash_confirm_body, plan.files, formatNumber(plan.files, locale()), bytes(plan.bytes), where),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
            Text(
                if (autoDays > 0) stringResource(R.string.trash_auto_note, autoDays) else stringResource(R.string.trash_auto_off),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
        if (plan.oldServerWarning) {
            Text(stringResource(R.string.trash_old_server_warning), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.warning, modifier = Modifier.testTag("trash-old-server"))
        }
        for ((reason, units) in plan.refused.groupBy { it.reason }) {
            Text(
                pluralStringResource(R.plurals.trash_refused_count, units.size, formatNumber(units.size, locale()), stringResource(reasonText(reason))),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.destructive,
            )
        }
    }
}

@Composable
fun TrashProgressBody(progress: TrashProgress?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val total = progress?.filesTotal ?: 0
        if (total > 0) {
            Text(stringResource(R.string.trash_notification_progress, progress!!.filesDone, total), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
            LinearProgressIndicator(
                progress = { progress.filesDone.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = HfpsTheme.colors.primary,
                trackColor = HfpsTheme.colors.muted,
            )
            progress.currentName?.let { Text(it, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, maxLines = 1) }
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
        }
    }
}

/** Units of an outcome list that went to the trash entirely. */
fun List<TrashOutcome>.trashedUnits(): List<TrashUnit> = filterIsInstance<TrashOutcome.Trashed>().map { it.unit }
