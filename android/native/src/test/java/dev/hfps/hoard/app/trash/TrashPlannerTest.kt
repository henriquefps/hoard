package dev.hfps.hoard.app.trash

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.server.paths.MappingState
import dev.hfps.hoard.server.paths.PathMapping
import org.junit.Test
import java.time.LocalDate

/** ADR 0023's pure rules: what a selection trashes, the trash root of each case, the day folders. */
class TrashPlannerTest {
    private fun f(path: String, size: Long = 10, dir: Boolean = false) = TrashFile(path, size, 1_000L, dir)

    private fun mapping(smbPrefix: String, serverPrefix: String) =
        PathMapping("m1", "photos", smbPrefix, "server", "folder-1", "Imagens", serverPrefix, null, 0, 0)

    private val day = LocalDate.of(2026, 11, 15)

    // --- The trash root (ADR 0023 "The SMB trash root") ---

    @Test
    fun `case 1 - the share root is the NAS folder root`() {
        val root = TrashPlanner.smbRoot("Trip/DSC1.ARW", MappingState.Mapped(mapping("", ""))) as RootChoice.Root
        assertThat(root.path).isEqualTo("")
        assertThat(root.serverFolderId).isEqualTo("folder-1")
        // On the server: `.hoard-trash/…` at the folder's root.
        assertThat(TrashPaths.trashedPath(root.path, "2026-11-15", "Trip/DSC1.ARW")).isEqualTo(".hoard-trash/2026-11-15/Trip/DSC1.ARW")
    }

    @Test
    fun `case 2 - the share root is inside the NAS folder`() {
        // The share exports Imagens/2024; the NAS folder is Imagens: serverPrefix 2024, the trash at the share root.
        val root = TrashPlanner.smbRoot("Trip/DSC1.ARW", MappingState.Mapped(mapping("", "2024"))) as RootChoice.Root
        assertThat(root.path).isEqualTo("")
        assertThat(root.serverPrefix).isEqualTo("2024")
        assertThat(root.serverFolderId).isEqualTo("folder-1")
    }

    @Test
    fun `case 3 - the share root is above the NAS folder - the root is the mapping's prefix`() {
        val root = TrashPlanner.smbRoot("imagens/2024/Trip/DSC1.ARW", MappingState.Mapped(mapping("Imagens", ""))) as RootChoice.Root
        // The path's own letter case.
        assertThat(root.path).isEqualTo("imagens")
        assertThat(TrashPaths.trashedPath(root.path, "2026-11-15", "imagens/2024/Trip/DSC1.ARW")).isEqualTo("imagens/.hoard-trash/2026-11-15/2024/Trip/DSC1.ARW")
        // A deeper prefix.
        val deep = TrashPlanner.smbRoot("disk/Imagens/2024/a.jpg", MappingState.Mapped(mapping("disk/Imagens", ""))) as RootChoice.Root
        assertThat(deep.path).isEqualTo("disk/Imagens")
    }

    @Test
    fun `case 4 - not on the server - the share root`() {
        val root = TrashPlanner.smbRoot("Other/DSC1.ARW", MappingState.NotOnServer) as RootChoice.Root
        assertThat(root).isEqualTo(RootChoice.Root(""))
    }

    @Test
    fun `case 5 - paired, not known, the server unreachable - refused`() {
        assertThat(TrashPlanner.smbRoot("Trip/DSC1.ARW", MappingState.Unreachable)).isEqualTo(RootChoice.Refused(TrashRefusal.SERVER_UNREACHABLE))
        val units = TrashPlanner.units("Trip", listOf(f("Trip/DSC1.ARW")), listOf("Trip/DSC1.ARW"))
        val plan = TrashPlanner.plan(TrashPlace("m1", "photos"), units, RootChoice.Refused(TrashRefusal.SERVER_UNREACHABLE), serverHasTrash = true)
        assertThat(plan.units).isEmpty()
        assertThat(plan.refused.single().reason).isEqualTo(TrashRefusal.SERVER_UNREACHABLE)
    }

    @Test
    fun `local roots - the volume root, a SAF grant, or refused`() {
        assertThat(TrashPlanner.localRoot("")).isEqualTo(RootChoice.Root(""))
        assertThat(TrashPlanner.localRoot("DCIM")).isEqualTo(RootChoice.Root("DCIM"))
        assertThat(TrashPlanner.localRoot(null)).isEqualTo(RootChoice.Refused(TrashRefusal.MOVE_UNSUPPORTED))
    }

