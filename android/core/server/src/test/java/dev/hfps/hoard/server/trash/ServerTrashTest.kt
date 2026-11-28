package dev.hfps.hoard.server.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.FakeServer
import dev.hfps.hoard.server.api.ServerApi
import dev.hfps.hoard.server.api.TrashFigures
import dev.hfps.hoard.server.api.TrashGroupIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ADR 0023 Amendment (2.10.0): server photos are trashed, restored and emptied through hoard-server 0.15.0's
 * `/v1/trash` (a fake answering its routes), never over SMB.
 */
class ServerTrashTest {
    private lateinit var server: FakeServer
    private lateinit var fake: FakeTrash
    private lateinit var routes: TrashRoutes
    private val waits = ArrayList<Long>()
    private val sleep: suspend (Long) -> Unit = { waits += it }

    @Before
    fun setUp() {
        server = FakeServer()
        fake = FakeTrash(server)
        routes = ApiTrashRoutes(ServerApi(server.url, { server.key }))
    }

    @After
    fun tearDown() = server.close()

    private fun live(n: Int, prefix: String = "it") = (1..n).map { i ->
        FakeTrash.Live("$prefix$i", "f1", "2026/rejects", "DSC%05d.ARW".format(i), 1000L + i).also { fake.live[it.id] = it }
    }

    @Test
    fun dryRunAddsUpBatchesAndSaysWhatIsSkipped() = runBlocking<Unit> {
        val ids = live(1203).map { it.id }.toMutableList()
        fake.live["held1"] = FakeTrash.Live("held1", "f1", "", "H.ARW", 5, skip = "held")
        fake.live["busy1"] = FakeTrash.Live("busy1", "f1", "", "B.MOV", 5, skip = "busy")
        fake.live["phone1"] = FakeTrash.Live("phone1", "f1", "", "P.JPG", 5, skip = "on_phone")
        fake.live["only1"] = FakeTrash.Live("only1", "f1", "", "O.JPG", 7, onlyCopy = true, edits = true)
        ids += listOf("held1", "busy1", "phone1", "only1")

        val p = ServerTrash.dryRun(routes, ids, sleep)

        assertThat(fake.moves.map { it.itemIds.size }).containsExactly(500, 500, 207).inOrder()
        assertThat(fake.moves.all { it.dryRun }).isTrue()
        assertThat(p.asked).isEqualTo(1207)
        assertThat(p.nasShots).isEqualTo(1204)
        assertThat(p.movable).isEqualTo(1204)
        assertThat(p.size).isEqualTo((1..1203).sumOf { 1000L + it } + 7)
        assertThat(p.skippedBy()).containsExactly("held", 1, "busy", 1, "on_phone", 1)
        assertThat(p.onlyCopy).containsExactly("only1")
        assertThat(p.edits).containsExactly("only1")
        assertThat(p.nasBlocked).isNull()
        assertThat(fake.live).hasSize(1207) // a dry run moves nothing
    }

    @Test
    fun dryRunSaysTheWriteMountProblemAndTheMoveFailsWithIt() = runBlocking<Unit> {
        val ids = live(3).map { it.id }
        fake.writeProblem = "write_mount_read_only"
        assertThat(ServerTrash.dryRun(routes, ids, sleep).nasBlocked).isEqualTo("write_mount_read_only")

        val r = ServerTrash.move(routes, ids, sleep = sleep)
        assertThat(r.moved).isEmpty()
        assertThat(r.failed).containsExactly("it1", "write_mount_read_only", "it2", "write_mount_read_only", "it3", "write_mount_read_only")
        assertThat(fake.trashed).isEmpty()
    }

    @Test
    fun partialFailuresAreRetriedAndMerged() = runBlocking<Unit> {
        val ids = live(5).map { it.id }
        fake.failNext["it2"] = ArrayDeque(listOf("not_writable"))
        fake.failNext["it4"] = ArrayDeque(listOf("error", "error"))
        val progress = ArrayList<Pair<Int, Int>>()

        val first = ServerTrash.move(routes, ids, progress = { d, t -> progress += d to t }, sleep = sleep)
        assertThat(first.moved).containsExactly("it1", "it3", "it5")
        assertThat(first.failedBy).containsExactly("not_writable", 1, "error", 1)
        assertThat(first.retryable).containsExactly("it2", "it4")
        assertThat(progress.last()).isEqualTo(5 to 5)

        val second = first.merge(ServerTrash.move(routes, first.retryable, sleep = sleep))
        assertThat(second.moved).containsExactly("it1", "it3", "it5", "it2")
        assertThat(second.failed).containsExactly("it4", "error")

        val third = second.merge(ServerTrash.move(routes, second.retryable, sleep = sleep))
        assertThat(third.failed).isEmpty()
        assertThat(third.moved).hasSize(5)
        assertThat(fake.trashed.keys).containsExactly("it1", "it2", "it3", "it4", "it5")
        // Moved through /v1: the unit is the device's, its group `hoard`.
        assertThat(fake.trashed.values.flatMap { it.units }.map { it.by }.toSet()).containsExactly(server.deviceId)
    }

