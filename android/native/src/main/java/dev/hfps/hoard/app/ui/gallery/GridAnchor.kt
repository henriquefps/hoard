package dev.hfps.hoard.app.ui.gallery

import dev.hfps.hoard.app.gallery.GalleryLayout
import dev.hfps.hoard.app.gallery.GridRow

/**
 * Where the Gallery grid is, by item and not by index (2.2.2): the key of the first visible cell and how far its row is
 * scrolled. A sync that adds shots above, a decision that changes the filtered list, a new process: the grid goes back
 * to the same shot, wherever its row now is.
 */
data class GridAnchor(val key: String, val offset: Int)

object GridAnchors {
    /** The anchor at the grid's first visible row (a header anchors to the first cell after it, offset 0). */
    fun at(layout: GalleryLayout, firstIndex: Int, offset: Int): GridAnchor? {
        val rows = layout.rows
        if (firstIndex !in rows.indices) return null
        (rows[firstIndex] as? GridRow.Cell)?.let { return GridAnchor(it.entry.key, offset) }
        for (i in firstIndex + 1 until rows.size) (rows[i] as? GridRow.Cell)?.let { return GridAnchor(it.entry.key, 0) }
        return null
    }

    /**
     * The row to scroll to so [anchor] is where it was, or null when the grid already shows it there ([firstIndex],
     * [offset]) or the shot is gone from the list (the grid then stays where it is).
     */
    fun restore(layout: GalleryLayout, anchor: GridAnchor?, firstIndex: Int, offset: Int): Pair<Int, Int>? {
        anchor ?: return null
        val row = layout.rowOf(anchor.key)
        if (row < 0) return null
        val current = at(layout, firstIndex, offset)
        if (current == anchor) return null
        // A header row on top that anchors to this cell is the same place.
        if (current?.key == anchor.key && anchor.offset == 0 && firstIndex <= row) return null
        return row to anchor.offset
    }

    /**
     * Back from the viewer on [key] (the shot opened, or the one swiped to): the row to scroll to so it shows, or null
     * when it is fully on screen already ([fullyVisible] rows). [columns] cells per line and [lines] lines on screen
     * put it in the middle.
     */
    fun reveal(layout: GalleryLayout, key: String, fullyVisible: Set<Int>, columns: Int, lines: Int): Int? {
        val row = layout.rowOf(key)
        if (row < 0 || row in fullyVisible) return null
        return (row - columns * (lines / 2)).coerceAtLeast(0)
    }
}