    @Test
    fun `inside a trash, or a folder holding its trash root, is refused`() {
        val inTrash = TrashPlanner.units(".hoard-trash/2026-11-15/Trip", listOf(f(".hoard-trash/2026-11-15/Trip/DSC1.ARW")), listOf(".hoard-trash/2026-11-15/Trip/DSC1.ARW"))
        val plan = TrashPlanner.plan(TrashPlace("m1", "photos"), inTrash, RootChoice.Root(""), serverHasTrash = true)
        assertThat(plan.refused.single().reason).isEqualTo(TrashRefusal.ALREADY_IN_TRASH)
        val folder = TrashPlanner.units("", listOf(f("Imagens", 0, dir = true)), listOf("Imagens"))
        val holding = TrashPlanner.plan(TrashPlace("m1", "photos"), folder, RootChoice.Root("Imagens"), serverHasTrash = true)
        assertThat(holding.refused.single().reason).isEqualTo(TrashRefusal.CONTAINS_TRASH)
        assertThat(TrashPaths.isInTrash("a/.Hoard-Trash/x")).isTrue()
    }

    @Test
    fun `an older server warns for mapped shots only`() {
        val units = TrashPlanner.units("Trip", listOf(f("Trip/DSC1.ARW")), listOf("Trip/DSC1.ARW"))
        val mapped = RootChoice.Root("", "folder-1", "")
        assertThat(TrashPlanner.plan(TrashPlace("m1", "photos"), units, mapped, serverHasTrash = false).oldServerWarning).isTrue()
        assertThat(TrashPlanner.plan(TrashPlace("m1", "photos"), units, mapped, serverHasTrash = true).oldServerWarning).isFalse()
        assertThat(TrashPlanner.plan(TrashPlace("m1", "photos"), units, RootChoice.Root(""), serverHasTrash = false).oldServerWarning).isFalse()
        val items = TrashPlanner.plan(TrashPlace("m1", "photos"), units, mapped, serverHasTrash = true) { if (it == "Trip/DSC1.ARW") "item-1" else null }
        assertThat(items.units.single().serverItems).containsExactly("Trip/DSC1.ARW", "item-1")
    }

    // --- Day folders ---

    @Test
    fun `the day folder takes -2, -3 when a target path is taken`() {
        val taken = hashSetOf<String>()
        val paths = listOf("Trip/DSC1.ARW", "Trip/DSC1.JPG")
        assertThat(TrashPlanner.dayFolderFor("", day, paths) { it in taken }).isEqualTo("2026-11-15")
        taken += ".hoard-trash/2026-11-15/Trip/DSC1.JPG"
        assertThat(TrashPlanner.dayFolderFor("", day, paths) { it in taken }).isEqualTo("2026-11-15-2")
        taken += ".hoard-trash/2026-11-15-2/Trip/DSC1.ARW"
        assertThat(TrashPlanner.dayFolderFor("", day, paths) { it in taken }).isEqualTo("2026-11-15-3")
        // Under a prefix root.
        assertThat(TrashPlanner.dayFolderFor("Imagens", day, listOf("Imagens/a.jpg")) { false }).isEqualTo("2026-11-15")
    }

    @Test
    fun `day folder names are read back`() {
        assertThat(TrashPaths.dateOf("2026-11-15")).isEqualTo(day)
        assertThat(TrashPaths.dateOf("2026-11-15-2")).isEqualTo(day)
        assertThat(TrashPaths.dateOf("2026-11-15-1")).isNull()
        assertThat(TrashPaths.dateOf("2026-13-40")).isNull()
        assertThat(TrashPaths.dateOf("notes")).isNull()
        assertThat(TrashPaths.parse("Imagens/.hoard-trash/2026-11-15-2/2024/a.jpg"))
            .isEqualTo(TrashPaths.Parsed("Imagens", "2026-11-15-2", "Imagens/2024/a.jpg"))
        assertThat(TrashPaths.parse(".hoard-trash/junk/a.jpg")).isNull()
        assertThat(TrashPaths.parse(".hoard-trash/2026-11-15/x/.hoard-trash/2026-11-15/a.jpg")).isNull()
        assertThat(TrashPaths.parse(".hoard-trash/2026-11-15")).isNull()
    }

    @Test
    fun `expired day folders are those at least the setting's days old`() {
        val names = listOf("2026-10-15", "2026-10-16", "2026-10-16-2", "2026-11-14", "junk", ".nomedia")
        assertThat(TrashPlanner.expired(names, day, 30)).containsExactly("2026-10-15", "2026-10-16", "2026-10-16-2").inOrder()
        assertThat(TrashPlanner.expired(names, day, 7)).containsExactly("2026-10-15", "2026-10-16", "2026-10-16-2").inOrder()
        assertThat(TrashPlanner.expired(names, day, 90)).isEmpty()
        assertThat(TrashPlanner.expired(names, day, 0)).isEmpty()
    }