    @Test
    fun aRateLimitWaitsAndAnUnreachableServerFailsTheBatch() = runBlocking<Unit> {
        val ids = live(2).map { it.id }
        fake.rateLimited = 1
        val r = ServerTrash.move(routes, ids, sleep = sleep)
        assertThat(r.moved).hasSize(2)
        assertThat(waits).containsExactly(1000L)

        val gone = ApiTrashRoutes(ServerApi("http://127.0.0.1:1", { server.key }, connectTimeoutMs = 500))
        val off = ServerTrash.move(gone, listOf("x1", "x2"), sleep = sleep)
        assertThat(off.failed).containsExactly("x1", TrashReasons.UNREACHABLE, "x2", TrashReasons.UNREACHABLE)
    }

    @Test
    fun cancelLeavesTheRestUnmoved() = runBlocking<Unit> {
        val ids = live(700).map { it.id }
        var batches = 0
        val r = ServerTrash.move(routes, ids, cancelled = { batches++ > 0 }, sleep = sleep)
        assertThat(r.moved).hasSize(500)
        assertThat(r.failedBy).containsExactly(TrashReasons.CANCELLED, 200)
        assertThat(fake.live).hasSize(200)
    }

    @Test
    fun listingIsPagedAndGroupedByDayAndPlace() = runBlocking<Unit> {
        fake.putTrashed("a", "A.ARW", "f1", "2026-11-20", 100, dir = "2026/x")
        fake.putTrashed("b", "B.ARW", "f1", "2026-11-20", 200, dir = "2026/x", by = "admin")
        fake.putTrashed("c", "C.ARW", "f2", "2026-11-21", 300)
        fake.putTrashed("h", "H.MOV", "f2", "2026-11-21", 400, held = true, compressedTo = "live-h")
        fake.putTrashed("d", "D.ARW", "f1", "2026-11-19", 50)

        val listing = ServerTrash.list(routes, sleep)
        assertThat(listing.entries.map { it.id }).containsExactly("d", "h", "c", "b", "a").inOrder()
        assertThat(listing.figures(TrashGroupIds.WEB)).isEqualTo(TrashFigures(1, 200))
        assertThat(listing.figures(TrashGroupIds.HOARD)).isEqualTo(TrashFigures(3, 450))
        assertThat(listing.figures(TrashGroupIds.COMPRESSED)).isEqualTo(TrashFigures(0, 0)) // held: not counted

        val days = ServerTrash.days(listing)
        assertThat(days.map { it.day }).containsExactly("2026-11-21", "2026-11-20", "2026-11-19").inOrder()
        val d21 = days.first()
        assertThat(d21.places.map { it.name }).containsExactly("Folder f2")
        assertThat(d21.places.single().heldLines.map { it.entry.id }).containsExactly("h")
        assertThat(d21.shots).isEqualTo(1) // the held one is listed, not counted
        assertThat(d21.bytes).isEqualTo(300)
        assertThat(days[1].places.single().dir).isEqualTo("2026/x")
        assertThat(days[1].shots).isEqualTo(2)
    }

    @Test
    fun restoreAndDeleteByUnitHeldNeverSent() = runBlocking<Unit> {
        fake.putTrashed("a", "A.ARW", "f1", "2026-11-20", 100)
        fake.putTrashed("b", "B.ARW", "f1", "2026-11-20", 200)
        fake.putTrashed("h", "H.MOV", "f1", "2026-11-20", 400, held = true, compressedTo = "x")
        val lines = ServerTrash.days(ServerTrash.list(routes, sleep)).single().places.single().lines
        val a = lines.first { it.entry.id == "a" }
        fake.restoreRefused[a.ref.refKey] = "path_taken"

        val restored = ServerTrash.restore(routes, lines.filter { !it.held }.map { it.ref }, sleep)
        assertThat(restored.done.map { it.itemId }).containsExactly("b")
        assertThat(restored.refused).containsExactly(a.ref.refKey, "path_taken")
        assertThat(restored.names).containsExactly(a.ref.refKey, "A.ARW")

        val deleted = ServerTrash.delete(routes, lines.filter { it.entry.id != "b" }, sleep)
        assertThat(deleted.done.map { it.itemId }).containsExactly("a")
        assertThat(deleted.refused.values).containsExactly(TrashReasons.HELD)
        assertThat(fake.deletes.flatten().map { it.itemId }).containsExactly("a") // the held unit was never sent
        assertThat(fake.trashed.keys).containsExactly("h")
    }

