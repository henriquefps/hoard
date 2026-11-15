package dev.hfps.hoard.app.ui.server

import android.content.ClipboardManager
import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.HoardDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.fieldColors
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsChoiceRow
import dev.hfps.hoard.designsystem.components.HfpsListRow
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.server.PairingCode
import dev.hfps.hoard.server.api.Candidate
import dev.hfps.hoard.server.api.DeviceView
import dev.hfps.hoard.server.sync.GallerySyncWorker
import dev.hfps.hoard.server.sync.PairingException
import dev.hfps.hoard.server.sync.SyncEngine
import kotlinx.coroutines.launch

/** Settings key of the preview cache limit, in MiB (ADR 0021). */
const val CACHE_LIMIT_KEY = "galleryCacheMb"
private val CACHE_CHOICES = listOf(256L, 1024L, 4096L)

/**
 * Settings › Photo server (ADR 0021): pair, status, sync now, this phone (ADR 0017), the preview cache, phone
 * folders, unpair.
 */
@Composable
fun ServerSettingsSection(container: AppContainer, onPair: () -> Unit) {
    val repo = container.gallery
    val account by repo.account.collectAsState(initial = null)
    val status by repo.sync.status.collectAsState()
    val items by repo.db.items().observeCount().collectAsState(initial = 0)
    val pending by repo.db.culling().observePendingCount().collectAsState(initial = 0)
    val folders by repo.db.device().observeFolders().collectAsState(initial = emptyList())
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var unpair by remember { mutableStateOf(false) }
    var addFolder by remember { mutableStateOf(false) }
    var changeAddress by remember { mutableStateOf(false) }
    var device by remember { mutableStateOf<DeviceView?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    val limitMb by container.settingsStore.observeString(CACHE_LIMIT_KEY).collectAsState(initial = null)
    val limit = (limitMb?.toLongOrNull() ?: 1024L) shl 20
    LaunchedEffect(account?.serverId) {
        if (account != null) device = runCatching { repo.sync.deviceView() }.getOrNull()
        cacheBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.previews.bytes() }
    }
    LaunchedEffect(limit) { repo.previews.maxBytes = limit }

    Eyebrow(stringResource(R.string.server_section), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
    Text(stringResource(R.string.server_section_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 4.dp))
    val a = account
    if (a == null) {
        HfpsListRow(
            title = stringResource(R.string.server_pair),
            subtitle = stringResource(R.string.server_pair_subtitle),
            icon = PhosphorRegular.QrCode,
            onClick = onPair,
            modifier = Modifier.testTag("server-pair"),
        )
    } else {
        val plain = a.url.startsWith("http://")
        HfpsListRow(
            title = stringResource(R.string.server_paired, a.serverName ?: "hoard-server"),
            subtitle = stringResource(R.string.server_paired_subtitle, a.serverVersion ?: "?", a.url) +
                if (plain) "\n" + stringResource(R.string.server_plain_http) else "",
            icon = if (plain) PhosphorRegular.Warning else PhosphorRegular.LockSimple,
            iconTint = if (plain) HfpsTheme.colors.destructive else HfpsTheme.colors.mutedForeground,
            modifier = Modifier.testTag("server-paired"),
            trailing = { GhostButton(stringResource(R.string.server_address_change), { changeAddress = true }, modifier = Modifier.testTag("server-address")) },
        )
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        val now = System.currentTimeMillis()
        val lines = listOfNotNull(
            a.lastSyncAt?.let { stringResource(R.string.server_status_synced, DateUtils.getRelativeTimeSpanString(it, now, DateUtils.MINUTE_IN_MILLIS).toString()) } ?: stringResource(R.string.server_status_never),
            stringResource(R.string.server_status_items, items),
            if (pending > 0) stringResource(R.string.server_status_pending, pending) else null,
            when (a.lastError) {
                null -> null
                SyncEngine.ERROR_OFFLINE -> stringResource(R.string.server_status_error_offline)
                SyncEngine.ERROR_UNAUTHORIZED -> stringResource(R.string.server_status_error_revoked)
                else -> stringResource(R.string.server_status_error_other, a.lastError!!)
            },
            // The pull went on; refused decisions wait for their backoff (ADR 0021 "Sync passes").
            status.pushError?.let { stringResource(R.string.server_status_push_refused, it) },
        )
        HfpsListRow(
            title = stringResource(R.string.server_status),
            subtitle = lines.joinToString("\n"),
            icon = if (a.lastError != null) PhosphorRegular.WarningCircle else PhosphorRegular.CheckCircle,
            modifier = Modifier.testTag("server-status"),
            trailing = {
                SecondaryButton(stringResource(R.string.server_sync_now), {
                    scope.launch { repo.syncNow() }
                }, enabled = !status.running, modifier = Modifier.testTag("server-sync-now"))
            },
        )
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        val group = device?.physicalDevice?.devices.orEmpty().filter { it.id != a.deviceId }
        HfpsListRow(
            title = stringResource(R.string.server_this_phone),
            subtitle = if (group.isEmpty()) stringResource(R.string.server_this_phone_alone) else stringResource(R.string.server_this_phone_group, group.joinToString { it.app ?: it.name ?: "?" }),
            icon = PhosphorRegular.DeviceMobile,
        )
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        HfpsListRow(
            title = stringResource(R.string.server_cache),
            subtitle = stringResource(R.string.server_cache_subtitle, Formatter.formatShortFileSize(context, cacheBytes), Formatter.formatShortFileSize(context, limit)),
            icon = PhosphorRegular.Database,
            trailing = {
                GhostButton(stringResource(R.string.server_cache_clear), {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.previews.clear() }
                        repo.thumbs.clear()
                        cacheBytes = 0
                        toaster.show(res.getString(R.string.server_cache_cleared))
                    }
                })
            },
        )
        Column(Modifier.padding(start = 54.dp, end = 16.dp)) {
            Text(stringResource(R.string.server_cache_limit), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            CACHE_CHOICES.forEach { mb ->
                HfpsChoiceRow(Formatter.formatShortFileSize(context, mb shl 20), limit == (mb shl 20), { scope.launch { container.settingsStore.putString(CACHE_LIMIT_KEY, mb.toString()) } })
            }
        }
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        OfflineStorage(container)
        HfpsRowDivider(Modifier.padding(start = 54.dp))
        HfpsListRow(
            title = stringResource(R.string.server_unpair),
            icon = PhosphorRegular.XCircle,
            iconTint = HfpsTheme.colors.destructive,
            onClick = { unpair = true },
            modifier = Modifier.testTag("server-unpair"),
        )
    }
    HfpsRowDivider(Modifier.padding(start = 54.dp))
    HfpsListRow(
        title = stringResource(R.string.server_device_folders),
        subtitle = (listOf(stringResource(R.string.server_device_folders_subtitle)) + folders.map { it.path }).joinToString("\n"),
        icon = PhosphorRegular.FolderSimple,
        trailing = { GhostButton(stringResource(R.string.server_device_folder_add), { addFolder = true }) },
    )
    folders.forEach { f ->
        HfpsListRow(
            title = f.path,
            icon = PhosphorRegular.X,
            onClick = { scope.launch { repo.deviceIndex.removeFolder(f.path) } },
            modifier = Modifier.padding(start = 38.dp),
        )
    }
    HfpsRowDivider(Modifier.padding(top = 8.dp))

    if (unpair && a != null) {
        ConfirmDialog(
            title = stringResource(R.string.server_unpair_title, a.serverName ?: "hoard-server"),
            body = stringResource(R.string.server_unpair_body),
            confirmLabel = stringResource(R.string.server_unpair),
            destructive = true,
            onDismiss = { unpair = false },
            onConfirm = {
                repo.sync.unpair()
                GallerySyncWorker.cancel(context)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.previews.clear() }
                repo.thumbs.clear()
                unpair = false
            },
        )
    }
    if (changeAddress && a != null) {
        var url by remember { mutableStateOf(a.url) }
        var failed by remember { mutableStateOf(false) }
        HoardDialog(
            title = stringResource(R.string.server_address_change),
            onDismiss = { changeAddress = false },
            confirm = {
                PrimaryButton(stringResource(R.string.server_address_save), {
                    scope.launch {
                        if (repo.sync.changeAddress(url.trim())) changeAddress = false else failed = true
                    }
                })
            },
            dismiss = { GhostButton(stringResource(android.R.string.cancel), { changeAddress = false }) },
        ) {
            Text(stringResource(R.string.server_address_hint), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            OutlinedTextField(url, { url = it; failed = false }, singleLine = true, isError = failed,
                supportingText = if (failed) ({ Text(stringResource(R.string.server_address_failed)) }) else null,
                colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("server-address-field"))
        }
    }
    if (addFolder) {
        var path by remember { mutableStateOf("/storage/emulated/0/") }
        HoardDialog(
            title = stringResource(R.string.server_device_folder_add),
            onDismiss = { addFolder = false },
            confirm = {
                PrimaryButton(stringResource(R.string.server_device_folder_add), {
                    scope.launch { repo.deviceIndex.addFolder(path.trim()) }
                    addFolder = false
                }, enabled = path.trim().length > 1)
            },
            dismiss = { GhostButton(stringResource(android.R.string.cancel), { addFolder = false }) },
        ) {
            OutlinedTextField(path, { path = it }, singleLine = true, label = { Text(stringResource(R.string.server_device_folder_add_hint)) }, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun pairErrorText(e: Throwable): Int = when ((e as? PairingException)?.reason) {
    PairingException.Reason.TokenRefused -> R.string.pair_error_refused
    PairingException.Reason.OtherServer -> R.string.pair_error_other
    PairingException.Reason.TooOld -> R.string.pair_error_old
    PairingException.Reason.Insecure -> R.string.pair_error_insecure
    PairingException.Reason.InvalidCode -> R.string.pair_error_code
    else -> R.string.pair_error_unreachable
}

/**
 * Pairing (ADR 0013): the QR code of the server's setup page (Google's code scanner, no camera permission), or its
 * link pasted. Then, when the server proposes it (ADR 0017), the one question "Is this the same phone as Reveal on
 * Pixel 8?".
 */
@Composable
fun PairScreen(container: AppContainer, onBack: () -> Unit) {
    val repo = container.gallery
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    var candidates by remember { mutableStateOf<List<Candidate>>(emptyList()) }

    fun pair(text: String) {
        val code = PairingCode.parse(text)
        if (code == null) {
            error = R.string.pair_error_code
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val paired = repo.sync.pair(code)
                GallerySyncWorker.schedule(context)
                toaster.show(res.getString(R.string.pair_done, paired.serverName ?: "hoard-server"))
                launch { repo.syncNow() }
                if (paired.candidates.isEmpty()) onBack() else candidates = paired.candidates
            } catch (e: Throwable) {
                error = pairErrorText(e)
            } finally {
                busy = false
            }
        }
    }

    HfpsScaffold(topBar = { HfpsTopBar(stringResource(R.string.pair_title), onBack = onBack) }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.pair_intro), style = HfpsTheme.typography.body, color = HfpsTheme.colors.foreground)
                if (QrScanner.available(context)) {
                    PrimaryButton(stringResource(R.string.pair_scan), {
                        QrScanner.scan(context, onResult = { text -> link = text; pair(text) }, onFailure = { error = R.string.pair_error_code })
                    }, enabled = !busy, icon = PhosphorRegular.QrCode, modifier = Modifier.fillMaxWidth().testTag("pair-scan"))
                }
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it; error = null },
                    label = { Text(stringResource(R.string.pair_link)) },
                    placeholder = { Text(stringResource(R.string.pair_link_hint)) },
                    singleLine = false,
                    minLines = 2,
                    isError = error != null,
                    supportingText = error?.let { { Text(stringResource(it)) } },
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().testTag("pair-link"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                    GhostButton(stringResource(R.string.pair_paste), {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let { link = it.trim() }
                    })
                    PrimaryButton(stringResource(if (busy) R.string.pair_working else R.string.pair_action), { pair(link) }, enabled = !busy && link.isNotBlank(), modifier = Modifier.testTag("pair-action"))
                }
            }
        }
    }
    if (candidates.isNotEmpty()) {
        ClaimDialog(candidates, onAnswer = { physicalId ->
            scope.launch {
                runCatching { repo.sync.claim(physicalId, answerOnly = physicalId == null) }
                candidates = emptyList()
                onBack()
            }
        })
    }
}

