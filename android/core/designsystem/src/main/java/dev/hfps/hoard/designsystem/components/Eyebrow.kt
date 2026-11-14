package dev.hfps.hoard.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** Small uppercase section label ("STORAGE", "ACCOUNT"): the only uppercase text in the app. Never for controls. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = HfpsTheme.colors.mutedForeground) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = HfpsTheme.typography.eyebrow,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
