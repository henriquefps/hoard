package dev.hfps.hoard.app.gallery

import android.os.Looper
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.FixedNowTheme
import dev.hfps.hoard.app.ShellScreenshotTest
import dev.hfps.hoard.app.ui.gallery.FastScrollerState
import dev.hfps.hoard.app.ui.gallery.GalleryContent
import dev.hfps.hoard.app.ui.gallery.GalleryUi
import dev.hfps.hoard.app.ui.gallery.GridAnchor
import dev.hfps.hoard.app.ui.gallery.KeepGridPosition
import dev.hfps.hoard.app.ui.shell.Tab
import dev.hfps.hoard.app.ui.shell.TabbedLayout
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Hoard 2.2.2 under Robolectric: the grid keeps its place (the shell's tab bar hiding under the viewer, reloads that add
 * shots above, a new process), the shot the viewer closed on comes into view, and the fast scroller only takes touches
 * that start on its thumb. Goldens of the scroller shown, hidden and dragged in `src/test/screenshots/`.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class GalleryStateTest {
    @get:Rule val compose = createComposeRule()

    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        compose.waitForIdle()
    }

    private fun ui(entries: List<GalleryEntry>, columns: Int = 4) = GalleryUi(layout(entries, byMonth = columns >= 7), columns = columns, total = entries.size)

    private fun firstKey(state: LazyGridState, layout: GalleryLayout): String? =
        layout.rows.drop(state.firstVisibleItemIndex).firstOrNull { it is GridRow.Cell }?.id

    private fun fullyVisibleKeys(state: LazyGridState, layout: GalleryLayout): List<String> {
        val info = state.layoutInfo
        return info.visibleItemsInfo.filter { it.offset.y >= info.viewportStartOffset && it.offset.y + it.size.height <= info.viewportEndOffset }.map { layout.rows[it.index].id }
    }

    @Test
    fun hidingTheTabBarKeepsTheScreensComposition() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        var selected by mutableStateOf<Tab?>(Tab.Gallery)
        val made = ArrayList<Any>()
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                TabbedLayout(selected = selected, onSelect = {}) {
                    val token = remember { Any().also { made += it } }
                    Text(token.hashCode().toString(), Modifier.testTag("content"))
                }
            }
        }
        settle()
        // The Gallery's viewer hides the bar, then closes: the content (the NavHost) is the same composition.
        selected = null
        settle()
        selected = Tab.Gallery
        settle()
        assertThat(made).hasSize(1)
    }

    @Test
    fun theGridKeepsItsShotAcrossTheViewerAReloadAndANewProcess() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val grid = LazyGridState()
        var anchor: GridAnchor? = null
        var layout by mutableStateOf(ui(GallerySamples.big(2000)))
        var viewerOpen by mutableStateOf(false)
        var lastViewed by mutableStateOf<String?>(null)
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                TabbedLayout(selected = if (viewerOpen) null else Tab.Gallery, onSelect = {}) {
                    GalleryContent(layout, FakeThumbs, grid = grid)
                    KeepGridPosition(grid, layout.layout, anchor = { anchor }, setAnchor = { anchor = it }, lastViewed = lastViewed, onRevealed = { lastViewed = null })
                }
            }
        }
        settle()
        val deep = layout.layout!!.rows.indexOfFirst { it is GridRow.Cell && it.entry.itemId == "b700" }
        runBlocking { grid.scrollToItem(deep) }
        settle()
        val key = firstKey(grid, layout.layout!!)!!
        assertThat(anchor?.key).isEqualTo(key)

        // The viewer opens over the grid and closes on the shot it opened.
        viewerOpen = true
        settle()
        viewerOpen = false
        lastViewed = key
        settle()
        assertThat(firstKey(grid, layout.layout!!)).isEqualTo(key)

        // A sync brings 300 newer shots: they go above, the grid stays on the same shot.
        layout = ui(GallerySamples.big(2300))
        settle()
        assertThat(firstKey(grid, layout.layout!!)).isEqualTo(key)
        assertThat(grid.firstVisibleItemIndex).isGreaterThan(deep)
    }

    @Test
    fun aNewProcessGoesBackToTheSavedShot() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val layout = ui(GallerySamples.big(2000))
        val target = layout.layout!!.rows.first { it is GridRow.Cell && it.entry.itemId == "b900" }.id
        // What the ViewModel's SavedStateHandle gives back after process death; the grid state starts at 0.
        var anchor: GridAnchor? = GridAnchor(target, 0)
        val grid = LazyGridState()
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                GalleryContent(layout, FakeThumbs, grid = grid)
                KeepGridPosition(grid, layout.layout, anchor = { anchor }, setAnchor = { anchor = it }, lastViewed = null, onRevealed = {})
            }
        }
        settle()
        // The grid scrolls by lines: the saved shot is on the first one.
        val row = layout.layout!!.rowOf(target)
        val first = grid.layoutInfo.visibleItemsInfo.filter { it.row >= 0 }.minOf { it.row }
        assertThat(grid.layoutInfo.visibleItemsInfo.single { it.index == row }.row).isEqualTo(first)
    }

    @Test
    fun backFromTheViewerTheShotSwipedToIsOnScreen() {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val layout = ui(GallerySamples.big(2000))
        val grid = LazyGridState()
        var anchor: GridAnchor? = null
        var lastViewed by mutableStateOf<String?>(null)
        compose.setContent {
            FixedNowTheme(darkTheme = false) {
                GalleryContent(layout, FakeThumbs, grid = grid)
                KeepGridPosition(grid, layout.layout, anchor = { anchor }, setAnchor = { anchor = it }, lastViewed = lastViewed, onRevealed = { lastViewed = null })
            }
        }
        settle()
        val start = layout.layout!!.rows.indexOfFirst { it is GridRow.Cell && it.entry.itemId == "b1500" }
        runBlocking { grid.scrollToItem(start) }
        settle()
        // Opened b1500, swiped 60 shots on in the viewer (older: further down the grid).
        val swiped = layout.layout!!.rows.first { it is GridRow.Cell && it.entry.itemId == "b1440" }.id
        assertThat(fullyVisibleKeys(grid, layout.layout!!)).doesNotContain(swiped)
        lastViewed = swiped
        settle()
        assertThat(fullyVisibleKeys(grid, layout.layout!!)).contains(swiped)
        assertThat(lastViewed).isNull()
    }

    // --- The fast scroller ----------------------------------------------------------------------------------------

    private fun scroller(state: FastScrollerState): Pair<LazyGridState, GalleryUi> {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val grid = LazyGridState()
        val ui = ui(GallerySamples.big(2000))
        compose.setContent { FixedNowTheme(darkTheme = false) { GalleryContent(ui, FakeThumbs, grid = grid, scroller = state) } }
        settle()
        return grid to ui
    }

    @Test
    fun aDragNearTheEdgeButNotOnTheThumbScrollsTheGrid() {
        val state = FastScrollerState(visible = true, hideAfterMs = 0)
        val (grid, ui) = scroller(state)
        val rows = ui.layout!!.rows.size
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            // In the 24 dp strip, half way down: the thumb is at the top.
            val x = width - 6.dp.toPx()
            down(Offset(x, height * 0.6f))
            repeat(10) { moveBy(Offset(0f, -40f)) }
            up()
        }
        settle()
        // A finger's scroll (a few rows), not a jump to 60 % of the library.
        assertThat(grid.firstVisibleItemIndex).isGreaterThan(0)
        assertThat(grid.firstVisibleItemIndex).isLessThan(rows / 10)
        assertThat(state.dragging).isFalse()
    }

    @Test
    fun aDragInTheMiddleScrollsTheGridAndShowsTheThumb() {
        val state = FastScrollerState(visible = false)
        val (grid, ui) = scroller(state)
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -40f)) }
            up()
        }
        settle()
        assertThat(grid.firstVisibleItemIndex).isIn(1 until ui.layout!!.rows.size / 10)
    }

    @Test
    fun aHiddenThumbCannotBeGrabbed() {
        val state = FastScrollerState(visible = false)
        val (grid, ui) = scroller(state)
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            // Where the thumb would be (top of the track), then down by half the screen.
            down(Offset(width - 6.dp.toPx(), 30.dp.toPx()))
            repeat(10) { moveBy(Offset(0f, 40f)) }
            up()
        }
        settle()
        // Pulling down at the top of the list does not move it; it never jumps half way.
        assertThat(grid.firstVisibleItemIndex).isLessThan(ui.layout!!.rows.size / 10)
    }

    @Test
    fun draggingTheThumbScrubsTheLibrary() {
        val state = FastScrollerState(visible = true, hideAfterMs = 0)
        val (grid, ui) = scroller(state)
        val rows = ui.layout!!.rows.size
        compose.onNodeWithTag("gallery-grid").performTouchInput {
            // On the thumb (12 dp padding + half its 44 dp), then half the travel down.
            val start = Offset(width - 6.dp.toPx(), 34.dp.toPx())
            down(start)
            val travel = height - 24.dp.toPx() - 44.dp.toPx()
            repeat(10) { moveBy(Offset(0f, travel / 20)) }
            up()
        }
        settle()
        assertThat(grid.firstVisibleItemIndex.toFloat() / rows).isWithin(0.08f).of(0.5f)
    }

    // --- Goldens --------------------------------------------------------------------------------------------------

    private fun shoot(name: String, state: FastScrollerState, scrollTo: Int = 600) {
        RuntimeEnvironment.setQualifiers("+${ShellScreenshotTest.PHONE}")
        val grid = LazyGridState(scrollTo)
        val ui = ui(GallerySamples.big(2000))
        compose.setContent { FixedNowTheme(darkTheme = false) { TabbedLayout(selected = Tab.Gallery, onSelect = {}) { GalleryContent(ui, FakeThumbs, grid = grid, scroller = state) } } }
        settle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun scrollerShown() = shoot("gallery_scroller_shown_phone_light", FastScrollerState(visible = true, hideAfterMs = 0))
    @Test fun scrollerHidden() = shoot("gallery_scroller_hidden_phone_light", FastScrollerState(visible = false))
    @Test fun scrollerDragging() = shoot("gallery_scroller_dragging_phone_light", FastScrollerState(visible = true, hideAfterMs = 0).apply {
        dragging = true
        dragFraction = 0.4f
    })
}
