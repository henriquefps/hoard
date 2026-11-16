package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The Trash screen's grouping (ADR 0023 "Trash screen"): by day, then place and folder; settled rows only. */
class TrashGroupsTest {
    @Test
    fun `days newest first, folders by place then path, unsettled rows left out`() {
        val days = TrashGroups.of(TrashSamples.rows, setOf(TrashSamples.usb), TrashSamples::label, ZoneOffset.UTC)
        assertThat(days.map { it.date }).containsExactly(LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 2)).inOrder()
        val first = days.first()
        // 2026-11-14 and 2026-11-14-2 are one day.
        assertThat(first.folders.map { it.placeLabel to it.folder }).containsExactly(
            "NAS › photos" to "Imagens/2024/Home",
            "NAS › photos" to "Imagens/2024/Trip",
            "SANDISK" to "DCIM/100MSDCF",
        ).inOrder()
        val trip = first.folders[1]
        // The `moving` DSC01003 is not listed.
        assertThat(trip.files).isEqualTo(3)
        assertThat(trip.shots).isEqualTo(2)
        assertThat(trip.bytes).isEqualTo(24_800_000L + 6_100_000 + 24_700_000)
        assertThat(first.folders.last().reachable).isFalse()
        assertThat(first.reachable).isTrue()
        assertThat(days.sumOf { it.files }).isEqualTo(8)
    }

    @Test
    fun `restore outcomes are summed and the first conflict named`() {
        val rows = TrashSamples.rows.take(3)
        val outcomes = listOf(
            RestoreOutcome.Restored(rows.take(2)),
            RestoreOutcome.Conflict(rows.drop(2), listOf("Imagens/2024/Trip/DSC01002.ARW")),
            RestoreOutcome.Partial(rows, rows.take(1), rows.drop(1), null, null),
        )
        assertThat(TrashGroups.restoredFiles(outcomes)).isEqualTo(3)
        assertThat(TrashGroups.firstConflict(outcomes)).isEqualTo("Imagens/2024/Trip/DSC01002.ARW")
    }
}
