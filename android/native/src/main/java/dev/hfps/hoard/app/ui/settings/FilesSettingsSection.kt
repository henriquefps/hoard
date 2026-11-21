package dev.hfps.hoard.app.ui.settings

import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.data.AppSettings
import dev.hfps.hoard.app.data.FilesSettings
import dev.hfps.hoard.app.files.RevealCopies
import dev.hfps.hoard.app.files.SortKey
import dev.hfps.hoard.app.files.SortOrder
import dev.hfps.hoard.app.files.formatBytes
import dev.hfps.hoard.app.transfers.CONFLICT_POLICIES
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.conflictHint
import dev.hfps.hoard.app.ui.common.conflictText
import dev.hfps.hoard.app.ui.common.sortDirectionText
import dev.hfps.hoard.app.ui.common.sortKeyText
import dev.hfps.hoard.app.ui.storage.StorageAccessIntents
import dev.hfps.hoard.app.ui.storage.rememberStoragePermission
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsSelect
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.handoff.HandoffServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Hoard 1's Settings for the Files side (SettingsPage.tsx), on the same `settings` rows, spread over the 2.9.0
 * Settings screens: browsing and transfers (Settings › Browsing and transfers), phone storage access and the folders
 * Android gave Hoard (Settings › Backup and phone folders), Reveal's copies and links (Settings › Cache and storage).
 */

/** Settings › Browsing and transfers, bound to the app's settings. */
@Composable
fun BrowsingSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = (context.applicationContext as HoardApplication).container
    val settings by container.appSettings.files.collectAsState(initial = FilesSettings())
    SettingsPageScaffold(stringResource(R.string.settings_browsing_title), onBack) {
        BrowsingSettingsContent(settings, container.appSettings)
    }
}

/** Browsing (hidden files, default sort) and transfers (conflict default, the route check timeout). */
@Composable
fun BrowsingSettingsContent(settings: FilesSettings, store: AppSettings?) {
    val scope = rememberCoroutineScope()
    SettingsSection(R.string.t_settings_browsing)
    HfpsListRow(
        title = stringResource(R.string.t_settings_show_hidden),
        subtitle = stringResource(R.string.t_settings_show_hidden_hint),
        icon = PhosphorRegular.Eye,
        subtitleMaxLines = Int.MAX_VALUE,
        onClick = { scope.launch { store?.setShowHidden(!settings.showHiddenFiles) } },
        trailing = {
            Switch(
                settings.showHiddenFiles,
                { v -> scope.launch { store?.setShowHidden(v) } },
                colors = SwitchDefaults.colors(checkedTrackColor = HfpsTheme.colors.primary),
            )
        },
    )
    HfpsSelect(
        options = SortKey.entries,
        selected = settings.defaultSort.key,
        optionLabel = { stringResource(sortKeyText(it)) },
        onSelect = { key -> scope.launch { store?.setDefaultSort(settings.defaultSort.copy(key = key)) } },
        label = stringResource(R.string.t_settings_default_sort),
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 8.dp).testTag("settings-sort"),
    )
    HfpsSelect(
        options = listOf(true, false),
        selected = settings.defaultSort.ascending,
        optionLabel = { stringResource(sortDirectionText(settings.defaultSort.key, it)) },
        onSelect = { asc -> scope.launch { store?.setDefaultSort(SortOrder(settings.defaultSort.key, asc)) } },
        label = stringResource(R.string.t_settings_sort_direction),
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).testTag("settings-sort-direction"),
    )
    SettingsHint(stringResource(R.string.t_settings_folders_first))

    HfpsRowDivider(Modifier.padding(top = 8.dp))
    SettingsSection(R.string.t_settings_downloads)
    val defaultSuffix = " (" + stringResource(R.string.t_settings_default) + ")"
    HfpsSelect(
        options = CONFLICT_POLICIES,
        selected = settings.defaultConflict,
        optionLabel = { stringResource(conflictText(it)) + if (it == "ask") defaultSuffix else "" },
        onSelect = { policy -> scope.launch { store?.setDefaultConflict(policy) } },
        label = stringResource(R.string.t_settings_default_conflict),
        modifier = Modifier.padding(horizontal = 16.dp).testTag("settings-conflict"),
    )
    SettingsHint(stringResource(conflictHint(settings.defaultConflict)))
    val numbers = remember { java.text.NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 } }
    HfpsSelect(
        options = AppSettings.PROBE_TIMEOUTS,
        selected = settings.probeTimeoutMs,
        optionLabel = { ms -> stringResource(R.string.t_settings_seconds, numbers.format(ms / 1000.0)) + if (ms == 1500L) defaultSuffix else "" },
        onSelect = { ms -> scope.launch { store?.setProbeTimeout(ms) } },
        label = stringResource(R.string.t_settings_probe_timeout),
        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).testTag("settings-probe-timeout"),
    )
    SettingsHint(stringResource(R.string.t_settings_probe_timeout_hint))
}

