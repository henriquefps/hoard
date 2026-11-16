package dev.hfps.hoard.app.ui.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    TrashRefusal.FOLDER_NOT_TRASHABLE,
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
    TrashRefusal.DECLINED -> R.string.trash_reason_declined
    TrashRefusal.FOLDER_NOT_TRASHABLE -> R.string.trash_reason_folder
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

    /** Each request with its plan; [plan] is all of them together, for the counts. */
    data class Confirm(val plans: List<Pair<TrashRequest, TrashPlan>>, val plan: TrashPlan) : Step
    data object Running : Step

    /** Units the trash refused that can be deleted permanently instead, by request ([trashed] already went to the trash). */
    data class Offer(val units: List<Pair<TrashRequest, List<TrashUnit>>>, val reason: TrashRefusal, val trashed: List<TrashUnit>) : Step {
        val all: List<TrashUnit> get() = units.flatMap { it.second }
    }
}

/** Plans of several places shown as one (the counts, the size, the warnings; never run as one). */
private fun combined(plans: List<TrashPlan>): TrashPlan =
    TrashPlan(plans.first().place, plans.flatMap { it.units }, plans.flatMap { it.refused }, plans.any { it.oldServerWarning })

/** One request: Files' delete, culling's rejects. */
@Composable
fun MoveToTrashDialog(
    container: AppContainer,
    request: TrashRequest,
    onDismiss: () -> Unit,
    onDone: (TrashDone) -> Unit,
    /** Culling (true): whole shots. Files (false): the selected files, sidecars only with a shot's last member. */
    shots: Boolean = true,
) = MoveToTrashDialog(container, listOf(request), onDismiss, onDone, shots)

/**
 * "Move to trash" (ADR 0023), for culling's rejects, the Files delete (the owner's decision 1) and the Gallery's
 * selection (several folders and places at once, [requests]): plans on fresh listings (the trash root may need the
 * photo server), confirms with the counts, size and where, warns when the server is too old to keep Reveal's edits,
 * says what is left out ([leftOut]: the Gallery's unmapped and upload-only server shots), runs each place in turn with
 * progress and Cancel, and offers the permanent delete only for what the trash refused for a reason that permanent
 * delete solves.
 */
