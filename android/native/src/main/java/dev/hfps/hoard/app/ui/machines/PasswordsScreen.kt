package dev.hfps.hoard.app.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.files.engineMachine
import dev.hfps.hoard.app.importer.PasswordsAfterImport
import dev.hfps.hoard.app.route.errorCodeOf
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.fieldColors
import dev.hfps.hoard.app.ui.files.machineIcon
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsRowDivider
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import dev.hfps.hoard.smb.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** A machine whose password Hoard Next still needs (after a bridge import, ADR 0019). */
data class PasswordItem(val id: String, val name: String, val icon: String?, val account: String)

/** How a saved password went: connected, saved but the machine is not reachable now, or refused. */
enum class PasswordResult { Connected, Offline, Wrong }

/** `DOMAIN\user`, or the user alone. */
fun accountOf(username: String, domain: String?): String = if (domain.isNullOrBlank()) username else "$domain\\$username"

/** Which machines still need a password: the pending ids, in the machines' order. */
fun pendingPasswords(pending: List<String>, machines: List<MachineWithAddresses>): List<PasswordItem> =
    machines.filter { it.machine.id in pending }.map { PasswordItem(it.machine.id, it.machine.name, it.machine.icon, accountOf(it.machine.username, it.machine.domain)) }

/** A connection test's error code → what the Passwords screen says. */
fun passwordResultOf(code: ErrorCode): PasswordResult = when (code) {
    ErrorCode.AUTH_FAILED, ErrorCode.ACCESS_DENIED -> PasswordResult.Wrong
    else -> PasswordResult.Offline
}

/**
 * The Passwords step after an import from Hoard 1 (the Keystore entries never leave Hoard 1): one card per machine
 * with its account and a password field. "Save and connect" stores the password in this app's Keystore
 * ([dev.hfps.hoard.smb.CredentialStore], never the database), then lists the shares to prove it: connected, saved but
 * offline (it connects later), or refused (type it again). "Later" leaves the machine for the Files tab's notice.
 */
@Composable
fun PasswordsScreen(
    items: List<PasswordItem>,
    onSave: suspend (PasswordItem, String) -> PasswordResult,
    onSkip: (PasswordItem) -> Unit,
    onDone: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    HfpsScaffold(
        modifier = modifier,
        topBar = {
            HfpsTopBar(stringResource(R.string.passwords_title), onBack = onBack) {
                GhostButton(stringResource(R.string.passwords_done), onDone, modifier = Modifier.testTag("passwords-done"))
            }
        },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxSize().testTag("passwords"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(
                        stringResource(R.string.passwords_intro),
                        style = HfpsTheme.typography.body,
                        color = HfpsTheme.colors.mutedForeground,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    )
                }
                if (items.isEmpty()) {
                    item { StateMessage(PhosphorRegular.CheckCircle, stringResource(R.string.passwords_all_set)) }
                }
                items(items, key = { it.id }) { item ->
                    if (item.id != items.first().id) HfpsRowDivider(Modifier.padding(start = 16.dp, bottom = 12.dp))
                    PasswordCard(item, onSave, onSkip)
                }
            }
        }
    }
}

@Composable
private fun PasswordCard(item: PasswordItem, onSave: suspend (PasswordItem, String) -> PasswordResult, onSkip: (PasswordItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<PasswordResult?>(null) }
    fun save() {
        if (password.isEmpty() || busy) return
        busy = true
        scope.launch {
            result = try {
                onSave(item, password)
            } finally {
                busy = false
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag("password-${item.id}"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.primary.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(machineIcon(item.icon), null, Modifier.size(18.dp), tint = HfpsTheme.colors.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(item.name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
                Text(stringResource(R.string.passwords_signs_in_as, item.account), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
        if (result == PasswordResult.Connected || result == PasswordResult.Offline) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (result == PasswordResult.Connected) PhosphorRegular.CheckCircle else PhosphorRegular.Info, null, Modifier.size(18.dp), tint = if (result == PasswordResult.Connected) HfpsTheme.colors.primary else HfpsTheme.colors.mutedForeground)
                Text(
                    stringResource(if (result == PasswordResult.Connected) R.string.passwords_connected else R.string.passwords_saved_offline, item.name),
                    style = HfpsTheme.typography.ui,
                    color = HfpsTheme.colors.cardForeground,
                    modifier = Modifier.testTag("password-result"),
                )
            }
            return@Column
        }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it; if (result == PasswordResult.Wrong) result = null },
            label = { Text(stringResource(R.string.passwords_field, item.name)) },
            singleLine = true,
            enabled = !busy,
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            trailingIcon = {
                HfpsIconButton(if (show) PhosphorRegular.EyeSlash else PhosphorRegular.Eye, stringResource(if (show) R.string.passwords_hide else R.string.passwords_show), { show = !show })
            },
            isError = result == PasswordResult.Wrong,
            supportingText = if (result == PasswordResult.Wrong) ({ Text(stringResource(R.string.passwords_wrong, item.name), modifier = Modifier.testTag("password-result")) }) else null,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().testTag("password-field-${item.id}"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
            } else {
                PrimaryButton(stringResource(R.string.passwords_save), { save() }, enabled = password.isNotEmpty(), modifier = Modifier.testTag("password-save-${item.id}"))
            }
            GhostButton(stringResource(R.string.passwords_later), { onSkip(item) }, enabled = !busy)
        }
    }
}

/**
 * [PasswordsScreen] over the app: the pending list in `settings` ([PasswordsAfterImport]), the machines, the Keystore
 * and a share listing as the connection test. A machine leaves the list once its password is saved (connected or not
 * reachable now) or the user picks "Later".
 */
@Composable
fun PasswordsRoute(container: AppContainer, onDone: () -> Unit, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val pending by remember { container.settingsStore.observeJson(PasswordsAfterImport.KEY).map(PasswordsAfterImport::parse) }.collectAsState(initial = emptyList())
    val machines by container.database.machines().observeAll().collectAsState(initial = emptyList())
    // A card stays on screen with its result after its machine left the pending list; "Later" hides it.
    var seen by remember { mutableStateOf<List<String>>(emptyList()) }
    var skipped by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(pending) { seen = (seen + pending).distinct() }
    PasswordsScreen(
        items = pendingPasswords(seen - skipped, machines),
        onSave = { item, password ->
            val machine = machines.firstOrNull { it.machine.id == item.id }
            if (machine == null) {
                PasswordResult.Offline
            } else {
                container.gateway.setCredentials(machine.machine.id, machine.machine.username, password, machine.machine.domain)
                // A session opened before (without the password) is dropped, then the shares prove the password.
                container.routes.forget(machine.machine.id)
                val result = try {
                    container.files.listShares(machine)
                    PasswordResult.Connected
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    passwordResultOf(errorCodeOf(e))
                }
                if (result != PasswordResult.Wrong) {
                    PasswordsAfterImport.remove(container.settingsStore, machine.machine.id)
                    container.routes.check(machine.engineMachine(), force = true)
                }
                result
            }
        },
        onSkip = { item ->
            skipped = skipped + item.id
            scope.launch { PasswordsAfterImport.remove(container.settingsStore, item.id) }
        },
        onDone = onDone,
        onBack = onBack,
    )
}
