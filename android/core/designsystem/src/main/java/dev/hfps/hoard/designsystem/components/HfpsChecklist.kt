@file:Suppress("MatchingDeclarationName")

package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.icons.PhosphorFill
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** One row of a [HfpsChecklist]: a stable [key] (test tag suffix), its [label] and an optional muted [detail]. */
data class ChecklistItem<K>(val key: K, val label: String, val detail: String? = null)

/**
 * A chooser of several items with checkboxes (what to include, what to delete): an eyebrow [title], an "all" row that
 * ticks or clears everything, one row per item (48dp+, the whole row toggles), and Cancel / [confirm] (disabled
 * while nothing is ticked). Stateless: [checked] in, [onCheckedChange] out. Tags: `<tagPrefix>-all`,
 * `<tagPrefix>-<key>`, `<tagPrefix>-confirm`.
 */
@Suppress("LongParameterList")
@Composable
fun <K> HfpsChecklist(
    title: String,
    allLabel: String,
    items: List<ChecklistItem<K>>,
    checked: Set<K>,
    onCheckedChange: (Set<K>) -> Unit,
    confirm: String,
    cancel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "checklist",
) {
    val all = items.all { it.key in checked }
    Column(modifier.navigationBarsPadding()) {
        Eyebrow(title, Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp))
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            CheckRow(allLabel, null, all, "$tagPrefix-all", emphasised = true) {
                onCheckedChange(if (all) emptySet() else items.mapTo(LinkedHashSet()) { it.key })
            }
            HfpsRowDivider()
            for (item in items) {
                CheckRow(item.label, item.detail, item.key in checked, "$tagPrefix-${item.key}") {
                    onCheckedChange(if (item.key in checked) checked - item.key else checked + item.key)
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            GhostButton(cancel, onCancel)
            PrimaryButton(
                text = confirm,
                onClick = onConfirm,
                enabled = checked.isNotEmpty(),
                modifier = Modifier.testTag("$tagPrefix-confirm"),
            )
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    detail: String?,
    checked: Boolean,
    tag: String,
    emphasised: Boolean = false,
    onToggle: () -> Unit,
) {
    val colors = HfpsTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = if (checked) PhosphorFill.CheckSquare else PhosphorRegular.Square,
            contentDescription = null,
            tint = if (checked) colors.primary else colors.mutedForeground,
            modifier = Modifier.size(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = if (emphasised) HfpsTheme.typography.uiMedium else HfpsTheme.typography.ui,
                color = colors.cardForeground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = HfpsTheme.typography.ui,
                    color = colors.mutedForeground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
