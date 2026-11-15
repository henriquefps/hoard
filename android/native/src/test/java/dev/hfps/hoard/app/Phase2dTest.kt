package dev.hfps.hoard.app

import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.files.InlineSpan
import dev.hfps.hoard.app.files.LinkTarget
import dev.hfps.hoard.app.files.inlineSpans
import dev.hfps.hoard.app.files.parseMarkdown
import dev.hfps.hoard.app.files.resolveLink
import dev.hfps.hoard.app.files.stripInline
import dev.hfps.hoard.app.ui.cull.betterOfPair
import dev.hfps.hoard.app.ui.shell.usesTransfersSheet
import dev.hfps.hoard.app.ui.transfers.TransfersSheet
import dev.hfps.hoard.app.ui.viewer.EditLayout
import dev.hfps.hoard.app.ui.viewer.ImageSource
import dev.hfps.hoard.app.ui.viewer.MarkdownHost
import dev.hfps.hoard.app.ui.viewer.MarkdownView
import dev.hfps.hoard.app.ui.viewer.editLayoutShown
import dev.hfps.hoard.app.ui.viewer.imageSource
import dev.hfps.hoard.app.ui.viewer.splitFits
import dev.hfps.hoard.data.TransferEntity
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.transfer.TransferStates
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Phase 2d (ADR 0019): the "Partly" rows closed (Markdown images and "Copy code", the split editor, the transfers
 * sheet, compare's focus score). The side-by-side build's import bridge, import offer
 * and Passwords step went with Hoard 1 in 2.0.0. Goldens under `src/test/screenshots/`, copied to docs/testing/native-2d/.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class Phase2dTest {
    @get:Rule val compose = createComposeRule()

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun shoot(name: String, qualifiers: String, dark: Boolean, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("+$qualifiers")
        compose.setContent { FixedNowTheme(darkTheme = dark) { content() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private val PHONE = ShellScreenshotTest.PHONE
    private val TABLET = ShellScreenshotTest.TABLET

    // --- Markdown: images, links, code ---------------------------------------------------------------------

    @Test fun imagesAndLinkedBadgesAreInlineSpans() {
        assertThat(inlineSpans("![A cat](img/cat.png \"title\")")).containsExactly(InlineSpan("A cat", image = "img/cat.png"))
        val badge = inlineSpans("[![CI](https://x/badge.png)](https://x/actions) and [docs](docs/a.md)")
        assertThat(badge.first()).isEqualTo(InlineSpan("CI", image = "https://x/badge.png", link = "https://x/actions"))
        assertThat(badge.last()).isEqualTo(InlineSpan("docs", link = "docs/a.md"))
        assertThat(stripInline("see ![chart](c.png) here")).isEqualTo("see chart here")
    }

    @Test fun imagesResolveByHoard1Rules() {
        assertThat(imageSource("notes/today.md", "img/a.jpg")).isEqualTo(ImageSource.Share("notes/img/a.jpg"))
        assertThat(imageSource("notes/today.md", "../b.PNG")).isEqualTo(ImageSource.Share("b.PNG"))
        assertThat(imageSource("notes/today.md", "/root.webp")).isEqualTo(ImageSource.Share("root.webp"))
        assertThat(imageSource("notes/today.md", "https://example.com/x.png")).isEqualTo(ImageSource.Web("https://example.com/x.png"))
        assertThat(imageSource("a.md", "data:image/png;base64,AAAA")).isEqualTo(ImageSource.Data("data:image/png;base64,AAAA"))
        // No thumbnail for these types, above the root, other schemes, backslashes: not shown.
        assertThat(imageSource("a.md", "diagram.svg")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "../up.png")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "file:///etc/x.png")).isEqualTo(ImageSource.Blocked)
        assertThat(imageSource("a.md", "mailto:me@x")).isEqualTo(ImageSource.Blocked)
        assertThat(resolveLink("a.md", "dir\\x.png")).isEqualTo(LinkTarget.Blocked)
    }

    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3F7FBF.toInt()) }.asImageBitmap()

    private val doc = """
        # Trip notes

        ![Lisbon](photos/lisbon.jpg)

        [![build](https://example.com/badge.png)](https://example.com) ![missing](gone.png)

        ```kotlin
        // pick the sharpest
        fun best(shots: List<Shot>) = shots.maxBy { it.score }
        ```
    """.trimIndent()

    private val markdown: @Composable () -> Unit = {
        MarkdownView(
            remember { parseMarkdown(doc) },
            14,
            false,
            MarkdownHost(loadImage = { src ->
                when (src) {
                    "photos/lisbon.jpg" -> bitmap(600, 300)
                    "https://example.com/badge.png" -> bitmap(90, 20)
                    else -> null
                }
            }),
            rememberLazyListState(),
        )
    }

    @Test fun markdownShowsImagesAndBrokenOnesWithTheirAlt() {
        compose.setContent { FixedNowTheme(darkTheme = false) { markdown() } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("missing").assertIsDisplayed() }.isSuccess }
        compose.onNodeWithTag("md-image-broken").assertIsDisplayed()
        assertThat(compose.onAllNodes(androidx.compose.ui.test.hasTestTag("md-image")).fetchSemanticsNodes()).hasSize(2)
    }

    @Test fun copyCodePutsTheBlockOnTheClipboard() {
        compose.setContent { FixedNowTheme(darkTheme = false) { markdown() } }
        compose.onNodeWithTag("md-copy-code").performClick()
        val clipboard = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(ClipboardManager::class.java)
        assertThat(clipboard.primaryClip!!.getItemAt(0).text.toString()).isEqualTo("// pick the sharpest\nfun best(shots: List<Shot>) = shots.maxBy { it.score }")
    }

    @Test fun markdownPhoneLight() = shoot("markdown_images_code_phone_light", PHONE, dark = false) { markdown() }

    @Test fun markdownPhoneDark() = shoot("markdown_images_code_phone_dark", PHONE, dark = true) { markdown() }

    // --- Split editor, transfers sheet, compare -----------------------------------------------------------------

    @Test fun splitOnlyOnWideWindows() {
        assertThat(splitFits(839)).isFalse()
        assertThat(splitFits(840)).isTrue()
        assertThat(editLayoutShown(null, wide = true)).isEqualTo(EditLayout.Split)
        assertThat(editLayoutShown(null, wide = false)).isEqualTo(EditLayout.Editor)
        assertThat(editLayoutShown(EditLayout.Split, wide = false)).isEqualTo(EditLayout.Editor)
        assertThat(editLayoutShown(EditLayout.Preview, wide = false)).isEqualTo(EditLayout.Preview)
        assertThat(editLayoutShown(EditLayout.Editor, wide = true)).isEqualTo(EditLayout.Editor)
    }

    @Test fun transfersSheetOnPhonesScreenOnTablets() {
        assertThat(usesTransfersSheet(390.dp)).isTrue()
        assertThat(usesTransfersSheet(599.dp)).isTrue()
        assertThat(usesTransfersSheet(600.dp)).isFalse()
    }

    @Test fun compareCrownsTheRankingElseTheSharperFocus() {
        assertThat(betterOfPair("a", "b", listOf("b" to 0.9, "a" to 0.4), emptyMap())).isEqualTo("b")
        assertThat(betterOfPair("a", "b", listOf("a" to 0.5, "b" to 0.5), emptyMap())).isNull()
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 310.0, "b" to 120.0))).isEqualTo("a")
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 12.0, "b" to 99.0))).isEqualTo("b")
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 50.0))).isNull()
        assertThat(betterOfPair("a", "b", null, mapOf("a" to 50.0, "b" to 50.0))).isNull()
    }

    private fun seedTransfers() = runBlocking {
        val dao = Samples2b.container.database.transfers()
        if (dao.all().isNotEmpty()) return@runBlocking
        fun row(id: String, state: String, name: String, done: Long, size: Long, items: Int, itemsDone: Int) = TransferEntity(
            id, "copy", "device", null, "", "[\"Download/$name\"]", "device", null, "", "DCIM/Backup", null, name, "ask", size, done,
            items, itemsDone, 0, 0, state, null, null, "2026-11-14T10:0$items:00Z",
            if (state == TransferStates.COMPLETED) "2026-11-14T10:30:00Z" else null,
        )
        dao.insert(row("s1", TransferStates.RUNNING, "clip.mp4", 40_000_000, 104_432_771, 1, 0))
        dao.insert(row("s2", TransferStates.COMPLETED, "DSC0001.JPG", 6_412_331, 6_412_331, 2, 2))
    }

    @Test fun transfersSheetPhone() {
        seedTransfers()
        RuntimeEnvironment.setQualifiers("+$PHONE")
        var all = 0
        compose.setContent { FixedNowTheme(darkTheme = false) { Samples2b.Toasts { TransfersSheet(onDismiss = {}, onOpenAll = { all++ }, onOpenDestination = {}) } } }
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("clip.mp4").assertIsDisplayed() }.isSuccess }
        captureScreenRoboImage("src/test/screenshots/transfers_sheet_phone_light.png")
        compose.onNodeWithTag("transfers-open-all").performClick()
        assertThat(all).isEqualTo(1)
    }
}