@Composable
fun MoveToTrashDialog(
    container: AppContainer,
    requests: List<TrashRequest>,
    onDismiss: () -> Unit,
    onDone: (TrashDone) -> Unit,
    shots: Boolean = true,
    leftOut: String? = null,
) {
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf<Step>(Step.Preparing) }
    val progress by container.trash.progress.collectAsState()
    val autoDays by container.appSettings.trashAutoEmptyDays.collectAsState(initial = dev.hfps.hoard.app.data.AppSettings.TRASH_AUTO_EMPTY_DEFAULT)

    val context = androidx.compose.ui.platform.LocalContext.current
    // ADR 0023 (the owner's decision 4): the first phone trash dialog offers Media management once.
    var askMedia by remember { mutableStateOf(false) }
    LaunchedEffect(requests) {
        if (requests.any { it.location.kind == dev.hfps.hoard.app.files.LocationKind.Device } && mediaManagementOf(context) == MediaManagement.NotAllowed &&
            !container.appSettings.trashMediaAskedNow()
        ) {
            askMedia = true
            container.appSettings.setTrashMediaAsked()
        }
    }
    LaunchedEffect(requests) {
        step = try {
            val plans = requests.map { r -> r to container.trash.prepareFolder(r.location, r.folder, r.paths, wholeShots = shots) }
            val offers = plans.map { (r, p) -> r to p.refused.filter { it.reason in PERMANENT_OFFER }.map { it.unit } }.filter { it.second.isNotEmpty() }
            val firstReason = plans.flatMap { it.second.refused }.firstOrNull { it.reason in PERMANENT_OFFER }?.reason
            if (plans.all { it.second.units.isEmpty() } && offers.isNotEmpty() && firstReason != null) {
                Step.Offer(offers, firstReason, emptyList())
            } else if (plans.isEmpty()) {
                Step.Confirm(emptyList(), TrashPlan(dev.hfps.hoard.app.trash.TrashPlace("device"), emptyList()))
            } else {
                Step.Confirm(plans, combined(plans.map { it.second }))
            }
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

    fun run(plans: List<Pair<TrashRequest, TrashPlan>>) {
        step = Step.Running
        scope.launch {
            val reports = ArrayList<Pair<TrashRequest, dev.hfps.hoard.app.trash.TrashReport>>()
            try {
                for ((r, p) in plans) {
                    if (p.units.isEmpty() && p.refused.isEmpty()) continue
                    reports += r to container.trash.start(p).await()
                    if (reports.last().second.cancelled.isNotEmpty()) break
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.show(res.getString(errorTitle(errorCodeOf(e))))
                finish(reports.flatMap { it.second.trashed.map { t -> t.unit } }, emptyList())
                return@launch
            }
            val all = reports.map { it.second }
            val trashed = all.flatMap { r -> r.trashed.map { it.unit } }
            val partial = all.sumOf { it.partial.size }
            val messages = buildList {
                if (trashed.isNotEmpty()) add(res.getQuantityString(R.plurals.trash_moved, trashed.size, trashed.size.toString()))
                if (partial > 0) add(res.getQuantityString(R.plurals.trash_partial, partial, partial.toString()))
                if (all.any { it.cancelled.isNotEmpty() }) add(res.getString(R.string.trash_cancelled))
                val other = all.flatMap { it.refused }.filter { it.reason !in PERMANENT_OFFER }
                if (other.isNotEmpty()) {
                    add(res.getQuantityString(R.plurals.trash_refused_count, other.size, other.size.toString(), res.getString(reasonText(other.first().reason))))
                }
            }
            if (messages.isNotEmpty()) toaster.show(messages.joinToString("\n"), long = messages.size > 1)
            val offers = reports.map { (r, rep) -> r to rep.refused.filter { it.reason in PERMANENT_OFFER }.map { it.unit } }.filter { it.second.isNotEmpty() }
            val reason = all.flatMap { it.refused }.firstOrNull { it.reason in PERMANENT_OFFER }?.reason
            if (offers.isNotEmpty() && reason != null) {
                step = Step.Offer(offers, reason, trashed)
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
                        TextButton({ run(s.plans) }, Modifier.testTag("trash-confirm")) {
                            Text(stringResource(R.string.trash_confirm_action), color = HfpsTheme.colors.primary, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                },
                dismiss = { TextButton(onDismiss) { Text(stringResource(if (count == 0) R.string.t_common_close else R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
            ) {
                TrashConfirmBody(plan, s.plans.map { (r, p) -> trashWhere(r.location, p) }.distinct().joinToString(", "), autoDays)
                if (leftOut != null) Text(leftOut, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 8.dp).testTag("trash-left-out"))
                if (askMedia) MediaManagementAsk { dev.hfps.hoard.app.ui.trash.openMediaManagement(context) }
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
            var busy by remember { mutableStateOf(false) }
            val count = s.all.size
            val first = s.units.first().first.location
            HoardDialog(
                title = if (shots) pluralStringResource(R.plurals.trash_permanent_title_shots, count, formatNumber(count, locale()))
                else pluralStringResource(R.plurals.trash_permanent_title, count, formatNumber(count, locale())),
                onDismiss = { if (!busy) finish(s.trashed, emptyList()) },
                // Keep is the default (the confirm slot); deleting for good is the other choice, never the default.
                confirm = {
                    TextButton({ finish(s.trashed, emptyList()) }, enabled = !busy, modifier = Modifier.testTag("trash-keep")) {
                        Text(stringResource(R.string.trash_keep), color = HfpsTheme.colors.primary, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                    }
                },
                dismiss = {
                    TextButton(
                        {
                            busy = true
                            scope.launch {
                                val deleted = s.units.flatMap { (r, units) -> container.trash.deletePermanently(r.location, units) }
                                toaster.show(res.getQuantityString(R.plurals.trash_deleted_permanently, deleted.size, deleted.size.toString()))
                                finish(s.trashed, deleted)
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.testTag("trash-delete-permanently"),
                    ) {
                        Text(stringResource(R.string.trash_delete_permanently), color = HfpsTheme.colors.destructive)
                    }
                },
            ) {
                PermanentDeleteBody(s.all, s.units.map { placeOf(it.first.location) }.distinct().joinToString(", "), first.kind == dev.hfps.hoard.app.files.LocationKind.Device, s.reason, shots)
            }
        }
    }
}

/** The one-time offer of Media management in the first phone trash dialog. */
@Composable
fun MediaManagementAsk(onAllow: () -> Unit) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.trash_media_ask), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        dev.hfps.hoard.designsystem.components.SecondaryButton(stringResource(R.string.trash_media_allow), onAllow, modifier = Modifier.testTag("trash-media-allow"))
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

/** The place of a location for the dialogs: `machine › share`, the drive's or the phone's name. */
fun placeOf(location: BrowseLocation): String =
    listOfNotNull(location.name, location.share?.takeIf { location.isSmb }).joinToString(" › ")

/**
 * The permanent delete's body (review of PR #59): exactly the files that go (the selection, sidecars only with a shot's
 * last member; culling: the whole reject shots, with the shot count as before), their size and place, why the trash
 * cannot take them, and that it is permanent.
 */
@Composable
fun PermanentDeleteBody(units: List<TrashUnit>, place: String, phone: Boolean, reason: TrashRefusal, shots: Boolean) {
    val files = units.sumOf { it.all.size }
    val bytes = units.sumOf { it.bytes }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (shots) {
            val where = if (phone) stringResource(R.string.t_cull_delete_where_phone) else stringResource(R.string.t_cull_delete_where_smb, place)
            Text(
                pluralStringResource(R.plurals.t_cull_delete_body, files, formatNumber(units.size, locale()), formatNumber(files, locale()), bytes(bytes), where),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
            Text(stringResource(R.string.trash_permanent_reason, stringResource(reasonText(reason))), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        } else if (units.any { u -> u.files.any { it.isDirectory } }) {
            // A folder's size is not known without walking it: its count and place only.
            Text(
                pluralStringResource(R.plurals.trash_permanent_folders, units.size, formatNumber(units.size, locale()), place, stringResource(reasonText(reason))),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
        } else {
            Text(
                pluralStringResource(R.plurals.trash_permanent_files, files, formatNumber(files, locale()), bytes(bytes), place, stringResource(reasonText(reason))),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
        }
        Text(stringResource(R.string.t_cull_delete_permanent), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.destructive)
    }
}

/** Units of an outcome list that went to the trash entirely. */
fun List<TrashOutcome>.trashedUnits(): List<TrashUnit> = filterIsInstance<TrashOutcome.Trashed>().map { it.unit }
