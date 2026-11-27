package dev.hfps.hoard.server.api

import kotlinx.serialization.Serializable

/*
 * The trash for paired devices (hoard-server 0.15.0, ADR 0023 Amendment (0.15.0), openapi tag `trash`): the shapes of
 * `GET /v1/trash` and `POST /v1/trash/{move,restore,delete,empty}`.
 */

/** A group's figures (`groups` of `GET /v1/trash`, `confirm` of Empty): shots and bytes of its units that are not held. */
@Serializable
data class TrashFigures(val count: Int = 0, val size: Long = 0) {
    operator fun plus(other: TrashFigures) = TrashFigures(count + other.count, size + other.size)

    companion object {
        val ZERO = TrashFigures()
    }
}

/** What Restore and Delete act on: an item's files in one day folder of one NAS folder, its server trash, or a kept side. */
@Serializable
data class TrashUnitView(
    /** `nas:<folderId>:<P>/.hoard-trash/<day>`, `server` or `compressed`. */
    val key: String,
    /** `nas` or `server`. */
    val source: String = "",
    /** yyyy-MM-dd. */
    val day: String = "",
    val folderId: String? = null,
    val folderName: String? = null,
    /** The original directory ("" = the folder's root); server: the phone path's directory. */
    val dir: String = "",
    /** Server units: the phone's name. */
    val device: String? = null,
    val files: Int = 0,
    val size: Long = 0,
    val held: Boolean = false,
    /** When the server's auto-empty takes it (Unix ms): only what the server moved. */
    val purgeAfter: Long? = null,
    val byServer: Boolean = false,
    /** `admin`, a device id, or absent (the server did not move it: Hoard over SMB before 0.15.0). */
    val by: String? = null,
    /** `web`, `hoard` or `compressed`. */
    val group: String = "",
    /** A backup-only unit only the web gallery may delete (the server answers `web_only` to a device). */
    val webOnly: Boolean = false,
) {
    /**
     * Whether a device may delete it (server-0150b): not a server trash unit (`server`: backup or upload copies only,
     * no NAS file), which a device may move and restore but only the web gallery may delete or empty.
     */
    val deviceMayDelete: Boolean get() = !webOnly && key != "server"

    /** `<P>/.hoard-trash/<day>` of a NAS unit (folder-relative), null for another unit. */
    val dayDir: String? get() = if (source == "nas" && key.startsWith("nas:")) key.removePrefix("nas:").substringAfter(':', "").ifEmpty { null } else null
}

@Serializable
data class TrashPlaceView(
    val source: String = "",
    /** The NAS folder's name. */
    val folder: String? = null,
    val path: String? = null,
    /** The folder-relative path in `.hoard-trash` (`<P>/.hoard-trash/<day>/<R>`). */
    val trash: String? = null,
    val device: String? = null,
    val held: Boolean = false,
)

@Serializable
data class TrashServerSince(val since: Long? = null, val purgeAfter: Long? = null)

/** One trashed item of `GET /v1/trash`. */
@Serializable
data class TrashEntryView(
    val id: String,
    val kind: String = "",
    val name: String = "",
    val size: Long = 0,
    val trashedAt: Long? = null,
    val places: List<TrashPlaceView> = emptyList(),
    val held: Boolean = false,
    val compressedTo: String? = null,
    val durationMs: Long? = null,
    val live: Boolean = false,
    val units: List<TrashUnitView> = emptyList(),
    val serverTrash: TrashServerSince? = null,
    val edits: Boolean = false,
)

@Serializable
data class TrashPage(
    val items: List<TrashEntryView> = emptyList(),
    val cursor: String? = null,
    val hasMore: Boolean = false,
    /** First page only: `web` | `hoard` | `compressed` → figures (units not held). */
    val groups: Map<String, TrashFigures>? = null,
    /** First page only: backup-only units Empty leaves (only the web gallery deletes them). */
    val webOnly: TrashFigures? = null,
)

@Serializable
data class TrashMoveRequest(val itemIds: List<String>, val dryRun: Boolean = false)

@Serializable
data class TrashWarnings(val onlyCopy: List<String> = emptyList(), val edits: List<String> = emptyList())

@Serializable
data class TrashMoveAnswer(
    val moved: List<String> = emptyList(),
    /** item id → `not_found`, `trashed`, `held`, `busy`, `on_phone`, `nothing`. */
    val skipped: Map<String, String> = emptyMap(),
    /** item id → `changed`, `path_taken`, `not_writable`, `not_same_device`, `unreadable`, `partial`, `error`. */
    val failed: Map<String, String> = emptyMap(),
    val counts: Map<String, Int> = emptyMap(),
    val warnings: TrashWarnings = TrashWarnings(),
    val nasShots: Int = 0,
    val nasFiles: Int = 0,
    val serverItems: Int = 0,
    val size: Long = 0,
    /** Dry run: the write mount's problem (NAS shots cannot move now). */
    val nasBlocked: String? = null,
)

@Serializable
data class TrashUnitRef(val itemId: String, val key: String) {
    /** The `refused` map's key. */
    val refKey: String get() = "$itemId|$key"
}

@Serializable
data class TrashUnitsRequest(val units: List<TrashUnitRef>)

@Serializable
data class TrashEmptyRequest(val confirm: TrashFigures, val groups: List<String>? = null)

@Serializable
data class TrashActionAnswer(
    val done: List<TrashUnitRef> = emptyList(),
    /** `<itemId>|<key>` → reason. */
    val refused: Map<String, String> = emptyMap(),
    /** path_taken: the file in the way (folder-relative). */
    val names: Map<String, String> = emptyMap(),
    val keptBy: List<String> = emptyList(),
    val size: Long = 0,
)

/** The groups of the trash (Empty confirms each apart, the owner's rule). */
object TrashGroupIds {
    /** Moved from the web gallery. */
    const val WEB = "web"

    /** Trashed by Hoard on a device (through `/v1`, or over SMB before 0.15.0). */
    const val HOARD = "hoard"

    /** Originals of compressed videos. */
    const val COMPRESSED = "compressed"

    val ALL = listOf(WEB, HOARD, COMPRESSED)
}
