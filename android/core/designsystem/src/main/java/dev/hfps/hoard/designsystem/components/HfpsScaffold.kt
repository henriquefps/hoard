package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlin.math.roundToInt

/**
 * The hfps shell: the fixed [topBar] (56dp on `background`, below the status bar and cutout) and ONE card that
 * fills the rest with an 8dp margin at the sides and bottom (plus the navigation bar inset), rounded 16dp, hairline
 * border. Content scrolls inside the card; the screen never does. Edge-to-edge: the caller enables it
 * (`enableEdgeToEdge()`) and this scaffold consumes the system-bar and cutout insets.
 *
 * With a [header] state the top bar collapses while the card's content scrolls down and comes back as soon as it
 * scrolls up (quick return, Hoard 1.9.1's compact-header rule: the content gets the space).
 */
@Composable
fun HfpsScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    header: CollapsingHeaderState? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(HfpsTheme.colors.background)
            .let { if (header != null) it.nestedScroll(header.connection) else it }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            if (header == null) {
                topBar()
            } else {
                Box(
                    Modifier
                        .clipToBounds()
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            header.limit = placeable.height.toFloat()
                            val offset = header.offset.coerceIn(-header.limit, 0f).roundToInt()
                            layout(placeable.width, placeable.height + offset) { placeable.place(0, offset) }
                        },
                ) { topBar() }
            }
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            shape = HfpsTheme.shapes.card,
            color = HfpsTheme.colors.card,
            contentColor = HfpsTheme.colors.cardForeground,
            border = BorderStroke(1.dp, HfpsTheme.colors.hairline),
        ) {
            Box(content = content)
        }
    }
}

/** How far the collapsing top bar of an [HfpsScaffold] is hidden: [offset] from 0 (shown) to -[limit] (hidden). */
@Stable
class CollapsingHeaderState {
    var offset by mutableFloatStateOf(0f)
        internal set
    var limit by mutableFloatStateOf(0f)
        internal set

    /** Takes the scroll first: scrolling down hides the bar before the list moves, scrolling up shows it again. */
    internal val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (available.y == 0f || limit == 0f) return Offset.Zero
            val before = offset
            offset = (offset + available.y).coerceIn(-limit, 0f)
            return Offset(0f, offset - before)
        }
    }
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }
