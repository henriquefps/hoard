package dev.hfps.hoard.app

import dev.hfps.hoard.app.ui.files.FavoriteItem
import dev.hfps.hoard.app.ui.files.FilesUiState
import dev.hfps.hoard.app.ui.files.MachineItem
import dev.hfps.hoard.app.ui.files.Place
import dev.hfps.hoard.app.ui.files.VolumeItem

/** Synthetic data for screenshots and UI tests (the fixture's machines, a phone with a card reader attached). */
object Samples {
    private const val GB = 1_000_000_000L

    val files = FilesUiState(
        loading = false,
        machines = listOf(
            MachineItem("m1", "NAS", "hard-drives", listOf("192.168.1.10", "100.101.102.103", "nas.tail1234.ts.net")),
            MachineItem("m2", "Família — desktop ✓", "desktop-tower", listOf("desktop.local")),
            MachineItem("m3", "Old laptop", null, emptyList()),
        ),
        phone = VolumeItem(null, "Internal shared storage", "internal", "mounted", 41 * GB, 128 * GB),
        removable = listOf(
            VolumeItem("1234-ABCD", "SanDisk SD card", "sd", "mounted", 12 * GB, 64 * GB),
            VolumeItem("9F3E-11C0", "USB drive", "usb", "mountedReadOnly", 200 * GB, 512 * GB),
        ),
        favorites = listOf(
            FavoriteItem("f1", "Lisboa", Place.Machine("NAS"), "2026/Lisboa", share = "photos"),
            FavoriteItem("f2", "Camera", Place.Phone, "DCIM/Camera"),
            FavoriteItem("f3", "Sony card", Place.Drive("SanDisk SD card", "1234-ABCD"), "DCIM/100MSDCF"),
            FavoriteItem("f4", "Users", Place.Machine("Família — desktop ✓"), "", share = "Users"),
        ),
    )

    val counts = mapOf("machines" to 3, "favorites" to 4, "cull_decisions" to 5, "transfers" to 2)
}
