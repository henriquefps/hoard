package dev.hfps.hoard.app.trash

import dev.hfps.hoard.image.format.ImageFormats
import dev.hfps.hoard.server.paths.MappingState
import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.xmp.SidecarNames
import java.time.LocalDate
import java.util.Locale

/*
 * The trash for rejects (ADR 0023), its pure half: which files a selection trashes (whole shots with their sidecars),
 * where each location's trash root is (the ADR 0022 mapping rule for SMB), and the `.hoard-trash/<day>/<path>` names.
 * JVM-tested (`TrashPlannerTest`).
 */

/** A listed file or folder: its path in the location, size and mtime (Unix ms). */
data class TrashFile(val path: String, val size: Long, val modifiedMillis: Long?, val isDirectory: Boolean = false) {
    val name: String get() = SharePaths.name(path)
}

/**
 * What one trash step moves together: a **shot** (every media file of a folder with one case-insensitive stem, then its
 * sidecars) or a **plain** file or folder, moved as it is.
 */
data class TrashUnit(
    /** Folder + lower-case stem (`a/b/dsc0001`) of a shot; the lower-case path of a plain item. */
    val key: String,
    /** The folder the unit is in. */
    val folder: String,
    /** The shot's media files (main files first), or the plain item. */
    val files: List<TrashFile>,
    /** The shot's sidecars (`X.xmp`, `X.ARW.xmp`, `.X.xmp`): moved after the files, restored with them. */
    val sidecars: List<TrashFile> = emptyList(),
    val isShot: Boolean,
) {
    val all: List<TrashFile> get() = files + sidecars
    val bytes: Long get() = all.sumOf { it.size }
    val stem: String? get() = if (isShot) SharePaths.name(key) else null
}

/** Why a unit is not trashed (the dialog then offers the permanent delete for these only, ADR 0023). */
enum class TrashRefusal {
    /** The unit is inside a `.hoard-trash` already. */
    ALREADY_IN_TRASH,

    /** A folder that holds its own trash root (moving it would move the trash into itself). */
    CONTAINS_TRASH,

    /** ADR 0023 case 5: paired, the folder's mapping not known yet, and the photo server cannot be reached. */
    SERVER_UNREACHABLE,

    /** The trash root cannot be written (`ACCESS_DENIED`: a read-only share root or prefix). */
    ROOT_NOT_WRITABLE,

    /** `STATUS_NOT_SAME_DEVICE`: the share spans two filesystems, a move would not be a rename. */
    NOT_SAME_DEVICE,

    /** A SAF tree whose provider cannot move, or a path outside the granted folder. */
    MOVE_UNSUPPORTED,

    /** The location (share, drive) could not be reached. */
    UNREACHABLE,

    /** The file is gone from its folder. */
    NOT_FOUND,

    /**
     * Mapped photos were found, but this item is outside the mapping that was learned (e.g. a sibling of a NAS folder
     * selected from above it): where its trash belongs is not known, so it is not guessed.
     */
    UNKNOWN_PLACE,

    /** The phone's MediaStore trash is not available (kept for older rows; Android 11+ has it since phase A3). */
    PHONE_TRASH_UNAVAILABLE,

    /** The user declined Android's consent dialog (the phone's trash). */
    DECLINED,

    /** A folder on the phone: Android's trash takes files, not folders. */
    FOLDER_NOT_TRASHABLE,

    /** Anything else (its error code goes with it). */
    FAILED,
}

/** The trash root chosen for a path: a folder of the location ("" = its root), or a refusal. */
sealed interface RootChoice {
    /** [serverFolderId] and [serverPrefix] when the root is inside a NAS folder of the paired server (a mapping). */
    data class Root(val path: String, val serverFolderId: String? = null, val serverPrefix: String? = null) : RootChoice

    data class Refused(val reason: TrashRefusal) : RootChoice
}

/** A unit with its trash root: what [TrashRunner] moves. */
data class PlannedUnit(
    val unit: TrashUnit,
    val root: String,
    val serverFolderId: String? = null,
    /** Server item ids of the unit's files by path, when the folder is mapped (best effort, for the index). */
    val serverItems: Map<String, String> = emptyMap(),
)

data class RefusedUnit(val unit: TrashUnit, val reason: TrashRefusal, val message: String? = null)

/** Where a plan runs: a location (machine id, `device`, `vol-<uuid>`) and, for SMB, its share. */
data class TrashPlace(val locationId: String, val share: String = "") {
    val isSmb: Boolean get() = share.isNotEmpty()
}

/**
 * A trash operation in one place: what moves and where, what is refused up front, and whether the paired server is too
 * old to keep Reveal's edits of the mapped photos (ADR 0023 "Older servers": A2 shows the warning).
 */
