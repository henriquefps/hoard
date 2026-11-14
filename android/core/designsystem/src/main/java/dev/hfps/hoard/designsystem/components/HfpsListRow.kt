package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/**
 * A settings-like list row inside the card: at least 56dp, [title] (15sp, up to 2 lines) with an optional muted
 * [subtitle] (13sp), an optional leading [icon] (tinted [iconTint]: `warning` for an item that needs attention)
 * and a [trailing] slot (value, switch, caret). Whole row tappable
 * when [onClick] is set. Separate rows with [HfpsRowDivider].
 */
@Composable
fun HfpsListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = HfpsTheme.colors.mutedForeground,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val rowModifier = if (onClick != null) modifier.clickable(role = Role.Button, onClick = onClick) else modifier
    Row(
        modifier = rowModifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = iconTint,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                color = HfpsTheme.colors.cardForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = HfpsTheme.typography.mono.copy(fontFamily = HfpsTheme.typography.ui.fontFamily),
                    color = HfpsTheme.colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) trailing()
    }
}

/** Hairline between two rows, inset to the row text. */
@Composable
fun HfpsRowDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier, thickness = 1.dp, color = HfpsTheme.colors.hairline)
}
