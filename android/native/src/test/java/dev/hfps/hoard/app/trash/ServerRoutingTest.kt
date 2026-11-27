package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.paths.MappingState
import dev.hfps.hoard.server.paths.PathMapping
import org.junit.Test

/**
 * ADR 0023 Amendment (2.10.0): Files' delete and culling's "Move rejects to trash" in a mapped server folder go
 * through the server (`POST /v1/trash/move` by item id), never over SMB; non-media files and unmapped places stay SMB
 * moves; the server unreachable, a photo it has not indexed, part of a photo and a folder of photos are refused.
 */
class ServerRoutingTest {
    private fun f(path: String, size: Long = 10, dir: Boolean = false) = TrashFile(path, size, 1_000L, dir)
    private val place = TrashPlace("m1", "photos")

    // The share exports the disk; the NAS folder f1 is `Imagens` (case 3): smbPrefix Imagens, serverPrefix "".
    private val mapping = PathMapping("m1", "photos", "Imagens", "srv", "f1", "Imagens", "", null, 0, 0)

    private val listing = listOf(
        f("Imagens/2024/Trip/DSC1.ARW"), f("Imagens/2024/Trip/DSC1.JPG"), f("Imagens/2024/Trip/DSC1.xmp"),
        f("Imagens/2024/Trip/DSC2.ARW"), f("Imagens/2024/Trip/notes.txt"), f("Imagens/2024/Trip/Sub", dir = true),
        f("Imagens/2024/Trip/Empty", dir = true),
    )

    private val items = mapOf(
        "2024/Trip/DSC1.ARW" to "item-1", "2024/Trip/DSC1.JPG" to "item-1", "2024/Trip/DSC2.ARW" to "item-2",
    )

    private fun plan(selected: List<String>, wholeShots: Boolean, state: MappingState = MappingState.Mapped(mapping)): TrashPlan {
        val units = TrashPlanner.units("Imagens/2024/Trip", listing, selected, wholeShots)
        return TrashPlanner.plan(place, units, { u -> TrashPlanner.smbRoot(u.files.first().path, state) }, serverHasTrash = true)
    }

    private val serverPath = { pu: PlannedUnit, path: String -> ServerRouting.serverPathOf(pu.root, pu.serverPrefix, path) }

    private fun route(p: TrashPlan, wholeShots: Boolean, lookup: ServerRouting.Lookup = ServerRouting.Lookup.Found(items), media: Set<String> = setOf("Imagens/2024/Trip/Sub")) =
        ServerRouting.route(p, listing, wholeShots, serverPath, lookup) { it in media }

    @Test
    fun `culling's rejects in a mapped folder go to the server by item, nothing over SMB`() {
        val p = plan(listOf("Imagens/2024/Trip/DSC1.ARW", "Imagens/2024/Trip/DSC2.ARW"), wholeShots = true)
        assertThat(ServerRouting.paths(p, listing, true, serverPath)).containsExactly("f1", listOf("2024/Trip/DSC1.ARW", "2024/Trip/DSC1.JPG", "2024/Trip/DSC2.ARW"))
        val r = route(p, true)
        assertThat(r.plan.units).isEmpty()
        assertThat(r.plan.refused).isEmpty()
        assertThat(r.plan.server.map { it.itemIds }).containsExactly(listOf("item-1"), listOf("item-2"))
        assertThat(r.plan.serverItems).containsExactly("item-1", "item-2")
        // The shot's sidecar goes with it on the server (the server's shot rule), not as an SMB move.
        assertThat(r.plan.server.first().unit.sidecars.map { it.name }).containsExactly("DSC1.xmp")
    }

    @Test
    fun `Files - a non-media file and a folder without photos stay SMB moves, a folder of photos is refused`() {
        val p = plan(listOf("Imagens/2024/Trip/notes.txt", "Imagens/2024/Trip/Empty", "Imagens/2024/Trip/Sub"), wholeShots = false)
        val r = route(p, false)
        assertThat(r.plan.units.map { it.unit.files.single().name }).containsExactly("notes.txt", "Empty")
        assertThat(r.plan.refused.map { it.unit.files.single().name to it.reason }).containsExactly("Sub" to TrashRefusal.FOLDER_ON_SERVER)
        assertThat(r.plan.server).isEmpty()
    }

    @Test
    fun `Files - the JPEG of a RAW + JPEG is part of a photo, the whole pair goes`() {
        val jpeg = route(plan(listOf("Imagens/2024/Trip/DSC1.JPG"), wholeShots = false), false)
        assertThat(jpeg.plan.refused.map { it.reason }).containsExactly(TrashRefusal.PART_OF_SHOT)
        assertThat(jpeg.plan.server).isEmpty()

        val pair = route(plan(listOf("Imagens/2024/Trip/DSC1.JPG", "Imagens/2024/Trip/DSC1.ARW"), wholeShots = false), false)
        assertThat(pair.plan.server.single().itemIds).containsExactly("item-1")
        assertThat(pair.plan.refused).isEmpty()
    }

    @Test
    fun `a photo the server has not indexed is refused and its folder walked`() {
        val r = route(plan(listOf("Imagens/2024/Trip/DSC2.ARW"), wholeShots = true), true, ServerRouting.Lookup.Found(emptyMap()))
        assertThat(r.plan.refused.map { it.reason }).containsExactly(TrashRefusal.NOT_ON_SERVER_YET)
        assertThat(r.unknownFolders).containsExactly("f1")
        assertThat(r.plan.units).isEmpty() // never an SMB move instead
    }

    @Test
    fun `the server unreachable refuses server photos, never falls back to SMB`() {
        val p = plan(listOf("Imagens/2024/Trip/DSC2.ARW", "Imagens/2024/Trip/notes.txt"), wholeShots = false)
        val r = route(p, false, ServerRouting.Lookup.Failed)
        assertThat(r.plan.refused.map { it.reason }).containsExactly(TrashRefusal.PHOTO_SERVER_UNREACHABLE)
        assertThat(r.plan.units.map { it.unit.files.single().name }).containsExactly("notes.txt")
    }

    @Test
    fun `a folder not on the server stays an SMB move`() {
        val p = plan(listOf("Imagens/2024/Trip/DSC2.ARW"), wholeShots = true, state = MappingState.NotOnServer)
        val r = route(p, true)
        assertThat(r.plan.units).hasSize(1)
        assertThat(r.plan.server).isEmpty()
    }

    @Test
    fun `server paths follow the mapping's prefixes`() {
        assertThat(ServerRouting.serverPathOf("Imagens", "", "Imagens/2024/a.jpg")).isEqualTo("2024/a.jpg")
        assertThat(ServerRouting.serverPathOf("", "2024", "Trip/a.jpg")).isEqualTo("2024/Trip/a.jpg")
        assertThat(ServerRouting.serverPathOf("Imagens", "", "Other/a.jpg")).isNull()
    }
}
