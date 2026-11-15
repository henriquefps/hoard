package dev.hfps.hoard.app.cull

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.image.CullModel
import org.junit.Test

/** Port of src/features/cull/model.test.ts (pairing is :core:image's CullModel, tested there and here once). */
class CullLogicTest {
    private fun file(name: String, size: Long) = ShotFile(name, "f/$name", size, null, 0)

    private fun shot(id: String, kind: String = "pair") = Shot(
        id = id,
        stem = id.uppercase(),
        raw = if (kind != "jpeg") file("$id.ARW", 25) else null,
        rendered = if (kind != "raw") file("$id.JPG", 6) else null,
        others = emptyList(),
    )

    private fun meta(captureMillis: Long?) = ShotMetadata(captureMillis = captureMillis)

    @Test
    fun `pairs RAW and JPEG by case-insensitive stem through the image core`() {
        val shots = CullModel.shots(
            listOf(
                ShotFile("DSC07668.ARW", "D/DSC07668.ARW", 25, null, 0),
                ShotFile("dsc07668.jpg", "D/dsc07668.jpg", 6, null, 0),
                ShotFile("DSC07669.ARW", "D/DSC07669.ARW", 25, null, 0),
                ShotFile("DSC07670.JPG", "D/DSC07670.JPG", 6, null, 0),
                ShotFile("._DSC07670.JPG", "D/._DSC07670.JPG", 6, null, 0),
            ),
        )
        assertThat(shots.map { it.id to it.kind }).containsExactly("dsc07668" to "pair", "dsc07669" to "raw", "dsc07670" to "jpeg").inOrder()
    }

    @Test
    fun `sorts by capture time with sub-seconds, name for ties, unknown times last`() {
        val shots = listOf(shot("dsc3"), shot("dsc1"), shot("dsc2"), shot("dsc10"), shot("dsc4"))
        val metadata = mapOf("dsc1" to meta(2_000), "dsc2" to meta(1_500), "dsc3" to meta(1_500), "dsc10" to meta(null))
        assertThat(sortByCapture(shots, metadata).map { it.id }).containsExactly("dsc2", "dsc3", "dsc1", "dsc4", "dsc10").inOrder()
    }

    @Test
    fun `flags and stars, with rating a reject turning it into a pick`() {
        assertThat(Decision(null, 3).withFlag(Flag.Reject)).isEqualTo(Decision(Flag.Reject, 3))
        assertThat(Decision(Flag.Reject, 0).withRating(4)).isEqualTo(Decision(Flag.Pick, 4))
        assertThat(Decision(null, 2).withRating(0)).isEqualTo(Decision(null, 0))
        assertThat(Decision(null, 0).withRating(9)).isEqualTo(Decision(null, 5))
    }

    @Test
    fun `applies and undoes changes, dropping unmarked shots`() {
        var decisions: Map<String, Decision> = emptyMap()
        val pick = changeOf(decisions, listOf("a", "b")) { d, _ -> d.withFlag(Flag.Pick) }!!
        decisions = applyChange(decisions, pick)
        assertThat(decisions["a"]).isEqualTo(Decision(Flag.Pick, 0))
        assertThat(changeOf(decisions, listOf("a")) { d, _ -> d.withFlag(Flag.Pick) }).isNull()
        val reject = changeOf(decisions, listOf("b", "c")) { d, _ -> d.withFlag(Flag.Reject) }!!
        decisions = applyChange(decisions, reject)
        assertThat(reject.shotIds).containsExactly("b", "c").inOrder()
        decisions = applyChange(decisions, reject, undo = true)
        assertThat(decisions["b"]).isEqualTo(Decision(Flag.Pick, 0))
        assertThat(decisions).doesNotContainKey("c")
    }

    @Test
    fun `keeps the undo stack bounded`() {
        var stack = emptyList<DecisionChange>()
        repeat(UNDO_LIMIT + 5) { stack = pushUndo(stack, DecisionChange(listOf(it.toString()), emptyList(), emptyList())) }
        assertThat(stack).hasSize(UNDO_LIMIT)
        assertThat(stack[0].shotIds).containsExactly("5")
    }

