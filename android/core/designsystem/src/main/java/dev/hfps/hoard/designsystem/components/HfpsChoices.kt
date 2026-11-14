package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/**
 * One option of a single choice (a radio row): [label] with an optional [detail] line, the whole row selectable.
 * Group the rows of one choice in a `Column(Modifier.selectableGroup())`.
 */
@Composable
fun HfpsChoiceRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    OptionRow(
        label = label,
        detail = detail,
        icon = if (selected) PhosphorFill.RadioButton else PhosphorRegular.Circle,
        on = selected,
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    )
}

/** A check box row: [label] with an optional [detail] line, the whole row toggles. */
@Composable
fun HfpsCheckRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    OptionRow(
        label = label,
        detail = detail,
        icon = if (checked) PhosphorFill.CheckSquare else PhosphorRegular.Square,
        on = checked,
        modifier = modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
    )
}

@Composable
private fun OptionRow(label: String, detail: String?, icon: ImageVector, on: Boolean, modifier: Modifier = Modifier) {
    val colors = HfpsTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (on) colors.primary else colors.mutedForeground,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(text = label, style = HfpsTheme.typography.ui, color = colors.cardForeground)
            if (detail != null) {
                Text(text = detail, style = HfpsTheme.typography.ui, color = colors.mutedForeground)
            }
        }
    }
}
