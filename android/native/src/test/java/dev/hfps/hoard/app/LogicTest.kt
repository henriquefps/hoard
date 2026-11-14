package dev.hfps.hoard.app

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.ui.files.FilesViewModel
import dev.hfps.hoard.app.ui.files.Place
import dev.hfps.hoard.app.ui.importer.ImportUiState
import dev.hfps.hoard.app.ui.importer.ImportViewModel
import dev.hfps.hoard.app.ui.themeModeOf
import dev.hfps.hoard.data.FavoriteEntity
import dev.hfps.hoard.data.LegacyImportEntity
import dev.hfps.hoard.data.MachineAddressEntity
import dev.hfps.hoard.data.MachineEntity
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.data.legacy.ImportFailure
import dev.hfps.hoard.data.legacy.ImportOutcome
import dev.hfps.hoard.data.legacy.ImportProgress
import dev.hfps.hoard.designsystem.theme.ThemeMode
import dev.hfps.hoard.smb.location.VolumeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.robolectric.annotation.Config

/** Pure logic of the shell's ViewModels (Robolectric for the platform's org.json). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LogicTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    @Test fun filesStateFromRowsAndVolumes() {
        val nas = MachineEntity("m1", "NAS", null, null, "u", null, null, 0, "t")
        val machines = listOf(
            MachineWithAddresses(
                nas,
                listOf(
                    MachineAddressEntity("a2", "m1", "100.64.0.1", 445, "tailscale", null, 1),
                    MachineAddressEntity("a1", "m1", "192.168.1.10", 445, "local", null, 0),
                ),
            ),
        )
        val favorites = listOf(
            FavoriteEntity("f1", "m1", "m1", "photos", "2026", "2026", 0, "t"),
            FavoriteEntity("f2", "device", null, "", "", "Phone root", 1, "t"),
            FavoriteEntity("f3", "vol-1234-abcd", null, "", "DCIM", "Card", 2, "t"),
            FavoriteEntity("f4", "vol-FFFF-0000", null, "", "", "Gone", 3, "t"),
        )
        val volumes = listOf(
            VolumeInfo(null, "Internal", true, false, "internal", "mounted", null, 10, 20, null, "path"),
            VolumeInfo("1234-ABCD", "SD", false, true, "sd", "mounted", null, 1, 2, "exfat", "path"),
        )

        val state = FilesViewModel.filesState(machines, favorites, volumes)

        assertThat(state.loading).isFalse()
        assertThat(state.machines.single().hosts).containsExactly("192.168.1.10", "100.64.0.1").inOrder()
        assertThat(state.phone?.label).isEqualTo("Internal")
        assertThat(state.removable.map { it.uuid }).containsExactly("1234-ABCD")
        assertThat(state.favorites.map { it.place }).containsExactly(
            Place.Machine("NAS"),
            Place.Phone,
            Place.Drive("SD", "1234-abcd"),
            Place.Drive(null, "FFFF-0000"),
        ).inOrder()
    }

    @Test fun importOutcomesBecomeScreenStates() {
        val record = LegacyImportEntity("capacitor", "imported", 1, 7, """{"machines":3,"favorites":4}""", "t")
        fun stateAfter(outcome: ImportOutcome) = ImportViewModel { outcome }.state.value

        assertThat(stateAfter(ImportOutcome.NothingToImport)).isEqualTo(ImportUiState.Ready)
        assertThat(stateAfter(ImportOutcome.AlreadyDone(record))).isEqualTo(ImportUiState.Ready)
        assertThat(stateAfter(ImportOutcome.Imported(record)))
            .isEqualTo(ImportUiState.Imported(mapOf("machines" to 3, "favorites" to 4)))
        assertThat(stateAfter(ImportOutcome.Failed(ImportFailure.TooNew, "v8")))
            .isEqualTo(ImportUiState.Failed(ImportFailure.TooNew, "v8"))
    }

    @Test fun retryRunsTheImporterAgainAndContinueLeaves() {
        var calls = 0
        val vm = ImportViewModel { progress ->
            calls++
            progress(ImportProgress("machines", 0, 11))
            if (calls == 1) ImportOutcome.Failed(ImportFailure.Other, "disk") else ImportOutcome.NothingToImport
        }
        assertThat(vm.state.value).isInstanceOf(ImportUiState.Failed::class.java)
        vm.retry()
        assertThat(calls).isEqualTo(2)
        assertThat(vm.state.value).isEqualTo(ImportUiState.Ready)

        val failed = ImportViewModel { ImportOutcome.Failed(ImportFailure.Other, "disk") }
        failed.finish()
        assertThat(failed.state.value).isEqualTo(ImportUiState.Ready)
    }

    @Test fun themeModeIds() {
        assertThat(themeModeOf(null)).isEqualTo(ThemeMode.System)
        assertThat(themeModeOf("dark")).isEqualTo(ThemeMode.Dark)
        assertThat(themeModeOf("light")).isEqualTo(ThemeMode.Light)
        assertThat(themeModeOf("bogus")).isEqualTo(ThemeMode.System)
    }
}
