package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/**
 * A single choice shown as a drop-down list (a "select"): an optional [label] above a field with the selected
 * option's text and a caret; tapping it opens a menu of [options], and choosing one calls [onSelect] at once and
 * closes the menu. The selected option is checked in the menu.
 */
@Composable
fun <T> HfpsSelect(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val c = HfpsTheme.colors
    var open by remember { mutableStateOf(false) }
    val value = optionLabel(selected)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, style = HfpsTheme.typography.uiMedium, color = c.cardForeground)
        Box {
            val shape = RoundedCornerShape(10.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 48.dp)
                    .clip(shape)
                    .border(1.dp, c.input, shape)
                    .clickable(enabled = enabled, role = Role.DropdownList) { open = true }
                    .semantics {
                        if (label != null) contentDescription = label
                        stateDescription = value
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value,
                    style = HfpsTheme.typography.body,
                    color = if (enabled) c.cardForeground else c.mutedForeground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(PhosphorRegular.CaretDown, null, Modifier.size(18.dp), tint = c.mutedForeground)
            }
            DropdownMenu(open, { open = false }, containerColor = c.card) {
                options.forEach { option ->
                    val on = option == selected
                    DropdownMenuItem(
                        text = { Text(optionLabel(option), style = HfpsTheme.typography.body) },
                        onClick = {
                            open = false
                            if (!on) onSelect(option)
                        },
                        trailingIcon = if (on) ({ Icon(PhosphorRegular.Check, null, Modifier.size(18.dp)) }) else ({ Spacer(Modifier.width(18.dp)) }),
                        colors = MenuDefaults.itemColors(textColor = c.cardForeground, trailingIconColor = c.primary),
                    )
                }
            }
        }
    }
}