data class TrashPlan(
    val place: TrashPlace,
    val units: List<PlannedUnit>,
    val refused: List<RefusedUnit> = emptyList(),
    val oldServerWarning: Boolean = false,
) {
    val shots: Int get() = units.count { it.unit.isShot }
    val files: Int get() = units.sumOf { it.unit.all.size }
    val bytes: Long get() = units.sumOf { it.unit.bytes }
    val roots: List<String> get() = units.map { it.root }.distinct()
}

/** Names and paths of the `.hoard-trash` folders. */
object TrashPaths {
    const val DIR = ".hoard-trash"

    /** Removable volumes and the phone's own fallback hide the trash from the media scanner. */
    const val NO_MEDIA = ".nomedia"

    private val DAY = Regex("^(\\d{4}-\\d{2}-\\d{2})(?:-(\\d+))?$")

    /** `<root>/.hoard-trash`. */
    fun trashDir(root: String): String = SharePaths.join(root, DIR)

    /** The day folder of a date: `yyyy-MM-dd`, or `yyyy-MM-dd-<n>` for [n] ≥ 2. */
    fun dayFolder(date: LocalDate, n: Int = 1): String = if (n <= 1) date.toString() else "$date-$n"

    /** The date of a day folder name (`2026-11-15`, `2026-11-15-2`), or null for anything else. */
    fun dateOf(dayFolder: String): LocalDate? {
        val m = DAY.matchEntire(dayFolder) ?: return null
        if (m.groupValues[2].isNotEmpty() && (m.groupValues[2].toIntOrNull() ?: 0) < 2) return null
        return runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
    }

    fun isDayFolder(name: String): Boolean = dateOf(name) != null

    /** `<root>/.hoard-trash/<day>/<path from the root>`. */
    fun trashedPath(root: String, dayFolder: String, originalPath: String): String {
        val rest = SharePaths.rest(originalPath, root) ?: error("$originalPath is not under $root")
        return SharePaths.join(SharePaths.join(trashDir(root), dayFolder), rest)
    }

    /** Whether a path is a `.hoard-trash` folder or inside one. */
    fun isInTrash(path: String): Boolean = SharePaths.segments(path).any { it.equals(DIR, ignoreCase = true) }

    /** A path inside a trash, read back: its root, day folder and original path (null for anything else). */
    data class Parsed(val root: String, val dayFolder: String, val originalPath: String)

    fun parse(trashedPath: String): Parsed? {
        val segments = SharePaths.segments(trashedPath)
        val at = segments.indexOfFirst { it.equals(DIR, ignoreCase = true) }
        if (at < 0 || segments.size < at + 3) return null
        val day = segments[at + 1]
        if (!isDayFolder(day)) return null
        val rest = segments.drop(at + 2)
        if (rest.any { it.equals(DIR, ignoreCase = true) }) return null
        val root = segments.take(at).joinToString("/")
        return Parsed(root, day, SharePaths.join(root, rest.joinToString("/")))
    }
}

object TrashPlanner {
    /** Video names the server indexes (ADR 0014): the video of a Live Photo goes with its shot. */
    private val VIDEOS = setOf("mp4", "m4v", "mov", "3gp", "3g2", "mts", "m2ts", "mkv", "webm", "avi")

    /** Most day folders one root is tried with for one day (the same path trashed that often in a day). */
    const val MAX_DAY_SUFFIX = 999

    private fun lower(s: String) = s.lowercase(Locale.ROOT)

    /** A shot's media file: a photo (RAW, JPEG, HEIF…) or a video, not hidden. */
    fun isMedia(name: String): Boolean =
        !name.startsWith(".") && (ImageFormats.isPhoto(name) || ImageFormats.extension(name).lowercase(Locale.ROOT) in VIDEOS)

    /** `folder/stem` of a shot, lower-case. */
    fun shotKey(folder: String, stem: String): String = lower(SharePaths.join(folder, stem))