/**
 * Phone storage: all files access, and the folders Android gave Hoard through the picker (each with "Release"; shown
 * only when there is one).
 */
@Composable
fun StorageAccessSection() {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = (context.applicationContext as? HoardApplication)?.container
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(StorageAccessIntents.granted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = StorageAccessIntents.granted(context) }
    val ask = rememberStoragePermission { granted = StorageAccessIntents.granted(context) }
    var trees by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (container == null) return@LaunchedEffect
        trees = withContext(Dispatchers.IO) { runCatching { container.destinations.list().map { it.uri to it.name } }.getOrDefault(emptyList()) }
    }

    SettingsSection(R.string.t_settings_storage)
    HfpsListRow(
        title = stringResource(R.string.t_settings_storage_access),
        subtitle = stringResource(if (granted) R.string.t_settings_storage_granted else R.string.t_settings_storage_denied),
        icon = PhosphorRegular.HardDrive,
        onClick = ask,
        trailing = { GhostButton(stringResource(R.string.t_settings_storage_manage), ask) },
        modifier = Modifier.testTag("settings-storage-access"),
        subtitleMaxLines = Int.MAX_VALUE,
    )
    if (trees.isNotEmpty()) {
        SettingsSection(R.string.t_settings_destinations, Modifier.padding(top = 4.dp))
        SettingsHint(stringResource(R.string.t_settings_destinations_hint))
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
}

/**
 * Photos copied for Reveal (ADR 0010 amendment "2.2.0: local copies": how many and how big, "Delete copies") and
 * Reveal's links (how many, "Revoke all").
 */
@Composable
fun RevealStorageSection() {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val container = (context.applicationContext as? HoardApplication)?.container
    val toaster = LocalToaster.current
    var links by remember { mutableIntStateOf(-1) }
    var revoking by remember { mutableStateOf(false) }
    // null = loading.
    var copies by remember { mutableStateOf<RevealCopies.Usage?>(null) }
    var deletingCopies by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        if (container == null) return@LaunchedEffect
        links = withContext(Dispatchers.IO) { runCatching { HandoffServer.count(context) }.getOrDefault(0) }
        copies = runCatching { container.revealCopies.usage() }.getOrDefault(RevealCopies.Usage(0, 0))
    }

    SettingsSection(R.string.reveal_copies_title)
    val usage = copies
    HfpsListRow(
        title = when {
            usage == null -> stringResource(R.string.reveal_copies_loading)
            usage.count == 0 -> stringResource(R.string.reveal_copies_none)
            else -> pluralStringResource(R.plurals.reveal_copies_count, usage.count, usage.count.toString(), formatBytes(usage.bytes))
        },
        subtitle = stringResource(R.string.reveal_copies_about),
        icon = PhosphorRegular.Copy,
        trailing = if (usage != null && usage.count > 0) ({ GhostButton(stringResource(R.string.reveal_copies_delete), { deletingCopies = true }) }) else null,
        modifier = Modifier.testTag("settings-reveal-copies"),
        subtitleMaxLines = Int.MAX_VALUE,
    )

    HfpsRowDivider(Modifier.padding(top = 8.dp))
    SettingsSection(R.string.t_reveal_links_title)
    HfpsListRow(
        title = if (links > 0) pluralStringResource(R.plurals.t_reveal_links_count, links, links.toString()) else stringResource(R.string.t_reveal_links_none),
        subtitle = stringResource(R.string.t_reveal_about),
        icon = PhosphorRegular.LinkSimple,
        trailing = if (links > 0) ({ GhostButton(stringResource(R.string.t_reveal_links_revoke), { revoking = true }) }) else null,
        modifier = Modifier.testTag("settings-reveal-links"),
        subtitleMaxLines = Int.MAX_VALUE,
    )

    if (deletingCopies) {
        ConfirmDialog(
            title = stringResource(R.string.reveal_copies_confirm_title),
            body = stringResource(R.string.reveal_copies_confirm_body),
            confirmLabel = stringResource(R.string.reveal_copies_delete),
            destructive = true,
            onDismiss = { deletingCopies = false },
            onConfirm = {
                val deleted = container?.let { c -> runCatching { c.revealCopies.deleteAll() }.getOrNull() }
                toaster.show(
                    if (deleted == null) res.getString(R.string.reveal_copies_failed)
                    else res.getQuantityString(R.plurals.reveal_copies_deleted, deleted.count, deleted.count.toString(), formatBytes(deleted.bytes)),
                )
                reload++
            },
        )
    }

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
