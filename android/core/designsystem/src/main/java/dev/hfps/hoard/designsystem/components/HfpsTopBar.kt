package dev.hfps.hoard.designsystem.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.designsystem.R
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** Height of the hfps header (`--header-height`). */
val TopBarHeight: Dp = 56.dp

/**
 * The hfps header: 56dp, transparent so it sits on `background`, no bottom border. Back control (when
 * [onBack] is set) + [title] + [actions]. [titleIsFileName] shortens the title in the middle, keeping the
 * extension. Insets are applied by `HfpsScaffold`.
 */
@Composable
fun HfpsTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = stringResource(R.string.hfps_back),
    titleStyle: TextStyle = HfpsTheme.typography.title,
    titleIsFileName: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HfpsIconButton(
                icon = PhosphorRegular.ArrowLeft,
                contentDescription = backContentDescription,
                onClick = onBack,
            )
        } else {
            Spacer(Modifier.width(12.dp))
        }
        val titleModifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp)
            .semantics { heading() }
        if (titleIsFileName) {
            FileNameText(
                name = title,
                style = titleStyle,
                color = HfpsTheme.colors.foreground,
                modifier = titleModifier,
            )
        } else {
            Text(
                text = title,
                modifier = titleModifier,
                style = titleStyle,
                color = HfpsTheme.colors.foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
