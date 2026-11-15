package dev.hfps.hoard.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.gallery.FlagChoice
import dev.hfps.hoard.app.gallery.GalleryFilter
import dev.hfps.hoard.app.gallery.KindFilter
import dev.hfps.hoard.app.gallery.SourceFilter
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsBottomSheet
import dev.hfps.hoard.designsystem.components.HfpsCheckRow
import dev.hfps.hoard.designsystem.components.HfpsChip
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The Gallery's filters (ADR 0021): kind, where, stars, pick / reject, edited, camera, dates. Applied as they change. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GalleryFilterSheet(vm: GalleryViewModel, onDismiss: () -> Unit) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val cameras by vm.cameras.collectAsStateWithLifecycle()
    val lenses by vm.lenses.collectAsStateWithLifecycle()
    var dates by remember { mutableStateOf(false) }
    fun set(f: GalleryFilter) = vm.setFilter(f)

    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("gallery-filter-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.gallery_filters), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
            @Composable
            fun Section(title: Int, content: @Composable () -> Unit) {
                Eyebrow(stringResource(title), Modifier.padding(top = 16.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
            }
            Section(R.string.gallery_filter_kind) {
                listOf(
                    KindFilter.All to R.string.gallery_kind_all,
                    KindFilter.Photos to R.string.gallery_kind_photos,
                    KindFilter.Videos to R.string.gallery_kind_videos,
                    KindFilter.RawPairs to R.string.gallery_kind_raw,
                    KindFilter.Motion to R.string.gallery_kind_motion,
                ).forEach { (k, label) -> HfpsChip(stringResource(label), filter.kind == k, { set(filter.copy(kind = k)) }, Modifier.testTag("filter-kind-${k.name}")) }
            }
            Section(R.string.gallery_filter_source) {
                listOf(
                    SourceFilter.All to R.string.gallery_source_all,
                    SourceFilter.Device to R.string.gallery_source_device,
                    SourceFilter.Server to R.string.gallery_source_server,
                    SourceFilter.Both to R.string.gallery_source_both,
                ).forEach { (k, label) -> HfpsChip(stringResource(label), filter.source == k, { set(filter.copy(source = k)) }, Modifier.testTag("filter-source-${k.name}")) }
            }
            Section(R.string.gallery_filter_rating) {
                HfpsChip(stringResource(R.string.gallery_rating_any), filter.minRating == 0, { set(filter.copy(minRating = 0)) })
                (1..5).forEach { n -> HfpsChip("$n+", filter.minRating == n, { set(filter.copy(minRating = n)) }, Modifier.testTag("filter-rating-$n"), icon = PhosphorRegular.Star) }
            }
            Section(R.string.gallery_filter_flag) {
                listOf(
                    FlagChoice.All to R.string.gallery_flag_all,
                    FlagChoice.Picked to R.string.gallery_flag_picked,
                    FlagChoice.Rejected to R.string.gallery_flag_rejected,
                    FlagChoice.Unmarked to R.string.gallery_flag_unmarked,
                ).forEach { (k, label) -> HfpsChip(stringResource(label), filter.flag == k, { set(filter.copy(flag = k)) }, Modifier.testTag("filter-flag-${k.name}")) }
            }
            HfpsCheckRow(stringResource(R.string.gallery_filter_edited), filter.editedOnly, { set(filter.copy(editedOnly = !filter.editedOnly)) }, Modifier.padding(top = 8.dp))
            if (cameras.isNotEmpty()) {
                Section(R.string.gallery_filter_camera) {
                    HfpsChip(stringResource(R.string.gallery_camera_any), filter.camera == null, { set(filter.copy(camera = null)) })
                    cameras.take(12).forEach { (name, count) -> HfpsChip("$name · $count", filter.camera == name, { set(filter.copy(camera = name)) }) }
                }
            }
            if (lenses.isNotEmpty()) {
                Section(R.string.gallery_filter_lens) {
                    HfpsChip(stringResource(R.string.gallery_lens_any), filter.lens == null, { set(filter.copy(lens = null)) })
                    lenses.take(12).forEach { (name, count) -> HfpsChip("$name · $count", filter.lens == name, { set(filter.copy(lens = name)) }) }
                }
            }
            Section(R.string.gallery_filter_date) {
                HfpsChip(stringResource(R.string.gallery_date_any), filter.from == null && filter.to == null, { set(filter.copy(from = null, to = null)) })
                val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                val label = if (filter.from != null || filter.to != null) listOfNotNull(filter.from?.format(format), filter.to?.format(format)).joinToString(" – ") else stringResource(R.string.gallery_date_pick)
                HfpsChip(label, filter.from != null || filter.to != null, { dates = true }, icon = PhosphorRegular.CalendarBlank)
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                GhostButton(stringResource(R.string.gallery_filters_clear), { set(GalleryFilter(query = filter.query)) })
                PrimaryButton(stringResource(R.string.gallery_filters_done), onDismiss)
            }
        }
    }
    if (dates) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = filter.from?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
            initialSelectedEndDateMillis = filter.to?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        DatePickerDialog(
            onDismissRequest = { dates = false },
            confirmButton = {
                TextButton({
                    set(filter.copy(from = day(state.selectedStartDateMillis), to = day(state.selectedEndDateMillis) ?: day(state.selectedStartDateMillis)))
                    dates = false
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton({ dates = false }) { Text(stringResource(android.R.string.cancel)) } },
        ) { DateRangePicker(state, Modifier.padding(top = 8.dp)) }
    }
}



/** Go to a month (ADR 0021): every year of the library with its months, each with its count. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun GoToMonthSheet(layout: dev.hfps.hoard.app.gallery.GalleryLayout, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val counts = remember(layout) {
        layout.entries.groupingBy { it.day.year * 100 + it.day.monthValue }.eachCount()
    }
    HfpsBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("gallery-goto")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            Text(stringResource(R.string.gallery_goto), style = HfpsTheme.typography.title, color = HfpsTheme.colors.cardForeground)
            layout.marks.groupBy { it.year }.forEach { (year, marks) ->
                Eyebrow(year.toString(), Modifier.padding(top = 16.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    marks.forEach { m ->
                        val name = java.time.format.DateTimeFormatter.ofPattern("LLL", locale).format(LocalDate.of(m.year, m.month, 1))
                        HfpsChip("$name · ${counts[m.year * 100 + m.month] ?: 0}", false, {
                            onGo(m.row)
                            onDismiss()
                        }, Modifier.testTag("goto-${m.year}-${m.month}"))
                    }
                }
            }
        }
    }
}