    @Test
    fun emptyConfirmsEachGroupAndALeftOutGroupStays() = runBlocking<Unit> {
        fake.putTrashed("w", "W.ARW", "f1", "2026-11-20", 100, by = "admin")
        fake.putTrashed("o", "O.ARW", "f1", "2026-11-20", 200)
        fake.putTrashed("c", "C.MOV", "f1", "2026-11-20", 300, by = "admin", compressedTo = "live-c")
        val shown = ServerTrash.list(routes, sleep).groups

        val r = ServerTrash.empty(routes, shown, listOf(TrashGroupIds.WEB, TrashGroupIds.COMPRESSED), sleep)
        assertThat(r).isInstanceOf(EmptyResult.Done::class.java)
        assertThat(fake.empties.single().groups).containsExactly(TrashGroupIds.WEB, TrashGroupIds.COMPRESSED).inOrder()
        assertThat(fake.empties.single().confirm).isEqualTo(TrashFigures(2, 400))
        assertThat(fake.trashed.keys).containsExactly("o") // the unchecked group stays
    }

    @Test
    fun emptyAfterTheTrashChangedAnswersTheCurrentFigures() = runBlocking<Unit> {
        fake.putTrashed("o", "O.ARW", "f1", "2026-11-20", 200)
        val shown = ServerTrash.list(routes, sleep).groups
        fake.putTrashed("p", "P.ARW", "f1", "2026-11-21", 50) // another device trashed one since

        val r = ServerTrash.empty(routes, shown, listOf(TrashGroupIds.HOARD), sleep)
        assertThat(r).isInstanceOf(EmptyResult.Conflict::class.java)
        assertThat((r as EmptyResult.Conflict).groups[TrashGroupIds.HOARD]).isEqualTo(TrashFigures(2, 250))
        assertThat(fake.trashed.keys).containsExactly("o", "p") // nothing deleted

        val again = ServerTrash.empty(routes, r.groups, listOf(TrashGroupIds.HOARD), sleep)
        assertThat(again).isInstanceOf(EmptyResult.Done::class.java)
        assertThat(fake.trashed).isEmpty()
    }

    @Test
    fun aShotInTwoGroupsIsCountedOnceByTheServer() = runBlocking<Unit> {
        // One item with a web unit and a Hoard unit: each group counts it, the server's total once.
        fake.putTrashed("x", "X.ARW", "f1", "2026-11-20", 100, by = "admin")
        fake.putTrashed("x", "X.ARW", "f1", "2026-11-21", 100)
        val shown = ServerTrash.list(routes, sleep).groups
        assertThat(ServerTrash.confirmOf(shown, TrashGroupIds.ALL)).isEqualTo(TrashFigures(2, 200))

        val r = ServerTrash.empty(routes, shown, TrashGroupIds.ALL, sleep)
        assertThat(r).isInstanceOf(EmptyResult.Done::class.java)
        assertThat(fake.empties.map { it.confirm }).containsExactly(TrashFigures(2, 200), TrashFigures(1, 200)).inOrder()
        assertThat(fake.trashed).isEmpty()
    }

    @Test
    fun autoEmptyTakesOnlyHoardsOldSmbMovesNeverHeldOrServerMoved() = runBlocking<Unit> {
        fake.putTrashed("old", "OLD.ARW", "f1", "2026-10-20", 100) // Hoard over SMB, 38 days ago
        fake.putTrashed("new", "NEW.ARW", "f1", "2026-11-20", 100) // Hoard over SMB, 7 days ago
        fake.putTrashed("dev", "DEV.ARW", "f1", "2026-10-01", 100, by = "dev-other") // moved by the server for a device
        fake.putTrashed("web", "WEB.ARW", "f1", "2026-10-01", 100, by = "admin") // moved by the server for the web
        fake.putTrashed("held", "HELD.MOV", "f1", "2026-10-01", 100, held = true)
        fake.putTrashed("cmp", "CMP.MOV", "f1", "2026-10-01", 100, compressedTo = "live")
        fake.putTrashed("edge", "EDGE.ARW", "f1", "2026-10-28", 100) // exactly 30 days

        // 30 days on 2026-11-27: the limit is 2026-10-28 (TrashService computes it as for its own day folders).
        val lines = ServerTrash.autoEmptyLines(ServerTrash.list(routes, sleep), "2026-10-28")
        assertThat(lines.map { it.entry.id }).containsExactly("old", "edge")
        assertThat(ServerTrash.autoEmptyLines(ServerTrash.list(routes, sleep), "")).isEmpty()

        ServerTrash.delete(routes, lines, sleep)
        assertThat(fake.trashed.keys).containsExactly("new", "dev", "web", "held", "cmp")
    }

