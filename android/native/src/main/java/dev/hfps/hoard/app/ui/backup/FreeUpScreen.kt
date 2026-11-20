package dev.hfps.hoard.app.ui.backup

import android.os.Build
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.backup.FreeUpMonth
import dev.hfps.hoard.app.backup.FreeUpMonths
import dev.hfps.hoard.app.backup.PhoneFreeUp
import dev.hfps.hoard.app.ui.common.bytes
import dev.hfps.hoard.app.ui.common.formatNumber
import dev.hfps.hoard.app.ui.common.locale
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.backup.FreeShot
import dev.hfps.hoard.server.backup.FreeUpBlock
import dev.hfps.hoard.server.backup.FreeUpEngine
import dev.hfps.hoard.server.backup.FreeUpExclusion
import dev.hfps.hoard.server.backup.FreeUpPlan
import dev.hfps.hoard.server.backup.FreeUpProgress
import dev.hfps.hoard.server.backup.FreeUpRules
import dev.hfps.hoard.server.backup.RevealGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Where free up stands. */
enum class FreeUpStage { Loading, Preview, Checking, Deleting, Done }

/** What a free up came to (the last screen). */
data class FreeUpResult(
    val files: Int = 0,
    val bytes: Long = 0,
    val shots: Int = 0,
    /** Shots that stay, with the reason (the server's answer, `changed`, `reveal`, …). */
    val kept: List<Pair<FreeShot, String>> = emptyList(),
    /** Shots the system did not delete (a declined or partial consent). */
    val declined: Int = 0,
    val keptForReveal: Int = 0,
    /** The server got the report now (else the next backup run sends it). */
    val reported: Boolean = true,
    val block: FreeUpBlock? = null,
)

/** Free up's state (ADR 0024 "Free up space"; goldens in `FreeUpScreenshotTest`). */
data class FreeUpUi(
    val stage: FreeUpStage = FreeUpStage.Loading,
    val block: FreeUpBlock? = null,
    val shots: List<FreeShot> = emptyList(),
    val excludedCounts: Map<FreeUpExclusion, Int> = emptyMap(),
    val gate: RevealGate = RevealGate.Off,
    /** Shots the owner left out (by key). */
    val leftOut: Set<String> = emptySet(),
    val rules: FreeUpRules = FreeUpRules(),
    val progress: FreeUpProgress = FreeUpProgress(),
    val result: FreeUpResult? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val chosen: List<FreeShot> get() = FreeUpMonths.chosen(shots, leftOut)
}

class FreeUpViewModel(private val container: AppContainer) : ViewModel() {
    private val freeUp: PhoneFreeUp get() = container.freeUp
    private val _ui = MutableStateFlow(FreeUpUi())
    val ui: StateFlow<FreeUpUi> = _ui

