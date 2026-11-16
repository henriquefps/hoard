package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** An in-memory [TrashIndex]. */
class MemoryTrashIndex : TrashIndex {
    val rows = LinkedHashMap<String, TrashEntryEntity>()
    override suspend fun put(rows: List<TrashEntryEntity>) = rows.forEach { this.rows[it.id] = it }
    override suspend fun setState(ids: List<String>, state: String) = ids.forEach { id -> rows[id]?.let { rows[id] = it.copy(state = state) } }
    override suspend fun delete(ids: List<String>) = ids.forEach { rows.remove(it) }
    override suspend fun unsettled() = rows.values.filter { it.state != TrashStates.TRASHED }
    override suspend fun inRoot(place: TrashPlace, root: String) = rows.values.filter { it.locationId == place.locationId && it.trashRoot == root }
    override suspend fun touchRoot(place: TrashPlace, root: String, serverFolderId: String?, listedAt: Long?) {}
}

/** A fake MediaStore: rows by path, owned or not, consent answered by [approve]. */
class FakeMediaStore : MediaStoreOps {
    val byUri = LinkedHashMap<String, MediaRow>()
    /** Files on disk that have no row yet (a sidecar no app indexed): [scan] makes one. */
    val unindexed = HashSet<String>()
    /** Files at a path, besides live rows (restore's free-path check). */
    val other = HashSet<String>()
    var approve = true
    var directAllowed = false
    override var tryDirect = false
    val requests = ArrayList<Pair<MediaRequest, Int>>()
    var scans = 0
    private var next = 1

    fun add(path: String, owned: Boolean = false, size: Long = 10): String {
        val uri = "content://media/external_primary/images/media/${next++}"
        byUri[uri] = MediaRow(uri, path, owned, trashed = false, size = size, isMedia = !path.endsWith(".xmp"))
        return uri
    }

    override fun find(path: String) = byUri.values.firstOrNull { it.path == path && !it.trashed }

    override suspend fun scan(path: String): MediaRow? {
        if (path !in unindexed) return null
        scans++
        unindexed -= path
        return byUri.getValue(add(path))
    }

    override fun get(uri: String) = byUri[uri]

    override fun exists(path: String) = path in other || byUri.values.any { it.path == path && !it.trashed }

    private fun set(uris: List<String>, trashed: Boolean) =
        uris.forEach { u -> byUri[u]?.let { byUri[u] = it.copy(trashed = trashed, expiresAt = if (trashed) 2_592_000_000L else null) } }

    /** All files access: plain files (sidecars) may be changed directly even when media rows are not tried directly. */
    var plainDirect = true

    override fun updateTrashed(uris: List<String>, trashed: Boolean): Boolean {
        val rows = uris.mapNotNull { byUri[it] }
        if (rows.any { !it.owned && it.isMedia } && !directAllowed) return false
        if (rows.any { !it.owned && !it.isMedia } && !plainDirect) return false
        set(uris, trashed)
        return true
    }

    /** Direct deletes of media rows Hoard does not own (the backend must never use them for a permanent delete). */
    var directMediaDeletes = 0

    override fun delete(uris: List<String>): Boolean {
        val rows = uris.mapNotNull { byUri[it] }
        if (rows.any { !it.owned && it.isMedia } && !directAllowed) return false
        if (rows.any { !it.owned && !it.isMedia } && !plainDirect) return false
        directMediaDeletes += rows.count { !it.owned && it.isMedia }
        uris.forEach { byUri.remove(it) }
        return true
    }

    override suspend fun request(kind: MediaRequest, uris: List<String>): Boolean {
        require(uris.size <= MediaTrashLogic.MAX_PER_REQUEST)
        require(uris.all { byUri[it]?.isMedia != false }) { "All requested items must be Media items" }
        requests += kind to uris.size
        if (!approve) return false
        when (kind) {
            MediaRequest.TRASH -> set(uris, true)
            MediaRequest.RESTORE -> set(uris, false)
            MediaRequest.DELETE -> uris.forEach { byUri.remove(it) }
        }
        return true
    }
}

/** The phone's MediaStore trash (ADR 0023, phase A3): batching, owned vs not, consent, restore's free path, Empty. */
class MediaStoreTrashTest {
    private val phone = TrashPlace("device")
    private val ms = FakeMediaStore()
    private val index = MemoryTrashIndex()
    private var ids = 0
    private val backend = MediaStoreTrashBackend(index, ms, now = { 1_000 }, newId = { "id-${++ids}" })

    private fun plan(folder: String, files: List<TrashFile>, selected: List<String>, wholeShots: Boolean = false) =
        TrashPlanner.plan(phone, TrashPlanner.units(folder, files, selected, wholeShots), RootChoice.Root(""), serverHasTrash = false)