    @Test
    fun theIndexMatchesHoardsSmbTrashByFolderAndTail() = runBlocking<Unit> {
        fake.putTrashed("a", "A.ARW", "f1", "2026-11-20", 100, dir = "2026/x", root = "2026")
        val index = ServerTrashIndex(ServerTrash.list(routes, sleep))
        // The server's `2026/.hoard-trash/2026-11-20/x/A.ARW`; a share exporting Imagens/2026 at its root (serverPrefix
        // `2026`) has it at `.hoard-trash/2026-11-20/x/A.ARW`: the same tail from `.hoard-trash` on.
        assertThat(index.covers("f1", ".hoard-trash/2026-11-20/x/A.ARW")).isTrue()
        assertThat(index.covers("f1", "Fotos/.HOARD-TRASH/2026-11-20/x/a.arw")).isTrue()
        assertThat(index.covers("f2", ".hoard-trash/2026-11-20/x/A.ARW")).isFalse()
        assertThat(index.covers(null, ".hoard-trash/2026-11-20/x/A.ARW")).isTrue()
        assertThat(index.covers("f1", ".hoard-trash/2026-11-21/x/A.ARW")).isFalse()
        assertThat(index.covers("f1", "2026/x/A.ARW")).isFalse()
    }

    // --- server-0150b: backup-only units, not_hoard -------------------------------------------------------------------

    @Test
    fun backupOnlyUnitsAreRestoredNeverDeletedFromADevice() = runBlocking<Unit> {
        fake.putTrashed("a", "A.ARW", "f1", "2026-11-20", 100)
        fake.putServerTrashed("b", "IMG_1.HEIC", "2026-11-20", 300)
        fake.putServerTrashed("c", "IMG_2.HEIC", "2026-11-20", 400)
        val listing = ServerTrash.list(routes, sleep)
        assertThat(listing.webOnly).isEqualTo(TrashFigures(2, 700))
        val lines = ServerTrash.days(listing).single().places.flatMap { it.lines }
        val backup = ServerTrash.days(listing).single().places.single { it.source == "server" }
        assertThat(backup.deletable).isEmpty() // no Delete permanently for them
        assertThat(backup.free).hasSize(2) // Restore still offered

        val deleted = ServerTrash.delete(routes, lines, sleep)
        assertThat(deleted.done.map { it.itemId }).containsExactly("a")
        assertThat(deleted.refusedBy).containsExactly(TrashReasons.WEB_ONLY, 2)
        assertThat(fake.deletes.flatten().map { it.itemId }).containsExactly("a") // never sent

        val restored = ServerTrash.restore(routes, listOf(lines.first { it.entry.id == "b" }.ref), sleep)
        assertThat(restored.done.map { it.itemId }).containsExactly("b")
        assertThat(fake.trashed.keys).containsExactly("c")
    }

    @Test
    fun emptyLeavesBackupOnlyUnitsAndTheConflictSaysSo() = runBlocking<Unit> {
        fake.putTrashed("o", "O.ARW", "f1", "2026-11-20", 200)
        fake.putServerTrashed("b", "IMG_1.HEIC", "2026-11-20", 300)
        val shown = ServerTrash.list(routes, sleep).groups
        assertThat(shown[TrashGroupIds.HOARD]).isEqualTo(TrashFigures(1, 200)) // the backup-only unit is not in the groups
        fake.putServerTrashed("c", "IMG_2.HEIC", "2026-11-21", 50)
        fake.putTrashed("p", "P.ARW", "f1", "2026-11-21", 10)

        val r = ServerTrash.empty(routes, shown, TrashGroupIds.ALL, sleep) as EmptyResult.Conflict
        assertThat(r.webOnly).isEqualTo(TrashFigures(2, 350))
        assertThat(ServerTrash.empty(routes, r.groups, TrashGroupIds.ALL, sleep)).isInstanceOf(EmptyResult.Done::class.java)
        assertThat(fake.trashed.keys).containsExactly("b", "c")
    }

    @Test
    fun aDeviceThatIsNotHoardIsRefusedWithItsReason() = runBlocking<Unit> {
        live(1)
        fake.notHoard = true
        val r = ServerTrash.move(routes, listOf("it1"), sleep = sleep)
        assertThat(r.failed).containsExactly("it1", TrashReasons.NOT_HOARD)
        val e = ServerTrash.empty(routes, mapOf(TrashGroupIds.HOARD to TrashFigures(1, 1)), listOf(TrashGroupIds.HOARD), sleep)
        assertThat(e).isEqualTo(EmptyResult.Failed(TrashReasons.NOT_HOARD))
    }
}
