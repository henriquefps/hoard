package dev.hfps.hoard.app.gallery

import dev.hfps.hoard.server.db.DeviceMediaEntity
import dev.hfps.hoard.server.db.ServerItemRow
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Synthetic gallery data for the model, behaviour and screenshot tests. */
object GallerySamples {
    fun fp(n: Int) = "v1:" + "%064x".format(n)

    fun ms(local: String): Long = LocalDateTime.parse(local).toInstant(ZoneOffset.ofHours(-3)).toEpochMilli()

    fun server(
        id: String,
        name: String,
        local: String,
        kind: String = "photo",
        folder: String = "photos/2024/Trip",
        main: Int = id.hashCode() and 0xffff,
        make: String? = "SONY",
        model: String? = "ILCE-7M4",
        duration: Long? = null,
        edit: String? = null,
        raw: Boolean = kind == "pair",
    ) = ServerItemRow(id, kind, fp(main), ms(local), local, make, model, if (make == "Canon") "RF24-105mm F4 L IS USM" else "FE 35mm F1.8", 2400, 1600, duration, name, folder, "$name $folder".lowercase(), raw, edit, if (edit != null) 1 else 0)

    private var nextId = 1L

    fun media(path: String, local: String, size: Long = 1000, fingerprint: String? = null, video: Boolean = false, duration: Long? = null, motion: Boolean = false): DeviceMediaEntity {
        val name = path.substringAfterLast('/')
        val dir = path.substringBeforeLast('/', "")
        val t = ms(local)
        return DeviceMediaEntity(
            id = nextId++, path = "/storage/emulated/0/$path", locationId = "device", dir = dir, name = name,
            shotKey = DeviceShots.shotKey("device", dir, name), mime = null, size = size, modified = t, taken = t,
            durationMs = duration, width = 4000, height = 3000, orientation = 0, video = video,
            fingerprint = fingerprint, fpSize = fingerprint?.let { size }, fpModified = fingerprint?.let { t }, motion = motion,
        )
    }

    /** A small library: a pair backed up from the phone, server pairs and videos, phone-only shots, an edit. */
    fun input(): GalleryInput {
        val server = listOf(
            server("pair-1", "DSC01000.ARW", "2024-05-03T10:00:00.250", kind = "pair", main = 1000),
            server("photo-1", "DSC01001.JPG", "2024-05-03T10:00:01.000", main = 1001),
            server("edit-1", "DSC01006.ARW", "2024-05-03T11:30:00.000", kind = "pair", main = 1006, edit = "v-1"),
            server("video-1", "C0001.MP4", "2024-05-03T12:00:00.000", kind = "video", main = 1100, duration = 83_000),
            server("motion-1", "PXL_20231224_MOTION.MP.jpg", "2023-12-24T18:00:00.000", kind = "motion", folder = "photos/Phone/2023-12", main = 1200, make = "Google", model = "Pixel 8"),
            server("old-1", "IMG_0300.JPG", "2023-01-15T08:00:00.000", folder = "photos/2023/Snow", main = 1300, make = "Canon", model = "Canon EOS R6"),
        )
        val files = mapOf(fp(1000) to "pair-1", fp(5000) to "pair-1", fp(1001) to "photo-1", fp(1006) to "edit-1", fp(1100) to "video-1", fp(1200) to "motion-1", fp(1300) to "old-1")
        val device = DeviceShots.group(
            listOf(
                // The JPEG of pair-1, on the phone too: shows once, backed up.
                media("DCIM/Camera/DSC01000.JPG", "2024-05-03T10:00:00.250", fingerprint = fp(5000)),
                media("DCIM/Camera/PXL_20241110_0001.jpg", "2024-11-10T09:00:00.000", fingerprint = fp(9001)),
                media("DCIM/Camera/PXL_20241110_0002.jpg", "2024-11-10T09:00:02.000", fingerprint = fp(9002)),
                media("DCIM/Camera/PXL_20241110_VID.mp4", "2024-11-10T09:05:00.000", fingerprint = fp(9003), video = true, duration = 12_000),
                media("Pictures/Screenshots/Screenshot_1.png", "2024-11-11T20:00:00.000"),
                media("DCIM/Camera/PXL_MOTION.MP.jpg", "2024-11-09T10:00:00.000", fingerprint = fp(9004), motion = true),
            ),
        )
        val serverDecisions = mapOf("pair-1" to EntryDecision(4, "pick"), "photo-1" to EntryDecision(0, "reject"), "edit-1" to EntryDecision(5, "pick"))
        val deviceDecisions = mapOf("device/DCIM/Camera/pxl_20241110_0001" to EntryDecision(3, null))
        return GalleryInput(server, device, files, serverDecisions, deviceDecisions, java.time.ZoneId.of("America/Sao_Paulo"))
    }

    fun entries() = GalleryModel.entries(input())

    /** A big library for the scroller: [n] server photos, a few a day. */
    fun big(n: Int): List<GalleryEntry> {
        val rows = (0 until n).map { i ->
            val day = java.time.LocalDate.of(2020, 1, 1).plusDays((i / 6).toLong())
            server("b$i", "IMG_%05d.JPG".format(i), "${day}T10:%02d:00.000".format(i % 60), main = 20000 + i)
        }
        return GalleryModel.entries(GalleryInput(rows, emptyList(), emptyMap(), emptyMap(), emptyMap()))
    }
}