    private fun f(path: String, size: Long = 10) = TrashFile(path, size, 1)

    @Test
    fun `batches keep units whole, at most 500 files, and split only a unit that is larger`() {
        val units = listOf(List(300) { it }, List(150) { it }, List(100) { it }, List(1200) { it }, List(2) { it })
        val batches = MediaTrashLogic.batches(units)
        assertThat(batches.map { b -> b.map { it.size } }).containsExactly(
            listOf(300, 150), listOf(100), listOf(500), listOf(500), listOf(200), listOf(2),
        ).inOrder()
        assertThat(batches.all { b -> b.sumOf { it.size } <= 500 }).isTrue()
    }

    @Test
    fun `owned rows are trashed directly, the others by one consent per batch`() = runBlocking<Unit> {
        ms.add("Pictures/Hoard/Trip/a.jpg", owned = true)
        ms.add("DCIM/Camera/IMG_1.JPG")
        ms.add("DCIM/Camera/IMG_2.JPG")
        val p1 = plan("Pictures/Hoard/Trip", listOf(f("Pictures/Hoard/Trip/a.jpg")), listOf("Pictures/Hoard/Trip/a.jpg"))
        val p2 = plan("DCIM/Camera", listOf(f("DCIM/Camera/IMG_1.JPG"), f("DCIM/Camera/IMG_2.JPG")), listOf("DCIM/Camera/IMG_1.JPG", "DCIM/Camera/IMG_2.JPG"))
        val report = backend.trash(TrashPlanner.merge(listOf(p1, p2)), { false }) {}
        assertThat(report.trashed).hasSize(3)
        // The owned copy asked nothing; the two others one request.
        assertThat(ms.requests).containsExactly(MediaRequest.TRASH to 2)
        assertThat(index.rows.values.map { it.state }.distinct()).containsExactly(TrashStates.TRASHED)
        assertThat(index.rows.values.all { it.mediaUri != null && it.expiresAt != null && it.trashedPath.isEmpty() }).isTrue()
    }

    @Test
    fun `1200 photos are three consents of at most 500`() = runBlocking<Unit> {
        val files = (1..1200).map { n -> f("DCIM/Camera/IMG_%04d.JPG".format(n)).also { ms.add(it.path) } }
        val report = backend.trash(plan("DCIM/Camera", files, files.map { it.path }), { false }) {}
        assertThat(report.trashed).hasSize(1200)
        assertThat(ms.requests.map { it.second }).containsExactly(500, 500, 200).inOrder()
    }

