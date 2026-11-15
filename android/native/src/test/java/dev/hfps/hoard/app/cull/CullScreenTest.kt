package dev.hfps.hoard.app.cull

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.Samples2b
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.data.CullSettings
import dev.hfps.hoard.app.ui.cull.AssistSettingsFields
import dev.hfps.hoard.app.ui.cull.CullFolderScreen
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.SmbAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2c's culling screens (ADR 0019): behaviour under Robolectric with a fake native side, and goldens under
 * `src/test/screenshots/` (copied to docs/testing/native-2c/): the grid, suggestions, the viewer (folder and burst
 * mode), the details, compare, the read-only note and the culling settings; phone portrait and landscape, tablet,
 * light and dark. Record with `./gradlew :native:recordRoborazziDebug`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CullScreenTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: HoardDatabase
    private val store = ViewModelStore()
    private val settings = MutableStateFlow(CullSettings())

    @Before fun setUp() {
        CullImages.forceSoftware = true
        CullImages.decodeDispatcher = Dispatchers.Unconfined
        db = HoardDatabase.open(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun tearDown() {
        CullImages.decodeDispatcher = Dispatchers.IO
        store.clear()
        db.close()
    }

    private fun vm(backend: FakeCullBackend): CullViewModel {
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CullViewModel(CullKey("m1", "media", "shoot"), backend, db.cull(), settings, compute = Dispatchers.Unconfined) as T
        }
        return ViewModelProvider(store, factory)[CullViewModel::class.java]
    }

    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun show(backend: FakeCullBackend, qualifiers: String = ShellScreenshotTest.PHONE, dark: Boolean = true, before: (CullViewModel) -> Unit = {}): CullViewModel {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        val vm = vm(backend)
        settle()
        before(vm)
        compose.setContent {
            HfpsTheme(darkTheme = dark) {
                Samples2b.Toasts { CullFolderScreen(Samples2b.container, vm, Samples2b.location, "shoot", {}, {}, {}) }
            }
        }
        settle()
        return vm
    }

    private fun capture(name: String) {
        settle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun analysed(): FakeCullBackend = FakeCullBackend(tmp.newFolder()).apply {
        analyses["dsc00006"] = FakeCullBackend.cleanAnalysis(subject = 400.0, faces = listOf(Face(0.4, 0.2, 0.1, 0.15, 0.9, 0.9)))
        analyses["dsc00010"] = FakeCullBackend.cleanAnalysis(blur = 0.8)
        analyses["dsc00011"] = FakeCullBackend.cleanAnalysis(mean = 0.15)
        analyses["dsc00012"] = FakeCullBackend.cleanAnalysis(faces = listOf(Face(0.3, 0.2, 0.2, 0.25, 0.05, 0.08)))
    }

    // --- Behaviour ------------------------------------------------------------------------------------------------

    @Test
    fun `the grid shows progress, filters, stacks and opens the viewer`() {
        val vm = show(analysed())
        compose.onNodeWithTag("cull-progress").assertIsDisplayed()
        compose.onNodeWithText("0 of 24 decided · 0 picked · 0 rejected").assertIsDisplayed()
        compose.onNodeWithTag("stack-dsc00005").assertIsDisplayed()
        compose.onNodeWithTag("cull-start").performClick()
        settle()
        compose.onNodeWithTag("cull-viewer").assertIsDisplayed()
        compose.onNodeWithTag("viewer-stem").assertIsDisplayed()
        compose.onNodeWithTag("decide-pick").performClick()
        settle()
        assertThat(vm.view.value.core.decisions.values.single().flag).isEqualTo(Flag.Pick)
        compose.onNodeWithContentDescription("Undo").performClick()
        settle()
        assertThat(vm.view.value.core.decisions).isEmpty()
    }

    @Test
    fun `a burst step rejects the whole burst and View burst opens burst mode`() {
        val vm = show(analysed())
        compose.onNodeWithTag("cell-dsc00006").performClick()
        settle()
        compose.onNodeWithTag("burst-actions").assertIsDisplayed()
        compose.onNodeWithTag("keep-this-one").performClick()
        settle()
        val burst = vm.view.value.members.getValue("dsc00005")
        assertThat(burstStatus(burst, vm.view.value.core.decisions)).isEqualTo(BurstStatus(4, 1, 4, true))
        assertThat(vm.view.value.core.decisions["dsc00006"]?.flag).isEqualTo(Flag.Pick)
    }

    @Test
    fun `suggestions show and Accept all decides as one step`() {
        val vm = show(analysed())
        compose.onNodeWithTag("cull-suggest").performClick()
        settle()
        compose.onNodeWithTag("cull-suggestions").assertIsDisplayed()
        assertThat(vm.view.value.core.decisions).isEmpty()
        compose.onNodeWithText("Accept all", substring = true).performClick()
        settle()
        assertThat(vm.view.value.core.decisions).isNotEmpty()
        assertThat(vm.view.value.core.undo).hasSize(1)
    }

    @Test
    fun `a read-only folder shows the note`() {
        show(FakeCullBackend(tmp.newFolder(), access = SmbAccess.READ))
        compose.onNodeWithTag("cull-read-only").assertIsDisplayed()
        compose.onNodeWithText("Ratings are saved only in Hoard for this folder").assertIsDisplayed()
    }

    // --- Goldens ---------------------------------------------------------------------------------------------------

    @Test fun gridPhoneDark() {
        show(analysed()) { it.decide(listOf("dsc00001", "dsc00003")) { d, id -> d.withFlag(if (id == "dsc00001") Flag.Pick else Flag.Reject).withRating(if (id == "dsc00001") 4 else 0) } }
        capture("cull_grid_phone_dark")
    }

    @Test fun gridPhoneLight() {
        show(analysed(), dark = false)
        capture("cull_grid_phone_light")
    }

    @Test fun gridLandscapeDark() {
        show(analysed(), ShellScreenshotTest.LANDSCAPE)
        capture("cull_grid_landscape_dark")
    }

    @Test fun gridTabletLight() {
        show(analysed(), ShellScreenshotTest.TABLET, dark = false)
        capture("cull_grid_tablet_light")
    }

    @Test fun suggestionsPhoneDark() {
        show(analysed()) { it.setSuggesting(true) }
        capture("cull_suggestions_phone_dark")
    }

    @Test fun readOnlyPhoneLight() {
        show(FakeCullBackend(tmp.newFolder(), access = SmbAccess.READ), dark = false)
        capture("cull_read_only_phone_light")
    }

    @Test fun viewerPhone() {
        show(analysed())
        compose.onNodeWithTag("cell-dsc00012").performClick()
        capture("cull_viewer_phone")
    }

    @Test fun viewerBurstPhone() {
        show(analysed())
        compose.onNodeWithTag("cell-dsc00006").performClick()
        capture("cull_viewer_burst_phone")
    }

    @Test fun viewerLandscape() {
        show(analysed(), ShellScreenshotTest.LANDSCAPE)
        compose.onNodeWithTag("cell-dsc00002").performClick()
        capture("cull_viewer_landscape")
    }

    @Test fun viewerDetailsTablet() {
        show(analysed(), ShellScreenshotTest.TABLET)
        compose.onNodeWithTag("cell-dsc00012").performClick()
        capture("cull_viewer_details_tablet")
    }

    @Test fun viewerDetailsPhone() {
        show(analysed())
        compose.onNodeWithTag("cell-dsc00011").performClick()
        settle()
        compose.onNodeWithContentDescription("Details").performClick()
        capture("cull_viewer_details_phone")
    }

    @Test fun compareLandscape() {
        show(analysed(), ShellScreenshotTest.LANDSCAPE)
        compose.onNodeWithTag("cell-dsc00002").performClick()
        settle()
        compose.onNodeWithContentDescription("Compare").performClick()
        capture("cull_compare_landscape")
    }

    @Test fun settingsPhoneLight() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        compose.setContent { HfpsTheme(darkTheme = false) { Samples2b.Toasts { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(HfpsTheme.colors.card).padding(16.dp)) { AssistSettingsFields(Samples2b.container) } } } }
        capture("cull_settings_phone_light")
    }
}
