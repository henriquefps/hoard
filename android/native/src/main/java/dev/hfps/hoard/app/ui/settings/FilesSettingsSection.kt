package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.app.data.FilesSettings
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.transfers.CONFLICT_POLICIES
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.conflictHint
import dev.hfps.hoard.app.ui.common.conflictText
import dev.hfps.hoard.app.ui.common.sortDirectionText
import dev.hfps.hoard.app.ui.common.sortKeyText
import dev.hfps.hoard.app.ui.storage.StorageAccessIntents
import dev.hfps.hoard.app.ui.storage.rememberStoragePermission
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.handoff.HandoffServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hoard 1's Settings for the Files side (SettingsPage.tsx), on the same `settings` rows: browsing (hidden files,
 * default sort), transfers (conflict default), the route check timeout, phone storage access, the folders Android
 * gave Hoard, and Reveal's links.
 */
@Composable
fun FilesSettingsSection() {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = (context.applicationContext as HoardApplication).container
    val settings by container.appSettings.files.collectAsState(initial = FilesSettings())
    FilesSettingsContent(settings, container.appSettings)
}

@Composable
fun FilesSettingsContent(settings: FilesSettings, store: AppSettings?) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = (context.applicationContext as? HoardApplication)?.container
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(StorageAccessIntents.granted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = StorageAccessIntents.granted(context) }
    val ask = rememberStoragePermission { granted = StorageAccessIntents.granted(context) }
    var trees by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var links by remember { mutableIntStateOf(-1) }
    var revoking by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (container == null) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            trees = runCatching { container.destinations.list().map { it.uri to it.name } }.getOrDefault(emptyList())
            links = runCatching { HandoffServer.count(context) }.getOrDefault(0)
        }
    }

    Section(R.string.t_settings_browsing)
    HfpsListRow(
        title = stringResource(R.string.t_settings_show_hidden),
        subtitle = stringResource(R.string.t_settings_show_hidden_hint),
        icon = PhosphorRegular.Eye,
        onClick = { scope.launch { store?.setShowHidden(!settings.showHiddenFiles) } },
        trailing = {
            Switch(
                settings.showHiddenFiles,
                { v -> scope.launch { store?.setShowHidden(v) } },
                colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary),
            )
        },
    )
    Eyebrow(stringResource(R.string.t_settings_default_sort), Modifier.padding(start = 16.dp, top = 12.dp))
    Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
        SortKey.entries.forEach { key ->
            HfpsChoiceRow(stringResource(sortKeyText(key)), settings.defaultSort.key == key, {
                scope.launch { store?.setDefaultSort(settings.defaultSort.copy(key = key)) }
            })
        }
    }
    Eyebrow(stringResource(R.string.t_settings_sort_direction), Modifier.padding(start = 16.dp, top = 12.dp))
    Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
        listOf(true, false).forEach { asc ->
            HfpsChoiceRow(stringResource(sortDirectionText(settings.defaultSort.key, asc)), settings.defaultSort.ascending == asc, {
                scope.launch { store?.setDefaultSort(SortOrder(settings.defaultSort.key, asc)) }
            })
        }
    }
    androidx.compose.material3.Text(
        stringResource(R.string.t_settings_folders_first),
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )

    HfpsRowDivider(Modifier.padding(top = 8.dp))
    Section(R.string.t_settings_downloads)
    Eyebrow(stringResource(R.string.t_settings_default_conflict), Modifier.padding(start = 16.dp, top = 4.dp))
    Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
        CONFLICT_POLICIES.forEach { policy ->
            HfpsChoiceRow(
                stringResource(conflictText(policy)) + if (policy == "ask") " (" + stringResource(R.string.t_settings_default) + ")" else "",
                settings.defaultConflict == policy,
                { scope.launch { store?.setDefaultConflict(policy) } },
            )
        }
    }
    androidx.compose.material3.Text(
        stringResource(conflictHint(settings.defaultConflict)),
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Eyebrow(stringResource(R.string.t_settings_probe_timeout), Modifier.padding(start = 16.dp, top = 12.dp))
    androidx.compose.material3.Text(
        stringResource(R.string.t_settings_probe_timeout_hint),
        style = HfpsTheme.typography.ui,
        color = HfpsTheme.colors.mutedForeground,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Column(Modifier.selectableGroup().padding(horizontal = 16.dp)) {
        AppSettings.PROBE_TIMEOUTS.forEach { ms ->
            val seconds = java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(ms / 1000.0)
            HfpsChoiceRow(
                stringResource(R.string.t_settings_seconds, seconds) + if (ms == 1500L) " (" + stringResource(R.string.t_settings_default) + ")" else "",
                settings.probeTimeoutMs == ms,
                { scope.launch { store?.setProbeTimeout(ms) } },
            )
        }
    }

    HfpsRowDivider(Modifier.padding(top = 8.dp))
    Section(R.string.t_settings_storage)
    HfpsListRow(
        title = stringResource(R.string.t_settings_storage_access),
        subtitle = stringResource(if (granted) R.string.t_settings_storage_granted else R.string.t_settings_storage_denied),
        icon = PhosphorRegular.LockSimple,
        onClick = ask,
        trailing = { GhostButton(stringResource(R.string.t_settings_storage_manage), ask) },
    )
    if (trees.isNotEmpty()) {
        Eyebrow(stringResource(R.string.t_settings_destinations), Modifier.padding(start = 16.dp, top = 12.dp))
        androidx.compose.material3.Text(
            stringResource(R.string.t_settings_destinations_hint),
            style = HfpsTheme.typography.ui,
            color = HfpsTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        trees.forEach { (uri, name) ->
            HfpsListRow(title = name, icon = PhosphorRegular.FolderSimple, trailing = {
                GhostButton(stringResource(R.string.t_settings_release), {
                    scope.launch {
                        withContext(Dispatchers.IO) { runCatching { container?.destinations?.release(uri) } }
                        toaster.show(res.getString(R.string.t_settings_released, name))
                        reload++
                    }
                })
            })
        }
    }

    HfpsRowDivider(Modifier.padding(top = 8.dp))
    Section(R.string.t_reveal_links_title)
    HfpsListRow(
        title = if (links > 0) pluralStringResource(R.plurals.t_reveal_links_count, links, links.toString()) else stringResource(R.string.t_reveal_links_none),
        subtitle = stringResource(R.string.t_reveal_about),
        icon = PhosphorRegular.LinkSimple,
        trailing = if (links > 0) ({ GhostButton(stringResource(R.string.t_reveal_links_revoke), { revoking = true }) }) else null,
    )
    HfpsRowDivider(Modifier.padding(top = 8.dp))

    if (revoking) {
        ConfirmDialog(
            title = stringResource(R.string.t_reveal_links_confirm_title),
            body = stringResource(R.string.t_reveal_links_confirm_body),
            confirmLabel = stringResource(R.string.t_reveal_links_revoke),
            destructive = true,
            onDismiss = { revoking = false },
            onConfirm = {
                val revoked = withContext(Dispatchers.IO) { runCatching { HandoffServer.revokeAll(context) }.getOrNull() }
                toaster.show(
                    if (revoked == null) res.getString(R.string.t_reveal_links_failed)
                    else res.getQuantityString(R.plurals.t_reveal_links_revoked, revoked, revoked.toString()),
                )
                reload++
            },
        )
    }
}

@Composable
private fun Section(title: Int) {
    Box(Modifier.fillMaxWidth()) {
        Eyebrow(stringResource(title), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
    }
}
