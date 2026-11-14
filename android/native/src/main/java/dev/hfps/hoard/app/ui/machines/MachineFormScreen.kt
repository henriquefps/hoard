package dev.hfps.hoard.app.ui.machines

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.hfps.hoard.app.R
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.app.route.AddressTestResult
import dev.hfps.hoard.app.ui.common.ConfirmDialog
import dev.hfps.hoard.app.ui.common.LocalToaster
import dev.hfps.hoard.app.ui.common.StateMessage
import dev.hfps.hoard.app.ui.common.addressKindText
import dev.hfps.hoard.app.ui.common.fieldColors
import dev.hfps.hoard.app.ui.common.problemText
import dev.hfps.hoard.app.ui.files.MACHINE_ICONS
import dev.hfps.hoard.app.ui.files.machineIcon
import dev.hfps.hoard.designsystem.components.Eyebrow
import dev.hfps.hoard.designsystem.components.GhostButton
import dev.hfps.hoard.designsystem.components.HfpsIconButton
import dev.hfps.hoard.designsystem.components.HfpsScaffold
import dev.hfps.hoard.designsystem.components.HfpsTopBar
import dev.hfps.hoard.designsystem.components.PrimaryButton
import dev.hfps.hoard.designsystem.components.SecondaryButton
import dev.hfps.hoard.designsystem.icons.PhosphorRegular
import dev.hfps.hoard.designsystem.theme.HfpsTheme
import kotlinx.coroutines.launch

/** Machine colours (`machines.color`), Hoard 1's list. */
val MACHINE_COLORS = listOf("blue", "violet", "rose", "green", "amber", "teal", "slate")

fun machineColor(id: String?): Color = when (id) {
    "violet" -> Color(0xFF7C5CD6)
    "rose" -> Color(0xFFD6457A)
    "green" -> Color(0xFF2E9E5B)
    "amber" -> Color(0xFFD98E04)
    "teal" -> Color(0xFF14958F)
    "slate" -> Color(0xFF64748B)
    else -> Color(0xFF3B6FE0)
}

fun machineIconName(id: String): Int = when (id) {
    "desktop-tower" -> R.string.t_machines_icons_desktop_tower
    "desktop" -> R.string.t_machines_icons_desktop
    "laptop" -> R.string.t_machines_icons_laptop
    "cloud" -> R.string.t_machines_icons_cloud
    "house" -> R.string.t_machines_icons_house
    "monitor-play" -> R.string.t_machines_icons_monitor_play
    "cpu" -> R.string.t_machines_icons_cpu
    else -> R.string.t_machines_icons_hard_drives
}