    init {
        load()
        viewModelScope.launch { freeUp.engine.progress.collect { p -> _ui.update { it.copy(progress = p) } } }
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(stage = FreeUpStage.Loading, result = null) }
            val rules = freeUp.rules()
            val preview = freeUp.engine.preview(rules)
            _ui.update {
                it.copy(
                    stage = FreeUpStage.Preview,
                    block = preview.block,
                    shots = preview.candidates.shots,
                    excludedCounts = preview.candidates.excluded,
                    gate = preview.gate,
                    leftOut = it.leftOut.filterTo(HashSet()) { k -> preview.candidates.shots.any { s -> s.key == k } },
                    rules = rules,
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    fun toggle(key: String) = _ui.update { it.copy(leftOut = if (key in it.leftOut) it.leftOut - key else it.leftOut + key) }

    fun toggleMonth(month: FreeUpMonth) = _ui.update { ui ->
        val keys = month.shots.map { it.key }
        val allOut = keys.all { it in ui.leftOut }
        ui.copy(leftOut = if (allOut) ui.leftOut - keys.toSet() else ui.leftOut + keys)
    }

    /**
     * The owner's go: the check just before (re-stat, hash, verify), then one system consent per request of at most
     * 1000 files; what each request really deleted is read back, reported and kept in `freed`.
     */
    fun start() {
        val shots = _ui.value.chosen
        if (shots.isEmpty() || _ui.value.stage != FreeUpStage.Preview) return
        viewModelScope.launch {
            _ui.update { it.copy(stage = FreeUpStage.Checking) }
            freeUp.settlePending()
            val check = freeUp.engine.check(shots)
            if (check.block != null && check.go.isEmpty()) {
                _ui.update { it.copy(stage = FreeUpStage.Done, result = FreeUpResult(kept = check.kept, keptForReveal = check.keptForReveal, block = check.block)) }
                return@launch
            }
            var answers = check.answers
            val stamps = HashMap(check.stamps)
            val kept = ArrayList(check.kept)
            var files = 0
            var bytes = 0L
            var freedShots = 0
            var declined = 0
            var reported = true
            var keptForReveal = check.keptForReveal
            for (initial in FreeUpPlan.requests(check.go, freeUp.requestSize())) {
                var request = initial
                // A yes is good for 10 minutes: a request made later (a slow consent before it) is checked again first.
                if (!freeUp.engine.fresh(request, answers)) {
                    _ui.update { it.copy(stage = FreeUpStage.Checking) }
                    val again = freeUp.engine.check(request)
                    answers = answers + again.answers
                    stamps += again.stamps
                    kept += again.kept
                    keptForReveal += again.keptForReveal
                    request = again.go
                }
                // Just before the dialog: every file as the check hashed it, every MediaStore id still naming that
                // file. A shot that changed is checked once more (hashed and verified again); else it stays.
                var (ok, dropped) = FreeUpPlan.confirm(request, stamps, freeUp::stamp, freeUp::mediaRow)
                if (dropped.isNotEmpty()) {
                    _ui.update { it.copy(stage = FreeUpStage.Checking) }
                    val again = freeUp.engine.check(dropped)
                    answers = answers + again.answers
                    stamps += again.stamps
                    kept += again.kept
                    keptForReveal += again.keptForReveal
                    val (ok2, still) = FreeUpPlan.confirm(again.go, stamps, freeUp::stamp, freeUp::mediaRow)
                    ok = ok + ok2
                    kept += still.map { it to FreeUpPlan.REASON_CHANGED }
                }
                request = ok
                if (request.isEmpty()) continue
                _ui.update { it.copy(stage = FreeUpStage.Deleting) }
                // Kept before the dialog: a process that dies while it is up settles it at the next start.
                freeUp.engine.awaitConsent(request, stamps)
                if (Build.VERSION.SDK_INT >= 30) freeUp.requestDelete(request)
                val gone = freeUp.gone(request)
                val settlement = FreeUpPlan.settle(request, gone)
                // Sidecars and listed RAWs go only while they are exactly what the check hashed.
                val done = freeUp.engine.freed(settlement) { f -> FreeUpPlan.unchanged(f, stamps, freeUp.stamp(f.absolute)) && freeUp.deleteOther(f) }
                freeUp.engine.consentSettled()
                files += done.files
                bytes += done.bytes
                freedShots += settlement.whole.size + settlement.partial.size
                declined += settlement.kept.size + settlement.partial.size
                reported = reported && done.reported
            }
            if (files > 0) freeUp.afterDeletion()
            _ui.update {
                it.copy(
                    stage = FreeUpStage.Done,
                    result = FreeUpResult(files, bytes, freedShots, kept, declined, keptForReveal, reported),
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = viewModelFactory { initializer { FreeUpViewModel(container) } }
    }
}

/** Free up space bound to the app (Settings › Backup › Free up space). */
@Composable
fun FreeUpScreen(container: AppContainer, onBack: () -> Unit) {
    val vm: FreeUpViewModel = viewModel(factory = FreeUpViewModel.factory(container))
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current
    val thumbs = remember(container) {
        { shot: FreeShot, px: Int ->
            shot.cover?.mediaId?.let { id ->
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(container.freeUp.uriOf(id), Size(px, px), null).asImageBitmap() else null
                }.getOrNull()
            }
        }
    }
    FreeUpContent(
        ui = ui,
        onBack = onBack,
        onToggle = vm::toggle,
        onToggleMonth = vm::toggleMonth,
        onStart = vm::start,
        onRetry = vm::load,
        thumb = { shot, px -> withContext(Dispatchers.IO) { thumbs(shot, px) } },
    )
}

/** The preview, the check, and the result (goldens in `FreeUpScreenshotTest`). */
@Composable
fun FreeUpContent(
    ui: FreeUpUi,
    onBack: () -> Unit,
    onToggle: (String) -> Unit = {},
    onToggleMonth: (FreeUpMonth) -> Unit = {},
    onStart: () -> Unit = {},
    onRetry: () -> Unit = {},
    thumb: suspend (FreeShot, Int) -> ImageBitmap? = { _, _ -> null },
) {
    HfpsScaffold(topBar = { HfpsTopBar(title = stringResource(R.string.freeup_title), onBack = onBack) }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                when (ui.stage) {
                    FreeUpStage.Loading -> Busy(stringResource(R.string.freeup_loading), null)
                    FreeUpStage.Checking, FreeUpStage.Deleting -> Busy(progressLine(ui), ui.progress)
                    FreeUpStage.Done -> Result(ui.result ?: FreeUpResult(), onBack)
                    FreeUpStage.Preview -> if (ui.block != null) {
                        Blocked(ui.block, onRetry)
                    } else {
                        Preview(ui, onToggle, onToggleMonth, onStart, thumb, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun progressLine(ui: FreeUpUi): String = when {
    ui.stage == FreeUpStage.Deleting -> stringResource(R.string.freeup_deleting)
    ui.progress.phase == FreeUpEngine.PHASE_HASHING -> stringResource(R.string.freeup_hashing, ui.progress.name.orEmpty(), formatNumber(ui.progress.done + 1, locale()), formatNumber(ui.progress.total, locale()))
    else -> stringResource(R.string.freeup_verifying)
}

@Composable
private fun Busy(line: String, progress: FreeUpProgress?) {
    Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp).testTag("freeup-busy"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(line, style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
        if (progress != null && progress.total > 0) {
            LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = HfpsTheme.colors.primary, trackColor = HfpsTheme.colors.muted)
        }
        Text(stringResource(R.string.freeup_nothing_yet), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
    }
}

@Composable
private fun Blocked(block: FreeUpBlock, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag("freeup-blocked"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(
                when (block) {
                    FreeUpBlock.NotPaired -> R.string.backup_unavailable_pair
                    FreeUpBlock.Unsupported -> R.string.backup_unavailable_old
                    FreeUpBlock.Offline -> R.string.backup_offline
                    FreeUpBlock.Unauthorized -> R.string.freeup_unauthorized
                },
            ),
            style = HfpsTheme.typography.body,
            color = HfpsTheme.colors.mutedForeground,
        )
        if (block == FreeUpBlock.Offline) SecondaryButton(stringResource(R.string.freeup_retry), onRetry)
    }
}

/** "2,340 photos and 112 videos from before 15 Oct 2026 · 18.4 GB". */
@Composable
fun freeUpSummary(shots: List<FreeShot>, rules: FreeUpRules, now: Long): String {
    val l = locale()
    val (photos, videos) = FreeUpPlan.counts(shots)
    val what = when {
        videos == 0 -> pluralStringResource(R.plurals.freeup_photos, photos, formatNumber(photos, l))
        photos == 0 -> pluralStringResource(R.plurals.freeup_videos, videos, formatNumber(videos, l))
        else -> stringResource(R.string.freeup_photos_and_videos, pluralStringResource(R.plurals.freeup_photos, photos, formatNumber(photos, l)), pluralStringResource(R.plurals.freeup_videos, videos, formatNumber(videos, l)))
    }
    val dated = if (rules.keepRecent) {
        val cutoff = Instant.ofEpochMilli(now - rules.recentDays * FreeUpPlan.DAY_MS).atZone(ZoneId.systemDefault()).toLocalDate()
        stringResource(R.string.freeup_before, what, DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(l).format(cutoff))
    } else {
        what
    }
    return dated + " · " + bytes(shots.sumOf { it.bytes })
}

@Composable
private fun Preview(
    ui: FreeUpUi,
    onToggle: (String) -> Unit,
    onToggleMonth: (FreeUpMonth) -> Unit,
    onStart: () -> Unit,
    thumb: suspend (FreeShot, Int) -> ImageBitmap?,
    modifier: Modifier,
) {
    val months = remember(ui.shots) { FreeUpMonths.group(ui.shots, ZoneId.systemDefault()) }
    val chosen = ui.chosen
    val l = locale()
    val monthFormat = remember(l) { DateTimeFormatter.ofPattern("LLLL yyyy", l) }
    LazyVerticalGrid(GridCells.Adaptive(96.dp), modifier.fillMaxWidth().testTag("freeup-grid")) {
        item("summary", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp).testTag("freeup-summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (chosen.isEmpty()) stringResource(R.string.freeup_none) else freeUpSummary(chosen, ui.rules, ui.now),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.cardForeground,
                )
                Text(stringResource(R.string.freeup_how), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
        if (ui.gate !is RevealGate.Off) {
            item("gate", span = { GridItemSpan(maxLineSpan) }) {
                Note(
                    when (ui.gate) {
                        is RevealGate.Stale -> R.string.freeup_reveal_stale
                        RevealGate.Ungrouped -> R.string.freeup_reveal_ungrouped
                        else -> R.string.freeup_reveal_on
                    },
                    PhosphorRegular.Warning,
                    "freeup-gate",
                )
            }
        }
        if (ui.excludedCounts.isNotEmpty()) {
            item("excluded", span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    excludedText(ui.excludedCounts, ui.rules),
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.mutedForeground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("freeup-excluded"),
                )
            }
        }
        for (m in months) {
            item("m-${m.month}", span = { GridItemSpan(maxLineSpan) }) {
                val goes = m.shots.filter { it.key !in ui.leftOut }
                Row(
                    Modifier.fillMaxWidth().clickable { onToggleMonth(m) }.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp).testTag("freeup-month"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow(monthFormat.format(m.month))
                        Text(
                            monthLine(goes) + " · " + bytes(goes.sumOf { it.bytes }),
                            style = HfpsTheme.typography.ui,
                            color = HfpsTheme.colors.mutedForeground,
                        )
                    }
                    Icon(
                        if (goes.isEmpty()) PhosphorRegular.Circle else PhosphorFill.CheckCircle,
                        stringResource(if (goes.isEmpty()) R.string.freeup_month_include else R.string.freeup_month_leave_out),
                        Modifier.size(22.dp),
                        tint = if (goes.isEmpty()) HfpsTheme.colors.mutedForeground else HfpsTheme.colors.primary,
                    )
                }
            }
            items(m.shots, key = { it.key }) { shot -> Cell(shot, shot.key !in ui.leftOut, onToggle, thumb) }
        }
    }
    Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.background).navigationBarsPadding().padding(16.dp)) {
        PrimaryButton(
            if (chosen.isEmpty()) stringResource(R.string.freeup_go_none) else stringResource(R.string.freeup_go, bytes(chosen.sumOf { it.bytes })),
            onStart,
            enabled = chosen.isNotEmpty() && !ui.gate.blocksAll,
            modifier = Modifier.fillMaxWidth().testTag("freeup-go"),
        )
    }
}

@Composable
private fun monthLine(shots: List<FreeShot>): String {
    val l = locale()
    val (photos, videos) = FreeUpPlan.counts(shots)
    return when {
        videos == 0 -> pluralStringResource(R.plurals.freeup_photos, photos, formatNumber(photos, l))
        photos == 0 -> pluralStringResource(R.plurals.freeup_videos, videos, formatNumber(videos, l))
        else -> stringResource(R.string.freeup_photos_and_videos, pluralStringResource(R.plurals.freeup_photos, photos, formatNumber(photos, l)), pluralStringResource(R.plurals.freeup_videos, videos, formatNumber(videos, l)))
    }
}

/** "Stay on the phone: 120 from the last 30 days · 8 favourites · …". */
@Composable
private fun excludedText(counts: Map<FreeUpExclusion, Int>, rules: FreeUpRules): String {
    val l = locale()
    val parts = FreeUpExclusion.entries.mapNotNull { e ->
        val n = counts[e] ?: return@mapNotNull null
        when (e) {
            FreeUpExclusion.Recent -> pluralStringResource(R.plurals.freeup_kept_recent, n, formatNumber(n, l), rules.recentDays)
            FreeUpExclusion.Favourite -> pluralStringResource(R.plurals.freeup_kept_favourites, n, formatNumber(n, l))
            FreeUpExclusion.Culling -> pluralStringResource(R.plurals.freeup_kept_culling, n, formatNumber(n, l))
            FreeUpExclusion.NotBackedUp -> pluralStringResource(R.plurals.freeup_kept_not_backed_up, n, formatNumber(n, l))
            FreeUpExclusion.NoMedia -> pluralStringResource(R.plurals.freeup_kept_no_media, n, formatNumber(n, l))
            FreeUpExclusion.Compressing -> pluralStringResource(R.plurals.freeup_kept_compressing, n, formatNumber(n, l))
        }
    }
    return stringResource(R.string.freeup_kept_intro, parts.joinToString(" · "))
}

@Composable
private fun Note(text: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).background(HfpsTheme.colors.card).padding(12.dp).testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = HfpsTheme.colors.warning)
        Text(stringResource(text), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.cardForeground)
    }
}

@Composable
private fun Cell(shot: FreeShot, goes: Boolean, onToggle: (String) -> Unit, thumb: suspend (FreeShot, Int) -> ImageBitmap?) {
    var image by remember(shot.key) { mutableStateOf<ImageBitmap?>(null) }
    val px = with(LocalDensity.current) { 128.dp.roundToPx() }
    LaunchedEffect(shot.key) { image = thumb(shot, px) }
    val label = stringResource(if (goes) R.string.freeup_shot_goes else R.string.freeup_shot_stays, shot.stem)
    Box(
        Modifier.padding(2.dp).aspectRatio(1f).background(HfpsTheme.colors.muted).clickable { onToggle(shot.key) }.testTag(if (goes) "freeup-shot-on" else "freeup-shot-off"),
    ) {
        image?.let { Image(it, label, Modifier.fillMaxSize().alpha(if (goes) 1f else 0.35f), contentScale = ContentScale.Crop) }
            ?: Text(shot.stem, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.align(Alignment.Center).padding(4.dp))
        if (shot.video) Icon(PhosphorFill.PlayCircle, null, Modifier.align(Alignment.BottomStart).padding(4.dp).size(18.dp), tint = HfpsTheme.colors.background)
        Box(
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(22.dp)
                .background(if (goes) HfpsTheme.colors.primary else HfpsTheme.colors.background.copy(alpha = 0.7f), androidx.compose.foundation.shape.CircleShape)
                .border(1.dp, if (goes) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground, androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (goes) Icon(PhosphorRegular.Check, label, Modifier.size(14.dp), tint = HfpsTheme.colors.primaryForeground)
        }
    }
}

/** A stay reason in words. */
@Composable
fun keptReason(reason: String): String = when {
    reason == "differs" -> stringResource(R.string.freeup_reason_differs)
    reason == "missing" || reason == "not_found" -> stringResource(R.string.freeup_reason_missing)
    reason == "changed_on_nas" -> stringResource(R.string.freeup_reason_changed_on_nas)
    reason == "unavailable" -> stringResource(R.string.freeup_reason_unavailable)
    reason == FreeUpPlan.REASON_CHANGED -> stringResource(R.string.freeup_reason_changed)
    reason == FreeUpPlan.REASON_COMPRESSING -> stringResource(R.string.freeup_reason_compressing)
    reason == FreeUpPlan.REASON_UNREADABLE -> stringResource(R.string.freeup_reason_unreadable)
    reason == "pending" -> stringResource(R.string.freeup_reason_pending)
    reason == "offline" -> stringResource(R.string.backup_offline)
    reason in FreeUpPlan.REVEAL_REASONS -> stringResource(R.string.freeup_reason_reveal)
    reason.startsWith("refused:") -> stringResource(R.string.freeup_reason_refused)
    else -> reason
}

@Composable
private fun Result(result: FreeUpResult, onDone: () -> Unit) {
    val l = locale()
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("freeup-result"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (result.files > 0) PhosphorRegular.CheckCircle else PhosphorRegular.Info, null, Modifier.size(22.dp), tint = HfpsTheme.colors.primary)
                Text(
                    if (result.files > 0) pluralStringResource(R.plurals.freeup_done, result.files, formatNumber(result.files, l), bytes(result.bytes)) else stringResource(R.string.freeup_done_none),
                    style = HfpsTheme.typography.body,
                    color = HfpsTheme.colors.cardForeground,
                    modifier = Modifier.testTag("freeup-done"),
                )
            }
            if (result.files > 0) Text(stringResource(R.string.freeup_done_gallery), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            if (!result.reported) Text(stringResource(R.string.freeup_done_report_later), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            result.block?.let { Text(stringResource(if (it == FreeUpBlock.Unauthorized) R.string.freeup_unauthorized else R.string.backup_offline), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.warning) }
        }
        if (result.declined > 0) {
            Text(
                pluralStringResource(R.plurals.freeup_declined, result.declined, formatNumber(result.declined, l)),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp).testTag("freeup-declined"),
            )
        }
        if (result.keptForReveal > 0) {
            Text(stringResource(R.string.freeup_kept_for_reveal, formatNumber(result.keptForReveal, l)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp).testTag("freeup-kept-reveal"))
        }
        val byReason = result.kept.filter { it.second !in FreeUpPlan.REVEAL_REASONS }.groupBy({ it.second }, { it.first })
        if (byReason.isNotEmpty()) {
            Eyebrow(stringResource(R.string.freeup_stayed), Modifier.padding(start = 16.dp, top = 8.dp))
            Column(Modifier.fillMaxWidth().background(HfpsTheme.colors.card).padding(16.dp).testTag("freeup-stayed"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((reason, shots) in byReason) {
                    Text(
                        stringResource(R.string.freeup_stayed_line, keptReason(reason), shots.take(3).joinToString { it.stem } + if (shots.size > 3) " +${shots.size - 3}" else ""),
                        style = HfpsTheme.typography.ui,
                        color = HfpsTheme.colors.cardForeground,
                    )
                }
            }
        }
        PrimaryButton(stringResource(R.string.freeup_close), onDone, modifier = Modifier.padding(16.dp).testTag("freeup-close"))
    }
}
