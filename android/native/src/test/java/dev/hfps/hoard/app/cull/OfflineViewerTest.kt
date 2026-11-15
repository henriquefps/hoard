package dev.hfps.hoard.app.cull

import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.Samples2b
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.data.CullSettings
import dev.hfps.hoard.app.ui.cull.CullFolderScreen
import dev.hfps.hoard.data.HoardDatabase
import dev.hfps.hoard.image.ScreenFile
import dev.hfps.hoard.image.ScreenSource
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.smbFail
import kotlinx.coroutines.CompletableDeferred
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
 * The viewer offline (Hoard 2.2.2), as the Gallery's backend answers it: the fast step is a phone copy (a thumbnail,
 * low resolution), the final step needs the server. Offline the thumbnail stays with a subtle hint; online a spinner
 * shows over it until the screen image arrives; a shot with no copy at all gets the offline placeholder, never the
 * broken-photo error.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class OfflineViewerTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: HoardDatabase
    private val store = ViewModelStore()

    /** The Gallery's server shots: [noCopy] shots have nothing on the phone; the final step waits on [network]. */
    private class ServerLike(private val fake: FakeCullBackend, private val noCopy: Set<String>, val network: CompletableDeferred<Boolean>) : CullBackend by fake {
        val finals = ArrayList<String>()

        override suspend fun thumbnail(shotId: String, maxSize: Int) =
            if (shotId in noCopy) smbFail(ErrorCode.UNREACHABLE, "Offline") else fake.thumbnail(shotId, maxSize)

        override suspend fun screen(shotId: String, longSide: Int, fast: Boolean, background: Boolean): ScreenFile {
            if (fast) {
                if (shotId in noCopy) smbFail(ErrorCode.UNREACHABLE, "Offline")
                return ScreenFile(fake.thumbnail(shotId, 320), 240, 160, ScreenSource.RENDERED, final = false, lowRes = true)
            }
            finals += shotId
            if (!network.await()) smbFail(ErrorCode.UNREACHABLE, "Offline")
            return fake.screen(shotId, longSide, fast = false, background = background)
        }
    }

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

    private fun settle() {
        repeat(40) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun open(backend: CullBackend, cell: String) {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CullViewModel(CullKey("m1", "media", "shoot"), backend, db.cull(), MutableStateFlow(CullSettings()), compute = Dispatchers.Unconfined) as T
        }
        val vm = ViewModelProvider(store, factory)[CullViewModel::class.java]
        settle()
        compose.setContent { FixedNowTheme(darkTheme = true) { Samples2b.Toasts { CullFolderScreen(Samples2b.container, vm, Samples2b.location, "shoot", {}, {}, {}) } } }
        settle()
        compose.onNodeWithTag("cell-$cell").performClick()
        settle()
    }

    private val failedText get() = RuntimeEnvironment.getApplication().getString(dev.hfps.hoard.app.R.string.t_cull_viewer_image_failed)

    @Test
    fun offlineTheCachedThumbnailShowsWithAHint() {
        val backend = ServerLike(FakeCullBackend(tmp.newFolder()), emptySet(), CompletableDeferred(false))
        open(backend, "dsc00012")
        compose.onNodeWithTag("viewer-offline-hint").assertIsDisplayed()
        compose.onNodeWithText(failedText).assertDoesNotExist()
        assertThat(backend.finals).contains("dsc00012")
        compose.onRoot().captureRoboImage("src/test/screenshots/gallery_viewer_offline_phone.png")
    }

    @Test
    fun onlineASpinnerShowsOverTheThumbnailUntilTheScreenImageArrives() {
        val network = CompletableDeferred<Boolean>()
        val backend = ServerLike(FakeCullBackend(tmp.newFolder()), emptySet(), network)
        open(backend, "dsc00012")
        compose.onNodeWithTag("viewer-upgrading").assertIsDisplayed()
        compose.onNodeWithTag("viewer-offline-hint").assertDoesNotExist()
        network.complete(true)
        settle()
        compose.onNodeWithTag("viewer-upgrading").assertDoesNotExist()
        compose.onNodeWithTag("viewer-offline-hint").assertDoesNotExist()
    }

    @Test
    fun aShotWithNoCopyOnThePhoneGetsTheOfflinePlaceholder() {
        val backend = ServerLike(FakeCullBackend(tmp.newFolder()), setOf("dsc00012"), CompletableDeferred(false))
        open(backend, "dsc00012")
        compose.onNodeWithTag("viewer-offline-placeholder").assertIsDisplayed()
        compose.onNodeWithText(failedText).assertDoesNotExist()
        compose.onRoot().captureRoboImage("src/test/screenshots/gallery_viewer_offline_none_phone.png")
    }
}