@Composable
fun MachineFormScreen(viewModel: MachineFormViewModel, state: MachineFormState, onBack: () -> Unit, onSaved: (String) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val res = androidx.compose.ui.platform.LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    var showPassword by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var repinning by remember { mutableStateOf(false) }
    val draft = state.draft
    val title = stringResource(if (state.isNew) R.string.t_machines_new else R.string.t_machines_edit)
    val save: () -> Unit = {
        scope.launch {
            if (viewModel.save()) {
                toaster.show(res.getString(if (state.isNew) R.string.t_machines_form_created else R.string.t_machines_form_saved))
                onSaved(state.id)
            } else if (viewModel.state.value.errors.ok) {
                toaster.show(res.getString(R.string.t_errors_unknown_title))
            }
        }
    }
    HfpsScaffold(topBar = {
        HfpsTopBar(title, onBack = onBack) {
            HfpsIconButton(PhosphorRegular.Check, stringResource(R.string.t_common_save), save, enabled = !state.saving && !state.missing)
        }
    }) {
        if (state.missing) {
            StateMessage(PhosphorRegular.WarningCircle, stringResource(R.string.t_machines_not_found)) {
                SecondaryButton(stringResource(R.string.t_common_back), onBack)
            }
            return@HfpsScaffold
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("machine-form"),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (state.isNew) Discovery(state, viewModel)

                Section(stringResource(R.string.t_machines_form_machine)) {
                    Field(
                        value = draft.name,
                        onChange = { v -> viewModel.update { it.copy(name = v) } },
                        label = stringResource(R.string.t_machines_form_name),
                        placeholder = stringResource(R.string.t_machines_form_name_placeholder),
                        error = state.errors.name?.let { stringResource(R.string.t_machines_form_errors_name) },
                        tag = "machine-name",
                    )
                    Text(stringResource(R.string.t_machines_form_icon), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MACHINE_ICONS.forEach { id ->
                            val selected = draft.icon == id
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) machineColor(draft.color).copy(alpha = 0.15f) else HfpsTheme.colors.muted)
                                    .border(if (selected) 2.dp else 0.dp, if (selected) machineColor(draft.color) else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable { viewModel.update { it.copy(icon = id) } },
                                contentAlignment = Alignment.Center,
                            ) { Icon(machineIcon(id), stringResource(machineIconName(id)), Modifier.size(22.dp), tint = if (selected) machineColor(draft.color) else HfpsTheme.colors.mutedForeground) }
                        }
                    }
                    Text(stringResource(R.string.t_machines_form_color), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MACHINE_COLORS.forEach { id ->
                            val selected = draft.color == id
                            Box(
                                Modifier.size(32.dp).clip(CircleShape).background(machineColor(id))
                                    .border(if (selected) 3.dp else 0.dp, HfpsTheme.colors.foreground, CircleShape)
                                    .clickable { viewModel.update { it.copy(color = id) } },
                            )
                        }
                    }
                }

                Section(stringResource(R.string.t_machines_form_sign_in), stringResource(R.string.t_machines_form_sign_in_hint)) {
                    Field(
                        value = draft.username,
                        onChange = { v -> viewModel.update { it.copy(username = v) } },
                        label = stringResource(R.string.t_machines_form_username),
                        error = state.errors.username?.let { stringResource(R.string.t_machines_form_errors_username) },
                        tag = "machine-username",
                    )
                    OutlinedTextField(
                        value = draft.password,
                        onValueChange = { v -> viewModel.update { it.copy(password = v) } },
                        label = { Text(stringResource(R.string.t_machines_form_password)) },
                        placeholder = if (state.hasPassword) ({ Text(stringResource(R.string.t_machines_form_password_keep)) }) else null,
                        singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            HfpsIconButton(
                                if (showPassword) PhosphorRegular.EyeSlash else PhosphorRegular.Eye,
                                stringResource(if (showPassword) R.string.t_machines_form_hide_password else R.string.t_machines_form_show_password),
                                { showPassword = !showPassword },
                            )
                        },
                        isError = state.errors.password != null,
                        supportingText = {
                            Text(
                                when (state.errors.password) {
                                    FormError.Password -> stringResource(R.string.t_machines_form_errors_password)
                                    FormError.PasswordForUser -> stringResource(R.string.t_machines_form_errors_password_for_user)
                                    else -> stringResource(if (state.hasPassword) R.string.t_machines_form_password_saved else R.string.t_machines_form_password_hint)
                                },
                            )
                        },
                        colors = fieldColors(),
                        modifier = Modifier.fillMaxWidth().testTag("machine-password"),
                    )
                    Field(
                        value = draft.domain,
                        onChange = { v -> viewModel.update { it.copy(domain = v) } },
                        label = stringResource(R.string.t_machines_form_domain),
                        placeholder = stringResource(R.string.t_machines_form_domain_placeholder),
                    )
                }

                Section(stringResource(R.string.t_machines_form_addresses), stringResource(R.string.t_machines_form_addresses_hint)) {
                    draft.addresses.forEachIndexed { index, address ->
                        AddressEditor(index, address, draft.addresses, state, viewModel)
                    }
                    state.errors.addresses?.let { Text(stringResource(R.string.t_machines_form_errors_addresses), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.destructive) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton(stringResource(R.string.t_machines_form_add_address), { viewModel.setAddresses(draft.addresses + DraftAddress(), manual = true) }, icon = PhosphorRegular.Plus)
                        if (draft.addresses.size > 1) GhostButton(stringResource(R.string.t_machines_form_sort_by_kind), viewModel::sortByKind, icon = PhosphorRegular.ArrowsDownUp)
                    }
                }

                Section(stringResource(R.string.t_machines_test_title), stringResource(R.string.t_machines_test_hint)) {
                    SecondaryButton(
                        stringResource(if (state.testing) R.string.t_machines_test_running else R.string.t_machines_test_run),
                        viewModel::test,
                        icon = PhosphorRegular.PlugsConnected,
                        enabled = !state.testing,
                    )
                    state.results?.let { TestResults(it) }
                }

                if (!state.isNew) {
                    Section(stringResource(R.string.t_machines_identity_title), stringResource(R.string.t_machines_identity_body)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(PhosphorRegular.Fingerprint, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(if (state.pinnedGuid != null) R.string.t_machines_identity_pinned else R.string.t_machines_identity_not_pinned),
                                    style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
                                    color = HfpsTheme.colors.cardForeground,
                                )
                                Text(
                                    state.pinnedGuid ?: stringResource(R.string.t_machines_identity_pins_on_connect),
                                    style = HfpsTheme.typography.mono,
                                    color = HfpsTheme.colors.mutedForeground,
                                )
                            }
                            if (state.pinnedGuid != null) SecondaryButton(stringResource(R.string.t_machines_identity_repin), { repinning = true })
                        }
                    }
                    Section(stringResource(R.string.t_machines_delete_section)) {
                        SecondaryButton(stringResource(R.string.t_machines_delete_action), { deleting = true }, icon = PhosphorRegular.Trash)
                    }
                }

                PrimaryButton(
                    stringResource(if (state.isNew) R.string.t_machines_form_create else R.string.t_common_save),
                    save,
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth().testTag("machine-save"),
                )
            }
        }
    }
    if (deleting) {
        ConfirmDialog(
            title = stringResource(R.string.t_machines_delete_title, draft.name),
            body = stringResource(R.string.t_machines_delete_body),
            confirmLabel = stringResource(R.string.t_common_delete),
            destructive = true,
            onDismiss = { deleting = false },
            onConfirm = {
                viewModel.delete()
                toaster.show(res.getString(R.string.t_machines_delete_done, draft.name))
                onDeleted()
            },
        )
    }
    if (repinning) {
        ConfirmDialog(
            title = stringResource(R.string.t_machines_identity_repin_title),
            body = stringResource(R.string.t_machines_identity_repin_body),
            confirmLabel = stringResource(R.string.t_machines_identity_repin),
            onDismiss = { repinning = false },
            onConfirm = {
                viewModel.repin()
                toaster.show(res.getString(R.string.t_machines_identity_repin_done))
            },
        )
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow(title)
        if (hint != null) Text(hint, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
        content()
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, placeholder: String? = null, error: String? = null, tag: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = fieldColors(),
        modifier = modifier.let { if (tag != null) it.testTag(tag) else it },
    )
}