    // --- What a selection trashes ---

    @Test
    fun `a shot goes whole with every sidecar convention`() {
        val listing = listOf(
            f("Trip/DSC1.ARW", 40), f("Trip/dsc1.jpg", 20), f("Trip/DSC1.HEIC", 15), f("Trip/DSC1.MOV", 90),
            f("Trip/DSC1.xmp", 1), f("Trip/DSC1.ARW.xmp", 1), f("Trip/.DSC1.xmp", 1), f("Trip/.dsc1.jpg.XMP", 1),
            f("Trip/._DSC1.xmp", 1), f("Trip/DSC10.ARW"), f("Trip/DSC10.xmp"), f("Trip/notes.txt"),
        )
        val unit = TrashPlanner.units("Trip", listing, listOf("Trip/dsc1.jpg")).single()
        assertThat(unit.isShot).isTrue()
        assertThat(unit.key).isEqualTo("trip/dsc1")
        assertThat(unit.stem).isEqualTo("dsc1")
        // RAW first, then renderings, then the Live Photo's video.
        assertThat(unit.files.map { it.name }).containsExactly("DSC1.ARW", "DSC1.HEIC", "dsc1.jpg", "DSC1.MOV").inOrder()
        // X.xmp, X.ARW.xmp, hidden .X.xmp (any case); never an AppleDouble ._X.xmp or another shot's.
        assertThat(unit.sidecars.map { it.name }).containsExactly("DSC1.xmp", "DSC1.ARW.xmp", ".DSC1.xmp", ".dsc1.jpg.XMP")
        assertThat(unit.bytes).isEqualTo(40L + 20 + 15 + 90 + 4)
    }

    @Test
    fun `a shot named twice is one unit, and its sidecar is not a plain file`() {
        val listing = listOf(f("Trip/DSC1.ARW"), f("Trip/DSC1.JPG"), f("Trip/DSC1.xmp"))
        val units = TrashPlanner.units("Trip", listing, listOf("Trip/DSC1.ARW", "Trip/DSC1.JPG", "Trip/DSC1.xmp"))
        assertThat(units).hasSize(1)
        assertThat(units.single().all.map { it.name }).containsExactly("DSC1.ARW", "DSC1.JPG", "DSC1.xmp")
    }

    @Test
    fun `plain files and folders go as they are`() {
        val listing = listOf(f("Docs/report.pdf"), f("Docs/old", 0, dir = true), f("Docs/lone.xmp"), f("Docs/DSC2.JPG"), f("Docs/DSC2.xmp"))
        val units = TrashPlanner.units("Docs", listing, listOf("Docs/report.pdf", "Docs/old", "Docs/lone.xmp", "Docs/DSC2.xmp"))
        assertThat(units.map { it.isShot }).containsExactly(false, false, false, false)
        assertThat(units.map { it.files.single().name }).containsExactly("report.pdf", "old", "lone.xmp", "DSC2.xmp").inOrder()
        assertThat(units.first().key).isEqualTo("docs/report.pdf")
        // Selecting the sidecar alone does not take its photo.
        assertThat(units.flatMap { it.all }.map { it.name }).doesNotContain("DSC2.JPG")
    }

    @Test
    fun `a plan counts shots, files and bytes, and merges folders of one place`() {
        val a = TrashPlanner.plan(TrashPlace("vol-1A2B"), TrashPlanner.units("A", listOf(f("A/x.jpg", 5), f("A/x.xmp", 1)), listOf("A/x.jpg")), RootChoice.Root(""), false)
        val b = TrashPlanner.plan(TrashPlace("vol-1A2B"), TrashPlanner.units("B", listOf(f("B/y.pdf", 7)), listOf("B/y.pdf")), RootChoice.Root(""), false)
        val merged = TrashPlanner.merge(listOf(a, b))
        assertThat(merged.shots).isEqualTo(1)
        assertThat(merged.files).isEqualTo(3)
        assertThat(merged.bytes).isEqualTo(13L)
        assertThat(merged.roots).containsExactly("")
    }

    @Test
    fun `the auto-empty setting is 30 days unless one of the choices is stored`() {
        val parse = dev.hfps.hoard.app.data.AppSettings::parseTrashDays
        assertThat(parse(null)).isEqualTo(30)
        assertThat(parse("7")).isEqualTo(7)
        assertThat(parse("90")).isEqualTo(90)
        assertThat(parse("0")).isEqualTo(0)
        assertThat(parse("14")).isEqualTo(30)
        assertThat(parse("7.5")).isEqualTo(30)
        assertThat(parse("\"7\"")).isEqualTo(30)
    }

