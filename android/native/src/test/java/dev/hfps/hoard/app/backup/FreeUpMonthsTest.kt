package dev.hfps.hoard.app.backup

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.api.CandidateDevice
import dev.hfps.hoard.server.backup.BackupKind
import dev.hfps.hoard.server.backup.FreeShot
import dev.hfps.hoard.server.db.BackupFileEntity
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** Free up's preview grouping and Settings › Backup's "Reveal not grouped" (ADR 0024 A3). */
class FreeUpMonthsTest {
    private fun shot(name: String, iso: String, video: Boolean = false, size: Long = 1000): FreeShot {
        val taken = Instant.parse(iso).toEpochMilli()
        val path = "DCIM/Camera/$name"
        val f = BackupFileEntity("r", path, "/storage/emulated/0/$path", 1, if (video) BackupKind.VIDEO else BackupKind.IMAGE, size, taken, taken, state = "stored")
        return FreeShot("device/DCIM/Camera/${name.substringBefore('.').lowercase()}", "r", "DCIM/Camera", name.substringBefore('.'), listOf(f))
    }

    @Test
    fun monthsOldestFirstWithCountsAndBytesInThePhonesZone() {
        val shots = listOf(
            shot("C.jpg", "2026-09-10T12:00:00Z"),
            shot("A.jpg", "2026-08-02T12:00:00Z"),
            shot("V.mp4", "2026-09-01T12:00:00Z", video = true, size = 5000),
            // 23:30 UTC on 31 August is already 1 September in Tokyo.
            shot("B.jpg", "2026-08-31T23:30:00Z"),
        )
        val utc = FreeUpMonths.group(shots, ZoneId.of("UTC"))
        assertThat(utc.map { it.month }).containsExactly(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).inOrder()
        assertThat(utc[0].shots.map { it.stem }).containsExactly("A", "B").inOrder()
        assertThat(utc[1].shots.map { it.stem }).containsExactly("V", "C").inOrder()
        assertThat(utc[1].photos to utc[1].videos).isEqualTo(1 to 1)
        assertThat(utc[1].bytes).isEqualTo(6000)
        val tokyo = FreeUpMonths.group(shots, ZoneId.of("Asia/Tokyo"))
        assertThat(tokyo[1].shots.map { it.stem }).containsExactly("B", "V", "C").inOrder()
        assertThat(FreeUpMonths.chosen(shots, setOf("device/DCIM/Camera/a")).map { it.stem }).containsExactly("C", "V", "B")
    }

    @Test
    fun revealNotGroupedOnlyWhenInstalledAndNoLiveRevealIsInThisPhonesGroup() {
        val now = 1_000L * 86_400_000
        val hoard = CandidateDevice("h", app = "hoard", lastSeenAt = now)
        val reveal = CandidateDevice("r", app = "reveal", lastSeenAt = now - 1000)
        assertThat(BackupFolders.revealNotGrouped(installed = true, listOf(hoard), now)).isTrue()
        assertThat(BackupFolders.revealNotGrouped(installed = true, listOf(hoard, reveal), now)).isFalse()
        assertThat(BackupFolders.revealNotGrouped(installed = false, listOf(hoard), now)).isFalse()
        // Revoked long ago (not seen in 90 days): not grouped any more.
        assertThat(BackupFolders.revealNotGrouped(installed = true, listOf(hoard, reveal.copy(lastSeenAt = now - 91L * 86_400_000)), now)).isTrue()
    }
}