    /**
     * The units a selection trashes (ADR 0023 "What goes to the trash", and the owner's decision that a plain delete in
     * Files trashes too). [listing] is the folder's listing ([folder]), [selected] the chosen paths in it:
     * - a media file names its **shot**: every media file of the folder with the same case-insensitive stem (RAW,
     *   JPEG, HEIF, a Live Photo's video) and its sidecars (`X.xmp`, `X.ARW.xmp`, hidden `.X.xmp`);
     * - a sidecar whose shot is selected goes with it; alone, it is a plain file;
     * - anything else (a document, a folder) is a plain item, moved as it is.
     * Units come in the listing's order of their first selected file; nothing is listed twice.
     *
     * [wholeShots] false is a delete in Files (ADR 0023 "Amendment (A2)", the sidecar rule): only the selected media
     * files go, never the shot's other files, and its sidecars go only when the selection removes the last media
     * member of that stem in the folder (a sidecar stays with the member that stays). Culling passes true: a reject is
     * the whole shot.
     */
    fun units(folder: String, listing: List<TrashFile>, selected: Collection<String>, wholeShots: Boolean = true): List<TrashUnit> {
        val folderPath = SharePaths.normalize(folder)
        val wanted = selected.map { lower(SharePaths.normalize(it)) }.toHashSet()
        val inFolder = listing.filter { SharePaths.parent(it.path).equals(folderPath, ignoreCase = true) }
        val byStem = inFolder.filter { !it.isDirectory && isMedia(it.name) }.groupBy { lower(ImageFormats.stem(it.name)) }
        val sidecars = inFolder.filter { !it.isDirectory && SidecarNames.isSidecar(it.name) }
        val taken = HashSet<String>()
        val units = ArrayList<TrashUnit>()
        val shotOf = HashMap<String, TrashUnit>()
        // Shots first, so a selected sidecar of a selected shot is not also a plain unit.
        for (file in inFolder) {
            if (lower(file.path) !in wanted || file.isDirectory || !isMedia(file.name)) continue
            val stem = lower(ImageFormats.stem(file.name))
            if (shotOf.containsKey(stem)) continue
            val members = byStem[stem].orEmpty()
            val moving = if (wholeShots) members else members.filter { lower(it.path) in wanted }
            val lastMember = moving.size == members.size
            val sidecarNames = SidecarNames.candidates(stem, members.map { it.name }).toHashSet()
            val unit = TrashUnit(
                key = shotKey(folderPath, stem),
                folder = folderPath,
                files = orderMembers(moving),
                sidecars = if (lastMember) sidecars.filter { lower(it.name) in sidecarNames } else emptyList(),
                isShot = true,
            )
            shotOf[stem] = unit
            unit.all.forEach { taken += lower(it.path) }
        }
        for (file in inFolder) {
            val key = lower(file.path)
            if (key !in wanted) continue
            if (!file.isDirectory && isMedia(file.name)) {
                val unit = shotOf.remove(lower(ImageFormats.stem(file.name))) ?: continue
                units += unit
                continue
            }
            if (key in taken) continue
            taken += key
            units += TrashUnit(key = key, folder = folderPath, files = listOf(file), isShot = false)
        }
        return units
    }

    /** RAWs first, then renderings, then videos; by name inside each (the shot's main file comes first). */
    private fun orderMembers(members: List<TrashFile>): List<TrashFile> = members.sortedWith(
        compareBy<TrashFile>({ f ->
            when {
                ImageFormats.classify(f.name).isRaw -> 0
                ImageFormats.isPhoto(f.name) -> 1
                else -> 2
            }
        }, { it.name }),
    )

    /**
     * The trash root of [path] on an SMB share (ADR 0023 "The SMB trash root"), from its folder's [state]:
     * - mapped (cases 1–3): the mapping's `smbPrefix` (empty = the share root), so the server sees
     *   `<serverPrefix>/.hoard-trash` inside its NAS folder; the prefix keeps the path's own letter case;
     * - not on the server (case 4): the share root;
     * - not known and the server unreachable (case 5): refused.
     * A path the mapping does not cover is refused ([TrashRefusal.UNKNOWN_PLACE]): a mapping learned from photos under
     * the selection may be deeper than the folder on screen, and its siblings are not known.
     */
    fun smbRoot(path: String, state: MappingState): RootChoice = when (state) {
        is MappingState.Mapped -> {
            if (!state.mapping.covers(path)) {
                RootChoice.Refused(TrashRefusal.UNKNOWN_PLACE)
            } else {
                val depth = SharePaths.segments(state.mapping.smbPrefix).size
                RootChoice.Root(SharePaths.segments(path).take(depth).joinToString("/"), state.mapping.folderId, state.mapping.serverPrefix)
            }
        }
        MappingState.NotOnServer -> RootChoice.Root("")
        MappingState.Unreachable -> RootChoice.Refused(TrashRefusal.SERVER_UNREACHABLE)
    }

    /**
     * The trash root of a phone or volume path: the highest folder a move can reach there ([moveRoot]: "" for path
     * access, the granted folder for a SAF tree), or refused when the location cannot move it.
     */
    fun localRoot(moveRoot: String?): RootChoice =
        moveRoot?.let { RootChoice.Root(SharePaths.normalize(it)) } ?: RootChoice.Refused(TrashRefusal.MOVE_UNSUPPORTED)

