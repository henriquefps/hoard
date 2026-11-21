package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.backup.BackupSettingsRow
import dev.hfps.hoard.app.ui.cull.AssistSettingsFields
import dev.hfps.hoard.app.ui.server.GalleryCacheSection
import dev.hfps.hoard.app.ui.server.PhoneFoldersSection
import dev.hfps.hoard.app.ui.server.ServerSettingsSection
import dev.hfps.hoard.app.ui.trash.TrashSettingsSection
import dev.hfps.hoard.designsystem.components.HfpsRowDivider

/*
 * The Settings screens bound to the app (Hoard 2.9.0, docs/design/settings-2026-11/MAPPING.md says where every
 * setting of the single Settings screen went). Appearance and About are in SettingsScreen.kt, Browsing and transfers
 * in FilesSettingsSection.kt.
 */

/** Settings › Photo server. */
@Composable
fun ServerSettingsScreen(container: AppContainer, onBack: () -> Unit, onPair: () -> Unit, onCompress: () -> Unit) {
    SettingsPageScaffold(stringResource(R.string.server_section), onBack) {
        ServerSettingsSection(container, onPair = onPair, onCompress = onCompress)
    }
}

/** Settings › Backup and phone folders: the phone backup (while paired), phone folders, phone storage access. */
@Composable
fun BackupFoldersSettingsScreen(container: AppContainer, onBack: () -> Unit, onBackup: () -> Unit) {
    val account by container.gallery.account.collectAsState(initial = null)
    SettingsPageScaffold(stringResource(R.string.settings_backup_title), onBack) {
        if (account != null) {
            BackupSettingsRow(container, onBackup)
            HfpsRowDivider(Modifier.padding(start = 54.dp))
        }
        PhoneFoldersSection(container)
        HfpsRowDivider(Modifier.padding(top = 8.dp))
        StorageAccessSection()
    }
}

/** Settings › Cache and storage: the photo server's caches (while paired), Reveal's copies and links. */
@Composable
fun StorageSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    SettingsPageScaffold(stringResource(R.string.settings_storage_title), onBack) {
        GalleryCacheSection(container)
        RevealStorageSection()
    }
}

/** Settings › Trash. */
@Composable
fun TrashSettingsScreen(container: AppContainer, onBack: () -> Unit, onOpenTrash: () -> Unit) {
    SettingsPageScaffold(stringResource(R.string.trash_title), onBack) {
        TrashSettingsSection(container, onOpenTrash = onOpenTrash)
    }
}

/** Settings › Culling: the assist's sensitivities, the burst gap, hidden XMP sidecars. */
@Composable
fun CullingSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    SettingsPageScaffold(stringResource(R.string.t_settings_culling), onBack) {
        CullingSettingsContent { AssistSettingsFields(container) }
    }
}

/** The culling screen's body around [fields] (also used by the screenshot tests). */
@Composable
fun CullingSettingsContent(fields: @Composable () -> Unit) {
    SettingsSection(R.string.t_settings_culling)
    SettingsHint(stringResource(R.string.t_settings_culling_hint), Modifier.padding(bottom = 4.dp))
    Box(Modifier.padding(horizontal = 16.dp).testTag("settings-culling")) { fields() }
}
