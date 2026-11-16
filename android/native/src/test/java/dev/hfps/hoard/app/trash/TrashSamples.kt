package dev.hfps.hoard.app.trash

import dev.hfps.hoard.data.TrashEntryEntity
import dev.hfps.hoard.data.TrashStates

/** Trash rows for the screen tests: two days, a share mapped under `Imagens`, a USB drive. */
object TrashSamples {
    val nas = TrashPlace("m1", "photos")
    val usb = TrashPlace("vol-1A2B-3C4D")

    private var n = 0

    fun row(place: TrashPlace, root: String, day: String, path: String, size: Long, state: String = TrashStates.TRASHED) = TrashEntryEntity(
        id = "e${++n}",
        runId = "r1",
        locationId = place.locationId,
        share = place.share,
        trashRoot = root,
        dayFolder = day,
        originalPath = path,
        trashedPath = TrashPaths.trashedPath(root, day, path),
        shotKey = path.substringBeforeLast('.').lowercase(),
        size = size,
        modifiedMillis = 1_000,
        trashedAt = 1_000,
        state = state,
    )

    val rows: List<TrashEntryEntity> = listOf(
        row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01001.ARW", 24_800_000),
        row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01001.JPG", 6_100_000),
        row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01002.ARW", 24_700_000),
        row(nas, "Imagens", "2026-11-14", "Imagens/2024/Home/IMG_0042.HEIC", 3_200_000),
        row(usb, "", "2026-11-14-2", "DCIM/100MSDCF/DSC00007.JPG", 5_400_000),
        row(nas, "Imagens", "2026-11-02", "Imagens/2023/Wedding/DSC09912.ARW", 25_000_000),
        row(nas, "Imagens", "2026-11-02", "Imagens/2023/Wedding/DSC09912.xmp", 4_000),
        row(nas, "", "2026-11-02", "Docs/old-report.pdf", 810_000),
        row(nas, "Imagens", "2026-11-14", "Imagens/2024/Trip/DSC01003.ARW", 24_000_000, state = TrashStates.MOVING),
    )

    fun label(place: TrashPlace) = when (place) {
        nas -> "NAS › photos"
        usb -> "SANDISK"
        else -> place.locationId
    }
}