/** ADR 0017 step 3: one model match → "Same phone" / "Another device"; several → a list and "None of these". */
@Composable
fun ClaimDialog(candidates: List<Candidate>, onAnswer: (String?) -> Unit) {
    val single = candidates.singleOrNull()
    HoardDialog(
        title = stringResource(R.string.claim_title),
        onDismiss = { onAnswer(null) },
        confirm = {
            if (single != null) PrimaryButton(stringResource(R.string.claim_same), { onAnswer(single.physicalDeviceId) }, modifier = Modifier.testTag("claim-same"))
        },
        dismiss = { GhostButton(stringResource(if (single != null) R.string.claim_other else R.string.claim_none), { onAnswer(null) }, modifier = Modifier.testTag("claim-other")) },
    ) {
      // Dialogs are windows of their own: expose their test tags too (debug E2E).
      Column(Modifier.semantics { testTagsAsResourceId = true }) {
        if (single != null) {
            val app = single.devices.firstOrNull()?.app?.replaceFirstChar { it.uppercase() } ?: "Reveal"
            Text(stringResource(R.string.claim_one, app, single.name ?: single.devices.firstOrNull()?.model ?: "?"), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
        } else {
            Text(stringResource(R.string.claim_many), style = HfpsTheme.typography.body, color = HfpsTheme.colors.cardForeground)
            candidates.forEach { c ->
                HfpsListRow(
                    title = c.name ?: "?",
                    subtitle = c.devices.joinToString { listOfNotNull(it.app, it.model).joinToString(" · ") },
                    icon = PhosphorRegular.DeviceMobile,
                    onClick = { onAnswer(c.physicalDeviceId) },
                )
            }
        }
        Text(stringResource(R.string.claim_why), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.padding(top = 8.dp))
      }
    }
}


private val ORIGINAL_CHOICES = listOf(512L, 2048L, 8192L)

/** Settings › Photo server › storage on this phone (ADR 0021): offline thumbnails and kept originals. */
@Composable
private fun OfflineStorage(container: AppContainer) {
    val repo = container.gallery
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = container.settingsStore
    val thumbsOn by store.observeString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_OFFLINE_THUMBS).collectAsState(initial = null)
    val charging by store.observeString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_THUMBS_CHARGING).collectAsState(initial = null)
    val originalsMb by store.observeString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_ORIGINALS_MB).collectAsState(initial = null)
    val items by repo.db.items().observeCount().collectAsState(initial = 0)
    var thumbs by remember { mutableStateOf(0 to 0L) }
    var originals by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(refresh) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            thumbs = repo.offlineThumbs.count() to repo.offlineThumbs.bytes()
            originals = repo.originals.bytes()
        }
    }
    val budget = (originalsMb?.toLongOrNull() ?: 2048L) shl 20
    LaunchedEffect(budget) { repo.originals.maxBytes = budget }
    fun reschedule() = scope.launch { repo.scheduleOfflineThumbs() }

    HfpsListRow(
        title = stringResource(R.string.server_offline_thumbs),
        subtitle = stringResource(R.string.server_offline_thumbs_subtitle, thumbs.first, items, Formatter.formatShortFileSize(context, thumbs.second)),
        icon = PhosphorRegular.Images,
        modifier = Modifier.testTag("server-offline-thumbs"),
        trailing = {
            GhostButton(stringResource(R.string.server_cache_clear), {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.offlineThumbs.clear() }
                    repo.thumbs.clear()
                    refresh++
                }
            })
        },
    )
    Column(Modifier.padding(start = 54.dp, end = 16.dp)) {
        dev.hfps.hoard.designsystem.components.HfpsCheckRow(stringResource(R.string.server_offline_thumbs_on), thumbsOn != "off", {
            scope.launch {
                store.putString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_OFFLINE_THUMBS, if (thumbsOn != "off") "off" else "on")
                reschedule()
            }
        })
        dev.hfps.hoard.designsystem.components.HfpsCheckRow(stringResource(R.string.server_offline_thumbs_charging), charging == "on", {
            scope.launch {
                store.putString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_THUMBS_CHARGING, if (charging == "on") "off" else "on")
                reschedule()
            }
        })
    }
    HfpsRowDivider(Modifier.padding(start = 54.dp))
    HfpsListRow(
        title = stringResource(R.string.server_originals),
        subtitle = stringResource(R.string.server_originals_subtitle, Formatter.formatShortFileSize(context, originals), Formatter.formatShortFileSize(context, budget)),
        icon = PhosphorRegular.HardDrive,
        trailing = {
            GhostButton(stringResource(R.string.server_cache_clear), {
                scope.launch {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repo.originals.clear() }
                    refresh++
                }
            })
        },
    )
    Column(Modifier.padding(start = 54.dp, end = 16.dp)) {
        ORIGINAL_CHOICES.forEach { mb ->
            HfpsChoiceRow(Formatter.formatShortFileSize(context, mb shl 20), budget == (mb shl 20), { scope.launch { store.putString(dev.hfps.hoard.app.gallery.GalleryRepository.KEY_ORIGINALS_MB, mb.toString()) } })
        }
    }
}
