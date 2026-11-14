package dev.hfps.hoard.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.data.FilesSettings
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.app.route.AddressTestResult
import dev.hfps.hoard.app.route.ConnectTest
import dev.hfps.hoard.app.route.ProbeResult
import dev.hfps.hoard.smb.transfer.TransferStates
import dev.hfps.hoard.app.ui.files.FilesActions
import dev.hfps.hoard.app.ui.files.FilesScreen
import dev.hfps.hoard.app.ui.machines.DraftAddress
import dev.hfps.hoard.app.ui.machines.MachineDraft
import dev.hfps.hoard.app.ui.machines.MachineFormScreen
import dev.hfps.hoard.app.ui.machines.MachineFormState
import dev.hfps.hoard.app.ui.machines.MachineFormViewModel
import dev.hfps.hoard.app.ui.settings.FilesSettingsContent
import dev.hfps.hoard.app.ui.settings.SettingsScreen
import dev.hfps.hoard.app.ui.settings.SettingsUiState
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import dev.hfps.hoard.app.ui.storage.StorageAccessScreen
import dev.hfps.hoard.app.ui.transfers.TransfersScreen
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2b's Files tab (ADR 0019): behaviour (Compose under Robolectric) and goldens under `src/test/screenshots/`,
 * copied to docs/testing/native-2b/. Record with `./gradlew :native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FilesTabTest {
    @get:Rule val compose = createComposeRule()

    private fun shoot(name: String, qualifiers: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { HfpsTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val PHONE = ShellScreenshotTest.PHONE
    private val LANDSCAPE = ShellScreenshotTest.LANDSCAPE
    private val TABLET = ShellScreenshotTest.TABLET

    private val tabbedFolder: @Composable () -> Unit = { TabbedLayout(Tab.Files, {}) { Samples2b.Browse(Samples2b.folder()) } }

    // --- Behaviour -------------------------------------------------------------------------------------------

    @Test fun aFolderListsFoldersFirstWithSizesAndHidesDotFiles() {
        compose.setContent { HfpsTheme(darkTheme = false) { Samples2b.Browse(Samples2b.folder()) } }
        compose.onNodeWithText("Local · 192.168.1.155 · 4 ms").assertIsDisplayed()
        compose.onNodeWithText("Lisboa").assertIsDisplayed()
        compose.onNodeWithTag("browse-list").performScrollToNode(hasText("6.1 MB · ", substring = true))
        compose.onNodeWithText("6.1 MB · ", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("browse-list").performScrollToNode(hasText("9 items", substring = true))
        compose.onNodeWithText("9 items", substring = true).assertIsDisplayed()
    }

    @Test fun theRowMenuHasHoard1sFileActions() {
        compose.setContent { HfpsTheme(darkTheme = false) { Samples2b.Browse(Samples2b.folder()) } }
        compose.onNodeWithTag("browse-list").performScrollToNode(hasText("notes.md"))
        compose.onNodeWithContentDescription("Actions for notes.md").performClick()
        listOf("Open in Hoard", "Open with…", "Copy", "Move", "Copy to…", "Move to…", "Download", "Share", "Copy path", "Rename", "Select", "Delete")
            .forEach { compose.onNodeWithText(it).assertExists() }
        compose.onNodeWithText("Select").performScrollTo().performClick()
        compose.waitForIdle()
        // Closing the menu after an action.
        compose.onNodeWithText("Copy path").assertDoesNotExist()
    }

    @Test fun selectionBarCountsAndReadOnlyDisablesWrites() {
        compose.setContent {
            HfpsTheme(darkTheme = false) { Samples2b.Browse(Samples2b.folder(selected = setOf("Photos/DSC0001.JPG", "Photos/notes.md"), readOnly = true)) }
        }
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Read-only", substring = true).assertExists()
    }

    @Test fun sharesShowAccessAndTheOnesAddedByName() {
        compose.setContent { HfpsTheme(darkTheme = false) { Samples2b.Browse(Samples2b.shares) } }
        compose.onNodeWithText("Media files").assertIsDisplayed()
        compose.onNodeWithText("Added by name").assertIsDisplayed()
        compose.onNodeWithText("Read-only").assertIsDisplayed()
        compose.onNodeWithText("Open share by name…").assertIsDisplayed()
    }

    @Test fun filesTabActionsReachTheShell() {
        var added = 0
        var opened: String? = null
        var transfers = 0
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                FilesScreen(
                    Samples.files,
                    {},
                    actions = FilesActions(
                        openLocation = { id, share, path -> opened = "$id|$share|$path" },
                        addMachine = { added++ },
                        transfers = { transfers++ },
                    ),
                )
            }
        }
        compose.onNodeWithText("Internal storage").performClick()
        compose.onNodeWithContentDescription("Transfers").performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Add machine"))
        compose.onNodeWithText("Add machine").performClick()
        assertThat(opened).isEqualTo("device|null|")
        assertThat(added to transfers).isEqualTo(1 to 1)
    }

    @Test fun theMachineFormValidatesBeforeSaving() {
        val vm = MachineFormViewModel(Samples2b.container, null)
        compose.setContent {
            HfpsTheme(darkTheme = false) {
                Samples2b.Toasts {
                    val state = vm.state.collectAsStateValue()
                    MachineFormScreen(vm, state, {}, {}, {})
                }
            }
        }
        compose.onNodeWithTag("machine-form").performScrollToNode(hasText("Add machine"))
        compose.onNodeWithTag("machine-save").performClick()
        compose.waitForIdle()
        val errors = vm.state.value.errors
        assertThat(errors.name).isNotNull()
        assertThat(errors.username).isNotNull()
        assertThat(errors.password).isNotNull()
        assertThat(errors.addresses).isNotNull()
        vm.update { it.copy(name = "NAS", username = "u", password = "p", addresses = listOf(DraftAddress("k", "nas.local", "99999"))) }
        assertThat(vm.validate()).isNull()
        assertThat(vm.state.value.errors.byAddress["k"]).isNotNull()
        vm.update { it.copy(addresses = listOf(DraftAddress("k", "smb://nas.local/share"), DraftAddress("j", "NAS.local"))) }
        assertThat(vm.validate()).isNull()
        vm.update { it.copy(addresses = listOf(DraftAddress("k", "smb://nas.local/share"), DraftAddress("j", "100.64.0.9", "4450"))) }
        assertThat(vm.validate()!!.map { it.host to it.port }).containsExactly("nas.local" to null, "100.64.0.9" to 4450).inOrder()
    }

    // --- Goldens ---------------------------------------------------------------------------------------------

    @Test fun browsePhoneLight() = shoot("browse_phone_light", PHONE, dark = false, content = tabbedFolder)
    @Test fun browsePhoneDark() = shoot("browse_phone_dark", PHONE, dark = true, content = tabbedFolder)
    @Test fun browseLandscapeLight() = shoot("browse_landscape_light", LANDSCAPE, dark = false, content = tabbedFolder)
    @Test fun browseLandscapeDark() = shoot("browse_landscape_dark", LANDSCAPE, dark = true, content = tabbedFolder)
    @Test fun browseTabletLight() = shoot("browse_tablet_light", TABLET, dark = false, content = tabbedFolder)
    @Test fun browseTabletDark() = shoot("browse_tablet_dark", TABLET, dark = true, content = tabbedFolder)

    @Test fun browseSelectionPhoneDark() = shoot("browse_selection_phone_dark", PHONE, dark = true) {
        TabbedLayout(Tab.Files, {}) { Samples2b.Browse(Samples2b.folder(selected = setOf("Photos/DSC0001.JPG", "Photos/DSC0001.ARW"))) }
    }

    @Test fun browseReadOnlyPhoneLight() = shoot("browse_read_only_phone_light", PHONE, dark = false) {
        TabbedLayout(Tab.Files, {}) { Samples2b.Browse(Samples2b.folder(readOnly = true)) }
    }

    @Test fun sharesPhoneLight() = shoot("shares_phone_light", PHONE, dark = false) { TabbedLayout(Tab.Files, {}) { Samples2b.Browse(Samples2b.shares) } }

    @Test fun filesWithRoutesPhoneDark() = shoot("files_routes_phone_dark", PHONE, dark = true) {
        TabbedLayout(Tab.Files, {}) {
            FilesScreen(
                Samples.files.copy(
                    routes = mapOf("m1" to Samples2b.online, "m2" to dev.hfps.hoard.app.route.RouteState(dev.hfps.hoard.app.route.RouteStatus.Error, errorCode = ErrorCode.AUTH_FAILED)),
                    storageGranted = false,
                    activeTransfers = 2,
                ),
                {},
            )
        }
    }

    private fun seedTransfers() = runBlocking {
        val dao = Samples2b.container.database.transfers()
        if (dao.all().isNotEmpty()) return@runBlocking
        fun row(id: String, state: String, name: String, done: Long, size: Long, items: Int, itemsDone: Int, failed: Int = 0) = TransferEntity(
            id, "copy", "device", null, "", "[\"Download/$name\"]", "device", null, "", "DCIM/Backup", null, name, "ask", size, done,
            items, itemsDone, failed, 0, state, if (state == TransferStates.FAILED) "TIMEOUT" else null, null, "2026-11-14T10:0$items:00Z",
            if (state == TransferStates.COMPLETED || state == TransferStates.FAILED) "2026-11-14T10:30:00Z" else null,
        )
        dao.insert(row("t1", TransferStates.RUNNING, "clip.mp4", 40_000_000, 104_432_771, 1, 0))
        dao.insert(row("t2", TransferStates.COMPLETED, "DSC0001.JPG", 6_412_331, 6_412_331, 2, 2))
        dao.insert(row("t3", TransferStates.FAILED, "backup.zip", 1_000_000, 9_820_100_000, 1, 0, failed = 1))
    }

    @Test fun transfersPhoneLight() {
        seedTransfers()
        shoot("transfers_phone_light", PHONE, dark = false) { Samples2b.Toasts { TransfersScreen({}, {}) } }
    }

    @Test fun transfersPhoneDark() {
        seedTransfers()
        shoot("transfers_phone_dark", PHONE, dark = true) { Samples2b.Toasts { TransfersScreen({}, {}) } }
    }

    private fun formState() = MachineFormState(
        isNew = false,
        id = "m1",
        loaded = Samples2b.machine,
        draft = MachineDraft(
            name = "Test Samba",
            icon = "desktop-tower",
            color = "teal",
            username = "hoard",
            addresses = listOf(DraftAddress("a1", "192.168.1.155"), DraftAddress("a2", "100.101.12.34")),
        ),
        manualOrder = true,
        hasPassword = true,
        pinnedGuid = "6f1c2b9e-3a5d-4c7e-9b21-0d8e4f7a1c35",
        results = listOf(
            AddressTestResult("192.168.1.155", 445, AddressKind.Local, ProbeResult(true, 4), ConnectTest(true, serverName = "SERVER", guid = "match")),
            AddressTestResult("100.101.12.34", 445, AddressKind.Tailscale, ProbeResult(false, reason = ErrorCode.TIMEOUT)),
        ),
    )

    @Test fun machineFormPhoneLight() {
        val vm = MachineFormViewModel(Samples2b.container, "m1")
        shoot("machine_form_phone_light", PHONE, dark = false) { Samples2b.Toasts { MachineFormScreen(vm, formState(), {}, {}, {}) } }
    }

    @Test fun machineFormTabletDark() {
        val vm = MachineFormViewModel(Samples2b.container, "m1")
        shoot("machine_form_tablet_dark", TABLET, dark = true) { Samples2b.Toasts { MachineFormScreen(vm, formState(), {}, {}, {}) } }
    }

    @Test fun storageAccessPhoneLight() = shoot("storage_access_phone_light", PHONE, dark = false) { Samples2b.Toasts { StorageAccessScreen {} } }

    @Test fun settingsFilesPhoneDark() = shoot("settings_files_phone_dark", PHONE, dark = true) {
        Samples2b.Toasts {
            SettingsScreen(SettingsUiState(), {}, {}, {}, {}, files = {
                FilesSettingsContent(FilesSettings(showHiddenFiles = true, defaultSort = SortOrder(SortKey.Modified, false), defaultConflict = "rename"), null)
            })
        }
    }
}

@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateValue(): T = this.collectAsState().value
