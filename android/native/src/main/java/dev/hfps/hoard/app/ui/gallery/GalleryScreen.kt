package dev.hfps.hoard.app.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme

/** Tab 1. Device and hoard-server items arrive in phase 3 (ADR 0019); until then an empty state says so. */
@Composable
fun GalleryScreen(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    HfpsScaffold(
        modifier = modifier,
        topBar = {
            HfpsTopBar(title = stringResource(R.string.gallery_title)) {
                HfpsIconButton(PhosphorRegular.Gear, stringResource(R.string.action_settings), onOpenSettings)
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(PhosphorRegular.Images, null, Modifier.size(40.dp), tint = HfpsTheme.colors.mutedForeground)
            Text(
                text = stringResource(R.string.gallery_empty_title),
                style = HfpsTheme.typography.title,
                color = HfpsTheme.colors.cardForeground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.gallery_empty_text),
                style = HfpsTheme.typography.body,
                color = HfpsTheme.colors.mutedForeground,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}
