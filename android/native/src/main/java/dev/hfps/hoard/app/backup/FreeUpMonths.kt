package dev.hfps.hoard.app.backup

import dev.hfps.hoard.server.backup.FreeShot
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/** One month of free up's preview: its shots (oldest first) and what freeing them gives back. */
data class FreeUpMonth(val month: YearMonth, val shots: List<FreeShot>) {
    val photos: Int get() = shots.count { !it.video }
    val videos: Int get() = shots.count { it.video }
    val bytes: Long get() = shots.sumOf { it.bytes }
}

/** The preview's grouping (pure, tested): by month of capture in the phone's time zone, oldest month first. */
object FreeUpMonths {
    fun group(shots: List<FreeShot>, zone: ZoneId): List<FreeUpMonth> = shots
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.taken).atZone(zone)) }
        .map { (m, list) -> FreeUpMonth(m, list.sortedBy { it.taken }) }
        .sortedBy { it.month }

    /** Shots that go: the candidates minus the ones the owner left out. */
    fun chosen(shots: List<FreeShot>, excluded: Set<String>): List<FreeShot> = shots.filter { it.key !in excluded }
}
