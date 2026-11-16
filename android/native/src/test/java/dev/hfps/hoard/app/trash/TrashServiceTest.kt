package dev.hfps.hoard.app.trash

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.data.TrashStates
import dev.hfps.hoard.smb.location.HoardFs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * The trash engine (ADR 0023) over an in-memory share and the real `trash_entries` / `trash_roots` of `hoard.db`:
 * rows before moves, a kill mid-shot then resume, refusals, restore (and its conflict), Empty, auto-empty.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TrashServiceTest {
    private lateinit var db: HoardDatabase
    private val share = TrashPlace("m1", "photos")
    private val drive = TrashPlace("vol-1A2B-3C4D")
    private val fs = HashMap<TrashPlace, MemoryHoardFs>()
    private var today = LocalDate.of(2026, 11, 15)
    private var clock = 1_000_000L
    private var ids = 0

    private fun fsOf(place: TrashPlace) = fs.getOrPut(place) { MemoryHoardFs() }
    private val nas get() = fsOf(share)

    private fun runner(unreachable: Set<TrashPlace> = emptySet()) = TrashRunner(
        index = RoomTrashIndex(db.trash()),
        fsFor = { place ->
            if (place in unreachable) {
                null
            } else {
                object : TrashFs {
                    override suspend fun <T> use(block: (HoardFs) -> T): T = block(fsOf(place))
                }
            }
        },
        now = { clock },
        today = { today },
        newId = { "id-${++ids}" },
    )

    @Before fun open() {
        db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun close() = db.close()

    /** A rejected shot of Trip (RAW + JPEG + sidecar) on the share. */
    private fun shot(n: Int, folder: String = "Imagens/2024/Trip") {
        nas.file("$folder/DSC$n.ARW", 40, modified = 111)
        nas.file("$folder/DSC$n.JPG", 20, modified = 222)
        nas.file("$folder/DSC$n.xmp", 1, modified = 333)
    }

    private fun plan(place: TrashPlace, folder: String, selected: List<String>, root: String = "", serverFolderId: String? = null): TrashPlan {
        val f = fsOf(place)
        val listing = f.list(folder).entries.map { TrashFile(it.path, it.size, dev.hfps.hoard.smb.IsoTime.parse(it.modifiedAt), it.isDirectory) }
        return TrashPlanner.plan(place, TrashPlanner.units(folder, listing, selected), RootChoice.Root(root, serverFolderId), serverHasTrash = true)
    }

    private suspend fun rows() = db.trash().all()

    @Test
    fun trashesAShotWithItsSidecarIntoTheDayFolder() = runBlocking<Unit> {
        shot(1)
        val report = runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW"), root = "Imagens", serverFolderId = "f1"))
        assertThat(report.trashed).hasSize(1)
        assertThat(nas.filesUnder("Imagens/2024")).isEmpty()
        val day = "Imagens/.hoard-trash/2026-11-15/2024/Trip"
        assertThat(nas.filesUnder("Imagens/.hoard-trash")).containsExactly("$day/DSC1.ARW", "$day/DSC1.JPG", "$day/DSC1.xmp")
        // A rename keeps size and mtime; SMB gets no .nomedia.
        assertThat(nas.nodes["$day/DSC1.ARW"]!!.modified).isEqualTo(111)
        assertThat(nas.exists("Imagens/.hoard-trash/.nomedia")).isFalse()
        val rows = rows()
        assertThat(rows.map { it.state }.distinct()).containsExactly(TrashStates.TRASHED)
        assertThat(rows.map { it.shotKey }.distinct()).containsExactly("imagens/2024/trip/dsc1")
        assertThat(rows.first { it.originalPath.endsWith("ARW") }.trashedPath).isEqualTo("$day/DSC1.ARW")
        assertThat(rows.map { it.serverFolderId }.distinct()).containsExactly("f1")
        assertThat(db.trash().roots().single().rootPath).isEqualTo("Imagens")
    }

    @Test
    fun theSamePathTrashedTwiceTheSameDayGoesToDashTwo() = runBlocking<Unit> {
        nas.file("Trip/a.pdf")
        runner().trash(plan(share, "Trip", listOf("Trip/a.pdf")))
        nas.file("Trip/a.pdf", size = 99)
        runner().trash(plan(share, "Trip", listOf("Trip/a.pdf")))
        assertThat(nas.filesUnder(".hoard-trash")).containsExactly(".hoard-trash/2026-11-15/Trip/a.pdf", ".hoard-trash/2026-11-15-2/Trip/a.pdf")
        assertThat(rows().map { it.dayFolder }).containsExactly("2026-11-15", "2026-11-15-2")
    }

    @Test
    fun aKillMidShotIsCompletedByResume() = runBlocking<Unit> {
        shot(1)
        shot(2)
        nas.killAtMove = 2
        assertThrows(Killed::class.java) { runBlocking { runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW"))) } }
        // Rows were written first: one moved and settled, two still `moving`; shot 2 has no rows yet.
        assertThat(rows().groupingBy { it.state }.eachCount()).containsExactly(TrashStates.TRASHED, 1, TrashStates.MOVING, 2)
        nas.killAtMove = null
        val resumed = runner().resume()
        // The started shot is completed (its two `moving` rows): all three files in the trash, no half shot left behind.
        assertThat(resumed.settled.map { it.originalPath }).containsExactly("Imagens/2024/Trip/DSC1.JPG", "Imagens/2024/Trip/DSC1.xmp")
        assertThat(resumed.rolledBack).isEmpty()
        assertThat(nas.filesUnder(".hoard-trash")).hasSize(3)
        assertThat(nas.filesUnder("Imagens/2024/Trip")).containsExactly("Imagens/2024/Trip/DSC2.ARW", "Imagens/2024/Trip/DSC2.JPG", "Imagens/2024/Trip/DSC2.xmp")
        assertThat(rows().map { it.state }.distinct()).containsExactly(TrashStates.TRASHED)
    }

    @Test
    fun aKillBeforeTheFirstMoveIsRolledBack() = runBlocking<Unit> {
        shot(1)
        nas.killAtMove = 1
        assertThrows(Killed::class.java) { runBlocking { runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.JPG"))) } }
        assertThat(rows().map { it.state }).containsExactly(TrashStates.MOVING, TrashStates.MOVING, TrashStates.MOVING)
        nas.killAtMove = null
        val resumed = runner().resume()
        assertThat(resumed.rolledBack).hasSize(3)
        assertThat(rows()).isEmpty()
        assertThat(nas.filesUnder("Imagens/2024/Trip")).hasSize(3)
        // An unreachable place keeps its rows for the next resume.
        nas.killAtMove = 1
        assertThrows(Killed::class.java) { runBlocking { runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.JPG"))) } }
        assertThat(runner(unreachable = setOf(share)).resume().unreachable).containsExactly(share)
        assertThat(rows()).hasSize(3)
    }

    @Test
    fun aReadOnlyRootOrAnotherFilesystemRefusesAndMovesNothing() = runBlocking<Unit> {
        shot(1)
        shot(2)
        nas.readOnly += ""
        val report = runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW")))
        assertThat(report.refused.map { it.reason }).containsExactly(TrashRefusal.ROOT_NOT_WRITABLE, TrashRefusal.ROOT_NOT_WRITABLE)
        assertThat(nas.filesUnder("Imagens/2024/Trip")).hasSize(6)
        assertThat(rows()).isEmpty()
        nas.readOnly.clear()
        nas.otherDevice += ".hoard-trash"
        val other = runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW")))
        assertThat(other.refused.single().reason).isEqualTo(TrashRefusal.NOT_SAME_DEVICE)
        assertThat(nas.filesUnder("Imagens/2024/Trip")).hasSize(6)
        assertThat(rows()).isEmpty()
        // An unreachable place refuses too.
        val away = runner(unreachable = setOf(share)).trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW")))
        assertThat(away.refused.single().reason).isEqualTo(TrashRefusal.UNREACHABLE)
    }

    @Test
    fun aShotWhoseFirstFileFailsStaysAndAHalfMovedShotIsReported() = runBlocking<Unit> {
        shot(1)
        shot(2)
        nas.busy += "Imagens/2024/Trip/DSC1.ARW"
        nas.busy += "Imagens/2024/Trip/DSC2.JPG"
        val report = runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW")))
        assertThat(report.refused.single().unit.key).isEqualTo("imagens/2024/trip/dsc1")
        assertThat(report.refused.single().reason).isEqualTo(TrashRefusal.FAILED)
        val partial = report.partial.single()
        assertThat(partial.entries.map { it.originalPath }).containsExactly("Imagens/2024/Trip/DSC2.ARW")
        assertThat(partial.failed).containsExactly("Imagens/2024/Trip/DSC2.JPG", "Imagens/2024/Trip/DSC2.xmp").inOrder()
        // The moved file stays in the trash (restorable); only its row is left.
        assertThat(rows().map { it.originalPath }).containsExactly("Imagens/2024/Trip/DSC2.ARW")
        assertThat(nas.filesUnder("Imagens/2024/Trip")).hasSize(5)
    }

    @Test
    fun cancelStopsBetweenUnits() = runBlocking<Unit> {
        shot(1)
        shot(2)
        var done = 0
        val report = runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW")), cancelled = { done >= 1 }) {
            done = it.unitsDone
        }
        assertThat(report.trashed).hasSize(1)
        assertThat(report.cancelled.single().unit.key).isEqualTo("imagens/2024/trip/dsc2")
        assertThat(nas.filesUnder(".hoard-trash")).hasSize(3)
    }

    @Test
    fun restoreGoesBackToTheExactPathAndRefusesAConflict() = runBlocking<Unit> {
        shot(1)
        shot(2)
        runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW")))
        // The folder is gone meanwhile; a new file took DSC2.JPG's place.
        nas.nodes.remove("Imagens/2024/Trip")
        nas.file("Imagens/2024/Trip/DSC2.JPG", 77)
        val outcomes = runner().restore(rows())
        val conflict = outcomes.filterIsInstance<RestoreOutcome.Conflict>().single()
        assertThat(conflict.paths).containsExactly("Imagens/2024/Trip/DSC2.JPG")
        assertThat(outcomes.filterIsInstance<RestoreOutcome.Restored>()).hasSize(1)
        // Shot 1 is back; shot 2 is entirely still in the trash, the newer file untouched.
        assertThat(nas.filesUnder("Imagens/2024/Trip")).containsExactly(
            "Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC1.JPG", "Imagens/2024/Trip/DSC1.xmp", "Imagens/2024/Trip/DSC2.JPG",
        )
        assertThat(nas.nodes["Imagens/2024/Trip/DSC2.JPG"]!!.size).isEqualTo(77)
        assertThat(nas.filesUnder(".hoard-trash")).hasSize(3)
        assertThat(rows().map { it.originalPath }).containsExactly("Imagens/2024/Trip/DSC2.ARW", "Imagens/2024/Trip/DSC2.JPG", "Imagens/2024/Trip/DSC2.xmp")
        // Moved away: restored, and the day folder it leaves empty is removed.
        nas.delete("Imagens/2024/Trip/DSC2.JPG", false)
        val again = runner().restore(rows())
        assertThat(again.single()).isInstanceOf(RestoreOutcome.Restored::class.java)
        assertThat(rows()).isEmpty()
        assertThat(nas.exists(".hoard-trash/2026-11-15")).isFalse()
        assertThat(nas.exists(".hoard-trash")).isTrue()
    }

    @Test
    fun restoreOfFilesEmptiedElsewhereForgetsTheirRows() = runBlocking<Unit> {
        nas.file("Trip/a.pdf")
        runner().trash(plan(share, "Trip", listOf("Trip/a.pdf")))
        nas.delete(".hoard-trash/2026-11-15", true)
        assertThat(runner().restore(rows()).single()).isInstanceOf(RestoreOutcome.Missing::class.java)
        assertThat(rows()).isEmpty()
    }

    @Test
    fun emptyDeletesTheFilesAndTheFoldersTheyLeave() = runBlocking<Unit> {
        shot(1)
        nas.file("Imagens/2024/Trip/keep.pdf")
        runner().trash(plan(share, "Imagens/2024/Trip", listOf("Imagens/2024/Trip/DSC1.ARW")))
        val purged = runner().empty(rows())
        assertThat(purged).hasSize(3)
        assertThat(rows()).isEmpty()
        assertThat(nas.filesUnder(".hoard-trash")).isEmpty()
        assertThat(nas.exists(".hoard-trash/2026-11-15")).isFalse()
        assertThat(nas.filesUnder("Imagens")).containsExactly("Imagens/2024/Trip/keep.pdf")
    }

    @Test
    fun autoEmptyDeletesBackDatedDayFoldersInEveryReachableRoot() = runBlocking<Unit> {
        // Trashed 40 days ago by this device, 10 days ago, and 35 days ago by another device (not indexed).
        nas.file("Trip/old.jpg")
        today = LocalDate.of(2026, 10, 6)
        runner().trash(plan(share, "Trip", listOf("Trip/old.jpg")))
        nas.file("Trip/new.jpg")
        today = LocalDate.of(2026, 11, 5)
        runner().trash(plan(share, "Trip", listOf("Trip/new.jpg")))
        nas.file(".hoard-trash/2026-10-11/Other/b.jpg")
        nas.file(".hoard-trash/junk/c.jpg")
        today = LocalDate.of(2026, 11, 15)
        val roots = listOf(TrashRunner.Root(share, "", "f1"), TrashRunner.Root(drive, ""))
        assertThat(runner().autoEmpty(roots, 0)).isEmpty()
        val expired = runner(unreachable = setOf(drive)).autoEmpty(roots, 30)
        assertThat(expired.single().dayFolders).containsExactly("2026-10-06", "2026-10-11").inOrder()
        assertThat(expired.single().purged.map { it.originalPath }).containsExactly("Trip/old.jpg")
        assertThat(nas.filesUnder(".hoard-trash")).containsExactly(".hoard-trash/2026-11-05/Trip/new.jpg", ".hoard-trash/junk/c.jpg")
        assertThat(rows().map { it.originalPath }).containsExactly("Trip/new.jpg")
        assertThat(db.trash().root("m1", "photos", "")!!.lastListed).isEqualTo(clock)
        // 7 days: the 10-day-old one goes too.
        runner().autoEmpty(roots, 7)
        assertThat(rows()).isEmpty()
    }

    @Test
    fun scanIndexesWhatAnotherDeviceTrashedAndForgetsWhatIsGone() = runBlocking<Unit> {
        nas.file("Trip/mine.jpg")
        runner().trash(plan(share, "Trip", listOf("Trip/mine.jpg")))
        nas.file(".hoard-trash/2026-11-14/Imagens/DSC9.ARW", 50)
        nas.file(".hoard-trash/2026-11-14/Imagens/DSC9.ARW.xmp", 2)
        nas.file(".hoard-trash/2026-11-14/Docs/report.pdf", 3)
        nas.delete(".hoard-trash/2026-11-15/Trip/mine.jpg", false)
        val rows = runner().scan(TrashRunner.Root(share, ""))
        assertThat(rows.map { it.originalPath }).containsExactly("Imagens/DSC9.ARW", "Imagens/DSC9.ARW.xmp", "Docs/report.pdf")
        assertThat(rows.filter { it.originalPath.startsWith("Imagens") }.map { it.shotKey }.distinct()).containsExactly("imagens/dsc9")
        assertThat(rows.first { it.originalPath == "Docs/report.pdf" }.shotKey).isEqualTo("docs/report.pdf")
        assertThat(rows.map { it.runId }.distinct()).containsExactly(TrashRunner.SCAN_RUN)
        // Found rows restore like any other.
        assertThat(runner().restore(rows).filterIsInstance<RestoreOutcome.Restored>()).hasSize(2)
        assertThat(nas.filesUnder("Imagens")).containsExactly("Imagens/DSC9.ARW", "Imagens/DSC9.ARW.xmp")
    }

    @Test
    fun aDriveGetsANomediaAndItsSafGrantAsTheRoot() = runBlocking<Unit> {
        val usb = fsOf(drive)
        usb.file("DCIM/100MSDCF/DSC1.JPG")
        runner().trash(plan(drive, "DCIM/100MSDCF", listOf("DCIM/100MSDCF/DSC1.JPG"), root = "DCIM"))
        assertThat(usb.exists("DCIM/.hoard-trash/.nomedia")).isTrue()
        assertThat(usb.filesUnder("DCIM/.hoard-trash/2026-11-15")).containsExactly("DCIM/.hoard-trash/2026-11-15/100MSDCF/DSC1.JPG")
    }

    @Test
    fun aRestoringRowAfterAKillIsSettledByWhereTheFileIs() = runBlocking<Unit> {
        nas.file("Trip/a.pdf")
        nas.file("Trip/b.pdf")
        runner().trash(plan(share, "Trip", listOf("Trip/a.pdf", "Trip/b.pdf")))
        val all = rows()
        db.trash().setState(all.map { it.id }, TrashStates.RESTORING)
        // a.pdf made it back before the kill, b.pdf did not.
        nas.move(all.first { it.originalPath == "Trip/a.pdf" }.trashedPath, "Trip/a.pdf")
        val resumed = runner().resume()
        assertThat(resumed.rolledBack.map { it.originalPath }).containsExactly("Trip/a.pdf")
        assertThat(rows().single().originalPath).isEqualTo("Trip/b.pdf")
        assertThat(rows().single().state).isEqualTo(TrashStates.TRASHED)
    }
}