    @Test
    fun `filters by flag and stars and counts progress`() {
        val shots = listOf("a", "b", "c", "d", "e").map { shot(it) }
        val decisions = mapOf("a" to Decision(Flag.Pick, 5), "b" to Decision(Flag.Pick, 2), "c" to Decision(Flag.Reject, 0), "d" to Decision(null, 3))
        fun ids(flag: FlagFilter, minStars: Int = 0) = filterShots(shots, decisions, CullFilter(flag, minStars)).map { it.id }
        assertThat(ids(FlagFilter.All)).containsExactly("a", "b", "c", "d", "e").inOrder()
        assertThat(ids(FlagFilter.Picked)).containsExactly("a", "b").inOrder()
        assertThat(ids(FlagFilter.Rejected)).containsExactly("c")
        assertThat(ids(FlagFilter.Unmarked)).containsExactly("d", "e").inOrder()
        assertThat(ids(FlagFilter.All, 3)).containsExactly("a", "d").inOrder()
        assertThat(ids(FlagFilter.Picked, 3)).containsExactly("a")
        assertThat(filterShots(shots, decisions, CullFilter())).hasSize(5)
        assertThat(progressOf(shots, decisions)).isEqualTo(CullProgress(total = 5, decided = 3, picked = 2, rejected = 1, unmarked = 2, rated = 3))
        assertThat(nextUnmarked(shots, decisions, 0)).isEqualTo(3)
        assertThat(nextUnmarked(shots, decisions, 4)).isEqualTo(3)
        assertThat(nextUnmarked(shots, shots.associate { it.id to Decision(Flag.Pick, 0) }, 0)).isEqualTo(-1)
        // Resume from "before the first" (-1) starts at the first shot.
        assertThat(nextUnmarked(shots, emptyMap(), -1)).isEqualTo(0)
    }

    private val copyShots = listOf(shot("p1"), shot("r1", "raw"), shot("j1", "jpeg"), shot("x1"), shot("u1")).let { list ->
        list.toMutableList().also { it[0] = it[0].copy(others = listOf(ShotFile("p1.HIF", "f/p1.HIF", 3, null, 0))) }
    }
    private val copyDecisions = mapOf(
        "p1" to Decision(Flag.Pick, 0),
        "r1" to Decision(Flag.Pick, 3),
        "j1" to Decision(Flag.Pick, 0),
        "x1" to Decision(Flag.Reject, 0),
    )

    @Test
    fun `copies every file of a pick with RAW + JPEG`() {
        val plan = planCopy(copyShots, copyDecisions, CopyFiles.Both)
        assertThat(plan.items.map { it.file.name }).containsExactly("p1.ARW", "p1.JPG", "p1.HIF", "r1.ARW", "j1.JPG").inOrder()
        assertThat(plan.shots).isEqualTo(3)
        assertThat(plan.fallbacks).isEqualTo(0)
        assertThat(plan.bytes).isEqualTo(25L + 6 + 3 + 25 + 6)
    }

    @Test
    fun `copies one kind, falling back to the only file a shot has`() {
        val raw = planCopy(copyShots, copyDecisions, CopyFiles.Raw)
        assertThat(raw.items.map { it.file.name to it.fallback }).containsExactly("p1.ARW" to false, "r1.ARW" to false, "j1.JPG" to true).inOrder()
        assertThat(raw.fallbacks).isEqualTo(1)
        val jpeg = planCopy(copyShots, copyDecisions, CopyFiles.Jpeg)
        assertThat(jpeg.items.map { it.file.name }).containsExactly("p1.JPG", "r1.ARW", "j1.JPG").inOrder()
        assertThat(jpeg.fallbacks).isEqualTo(1)
    }

    @Test
    fun `names the destination folder`() {
        assertThat(copyTargetPath("Pictures", "DCIM/100MSDCF", CopyPicksOptions())).isEqualTo("Pictures/100MSDCF")
        assertThat(copyTargetPath("", "DCIM", CopyPicksOptions())).isEqualTo("DCIM")
        assertThat(copyTargetPath("Pictures", "", CopyPicksOptions())).isEqualTo("Pictures")
        assertThat(copyTargetPath("Fotos", "DCIM/100MSDCF", CopyPicksOptions(CopyFiles.Raw, CopyFolder.New, " Celine "))).isEqualTo("Fotos/Celine")
        assertThat(copyTargetPath("Fotos", "DCIM", CopyPicksOptions(CopyFiles.Raw, CopyFolder.None))).isEqualTo("Fotos")
    }