@Composable
private fun AddressEditor(index: Int, address: DraftAddress, all: List<DraftAddress>, state: MachineFormState, viewModel: MachineFormViewModel) {
    val error = state.errors.byAddress[address.key]
    val kind = address.host.takeIf { it.isNotBlank() }?.let { AddressKind.detect(dev.hfps.hoard.app.route.parseHostInput(it)?.host ?: it) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Field(
                value = address.host,
                onChange = { v -> viewModel.setAddresses(all.map { if (it.key == address.key) it.copy(host = v) else it }, manual = true) },
                label = stringResource(R.string.t_machines_form_host_label, (index + 1).toString()),
                placeholder = stringResource(R.string.t_machines_form_host_placeholder),
                error = when (error) {
                    FormError.Host -> stringResource(R.string.t_machines_form_errors_host)
                    FormError.Port -> stringResource(R.string.t_machines_form_errors_port)
                    FormError.Duplicate -> stringResource(R.string.t_machines_form_errors_duplicate)
                    else -> null
                },
                tag = "machine-host-$index",
                modifier = Modifier.weight(1f),
            )
            Field(
                value = address.port,
                onChange = { v -> viewModel.setAddresses(all.map { if (it.key == address.key) it.copy(port = v.filter(Char::isDigit).take(5)) else it }, manual = true) },
                label = "445",
                keyboard = KeyboardType.Number,
                tag = "machine-port-$index",
                modifier = Modifier.width(88.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (kind != null) {
                Text(stringResource(addressKindText(kind)), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground, modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            HfpsIconButton(PhosphorRegular.ArrowUp, stringResource(R.string.t_common_move_up, address.host), {
                viewModel.setAddresses(all.toMutableList().apply { add(index - 1, removeAt(index)) }, manual = true)
            }, enabled = index > 0)
            HfpsIconButton(PhosphorRegular.ArrowDown, stringResource(R.string.t_common_move_down, address.host), {
                viewModel.setAddresses(all.toMutableList().apply { add(index + 1, removeAt(index)) }, manual = true)
            }, enabled = index < all.lastIndex)
            HfpsIconButton(PhosphorRegular.Trash, stringResource(R.string.t_machines_form_remove_address, address.host.ifBlank { stringResource(R.string.t_machines_form_empty_address) }), {
                viewModel.setAddresses(all.filterNot { it.key == address.key }.ifEmpty { listOf(DraftAddress()) }, manual = true)
            })
        }
    }
}

@Composable
private fun TestResults(results: List<AddressTestResult>) {
    val working = results.count { it.connect?.ok == true }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (working == 0) stringResource(R.string.t_machines_test_summary_none)
            else pluralStringResource(R.plurals.t_machines_test_summary_ok, results.size, working.toString(), results.size.toString()),
            style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium),
            color = if (working == 0) HfpsTheme.colors.destructive else HfpsTheme.colors.cardForeground,
        )
        results.forEach { r ->
            val connect = r.connect
            val text = when {
                !r.probe.reachable -> stringResource(problemText(r.probe.reason ?: dev.hfps.hoard.smb.ErrorCode.UNREACHABLE))
                connect == null -> stringResource(R.string.t_route_reachable, (r.probe.latencyMs ?: 0).toString())
                connect.ok -> listOfNotNull(
                    stringResource(R.string.t_route_latency, (r.probe.latencyMs ?: 0).toString()),
                    connect.serverName?.let { stringResource(R.string.t_machines_test_signed_in_to, it) } ?: stringResource(R.string.t_machines_test_signed_in),
                    if (connect.guid == "match") stringResource(R.string.t_machines_test_same_server) else null,
                ).joinToString(" · ")
                connect.guid == "mismatch" -> stringResource(R.string.t_machines_test_other_server)
                else -> stringResource(problemText(connect.code ?: dev.hfps.hoard.smb.ErrorCode.UNKNOWN))
            }
            val ok = connect?.ok == true
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (ok) PhosphorRegular.CheckCircle else PhosphorRegular.WarningCircle, null, Modifier.size(18.dp), tint = if (ok) HfpsTheme.colors.primary else HfpsTheme.colors.destructive)
                Column {
                    Text("${stringResource(addressKindText(r.kind))} · ${r.host}:${r.port}", style = HfpsTheme.typography.mono, color = HfpsTheme.colors.cardForeground)
                    Text(text, style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
                }
            }
        }
    }
}

@Composable
private fun Discovery(state: MachineFormState, viewModel: MachineFormViewModel) {
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.discover() }
    Section(stringResource(R.string.t_machines_discover_title), stringResource(R.string.t_machines_discover_hint)) {
        if (state.discovering) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HfpsTheme.colors.primary)
                Text(stringResource(R.string.t_machines_discover_searching), style = HfpsTheme.typography.ui, color = HfpsTheme.colors.mutedForeground)
            }
        }
        state.discovered.orEmpty().forEach { service ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(HfpsTheme.colors.muted.copy(alpha = 0.5f)).clickable { viewModel.apply(service) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(PhosphorRegular.Broadcast, null, Modifier.size(20.dp), tint = HfpsTheme.colors.primary)
                Column {
                    Text(service.name, style = HfpsTheme.typography.body.copy(fontWeight = FontWeight.Medium), color = HfpsTheme.colors.cardForeground)
                    Text("${service.host}:${service.port}", style = HfpsTheme.typography.mono, color = HfpsTheme.colors.mutedForeground)
                }
            }
        }
    }
}
