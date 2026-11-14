package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/** The ellipsis character used by [FileNames.ellipsize]. */
private const val ELLIPSIS = "…"

/** File-name shortening that keeps what identifies a file: the extension and both ends of the stem. */
object FileNames {
    /** Share of the kept stem characters taken from its start (the rest from its end, next to the extension). */
    private const val HEAD_SHARE = 0.5f

    /**
     * [name] if it [fits], else the longest `head…tail.ext` that fits (`IMG_20…0042.jpg`): the extension is always
     * kept, the stem loses characters from its middle. Falls back to `…ext` (and finally `…`) when nothing else fits.
     */
    @Suppress("ReturnCount")
    fun ellipsize(name: String, fits: (String) -> Boolean): String {
        if (fits(name)) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= MAX_EXTENSION) name.substring(dot) else ""
        val stem = name.substring(0, name.length - ext.length)
        for (keep in stem.length - 1 downTo 1) {
            val head = (keep * HEAD_SHARE + HEAD_ROUNDING).toInt().coerceIn(1, keep)
            val candidate = stem.take(head) + ELLIPSIS + stem.takeLast(keep - head) + ext
            if (fits(candidate)) return candidate
        }
        return if (fits(ELLIPSIS + ext)) ELLIPSIS + ext else ELLIPSIS
    }

    private const val MAX_EXTENSION = 6
    private const val HEAD_ROUNDING = 0.5f
}

/**
 * One line of a file name in [style], shortened in the middle with [FileNames.ellipsize] to the width it gets, so
 * the extension and the end of the number stay visible. TalkBack reads the full name.
 */
@Composable
fun FileNameText(name: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier.semantics(mergeDescendants = true) { contentDescription = name }) {
        val maxPx = constraints.maxWidth
        val shown = remember(name, style, maxPx, density) {
            if (maxPx == Constraints.Infinity) {
                name
            } else {
                FileNames.ellipsize(name) {
                    measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <=
                        maxPx
                }
            }
        }
        Text(text = shown, style = style, color = color, maxLines = 1, softWrap = false)
    }
}