    // --- Review of PR #58 ---

    @Test
    fun `a selection that holds a NAS folder, a trash root or a hoard-trash is refused`() {
        val listing = listOf(f("Imagens", 0, dir = true), f("Other", 0, dir = true), f("Music", 0, dir = true), f("Free", 0, dir = true))
        val units = TrashPlanner.units("", listing, listOf("Imagens", "Other", "Music", "Free"))
        val plan = TrashPlanner.plan(
            TrashPlace("m1", "disk"),
            units,
            { RootChoice.Root("") },
            serverHasTrash = true,
            protectedPaths = listOf("Imagens", "Other/Phone"),
        ) { dir -> dir == "Music" }
        // Imagens is a mapping's smbPrefix, Other holds a known trash root, Music a .hoard-trash found by the walk.
        assertThat(plan.refused.map { it.unit.key to it.reason }).containsExactly(
            "imagens" to TrashRefusal.CONTAINS_TRASH,
            "other" to TrashRefusal.CONTAINS_TRASH,
            "music" to TrashRefusal.CONTAINS_TRASH,
        )
        assertThat(plan.units.map { it.unit.key }).containsExactly("free")
    }

    @Test
    fun `a path the learned mapping does not cover is not guessed`() {
        val state = MappingState.Mapped(mapping("Imagens/2024", ""))
        assertThat(TrashPlanner.smbRoot("Imagens/2023/a.jpg", state)).isEqualTo(RootChoice.Refused(TrashRefusal.UNKNOWN_PLACE))
        assertThat((TrashPlanner.smbRoot("Imagens/2024/Trip/a.jpg", state) as RootChoice.Root).path).isEqualTo("Imagens/2024")
    }

    // --- The sidecar rule of a delete in Files (Amendment A2) ---

    private val shoot = listOf(
        f("S/DSC1.ARW", 40), f("S/DSC1.JPG", 20), f("S/DSC1.xmp", 1), f("S/DSC1.JPG.xmp", 1), f("S/.DSC1.xmp", 1),
        f("S/DSC2.JPG", 20), f("S/DSC2.xmp", 1), f("S/DSC2.JPG.xmp", 1),
    )

    @Test
    fun `a Files delete of the last member takes its sidecars`() {
        val unit = TrashPlanner.units("S", shoot, listOf("S/DSC2.JPG"), wholeShots = false).single()
        assertThat(unit.files.map { it.name }).containsExactly("DSC2.JPG")
        assertThat(unit.sidecars.map { it.name }).containsExactly("DSC2.xmp", "DSC2.JPG.xmp")
    }

    @Test
    fun `a Files delete leaves the sidecars and the other files with the member that stays`() {
        val unit = TrashPlanner.units("S", shoot, listOf("S/DSC1.JPG"), wholeShots = false).single()
        // The RAW stays, so every sidecar of the shot stays with it; the RAW never goes along.
        assertThat(unit.files.map { it.name }).containsExactly("DSC1.JPG")
        assertThat(unit.sidecars).isEmpty()
        assertThat(unit.key).isEqualTo("s/dsc1")
    }

    @Test
    fun `a Files selection holding every member takes the sidecars once`() {
        val units = TrashPlanner.units("S", shoot, listOf("S/DSC1.JPG", "S/DSC1.ARW", "S/DSC2.JPG", "S/DSC1.xmp"), wholeShots = false)
        assertThat(units.map { it.key }).containsExactly("s/dsc1", "s/dsc2").inOrder()
        val dsc1 = units.first()
        assertThat(dsc1.files.map { it.name }).containsExactly("DSC1.ARW", "DSC1.JPG").inOrder()
        assertThat(dsc1.sidecars.map { it.name }).containsExactly("DSC1.xmp", "DSC1.JPG.xmp", ".DSC1.xmp")
        // A selected sidecar of a shot whose member stays is a plain file the user chose.
        val partial = TrashPlanner.units("S", shoot, listOf("S/DSC1.JPG", "S/DSC1.xmp"), wholeShots = false)
        assertThat(partial.map { it.files.single().name to it.isShot }).containsExactly("DSC1.JPG" to true, "DSC1.xmp" to false).inOrder()
        // Culling (whole shots) is unchanged: the RAW and every sidecar go.
        val culled = TrashPlanner.units("S", shoot, listOf("S/DSC1.JPG")).single()
        assertThat(culled.all.map { it.name }).containsExactly("DSC1.ARW", "DSC1.JPG", "DSC1.xmp", "DSC1.JPG.xmp", ".DSC1.xmp")
    }
}
