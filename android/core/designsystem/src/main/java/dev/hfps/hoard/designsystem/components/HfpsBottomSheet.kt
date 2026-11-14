package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/**
 * hfps bottom sheet for forms and choosers on phones (Vaul's equivalent): drag handle, 16dp top radius, the card
 * surface. Material's sheet tracks the finger and flings with velocity; dismiss by drag, scrim tap or back.
 *
 * It opens fully expanded, with no half-height stop (`skipPartiallyExpanded`), so **one back always closes it**.
 * With a half-height stop, Material's sheet takes back from the expanded state as "collapse to half" whenever the
 * content is taller than half the window: a sheet whose content grows after it opened (details that load after it opens)
 * gains that stop while expanded, and on a shorter window (a phone at 450 dpi, a larger font) back only collapsed it.
 *
 * On wide windows (tablets, unfolded foldables, >= [DialogMinWidth]) the same content opens as a centred dialog
 * (hfps: drawers on phones, dialogs on tablets): a bottom drawer across a 1280dp screen is far from the hand
 * and stretches the form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HfpsBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val wide = with(density) { LocalWindowInfo.current.containerSize.width.toDp() } >= DialogMinWidth
    if (wide) {
        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(
                shape = HfpsTheme.shapes.sheet,
                color = HfpsTheme.colors.card,
                contentColor = HfpsTheme.colors.cardForeground,
                modifier = modifier
                    .widthIn(max = DialogMaxWidth)
                    .padding(24.dp),
            ) {
                Column(Modifier.padding(top = 16.dp), content = content)
            }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = HfpsTheme.shapes.sheet,
        containerColor = HfpsTheme.colors.card,
        contentColor = HfpsTheme.colors.cardForeground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = HfpsTheme.colors.mutedForeground) },
        content = content,
    )
}

/** From this window width the sheet is a dialog (use the same breakpoint for tablet layouts). */
val DialogMinWidth = 840.dp
private val DialogMaxWidth = 560.dp
