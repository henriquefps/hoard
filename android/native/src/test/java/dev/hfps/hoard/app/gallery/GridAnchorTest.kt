package dev.hfps.hoard.app.gallery

import com.google.common.truth.Truth.assertThat
import dev.hfps.hoard.app.ui.gallery.GridAnchor
import dev.hfps.hoard.app.ui.gallery.GridAnchors
import dev.hfps.hoard.app.ui.gallery.ScrollerGeometry
import org.junit.Test

/** Hoard 2.2.2: the grid's place by shot (not by index), the return from the viewer, the scroller's hit area. */
class GridAnchorTest {
    private val small = layout(GallerySamples.big(2000), byMonth = false)
    /** The same library after a sync brought 300 newer shots: they go above everything. */
    private val grown = layout(GallerySamples.big(2300), byMonth = false)

    private fun cellRow(layout: GalleryLayout, n: Int) = layout.rows.indexOfFirst { it is GridRow.Cell && it.entry.itemId == "b$n" }

    @Test
    fun anAnchorIsTheFirstVisibleCellAndAHeaderAnchorsToTheNextCell() {
        val row = cellRow(small, 700)
        assertThat(GridAnchors.at(small, row, 37)).isEqualTo(GridAnchor(small.rows[row].id, 37))
        val header = small.rows.indexOfFirst { it is GridRow.Header && small.rows.indexOf(it) > 10 }
        val next = small.rows[header + 1] as GridRow.Cell
        assertThat(GridAnchors.at(small, header, 12)).isEqualTo(GridAnchor(next.entry.key, 0))
        assertThat(GridAnchors.at(small, -1, 0)).isNull()
        assertThat(GridAnchors.at(small, small.rows.size, 0)).isNull()
    }

    @Test
    fun aReloadThatAddsShotsAboveFindsTheSameShotByKey() {
        val row = cellRow(small, 700)
        val anchor = GridAnchors.at(small, row, 20)!!
        // The grid kept its index (the lazy grid's key lookup only searches near the old index): wrong shot.
        val restored = GridAnchors.restore(grown, anchor, row, 20)
        assertThat(restored).isEqualTo(cellRow(grown, 700) to 20)
        assertThat(cellRow(grown, 700)).isGreaterThan(row)
        // Already there: nothing to do.
        assertThat(GridAnchors.restore(grown, anchor, cellRow(grown, 700), 20)).isNull()
        // The shot is gone (filtered out, deleted): the grid stays where it is.
        assertThat(GridAnchors.restore(small, GridAnchor("s:nope", 0), row, 0)).isNull()
        assertThat(GridAnchors.restore(small, null, row, 0)).isNull()
    }

    @Test
    fun backFromTheViewerTheShotComesIntoViewOnlyWhenOffScreen() {
        val row = cellRow(small, 300)
        val key = small.rows[row].id
        assertThat(GridAnchors.reveal(small, key, fullyVisible = (row - 4..row + 4).toSet(), columns = 4, lines = 6)).isNull()
        // Swiped far in the viewer: a few lines above it, so it lands mid-screen.
        assertThat(GridAnchors.reveal(small, key, fullyVisible = (0..20).toSet(), columns = 4, lines = 6)).isEqualTo(row - 12)
        assertThat(GridAnchors.reveal(small, "s:nope", fullyVisible = emptySet(), columns = 4, lines = 6)).isNull()
        val first = small.rows.indexOfFirst { it is GridRow.Cell }
        assertThat(GridAnchors.reveal(small, small.rows[first].id, fullyVisible = setOf(500), columns = 4, lines = 6)).isEqualTo(0)
    }

    @Test
    fun onlyATouchOnTheShownThumbInsideTheEdgeStripGrabsTheScroller() {
        val w = 1080f
        val h = 2000f
        val strip = 63f // 24 dp at 2.625x
        val thumb = 115f
        val pad = 31f
        val slop = 21f
        val top = ScrollerGeometry.thumbTop(0.5f, h, thumb, pad)
        fun on(x: Float, y: Float, visible: Boolean = true) = ScrollerGeometry.onThumb(x, y, w, h, 0.5f, visible, strip, thumb, pad, slop)
        assertThat(on(w - 10, top + 10)).isTrue()
        assertThat(on(w - strip + 1, top + thumb + slop - 1)).isTrue()
        // Hidden thumb, outside the strip, above or below the thumb: the grid scrolls.
        assertThat(on(w - 10, top + 10, visible = false)).isFalse()
        assertThat(on(w - strip - 1, top + 10)).isFalse()
        assertThat(on(w / 2, top + 10)).isFalse()
        assertThat(on(w - 10, top - slop - 1)).isFalse()
        assertThat(on(w - 10, top + thumb + slop + 1)).isFalse()
        assertThat(on(w - 10, 10f)).isFalse()
        // The travel maps back and forth.
        assertThat(ScrollerGeometry.fractionAt(top, h, thumb, pad)).isWithin(1e-4f).of(0.5f)
        assertThat(ScrollerGeometry.fractionAt(-50f, h, thumb, pad)).isEqualTo(0f)
        assertThat(ScrollerGeometry.fractionAt(h + 50, h, thumb, pad)).isEqualTo(1f)
        assertThat(ScrollerGeometry.rowAt(1f, 2500)).isEqualTo(2499)
    }
}
