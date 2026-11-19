package dev.hfps.hoard.app.trash

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.server.api.CompressBatchResult
import dev.hfps.hoard.server.api.FileLocation
import dev.hfps.hoard.server.api.Item
import dev.hfps.hoard.server.api.ItemFile
import dev.hfps.hoard.server.paths.PathMapping
import dev.hfps.hoard.smb.location.HoardFs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * ADR 0025 Amendment (S1): Empty, Delete permanently and auto-empty leave every trash entry an unfinished compression
 * journal holds (`held: true` on its server trash location), on the share (an SMB `.hoard-trash` file that maps to a
 * held server location) and on the server (compressed originals the server keeps, emptied through its purge route).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HeldTrashTest {
    private lateinit var db: HoardDatabase
    private val share = TrashPlace("m1", "photos")
    private val nas = MemoryHoardFs().apply { clock = { noon(LocalDate.of(2026, 11, 19)) } }
    private var today = LocalDate.of(2026, 11, 19)
    private fun noon(d: LocalDate) = d.atTime(12, 0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
    private var ids = 0

    /** The share `photos` of machine `m1` holds the NAS folder `nas-1` under `Videos` (its root is the share's `Videos`). */
    private val mapping = PathMapping("m1", "photos", "Videos", "srv", "nas-1", "videos", "", null, 0, 0)

    private fun runner() = TrashRunner(
        index = RoomTrashIndex(db.trash()),
        fsFor = { object : TrashFs { override suspend fun <T> use(block: (HoardFs) -> T): T = block(nas) } },
        now = { noon(today) },
        today = { today },
        newId = { "id-${++ids}" },
    )

    @Before fun open() {
        db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun close() = db.close()

    private suspend fun rows() = db.trash().all()

    /** A trashed item a compression replaced: its original in `<root>/.hoard-trash/<day>/<path>`, held or not. */
    private fun original(id: String, path: String, day: String, held: Boolean? = null, size: Long = 400) = Item(
        id = id,
        rev = 1,
        kind = "video",
        mainFingerprint = "v1:$id",
        compressedTo = "out-$id",
        files = listOf(
            ItemFile(
                "v1:$id", "video", path.substringAfterLast('/'), size = size,
                locations = listOf(FileLocation(source = "nas", folderId = "nas-1", path = path, trash = ".hoard-trash/$day/$path", held = held)),
            ),
        ),
    )

    /** The share's `.hoard-trash` with two originals (one held) and a plain trashed photo, all on [day]. */
    private suspend fun trashOnShare(day: String) {
        nas.file("Videos/.hoard-trash/$day/2024/held.mp4", 400)
        nas.file("Videos/.hoard-trash/$day/2024/free.mp4", 300)
        nas.file("Videos/.hoard-trash/$day/2024/photo.jpg", 20)
        nas.nodes["Videos/.hoard-trash/$day"]!!.modified = noon(LocalDate.parse(day))
        runner().scan(TrashRunner.Root(share, "Videos", "nas-1"))
    }

    private fun held(day: String) = CompressedTrash.held(
        listOf(original("a", "2024/held.mp4", day, held = true), original("b", "2024/free.mp4", day)),
        listOf(mapping),
    )

    @Test
    fun theHeldStateMapsServerTrashLocationsToShareTrashPaths() {
        val h = held("2026-11-19")
        assertThat(h.holds(share, "Videos/.hoard-trash/2026-11-19/2024/held.mp4")).isTrue()
        assertThat(h.holds(TrashPlace("m1", "PHOTOS"), "videos/.hoard-trash/2026-11-19/2024/HELD.mp4")).isTrue()
        assertThat(h.holds(share, "Videos/.hoard-trash/2026-11-19/2024/free.mp4")).isFalse()
        // A trashed folder holding a held file is held as a whole; its day folder holds one.
        assertThat(h.holds(share, "Videos/.hoard-trash/2026-11-19/2024")).isTrue()
        assertThat(h.holdsUnder(share, "Videos/.hoard-trash/2026-11-19")).isTrue()
        assertThat(h.holdsUnder(share, "Videos/.hoard-trash/2026-11-18")).isFalse()
        assertThat(h.serverItems).containsExactly("a")
        // Another share: the same day folder and path still count (a share whose mapping is not learned); another path not.
        assertThat(h.holds(TrashPlace("m2", "photos"), "Videos/.hoard-trash/2026-11-19/2024/held.mp4")).isTrue()
        assertThat(h.holds(TrashPlace("m2", "photos"), "Videos/.hoard-trash/2026-11-18/2024/held.mp4")).isFalse()
        assertThat(CompressedTrash.held(listOf(original("a", "2024/held.mp4", "2026-11-19", held = true)), emptyList()).holdsUnder(share, "Videos")).isFalse()
    }

    @Test
    fun aShareWithoutALearnedMappingIsMatchedByTheTrashPath() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        // No mapping: the server's `.hoard-trash/<day>/<path>` still names the share's file (a live item's held link).
        val live = original("a", "2024/held.mp4", "2026-11-19", held = true).copy(compressedTo = null)
        val h = CompressedTrash.held(listOf(live), emptyList())
        assertThat(h.holds(TrashPlace("m9", "other"), "Videos/.hoard-trash/2026-11-19/2024/held.mp4")).isTrue()
        assertThat(h.holds(share, "Videos/.hoard-trash/2026-11-19/2024/free.mp4")).isFalse()
        assertThat(h.holdsUnder(share, "Videos/.hoard-trash/2026-11-19")).isTrue()
        assertThat(HeldTrash.tailOf("A/B/.hoard-trash/2026-11-19/x/Y.mp4")).isEqualTo(".hoard-trash/2026-11-19/x/y.mp4")
        assertThat(HeldTrash.tailOf("A/B/x.mp4")).isNull()
        val purged = runner().empty(rows(), held = h)
        assertThat(purged.map { it.originalPath }).containsExactly("Videos/2024/free.mp4", "Videos/2024/photo.jpg")
        assertThat(nas.exists("Videos/.hoard-trash/2026-11-19/2024/held.mp4")).isTrue()
    }

    @Test
    fun emptyLeavesAHeldEntryOnTheShare() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        val purged = runner().empty(rows(), held = held("2026-11-19"))
        assertThat(purged.map { it.originalPath }).containsExactly("Videos/2024/free.mp4", "Videos/2024/photo.jpg")
        assertThat(nas.filesUnder("Videos/.hoard-trash")).containsExactly("Videos/.hoard-trash/2026-11-19/2024/held.mp4")
        assertThat(rows().map { it.originalPath }).containsExactly("Videos/2024/held.mp4")
    }

    @Test
    fun deletePermanentlyOfTheHeldEntryAloneDeletesNothing() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        val heldRow = rows().filter { it.originalPath.endsWith("held.mp4") }
        assertThat(runner().empty(heldRow, held = held("2026-11-19"))).isEmpty()
        assertThat(nas.exists("Videos/.hoard-trash/2026-11-19/2024/held.mp4")).isTrue()
        assertThat(rows()).hasSize(3)
    }

    @Test
    fun autoEmptyEmptiesADayFolderAroundAHeldEntry() = runBlocking<Unit> {
        trashOnShare("2026-10-01")
        today = LocalDate.of(2026, 11, 19)
        val expired = runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, held = held("2026-10-01"))
        assertThat(expired.single().dayFolders).containsExactly("2026-10-01")
        // The held original stays (and its row); the rest of the day folder went, counted without it.
        assertThat(nas.filesUnder("Videos/.hoard-trash").filter { !it.endsWith(TrashRunner.CLOCK_FILE) })
            .containsExactly("Videos/.hoard-trash/2026-10-01/2024/held.mp4")
        assertThat(expired.single().files).isEqualTo(2)
        assertThat(expired.single().bytes).isEqualTo(320)
        assertThat(rows().map { it.originalPath }).containsExactly("Videos/2024/held.mp4")
        // Released by its journal: the next run takes it.
        runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, held = HeldTrash.NONE)
        assertThat(nas.exists("Videos/.hoard-trash/2026-10-01")).isFalse()
        assertThat(rows()).isEmpty()
    }

    @Test
    fun autoEmptyDryRunDoesNotCountHeldEntries() = runBlocking<Unit> {
        trashOnShare("2026-10-01")
        val preview = runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, dryRun = true, held = held("2026-10-01"))
        assertThat(preview.single().files).isEqualTo(2)
        assertThat(nas.filesUnder("Videos/.hoard-trash").count { it.endsWith(".mp4") || it.endsWith(".jpg") }).isEqualTo(3)
    }

    @Test
    fun anUnknownHeldStateLeavesEveryShareAlone() = runBlocking<Unit> {
        trashOnShare("2026-10-01")
        val unknown = HeldTrash(unknown = true)
        // A mapped root: nothing deleted by auto-empty or Empty while the server could not be asked.
        assertThat(runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, held = unknown)).isEmpty()
        assertThat(runner().empty(rows(), held = unknown)).isEmpty()
        assertThat(rows()).hasSize(3)
        // An unmapped root of a share too (review of #76: it may be a server folder whose mapping is not learned).
        nas.file("Other/.hoard-trash/2026-10-01/x.jpg", 5)
        nas.nodes["Other/.hoard-trash/2026-10-01"]!!.modified = noon(LocalDate.of(2026, 10, 1))
        assertThat(runner().autoEmpty(listOf(TrashRunner.Root(share, "Other")), 30, held = unknown)).isEmpty()
        assertThat(nas.exists("Other/.hoard-trash/2026-10-01/x.jpg")).isTrue()
        // A local drive is not a server's: emptied as before.
        assertThat(runner().autoEmpty(listOf(TrashRunner.Root(TrashPlace("vol-1A2B"), "Other")), 30, held = unknown).single().files).isEqualTo(1)
    }

    @Test
    fun theSplitRoutesCompressedOriginalsAndLeavesHeldOnes() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        val state = TrashCompressionState(
            held("2026-11-19"),
            CompressedTrash.originals(listOf(original("a", "2024/held.mp4", "2026-11-19", held = true) to 2L, original("b", "2024/free.mp4", "2026-11-19") to 1L), listOf(mapping)),
        )
        val split = TrashSplit.of(rows(), state)
        assertThat(split.held.map { it.originalPath }).containsExactly("Videos/2024/held.mp4")
        assertThat(split.compressed.keys.map { it.itemId }).containsExactly("b")
        assertThat(split.plain.map { it.originalPath }).containsExactly("Videos/2024/photo.jpg")
        // Every original has a row: none is server-only.
        assertThat(state.serverOnly(rows())).isEmpty()
        // The groups: the held row is listed but not counted; the compressed one labelled.
        val folder = TrashGroups.of(rows(), emptySet(), { "NAS" }, compression = state).single().folders.single()
        assertThat(folder.files).isEqualTo(2)
        assertThat(folder.bytes).isEqualTo(320)
        assertThat(folder.heldRows.map { it.originalPath }).containsExactly("Videos/2024/held.mp4")
        assertThat(folder.originals.map { it.first.itemId }).containsExactly("b")
    }

    // --- The server's side: compressed originals the server keeps (purge and restore routes, against a fake) ---

    private class FakeRoutes : CompressRoutes {
        val purged = ArrayList<String>()
        val restored = ArrayList<String>()
        /** Item ids the server answers `{"error": "state"}` for (a journal running). */
        val busy = HashSet<String>()

        override suspend fun restore(itemIds: List<String>) = itemIds.associateWith { id ->
            if (id in busy) CompressBatchResult(error = "state") else CompressBatchResult(id = "1", state = "replacing").also { restored += id }
        }

        override suspend fun purge(itemIds: List<String>) = itemIds.associateWith { id ->
            if (id in busy) CompressBatchResult(error = "state") else CompressBatchResult(id = "1", state = "purged").also { purged += id }
        }
    }

    private fun kept(id: String, held: Boolean = false) = CompressedOriginal(id, "$id.mp4", 400, "out-$id", held, 1, emptyList())

    /** A server whose purge answers every id with [error] (or throws a 503 for the whole request). */
    private class RefusingRoutes(val error: String, val whole: Boolean = false) : CompressRoutes {
        var purges = 0
        override suspend fun restore(itemIds: List<String>) = itemIds.associateWith { CompressBatchResult(error = error) }
        override suspend fun purge(itemIds: List<String>): Map<String, CompressBatchResult> {
            purges++
            if (whole) throw dev.hfps.hoard.server.api.ServerException(503, "unavailable", "no", reason = error)
            return itemIds.associateWith { CompressBatchResult(error = error) }
        }
    }

    private suspend fun stateFor(day: String) = TrashCompressionState(
        held(day),
        CompressedTrash.originals(listOf(original("a", "2024/held.mp4", day, held = true) to 2L, original("b", "2024/free.mp4", day) to 1L), listOf(mapping)),
    )

    private suspend fun emptyAgainst(routes: CompressRoutes, state: TrashCompressionState) =
        emptyTrash(rows(), state, emptyList(), CompressedTrashActions({ routes })) { plain, protect -> runner().empty(plain, held = protect) }

    @Test
    fun aRefusedPurgeDeletesNothingOfTheOriginal() = runBlocking<Unit> {
        // Review of #76: compressed originals are deleted only by the server, whatever it answers.
        for ((reason, whole) in listOf("no_write_mount" to true, "not_writable" to false, "output_missing" to false, "busy" to false, "state" to false)) {
            nas.nodes.keys.filter { it.startsWith("Videos/.hoard-trash") }.forEach { nas.nodes.remove(it) }
            db.trash().all().let { all -> db.trash().delete(all.map { it.id }) }
            trashOnShare("2026-11-19")
            val server = RefusingRoutes(reason, whole)
            val r = emptyAgainst(server, stateFor("2026-11-19"))
            assertThat(server.purges).isEqualTo(1)
            assertThat(r.server.failed).containsExactly("b", reason)
            assertThat(r.routed).isEmpty()
            // Only the plain photo went; the original (b) and the held one (a) are on the share with their rows.
            assertThat(r.deleted.map { it.originalPath }).containsExactly("Videos/2024/photo.jpg")
            assertThat(nas.filesUnder("Videos/.hoard-trash")).containsExactly(
                "Videos/.hoard-trash/2026-11-19/2024/free.mp4", "Videos/.hoard-trash/2026-11-19/2024/held.mp4",
            )
            assertThat(rows().map { it.originalPath }).containsExactly("Videos/2024/free.mp4", "Videos/2024/held.mp4")
        }
    }

    @Test
    fun aPurgedOriginalIsTheServersDeleteNotHoards() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        val server = FakeRoutes()
        val r = emptyAgainst(server, stateFor("2026-11-19"))
        assertThat(server.purged).containsExactly("b")
        assertThat(r.routed.map { it.originalPath }).containsExactly("Videos/2024/free.mp4")
        // Hoard never touched the file: the server deletes it through its write mount.
        assertThat(nas.exists("Videos/.hoard-trash/2026-11-19/2024/free.mp4")).isTrue()
    }

    @Test
    fun autoEmptyLeavesCompressedOriginalsToTheServer() = runBlocking<Unit> {
        trashOnShare("2026-10-01")
        val state = stateFor("2026-10-01")
        val expired = runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, held = state.protect)
        assertThat(expired.single().files).isEqualTo(1)
        assertThat(nas.filesUnder("Videos/.hoard-trash").filter { !it.endsWith(TrashRunner.CLOCK_FILE) }).containsExactly(
            "Videos/.hoard-trash/2026-10-01/2024/free.mp4", "Videos/.hoard-trash/2026-10-01/2024/held.mp4",
        )
        assertThat(rows().map { it.originalPath }).containsExactly("Videos/2024/free.mp4", "Videos/2024/held.mp4")
    }

    @Test
    fun autoEmptyUsesTheSamePredicateAsEmpty() = runBlocking<Unit> {
        trashOnShare("2026-10-01")
        // Held only through the row's server item (no path, no tail): kept by auto-empty as by Empty.
        val byItem = HeldTrash(serverItems = setOf("srv-photo"))
        val photo = rows().single { it.originalPath.endsWith("photo.jpg") }
        db.trash().put(listOf(photo.copy(serverItemId = "srv-photo")))
        runner().autoEmpty(listOf(TrashRunner.Root(share, "Videos", "nas-1")), 30, held = byItem)
        assertThat(nas.exists("Videos/.hoard-trash/2026-10-01/2024/photo.jpg")).isTrue()
        assertThat(nas.exists("Videos/.hoard-trash/2026-10-01/2024/free.mp4")).isFalse()
    }

    @Test
    fun anUnmappedShareMatchesCompressedOriginalsByTheirTrashPath() = runBlocking<Unit> {
        trashOnShare("2026-11-19")
        // No mapping: restore and Empty still go through the server for b (review of #76), not a plain rename.
        val state = TrashCompressionState(HeldTrash.NONE, CompressedTrash.originals(listOf(original("b", "2024/free.mp4", "2026-11-19") to 1L), emptyList()))
        val split = TrashSplit.of(rows(), state)
        assertThat(split.compressed.keys.map { it.itemId }).containsExactly("b")
        assertThat(split.plain.map { it.originalPath }).containsExactly("Videos/2024/held.mp4", "Videos/2024/photo.jpg")
        assertThat(state.protect.holds(TrashPlace("m9", "x"), "Any/.hoard-trash/2026-11-19/2024/free.mp4")).isTrue()
    }

    @Test
    fun purgeOfServerKeptOriginalsSkipsHeldOnes() = runBlocking<Unit> {
        val server = FakeRoutes()
        val actions = CompressedTrashActions({ server })
        val out = actions.purge(listOf(kept("a", held = true), kept("b"), kept("c")), HeldTrash(serverItems = setOf("c")))
        assertThat(server.purged).containsExactly("b")
        assertThat(out.done).containsExactly("b")
        assertThat(out.held).containsExactly("a", "c")
    }

    @Test
    fun theServersRefusalIsReportedNotDone() = runBlocking<Unit> {
        val server = FakeRoutes().apply { busy += "b" }
        val actions = CompressedTrashActions({ server })
        val out = actions.restore(listOf(kept("a"), kept("b")))
        assertThat(server.restored).containsExactly("a")
        assertThat(out.done).containsExactly("a")
        assertThat(out.failed).containsExactly("b", "state")
        // No server: nothing done, every id failed as unreachable.
        val offline = CompressedTrashActions({ null }).purge(listOf(kept("x")), HeldTrash.NONE)
        assertThat(offline.done).isEmpty()
        assertThat(offline.failed).containsExactly("x", CompressedTrashActions.UNREACHABLE)
    }

    @Test
    fun aRestoredOutputInTheTrashIsListedLikeAnOriginal() {
        val o = CompressedTrash.originals(listOf(original("out", "2024/x.mp4", "2026-11-19").copy(compressedTo = "orig") to 5L), listOf(mapping)).single()
        assertThat(o.compressedTo).isEqualTo("orig")
        assertThat(o.smbPaths).containsExactly(share to "Videos/.hoard-trash/2026-11-19/2024/x.mp4")
        assertThat(o.size).isEqualTo(400)
        // An item without compressedTo is not a compressed original.
        assertThat(CompressedTrash.originals(listOf(original("p", "2024/y.mp4", "2026-11-19").copy(compressedTo = null) to 1L), listOf(mapping))).isEmpty()
    }
}