    @Test
    fun `deletes every file of each rejected shot`() {
        val plan = planDeleteRejects(copyShots, copyDecisions)
        assertThat(plan.shots).containsExactly("x1")
        assertThat(plan.files.map { it.name }).containsExactly("x1.ARW", "x1.JPG").inOrder()
        assertThat(plan.bytes).isEqualTo(31L)
    }

    @Test
    fun `slices the order around the current shot`() {
        val order = listOf("a", "b", "c", "d", "e", "f")
        assertThat(prefetchSlice(order, 2, 3, 1)).isEqualTo(PrefetchSlice(listOf("b", "c", "d", "e", "f"), 1))
        assertThat(prefetchSlice(order, 0, 3, 1)).isEqualTo(PrefetchSlice(listOf("a", "b", "c", "d"), 0))
        assertThat(prefetchSlice(order, 5, 3, 2)).isEqualTo(PrefetchSlice(listOf("d", "e", "f"), 2))
        assertThat(prefetchSlice(emptyList(), 0, 3, 1)).isEqualTo(PrefetchSlice(emptyList(), 0))
        assertThat(prefetchSlice(order, 9, 3, 1)).isEqualTo(PrefetchSlice(emptyList(), 0))
    }

    @Test
    fun `picks the sample size for a zoom level`() {
        assertThat(sampleForScale(1.0)).isEqualTo(1)
        assertThat(sampleForScale(2.0)).isEqualTo(1)
        assertThat(sampleForScale(0.6)).isEqualTo(1)
        assertThat(sampleForScale(0.5)).isEqualTo(2)
        assertThat(sampleForScale(0.3)).isEqualTo(2)
        assertThat(sampleForScale(0.24)).isEqualTo(4)
        assertThat(sampleForScale(0.001)).isEqualTo(16)
        assertThat(sampleForScale(0.0)).isEqualTo(16)
    }

    @Test
    fun `covers the visible area with grid-aligned tiles, center first`() {
        val tiles = tilesFor(ImageRect(600.0, 300.0, 1000.0, 400.0), 6000, 4000, 1)
        assertThat(tiles.map { it.key }).containsExactly("1:512:0", "1:1024:0", "1:1536:0", "1:512:512", "1:1024:512", "1:1536:512")
        assertThat(tiles[0].key).isEqualTo("1:1024:0")
        val edge = tilesFor(ImageRect(5900.0, 3900.0, 500.0, 500.0), 6000, 4000, 1)
        assertThat(edge).containsExactly(Tile("1:${TILE_SIZE * 11}:${TILE_SIZE * 7}", 5632, 3584, 368, 416, 1))
        assertThat(tilesFor(ImageRect(0.0, 0.0, 6000.0, 4000.0), 6000, 4000, 4)).hasSize(6)
    }

    @Test
    fun `pages both ways by distance or a fling in the finger's direction, inside the order`() {
        val w = 400.0
        assertThat(pageTarget(-w * 0.3, 0.0, w, 5, 10)).isEqualTo(6)
        assertThat(pageTarget(w * 0.3, 0.0, w, 5, 10)).isEqualTo(4)
        assertThat(pageTarget(-w * 0.2, 0.0, w, 5, 10)).isEqualTo(5)
        assertThat(pageTarget(w * Paging.DISTANCE, 0.0, w, 5, 10)).isEqualTo(4)
        assertThat(pageTarget(-40.0, -0.8, w, 5, 10)).isEqualTo(6)
        assertThat(pageTarget(60.0, -0.8, w, 5, 10)).isEqualTo(5)
        assertThat(pageTarget(8.0, 2.0, w, 5, 10)).isEqualTo(5)
        assertThat(pageTarget(w, 1.0, w, 0, 10)).isEqualTo(0)
        assertThat(pageTarget(-w, -1.0, w, 9, 10)).isEqualTo(9)
        assertThat(pageTarget(w, 1.0, 0.0, 3, 10)).isEqualTo(3)
    }
}
