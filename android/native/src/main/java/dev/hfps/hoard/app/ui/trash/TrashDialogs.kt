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
import dev.hfps.hoard.server.trash.MovePreview
import dev.hfps.hoard.server.trash.MoveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** What a trash dialog moves: [paths] of [folder] in [location] (the trash adds each shot's other files and sidecars). */
data class TrashRequest(val location: BrowseLocation, val folder: String, val paths: List<String>)

/**
 * What left the folder: units moved to the trash entirely (the server's moves included), and units deleted permanently
 * instead; [serverMoved]: the server items the server moved (ADR 0023 Amendment (2.10.0)).
 */
data class TrashDone(val trashed: List<TrashUnit>, val deleted: List<TrashUnit>, val serverMoved: List<String> = emptyList()) {
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
    TrashRefusal.PHOTO_SERVER_UNREACHABLE -> R.string.trash_reason_photo_server_unreachable
    TrashRefusal.NOT_ON_SERVER_YET -> R.string.trash_reason_not_on_server_yet
    TrashRefusal.PART_OF_SHOT -> R.string.trash_reason_part_of_shot
    TrashRefusal.FOLDER_ON_SERVER -> R.string.trash_reason_folder_on_server
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

    /** Each request with its plan; [plan] is all of them together, for the counts; [server]: the server's dry run. */
    data class Confirm(val plans: List<Pair<TrashRequest, TrashPlan>>, val plan: TrashPlan, val server: ServerPart? = null) : Step
    data object Running : Step

    /** What the server did with the photos (ADR 0023 Amendment (2.10.0)): moved, skipped and failed, with Retry. */
    data class ServerDone(val result: MoveResult, val trashed: List<TrashUnit>, val serverUnits: List<dev.hfps.hoard.app.trash.ServerUnit>, val retrying: Boolean = false) : Step

    /** Units the trash refused that can be deleted permanently instead, by request ([trashed] already went to the trash). */
    data class Offer(val units: List<Pair<TrashRequest, List<TrashUnit>>>, val reason: TrashRefusal, val trashed: List<TrashUnit>) : Step {
        val all: List<TrashUnit> get() = units.flatMap { it.second }
    }
}

/** Plans of several places shown as one (the counts, the size, the warnings; never run as one). */
private fun combined(plans: List<TrashPlan>): TrashPlan =
    TrashPlan(plans.first().place, plans.flatMap { it.units }, plans.flatMap { it.refused }, plans.any { it.oldServerWarning }, plans.flatMap { it.server })

/**
 * The server's part of a Move to trash (ADR 0023 Amendment (2.10.0)): the photos it moves ([ids]), its dry run
 * ([preview]; null when it could not be reached), and the items whose `on_phone` skip is not news ([phoneSelected]: their
 * phone copy is in the selection, it goes to the phone's trash).
 */
data class ServerPart(val ids: List<String>, val preview: MovePreview?, val phoneSelected: Set<String> = emptySet()) {
    /** Skips worth telling (on_phone of a selected phone copy is not one). */
    val skipped: Map<String, String> get() = preview?.skipped.orEmpty().filter { (id, why) -> !(why == "on_phone" && id in phoneSelected) }
    val movable: Int get() = preview?.movable ?: 0

    /** Nothing of it can move now: unreachable, or every item skipped, or only NAS shots while the write mount is blocked. */
    val blocked: Boolean get() = preview == null || movable == 0 || preview.nasBlocked != null && preview.serverItems == 0
}

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
    /** Server photos the server moves (the Gallery, ADR 0023 Amendment (2.10.0)); Files' and culling's come with their plans. */
    serverItems: List<String> = emptyList(),
    /** Items whose phone copy is selected too (their `on_phone` skip is expected). */
    phoneSelected: Set<String> = emptySet(),
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
            val ids = (serverItems + plans.flatMap { it.second.serverItems }).distinct()
            val server = if (ids.isEmpty()) null else ServerPart(ids, runCatching { container.trash.server.dryRun(ids) }.getOrElse { if (it is CancellationException) throw it else null }, phoneSelected)
            if (plans.all { it.second.units.isEmpty() } && server == null && offers.isNotEmpty() && firstReason != null) {
                Step.Offer(offers, firstReason, emptyList())
            } else if (plans.isEmpty()) {
                Step.Confirm(emptyList(), TrashPlan(dev.hfps.hoard.app.trash.TrashPlace("device"), emptyList()), server)
            } else {
                Step.Confirm(plans, combined(plans.map { it.second }), server)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Step.PrepareFailed(errorTitle(errorCodeOf(e)))
        }
    }

    var serverMoved by remember { mutableStateOf(emptyList<String>()) }

    fun finish(trashed: List<TrashUnit>, deleted: List<TrashUnit>) {
        onDone(TrashDone(trashed, deleted, serverMoved))
        onDismiss()
    }

    /** Units of the plans the server moved whole (every item of the unit moved). */
    fun movedUnits(units: List<dev.hfps.hoard.app.trash.ServerUnit>, moved: Collection<String>): List<TrashUnit> =
        units.filter { u -> u.itemIds.isNotEmpty() && u.itemIds.all { it in moved } }.map { it.unit }

    fun run(plans: List<Pair<TrashRequest, TrashPlan>>, server: ServerPart?) {
        step = Step.Running
        scope.launch {
            val reports = ArrayList<Pair<TrashRequest, dev.hfps.hoard.app.trash.TrashReport>>()
            var serverResult: MoveResult? = null
            val serverUnits = plans.flatMap { it.second.server }
            try {
                for ((r, p) in plans) {
                    if (p.units.isEmpty() && p.refused.isEmpty()) continue
                    reports += r to container.trash.start(p.copy(server = emptyList())).await()
                    if (reports.last().second.cancelled.isNotEmpty()) break
                }
                if (server != null && !server.blocked && reports.none { it.second.cancelled.isNotEmpty() }) {
                    val skipped = server.preview?.skipped.orEmpty().keys
                    serverResult = container.trash.server.move(server.ids.filter { it !in skipped })
                    serverMoved = serverResult.moved
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toaster.show(res.getString(errorTitle(errorCodeOf(e))))
                finish(reports.flatMap { it.second.trashed.map { t -> t.unit } }, emptyList())
                return@launch
            }
            val all = reports.map { it.second }
            val trashed = all.flatMap { r -> r.trashed.map { it.unit } } + movedUnits(serverUnits, serverResult?.moved.orEmpty())
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
            val sr = serverResult
            if (sr != null && (sr.failed.isNotEmpty() || sr.skipped.keys.any { it !in phoneSelected || sr.skipped[it] != "on_phone" })) {
                step = Step.ServerDone(sr, trashed, serverUnits)
            } else if (offers.isNotEmpty() && reason != null) {
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
            val count = plan.units.size + (s.server?.takeIf { !it.blocked }?.movable ?: 0)
            HoardDialog(
                title = if (count == 0) stringResource(R.string.trash_title)
                else if (allShots) pluralStringResource(R.plurals.trash_confirm_title_shots, count, formatNumber(count, locale()))
                else pluralStringResource(R.plurals.trash_confirm_title_items, count, formatNumber(count, locale())),
                onDismiss = onDismiss,
                confirm = {
                    if (count > 0) {
                        TextButton({ run(s.plans, s.server) }, Modifier.testTag("trash-confirm")) {
                            Text(stringResource(R.string.trash_confirm_action), color = HfpsTheme.colors.primary, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                        }
                    }
                },
                dismiss = { TextButton(onDismiss) { Text(stringResource(if (count == 0) R.string.t_common_close else R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
            ) {
                if (plan.units.isNotEmpty() || plan.refused.isNotEmpty() || s.server == null) {
                    TrashConfirmBody(plan, s.plans.map { (r, p) -> trashWhere(r.location, p) }.distinct().joinToString(", "), autoDays)
                }
                s.server?.let { ServerConfirmBody(it, container.compress.info.collectAsState().value?.trashDays ?: SERVER_TRASH_DAYS) }
                if (leftOut != null) Text(leftOut, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 8.dp).testTag("trash-left-out"))
                if (askMedia) MediaManagementAsk { dev.hfps.hoard.app.ui.trash.openMediaManagement(context) }
            }
        }
        Step.Running -> HoardDialog(
            title = stringResource(R.string.trash_running),
            onDismiss = {},
            confirm = {},
            dismiss = { TextButton({ container.trash.cancel(); container.trash.server.cancel() }) { Text(stringResource(R.string.t_common_cancel), color = HfpsTheme.colors.foreground) } },
        ) {
            val serverProgress by container.trash.server.progress.collectAsState()
            val sp = serverProgress
            if (sp != null && progress == null) ServerProgressBody(sp) else TrashProgressBody(progress)
        }
        is Step.ServerDone -> HoardDialog(
            title = if (s.result.failed.isEmpty()) stringResource(R.string.trash_server_done_title) else stringResource(R.string.trash_server_failed_title),
            onDismiss = { if (!s.retrying) finish(s.trashed, emptyList()) },
            confirm = {
                if (s.result.failed.isNotEmpty()) {
                    TextButton(
                        {
                            step = s.copy(retrying = true)
                            scope.launch {
                                val again = s.result.merge(container.trash.server.move(s.result.retryable))
                                serverMoved = again.moved
                                val trashed = (s.trashed + movedUnits(s.serverUnits, again.moved)).distinct()
                                step = Step.ServerDone(again, trashed, s.serverUnits)
                            }
                        },
                        enabled = !s.retrying,
                        modifier = Modifier.testTag("trash-retry-failed"),
                    ) {
                        Text(stringResource(R.string.trash_server_retry), color = HfpsTheme.colors.primary, style = HfpsTheme.typography.ui.copy(fontWeight = FontWeight.SemiBold))
                    }
                }
            },
            dismiss = { TextButton({ finish(s.trashed, emptyList()) }, enabled = !s.retrying) { Text(stringResource(R.string.t_common_close), color = HfpsTheme.colors.foreground) } },
        ) {
            if (s.retrying) LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
            ServerResultBody(s.result, phoneSelected)
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

/** The server trash's days when the server does not say (hoard-server's default `compress.trashDays`). */
const val SERVER_TRASH_DAYS = 30

/**
 * The server's part of the confirmation (ADR 0023 Amendment (2.10.0)): how many photos, files and how much go to the
 * photo server's trash, what it leaves out and why (held, busy, on a phone…), the write mount's problem, and the
 * warnings (the only copy; Reveal's edits).
 */
@Composable
fun ServerConfirmBody(part: ServerPart, serverDays: Int) {
    val p = part.preview
    Column(Modifier.padding(top = 8.dp).testTag("trash-server-confirm"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (p == null) {
            Text(stringResource(R.string.trash_reason_photo_server_unreachable), style = HfpsTheme.typography.body, color = HfpsTheme.colors.destructive)
            return@Column
        }
        if (p.movable > 0) {
            Text(
                pluralStringResource(
                    R.plurals.trash_server_confirm_body,
                    p.movable,
                    formatNumber(p.movable, locale()),
                    pluralStringResource(R.plurals.trash_server_files_size, p.nasFiles + p.serverItems, formatNumber(p.nasFiles + p.serverItems, locale()), bytes(p.size)),
                ),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
            )
            Text(stringResource(R.string.trash_server_auto_note, serverDays), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        p.nasBlocked?.let { Text(stringResource(R.string.trash_server_blocked, serverReason(it)), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.destructive, modifier = Modifier.testTag("trash-server-blocked")) }
        if (p.onlyCopy.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.trash_server_only_copy, p.onlyCopy.size, formatNumber(p.onlyCopy.size, locale())), style = HfpsTheme.typography.uiMedium, color = HfpsTheme.colors.warning)
        }
        if (p.edits.isNotEmpty()) {
            Text(pluralStringResource(R.plurals.trash_server_edits, p.edits.size, formatNumber(p.edits.size, locale())), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        for ((why, n) in part.skipped.values.groupingBy { it }.eachCount()) {
            Text(pluralStringResource(R.plurals.trash_server_skipped, n, formatNumber(n, locale()), serverReason(why)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
    }
}

@Composable
fun ServerProgressBody(progress: Pair<Int, Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.trash_server_progress, progress.first, progress.second), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        LinearProgressIndicator(
            progress = { if (progress.second == 0) 0f else progress.first.toFloat() / progress.second },
            modifier = Modifier.fillMaxWidth(),
            color = HfpsTheme.colors.primary,
            trackColor = HfpsTheme.colors.muted,
        )
    }
}

/** Per item: how many moved, and the skipped and failed ones by reason, in words. */
@Composable
fun ServerResultBody(result: MoveResult, phoneSelected: Set<String> = emptySet()) {
    Column(Modifier.testTag("trash-server-result"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(pluralStringResource(R.plurals.trash_moved, result.moved.size, formatNumber(result.moved.size, locale())), style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
        val skipped = result.skipped.filter { (id, why) -> !(why == "on_phone" && id in phoneSelected) }
        for ((why, n) in skipped.values.groupingBy { it }.eachCount()) {
            Text(pluralStringResource(R.plurals.trash_server_skipped, n, formatNumber(n, locale()), serverReason(why)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        }
        for ((why, n) in result.failedBy) {
            Text(pluralStringResource(R.plurals.trash_server_failed, n, formatNumber(n, locale()), serverReason(why)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive)
        }
    }
}

/** A server trash reason (skip, failure or refusal of `/v1/trash`) in words. */
@Composable
fun serverReason(reason: String): String = serverReasonText(LocalResources.current, reason)

fun serverReasonText(res: android.content.res.Resources, reason: String): String {
    val id = when (reason) {
        "not_found" -> R.string.trash_srv_not_found
        "trashed" -> R.string.trash_srv_trashed
        "held" -> R.string.trash_srv_held
        "busy" -> R.string.trash_srv_busy
        "on_phone" -> R.string.trash_srv_on_phone
        "nothing" -> R.string.trash_srv_nothing
        "changed" -> R.string.trash_srv_changed
        "path_taken" -> R.string.trash_srv_path_taken
        "not_writable" -> R.string.trash_srv_not_writable
        "not_same_device" -> R.string.trash_srv_not_same_device
        "unreadable" -> R.string.trash_srv_unreadable
        "partial" -> R.string.trash_srv_partial
        "state" -> R.string.trash_srv_state
        dev.hfps.hoard.server.trash.TrashReasons.WEB_ONLY -> R.string.trash_srv_web_only
        dev.hfps.hoard.server.trash.TrashReasons.NOT_HOARD -> R.string.trash_srv_not_hoard
        dev.hfps.hoard.server.trash.TrashReasons.UNREACHABLE -> R.string.trash_srv_unreachable
        dev.hfps.hoard.server.trash.TrashReasons.CANCELLED -> R.string.trash_srv_cancelled
        dev.hfps.hoard.server.trash.TrashReasons.RATE_LIMITED -> R.string.trash_srv_rate_limited
        in dev.hfps.hoard.server.trash.TrashReasons.WRITE_MOUNT -> R.string.trash_srv_write_mount
        "error" -> R.string.trash_srv_error
        else -> return res.getString(R.string.trash_srv_other, reason)
    }
    return res.getString(id)
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
