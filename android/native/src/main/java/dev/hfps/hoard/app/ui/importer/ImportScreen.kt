package dev.hfps.hoard.app.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** The first launch after the upgrade from Hoard 1: progress, then what came over (or why nothing did). */
@Composable
fun ImportScreen(state: ImportUiState, onContinue: () -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    HfpsScaffold(
        modifier = modifier,
        topBar = { HfpsTopBar(title = stringResource(R.string.import_title)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
                when (state) {
                    is ImportUiState.Imported -> Imported(state, onContinue)
                    is ImportUiState.Failed -> Failed(state, onRetry, onContinue)
                    else -> Running(state as? ImportUiState.Running)
                }
            }
        }
    }
}

@Composable
private fun Running(state: ImportUiState.Running?) {
    val progress = state?.progress
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.import_running_text),
            style = HfpsTheme.typography.bodyLarge,
            color = HfpsTheme.colors.cardForeground,
        )
        if (progress == null || progress.total == 0) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = HfpsTheme.colors.primary,
                trackColor = HfpsTheme.colors.muted,
            )
        } else {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
                color = HfpsTheme.colors.primary,
                trackColor = HfpsTheme.colors.muted,
            )
            Text(
                text = stringResource(R.string.import_running_progress, progress.done, progress.total),
                style = HfpsTheme.typography.ui,
                color = HfpsTheme.colors.mutedForeground,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

@Composable
private fun Imported(state: ImportUiState.Imported, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Status(
            icon = { Icon(PhosphorRegular.CheckCircle, null, Modifier.size(28.dp), tint = HfpsTheme.colors.primary) },
            title = stringResource(R.string.import_done_title),
            text = stringResource(R.string.import_done_text),
        )
        Eyebrow(stringResource(R.string.import_done_counts), Modifier.padding(start = 16.dp, top = 16.dp))
        val rows = listOf(
            Triple(PhosphorRegular.HardDrives, R.plurals.import_count_machines, state.counts["machines"] ?: 0),
            Triple(PhosphorRegular.Star, R.plurals.import_count_favorites, state.counts["favorites"] ?: 0),
            Triple(PhosphorRegular.Images, R.plurals.import_count_decisions, state.counts["cull_decisions"] ?: 0),
            Triple(PhosphorRegular.Copy, R.plurals.import_count_transfers, state.counts["transfers"] ?: 0),
        )
        Column {
            rows.forEachIndexed { index, (icon, plural, count) ->
                if (index > 0) HfpsRowDivider(Modifier.padding(start = 54.dp))
                HfpsListRow(title = pluralStringResource(plural, count, count), icon = icon)
            }
        }
        PrimaryButton(
            text = stringResource(R.string.import_continue),
            onClick = onContinue,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp).fillMaxWidth(),
        )
    }
}

@Composable
private fun Failed(state: ImportUiState.Failed, onRetry: () -> Unit, onContinue: () -> Unit) {
    val reason = when (state.failure) {
        ImportFailure.TooNew -> R.string.import_failed_too_new
        ImportFailure.Unreadable -> R.string.import_failed_unreadable
        ImportFailure.SchemaMismatch -> R.string.import_failed_schema
        ImportFailure.Other -> R.string.import_failed_other
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Status(
            icon = { Icon(PhosphorRegular.WarningCircle, null, Modifier.size(28.dp), tint = HfpsTheme.colors.destructive) },
            title = stringResource(R.string.import_failed_title),
            text = stringResource(reason) + "\n\n" + stringResource(R.string.import_failed_note),
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = stringResource(R.string.import_retry),
                onClick = onRetry,
                icon = PhosphorRegular.ArrowsClockwise,
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                text = stringResource(R.string.import_skip),
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Status(icon: @Composable () -> Unit, title: String, text: String) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        icon()
        Text(title, style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
        Text(text, style = HfpsTheme.typography.body, color = HfpsTheme.colors.mutedForeground)
    }
}