    @Test
    fun `a declined consent changes nothing and keeps no row`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/IMG_1.JPG")
        ms.approve = false
        val report = backend.trash(plan("DCIM/Camera", listOf(f("DCIM/Camera/IMG_1.JPG")), listOf("DCIM/Camera/IMG_1.JPG")), { false }) {}
        assertThat(report.refused.single().reason).isEqualTo(TrashRefusal.DECLINED)
        assertThat(index.rows).isEmpty()
        assertThat(ms.byUri.values.single().trashed).isFalse()
    }

    @Test
    fun `with All files access a direct update is tried first and no consent is needed when it works`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/IMG_1.JPG")
        ms.tryDirect = true
        ms.directAllowed = true
        backend.trash(plan("DCIM/Camera", listOf(f("DCIM/Camera/IMG_1.JPG")), listOf("DCIM/Camera/IMG_1.JPG")), { false }) {}
        assertThat(ms.requests).isEmpty()
        // Refused directly: the consent is asked instead.
        ms.add("DCIM/Camera/IMG_2.JPG")
        ms.directAllowed = false
        backend.trash(plan("DCIM/Camera", listOf(f("DCIM/Camera/IMG_2.JPG")), listOf("DCIM/Camera/IMG_2.JPG")), { false }) {}
        assertThat(ms.requests).containsExactly(MediaRequest.TRASH to 1)
    }

    @Test
    fun `a sidecar without a row is scanned first and trashed with its photo`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/IMG_1.JPG")
        ms.unindexed += "DCIM/Camera/IMG_1.xmp"
        val files = listOf(f("DCIM/Camera/IMG_1.JPG"), f("DCIM/Camera/IMG_1.xmp"))
        val report = backend.trash(plan("DCIM/Camera", files, listOf("DCIM/Camera/IMG_1.JPG")), { false }) {}
        assertThat(ms.scans).isEqualTo(1)
        assertThat(report.trashed.single().entries.map { it.originalPath }).containsExactly("DCIM/Camera/IMG_1.JPG", "DCIM/Camera/IMG_1.xmp")
        // The consent holds the photo only: Android refuses plain files there, the sidecar is trashed directly.
        assertThat(ms.requests).containsExactly(MediaRequest.TRASH to 1)
        // Without All files access a sidecar cannot follow: the shot is reported half-moved, never silently split.
        ms.add("DCIM/Camera/IMG_2.JPG")
        ms.add("DCIM/Camera/IMG_2.xmp")
        ms.plainDirect = false
        val files2 = listOf(f("DCIM/Camera/IMG_2.JPG"), f("DCIM/Camera/IMG_2.xmp"))
        val second = backend.trash(plan("DCIM/Camera", files2, listOf("DCIM/Camera/IMG_2.JPG")), { false }) {}
        assertThat(second.partial.single().failed).containsExactly("DCIM/Camera/IMG_2.xmp")
    }

    @Test
    fun `restore checks the original path is free, and forgets what Android purged or the user restored`() = runBlocking<Unit> {
        listOf("A.JPG", "B.JPG", "C.JPG", "D.JPG").forEach { ms.add("DCIM/Camera/$it") }
        val files = listOf("A.JPG", "B.JPG", "C.JPG", "D.JPG").map { f("DCIM/Camera/$it") }
        backend.trash(plan("DCIM/Camera", files, files.map { it.path }), { false }) {}
        val rows = index.rows.values.toList()
        // B's path is taken by a new file; C was purged by Android; D was restored in the system's Files app.
        ms.other += "DCIM/Camera/B.JPG"
        ms.byUri.remove(rows.first { it.originalPath.endsWith("C.JPG") }.mediaUri)
        rows.first { it.originalPath.endsWith("D.JPG") }.mediaUri!!.let { u -> ms.byUri[u] = ms.byUri.getValue(u).copy(trashed = false) }
        ms.requests.clear()
        val outcomes = backend.restore(rows)
        assertThat(outcomes.filterIsInstance<RestoreOutcome.Restored>().single().entries.single().originalPath).isEqualTo("DCIM/Camera/A.JPG")
        assertThat(outcomes.filterIsInstance<RestoreOutcome.Conflict>().single().paths).containsExactly("DCIM/Camera/B.JPG")
        assertThat(outcomes.filterIsInstance<RestoreOutcome.Missing>()).hasSize(2)
        assertThat(ms.requests).containsExactly(MediaRequest.RESTORE to 1)
        assertThat(index.rows.values.map { it.originalPath }).containsExactly("DCIM/Camera/B.JPG")
    }

    @Test
    fun `empty deletes with one consent and counts what Android already purged`() = runBlocking<Unit> {
        listOf("A.JPG", "B.JPG").forEach { ms.add("DCIM/Camera/$it") }
        ms.add("Pictures/Hoard/c.jpg", owned = true)
        val files = listOf(f("DCIM/Camera/A.JPG"), f("DCIM/Camera/B.JPG"))
        backend.trash(TrashPlanner.merge(listOf(plan("DCIM/Camera", files, files.map { it.path }), plan("Pictures/Hoard", listOf(f("Pictures/Hoard/c.jpg")), listOf("Pictures/Hoard/c.jpg")))), { false }) {}
        ms.byUri.remove(index.rows.values.first { it.originalPath.endsWith("A.JPG") }.mediaUri)
        ms.requests.clear()
        val purged = backend.empty(index.rows.values.toList()) { false }
        assertThat(purged.map { it.originalPath }).containsExactly("DCIM/Camera/A.JPG", "DCIM/Camera/B.JPG", "Pictures/Hoard/c.jpg")
        assertThat(ms.requests).containsExactly(MediaRequest.DELETE to 1)
        assertThat(index.rows).isEmpty()
        assertThat(ms.byUri).isEmpty()
    }

    @Test
    fun `reconcile forgets purged and restored rows and settles a killed run`() = runBlocking<Unit> {
        listOf("A.JPG", "B.JPG", "C.JPG").forEach { ms.add("DCIM/Camera/$it") }
        val files = listOf("A.JPG", "B.JPG", "C.JPG").map { f("DCIM/Camera/$it") }
        backend.trash(plan("DCIM/Camera", files, files.map { it.path }), { false }) {}
        val rows = index.rows.values.toList()
        ms.byUri.remove(rows[0].mediaUri)
        rows[1].mediaUri!!.let { u -> ms.byUri[u] = ms.byUri.getValue(u).copy(trashed = false) }
        // A `moving` row whose item is in the trash (the process died after the consent).
        index.put(listOf(rows[2].copy(state = TrashStates.MOVING, expiresAt = null)))
        backend.reconcile(index.rows.values.toList())
        val left = index.rows.values.single()
        assertThat(left.originalPath).isEqualTo("DCIM/Camera/C.JPG")
        assertThat(left.state).isEqualTo(TrashStates.TRASHED)
        assertThat(left.expiresAt).isNotNull()
    }

    @Test
    fun `restore's free path check and the owned split are plain functions`() {
        assertThat(MediaTrashLogic.taken(listOf("a", "b"), { it == "b" })).containsExactly("b")
        val (owned, others) = MediaTrashLogic.split(listOf(MediaRow("u1", "a", true, false), MediaRow("u2", "b", false, false)))
        assertThat(owned.map { it.uri }).containsExactly("u1")
        assertThat(others.map { it.uri }).containsExactly("u2")
        assertThat(AndroidMediaStoreOps.originalName("1700000000-IMG_1-final.JPG")).isEqualTo("IMG_1-final.JPG")
    }

    // --- Review of PR #60: permanent deletes always ask, sidecars only after their shot's consent ---

    @Test
    fun `empty of rows Hoard does not own always asks, even when a direct delete would work`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/A.JPG")
        ms.add("Pictures/Hoard/b.jpg", owned = true)
        ms.tryDirect = true
        ms.directAllowed = true
        val p = TrashPlanner.merge(listOf(plan("DCIM/Camera", listOf(f("DCIM/Camera/A.JPG")), listOf("DCIM/Camera/A.JPG")), plan("Pictures/Hoard", listOf(f("Pictures/Hoard/b.jpg")), listOf("Pictures/Hoard/b.jpg"))))
        backend.trash(p, { false }) {}
        // Trash went directly (tryDirect).
        assertThat(ms.requests).isEmpty()
        backend.empty(index.rows.values.toList()) { false }
        // The delete of someone else's photo asked; Hoard's own copy did not need to.
        assertThat(ms.requests).containsExactly(MediaRequest.DELETE to 1)
        assertThat(ms.directMediaDeletes).isEqualTo(0)
        assertThat(ms.byUri).isEmpty()
    }

    @Test
    fun `a sidecar is deleted only after its shot's consent, never when it was declined`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/A.JPG")
        ms.add("DCIM/Camera/A.xmp")
        val files = listOf(f("DCIM/Camera/A.JPG"), f("DCIM/Camera/A.xmp"))
        backend.trash(plan("DCIM/Camera", files, listOf("DCIM/Camera/A.JPG")), { false }) {}
        ms.requests.clear()
        ms.approve = false
        assertThat(backend.empty(index.rows.values.toList()) { false }).isEmpty()
        // Declined: the photo and its sidecar are both still there, still in Hoard's trash.
        assertThat(ms.byUri.values.map { it.path }).containsExactly("DCIM/Camera/A.JPG", "DCIM/Camera/A.xmp")
        assertThat(index.rows.values.map { it.state }.distinct()).containsExactly(TrashStates.TRASHED)
        ms.approve = true
        val purged = backend.empty(index.rows.values.toList()) { false }
        assertThat(purged.map { it.originalPath }).containsExactly("DCIM/Camera/A.JPG", "DCIM/Camera/A.xmp")
        // The consent held the photo only (Android refuses plain rows); the sidecar followed directly.
        assertThat(ms.requests).containsExactly(MediaRequest.DELETE to 1, MediaRequest.DELETE to 1).inOrder()
        assertThat(ms.byUri).isEmpty()
    }

    @Test
    fun `auto-empty never touches rows Hoard does not own and purges its own old ones`() = runBlocking<Unit> {
        ms.add("DCIM/Camera/A.JPG")
        ms.add("Pictures/Hoard/b.jpg", owned = true)
        val p = TrashPlanner.merge(listOf(plan("DCIM/Camera", listOf(f("DCIM/Camera/A.JPG")), listOf("DCIM/Camera/A.JPG")), plan("Pictures/Hoard", listOf(f("Pictures/Hoard/b.jpg")), listOf("Pictures/Hoard/b.jpg"))))
        backend.trash(p, { false }) {}
        ms.requests.clear()
        // Trashed at 1_000: a limit before it purges nothing.
        assertThat(backend.autoEmpty(index.rows.values.toList(), limitMs = 999)).isEmpty()
        val purged = backend.autoEmpty(index.rows.values.toList(), limitMs = 2_000)
        assertThat(purged.map { it.originalPath }).containsExactly("Pictures/Hoard/b.jpg")
        // No dialog from the background; Android's own expiry deletes the other one.
        assertThat(ms.requests).isEmpty()
        assertThat(ms.byUri.values.single().path).isEqualTo("DCIM/Camera/A.JPG")
        assertThat(index.rows.values.single().originalPath).isEqualTo("DCIM/Camera/A.JPG")
    }
}
