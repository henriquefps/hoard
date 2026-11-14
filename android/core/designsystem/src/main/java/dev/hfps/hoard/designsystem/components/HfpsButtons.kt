package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.theme.HfpsTheme

private val ButtonHeight = 44.dp
private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Filled accent button: the one primary action of a screen or sheet ("Save"). */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = HfpsTheme.colors
    HfpsButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.primary,
            contentColor = c.primaryForeground,
            disabledContainerColor = c.muted,
            disabledContentColor = c.mutedForeground,
        ),
        border = null,
    )
}

/** Neutral filled button with a hairline border. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = HfpsTheme.colors
    HfpsButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = c.secondary,
            contentColor = c.secondaryForeground,
            disabledContainerColor = c.muted,
            disabledContentColor = c.mutedForeground,
        ),
        border = BorderStroke(1.dp, c.hairline),
    )
}

/** Text-only button for tertiary actions ("Cancel", "Reset"). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val c = HfpsTheme.colors
    HfpsButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        icon = icon,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = c.foreground,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = c.mutedForeground,
        ),
        border = null,
    )
}

@Composable
private fun HfpsButton(
    text: String,
    onClick: () -> Unit,
    colors: ButtonColors,
    border: BorderStroke?,
    icon: ImageVector?,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
        enabled = enabled,
        shape = HfpsTheme.shapes.control,
        colors = colors,
        border = border,
        elevation = null,
        contentPadding = ButtonPadding,
    ) {
        ButtonContent(text = text, icon = icon)
    }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
    }
    Text(text = text, style = HfpsTheme.typography.uiMedium)
}