    /**
     * Refusals that do not depend on the root rule (review of PR #58): inside a trash already; or a folder that holds a
     * place the trash must never move: a mapping's `smbPrefix` (the whole NAS folder would leave the server), a known
     * trash root ([protectedPaths]), the unit's own trash root, or any `.hoard-trash` below it ([holdsTrash], from a
     * walk of the folder; true when the walk could not finish).
     */
    fun refusalOf(unit: TrashUnit, root: String?, protectedPaths: Collection<String> = emptyList(), holdsTrash: (String) -> Boolean = { false }): TrashRefusal? {
        if (unit.all.any { TrashPaths.isInTrash(it.path) }) return TrashRefusal.ALREADY_IN_TRASH
        for (f in unit.files) {
            if (!f.isDirectory) continue
            val guarded = protectedPaths + listOfNotNull(root)
            if (guarded.any { SharePaths.rest(it, f.path) != null }) return TrashRefusal.CONTAINS_TRASH
            if (holdsTrash(f.path)) return TrashRefusal.CONTAINS_TRASH
        }
        return null
    }

    /**
     * Plans [units] of one folder, each with its root choice ([choiceFor], from [smbRoot] or [localRoot]).
     * [serverHasTrash]: the paired server lists `trash`; without it a mapped unit sets [TrashPlan.oldServerWarning]
     * (ADR 0023 "Older servers"). [serverItems]: the server's item id of a file path, when known. [protectedPaths] and
     * [holdsTrash]: see [refusalOf].
     */
    fun plan(
        place: TrashPlace,
        units: List<TrashUnit>,
        choiceFor: (TrashUnit) -> RootChoice,
        serverHasTrash: Boolean,
        serverItems: (String) -> String? = { null },
        protectedPaths: Collection<String> = emptyList(),
        holdsTrash: (String) -> Boolean = { false },
    ): TrashPlan {
        val planned = ArrayList<PlannedUnit>()
        val refused = ArrayList<RefusedUnit>()
        for (unit in units) {
            val choice = choiceFor(unit)
            val root = (choice as? RootChoice.Root)?.path
            val early = refusalOf(unit, root, protectedPaths, holdsTrash)
            when {
                early != null -> refused += RefusedUnit(unit, early)
                choice is RootChoice.Refused -> refused += RefusedUnit(unit, choice.reason)
                choice is RootChoice.Root -> planned += PlannedUnit(
                    unit = unit,
                    root = choice.path,
                    serverFolderId = choice.serverFolderId,
                    serverItems = unit.all.mapNotNull { f -> serverItems(f.path)?.let { f.path to it } }.toMap(),
                )
            }
        }
        val warn = !serverHasTrash && planned.any { it.serverFolderId != null }
        return TrashPlan(place, planned, refused, warn)
    }

    /** [plan] with one root choice for every unit. */
    fun plan(place: TrashPlace, units: List<TrashUnit>, choice: RootChoice, serverHasTrash: Boolean, serverItems: (String) -> String? = { null }): TrashPlan =
        plan(place, units, { choice }, serverHasTrash, serverItems)

    /** Merges plans of several folders of one place (a selection across folders, culling of several folders). */
    fun merge(plans: List<TrashPlan>): TrashPlan {
        require(plans.isNotEmpty())
        val place = plans.first().place
        require(plans.all { it.place == place }) { "one place per plan" }
        return TrashPlan(place, plans.flatMap { it.units }, plans.flatMap { it.refused }, plans.any { it.oldServerWarning })
    }

    /**
     * The day folder a unit goes to under [root] (ADR 0023): `<day>`, or `<day>-2`, `-3`… when one of its target
     * paths is already [taken] there (the same original path trashed twice the same day). Every file of the unit lands
     * in the same day folder.
     */
    fun dayFolderFor(root: String, day: LocalDate, paths: List<String>, taken: (String) -> Boolean): String {
        for (n in 1..MAX_DAY_SUFFIX) {
            val folder = TrashPaths.dayFolder(day, n)
            if (paths.none { taken(TrashPaths.trashedPath(root, folder, it)) }) return folder
        }
        error("No free day folder under $root for $day")
    }

    /** Day folders of a root older than [days] days on [today] (auto-empty; the day folder dates them). */
    fun expired(dayFolders: Collection<String>, today: LocalDate, days: Int): List<String> {
        if (days <= 0) return emptyList()
        val limit = today.minusDays(days.toLong())
        return dayFolders.filter { name -> TrashPaths.dateOf(name)?.let { !it.isAfter(limit) } == true }.sorted()
    }
}
