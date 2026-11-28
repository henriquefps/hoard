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

    // --- Review of #88, B2: no SMB permanent delete for a server folder's photos -------------------------------------

    @Test
    fun `a server folder's trash is never offered the SMB permanent delete`() {
        val inTrash = listOf(
            f("Imagens/.hoard-trash/2026-11-20/2024/Trip/DSC9.ARW"), f("Imagens/.hoard-trash/2026-11-20/2024/Trip/notes.txt"),
        )
        val units = TrashPlanner.units("Imagens/.hoard-trash/2026-11-20/2024/Trip", inTrash, inTrash.map { it.path }, wholeShots = false)
        val p = TrashPlanner.plan(place, units, { u -> TrashPlanner.smbRoot(u.files.first().path, MappingState.Mapped(mapping)) }, serverHasTrash = true)
        assertThat(p.refused.map { it.reason to it.serverFolderId }).containsExactly(
            TrashRefusal.ALREADY_IN_TRASH to "f1", TrashRefusal.ALREADY_IN_TRASH to "f1",
        )
        val r = ServerRouting.route(p, inTrash, false, serverPath, ServerRouting.Lookup.Found(emptyMap())) { false }
        // The photo and the plain file in the server folder's .hoard-trash: the Trash screen, through the server.
        assertThat(r.plan.refused.map { it.reason }.toSet()).containsExactly(TrashRefusal.SERVER_TRASH_ONLY)
        assertThat(r.plan.refused.map { it.reason }.none { it in ServerRouting.OFFERS_PERMANENT }).isTrue()
        assertThat(r.plan.units).isEmpty()
    }

    @Test
    fun `a share outside the server keeps the permanent offer for its own trash`() {
        val inTrash = listOf(f(".hoard-trash/2026-11-20/Docs/DSC9.ARW"))
        val units = TrashPlanner.units(".hoard-trash/2026-11-20/Docs", inTrash, inTrash.map { it.path }, wholeShots = false)
        val p = TrashPlanner.plan(place, units, { u -> TrashPlanner.smbRoot(u.files.first().path, MappingState.NotOnServer) }, serverHasTrash = true)
        val r = ServerRouting.route(p, inTrash, false, serverPath, ServerRouting.Lookup.Found(emptyMap())) { false }
        assertThat(r.plan.refused.map { it.reason }).containsExactly(TrashRefusal.ALREADY_IN_TRASH)
    }

    @Test
    fun `other offered refusals - a server folder's photo loses the offer, a plain file keeps it`() {
        val photo = TrashUnit("imagens/2024/trip/dsc1", "Imagens/2024/Trip", listOf(f("Imagens/2024/Trip/DSC1.ARW")), isShot = true)
        val plain = TrashUnit("imagens/2024/trip/notes.txt", "Imagens/2024/Trip", listOf(f("Imagens/2024/Trip/notes.txt")), isShot = false)
        val dir = TrashUnit("imagens/2024/trip/sub", "Imagens/2024/Trip", listOf(f("Imagens/2024/Trip/Sub", dir = true)), isShot = false)
        for (reason in ServerRouting.OFFERS_PERMANENT - TrashRefusal.ALREADY_IN_TRASH) {
            assertThat(ServerRouting.serverOnly(RefusedUnit(photo, reason, serverFolderId = "f1")).reason).isEqualTo(TrashRefusal.SERVER_TRASH_ONLY)
            assertThat(ServerRouting.serverOnly(RefusedUnit(dir, reason, serverFolderId = "f1")).reason).isEqualTo(TrashRefusal.SERVER_TRASH_ONLY)
            assertThat(ServerRouting.serverOnly(RefusedUnit(plain, reason, serverFolderId = "f1")).reason).isEqualTo(reason)
            assertThat(ServerRouting.serverOnly(RefusedUnit(photo, reason)).reason).isEqualTo(reason) // not a server folder
        }
        assertThat(dev.hfps.hoard.app.ui.trash.PERMANENT_OFFER).doesNotContain(TrashRefusal.SERVER_TRASH_ONLY)
    }
}
