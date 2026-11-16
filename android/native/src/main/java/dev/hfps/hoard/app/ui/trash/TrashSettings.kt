package dev.hfps.hoard.app.ui.trash

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.launch

/** Settings › Trash (ADR 0023), bound to the app's settings. */
@Composable
fun TrashSettingsSection(container: AppContainer, onOpenTrash: () -> Unit) {
    val days by container.appSettings.trashAutoEmptyDays.collectAsState(initial = AppSettings.TRASH_AUTO_EMPTY_DEFAULT)
    val scope = rememberCoroutineScope()
    TrashSettingsContent(days, onDays = {
        scope.launch {
            container.appSettings.setTrashAutoEmptyDays(it)
            dev.hfps.hoard.app.trash.TrashCleanupWorker.now(container.appContext)
        }
    }, onOpenTrash = onOpenTrash)
}

/**
 * The section itself: "Empty the trash automatically" (7 / 30 / 90 days or never; 30 by default), "Open the trash",
 * and Media management, which comes with the phone's trash (A3).
 */
@Composable
fun TrashSettingsContent(days: Int, onDays: (Int) -> Unit, onOpenTrash: () -> Unit) {
    Eyebrow(stringResource(R.string.trash_title), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
    HfpsListRow(title = stringResource(R.string.trash_settings_open), icon = PhosphorRegular.Trash, onClick = onOpenTrash, modifier = Modifier.testTag("settings-open-trash"))
    HfpsRowDivider(Modifier.padding(start = 54.dp))
    Text(
        stringResource(R.string.trash_settings_auto),
        style = HfpsTheme.typography.body,
        color = HfpsTheme.colors.foreground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    Text(
        stringResource(R.string.trash_settings_auto_hint),
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Column(Modifier.selectableGroup().padding(horizontal = 16.dp).testTag("settings-trash-days")) {
        for (option in AppSettings.TRASH_AUTO_EMPTY_DAYS) {
            val label = if (option == 0) stringResource(R.string.trash_settings_never) else stringResource(R.string.trash_settings_days, option)
            HfpsChoiceRow(label, days == option, { onDays(option) })
        }
    }
    Text(
        stringResource(R.string.trash_settings_media),
        style = HfpsTheme.typography.body,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
    Text(
        stringResource(R.string.trash_settings_media_hint),
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    HfpsRowDivider(Modifier.padding(top = 8.dp))
}
