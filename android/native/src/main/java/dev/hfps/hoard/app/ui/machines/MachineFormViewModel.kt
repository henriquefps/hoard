package dev.hfps.hoard.app.ui.machines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.hfps.hoard.app.AppContainer
import dev.hfps.hoard.app.route.AddressKind
import dev.hfps.hoard.app.route.AddressTestResult
import dev.hfps.hoard.app.route.EngineAddress
import dev.hfps.hoard.app.route.SmbForEngine
import dev.hfps.hoard.app.route.isValidPort
import dev.hfps.hoard.app.route.parseHostInput
import dev.hfps.hoard.app.route.testConnection
import dev.hfps.hoard.data.MachineAddressEntity
import dev.hfps.hoard.data.MachineEntity
import dev.hfps.hoard.data.MachineWithAddresses
import dev.hfps.hoard.smb.DiscoveredService
import dev.hfps.hoard.smb.NsdDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

data class DraftAddress(val key: String = UUID.randomUUID().toString(), val host: String = "", val port: String = "", val label: String? = null)

data class MachineDraft(
    val name: String = "",
    val icon: String = "desktop-tower",
    val color: String? = "blue",
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val addresses: List<DraftAddress> = listOf(DraftAddress()),
)

enum class FormError { Name, Username, Password, PasswordForUser, Addresses, Host, Port, Duplicate }

data class FormErrors(
    val name: FormError? = null,
    val username: FormError? = null,
    val password: FormError? = null,
    val addresses: FormError? = null,
    val byAddress: Map<String, FormError> = emptyMap(),
) {
    val ok: Boolean get() = name == null && username == null && password == null && addresses == null && byAddress.isEmpty()
}

data class MachineFormState(
    val isNew: Boolean,
    val id: String,
    /** Edit: the row (null while loading); [missing] when it does not exist. */
    val loaded: MachineWithAddresses? = null,
    val missing: Boolean = false,
    val draft: MachineDraft = MachineDraft(),
    /** Addresses keep the user's order once they reorder them (new machines sort local first until then). */
    val manualOrder: Boolean = false,
    val hasPassword: Boolean = false,
    val errors: FormErrors = FormErrors(),
    val saving: Boolean = false,
    val testing: Boolean = false,
    val results: List<AddressTestResult>? = null,
    val discovering: Boolean = false,
    val discovered: List<DiscoveredService>? = null,
    /** The pinned server GUID as stored now. */
    val pinnedGuid: String? = null,
)

/**
 * `/machines/new` and `/machines/$id/edit` (Hoard 1's MachineFormPage): name, icon, colour, sign-in (the password goes
 * to the Keystore only), addresses in priority order, discovery, "Test connection", the server identity and re-pin.
 */
class MachineFormViewModel(
    private val container: AppContainer,
    machineId: String?,
    private val smb: SmbForEngine = container.gateway,
) : ViewModel() {
    private val _state = MutableStateFlow(MachineFormState(isNew = machineId == null, id = machineId ?: UUID.randomUUID().toString(), manualOrder = machineId != null))
    val state: StateFlow<MachineFormState> = _state.asStateFlow()
    private var credentialsStored = false
    private var saved = false

    init {
        val id = _state.value.id
        if (machineId != null) {
            viewModelScope.launch {
                val machine = container.database.machines().get(id)
                _state.update { s ->
                    if (machine == null) s.copy(missing = true) else s.copy(loaded = machine, draft = draftOf(machine), pinnedGuid = machine.machine.serverGuid)
                }
                val has = container.gateway.hasCredentials(id)
                _state.update { it.copy(hasPassword = has) }
            }
            viewModelScope.launch {
                container.database.machines().observe(id).collect { m -> _state.update { it.copy(pinnedGuid = m?.machine?.serverGuid) } }
            }
        }
    }

    fun update(transform: (MachineDraft) -> MachineDraft) = _state.update { it.copy(draft = transform(it.draft), results = null) }

    fun setAddresses(addresses: List<DraftAddress>, manual: Boolean) = _state.update {
        val ordered = if (manual || it.manualOrder) addresses else AddressKind.sortByKind(addresses) { a -> a.host }
        it.copy(draft = it.draft.copy(addresses = ordered), manualOrder = it.manualOrder || manual, results = null)
    }

    fun sortByKind() = _state.update { it.copy(draft = it.draft.copy(addresses = AddressKind.sortByKind(it.draft.addresses) { a -> a.host })) }

    /** Validates the draft; returns the addresses to save (host and port, in order) when it is fine. */
    fun validate(): List<EngineAddress>? {
        val s = _state.value
        val d = s.draft
        val byAddress = HashMap<String, FormError>()
        val addresses = ArrayList<EngineAddress>()
        val seen = HashSet<String>()
        for (address in d.addresses) {
            if (address.host.isBlank() && address.port.isBlank()) continue
            val parsed = parseHostInput(address.host)
            val port = address.port.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() ?: if (address.port.isBlank()) parsed?.port else -1
            when {
                parsed == null -> byAddress[address.key] = FormError.Host
                port != null && !isValidPort(port) -> byAddress[address.key] = FormError.Port
                else -> {
                    val key = parsed.host.lowercase() + ":" + (port ?: 445)
                    if (!seen.add(key)) byAddress[address.key] = FormError.Duplicate
                    addresses += EngineAddress(parsed.host, port)
                }
            }
        }
        val loaded = s.loaded
        val userChanged = loaded != null && (loaded.machine.username != d.username.trim() || (loaded.machine.domain ?: "") != d.domain.trim())
        val errors = FormErrors(
            name = FormError.Name.takeIf { d.name.isBlank() },
            username = FormError.Username.takeIf { d.username.isBlank() },
            password = when {
                d.password.isEmpty() && (s.isNew || !s.hasPassword) -> FormError.Password
                d.password.isEmpty() && userChanged -> FormError.PasswordForUser
                else -> null
            },
            addresses = FormError.Addresses.takeIf { addresses.isEmpty() && byAddress.isEmpty() },
            byAddress = byAddress,
        )
        _state.update { it.copy(errors = errors) }
        return if (errors.ok) addresses else null
    }

    private suspend fun storeCredentials() {
        val d = _state.value.draft
        if (d.password.isEmpty()) return
        container.gateway.setCredentials(_state.value.id, d.username.trim(), d.password, d.domain.trim())
        credentialsStored = true
        _state.update { it.copy(hasPassword = true) }
    }

    /** Saves the machine and its password; false when the form has errors or the write failed. */
    suspend fun save(): Boolean {
        val addresses = validate() ?: return false
        val s = _state.value
        val d = s.draft
        _state.update { it.copy(saving = true) }
        return try {
            val entity = MachineEntity(
                id = s.id,
                name = d.name.trim(),
                icon = d.icon,
                color = d.color,
                username = d.username.trim(),
                domain = d.domain.trim().ifEmpty { null },
                serverGuid = s.loaded?.machine?.serverGuid,
                sortOrder = s.loaded?.machine?.sortOrder ?: 0,
                createdAt = s.loaded?.machine?.createdAt ?: Instant.now().toString(),
            )
            val rows = addresses.mapIndexed { index, a ->
                MachineAddressEntity(UUID.randomUUID().toString(), s.id, a.host.trim(), a.port ?: 445, AddressKind.detect(a.host).id, null, index)
            }
            container.database.machines().save(entity, rows, create = s.isNew)
            storeCredentials()
            saved = true
            container.routes.forget(s.id)
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            _state.update { it.copy(saving = false) }
        }
    }

    fun test() {
        val addresses = validateForTest() ?: return
        _state.update { it.copy(testing = true, results = null) }
        viewModelScope.launch {
            try {
                val d = _state.value.draft
                if (d.password.isNotEmpty() && d.username.isNotBlank()) storeCredentials()
                val timeout = container.appSettings.files.first().probeTimeoutMs
                val results = testConnection(smb, _state.value.id, addresses, _state.value.pinnedGuid, timeout)
                _state.update { it.copy(results = results) }
            } finally {
                _state.update { it.copy(testing = false) }
            }
        }
    }

    private fun validateForTest(): List<EngineAddress>? {
        validate()
        return _state.value.draft.addresses.mapNotNull { a ->
            val parsed = parseHostInput(a.host) ?: return@mapNotNull null
            EngineAddress(parsed.host, a.port.trim().toIntOrNull() ?: parsed.port)
        }.distinctBy { it.host.lowercase() + ":" + (it.port ?: 445) }.ifEmpty { null }
    }

    /** SMB servers announced on this network (mDNS `_smb._tcp`), new machines only. */
    fun discover() {
        if (_state.value.discovering) return
        _state.update { it.copy(discovering = true) }
        viewModelScope.launch {
            val found = runCatching { withContext(Dispatchers.IO) { NsdDiscovery(container.appContext).discover(3_000) } }.getOrDefault(emptyList())
            _state.update { it.copy(discovering = false, discovered = found) }
        }
    }

    /** Fills the form from a discovered server (its name when none was typed, its address if new). */
    fun apply(service: DiscoveredService) = _state.update { s ->
        val d = s.draft
        val exists = d.addresses.any { it.host.trim().equals(service.host, ignoreCase = true) }
        val filled = d.addresses.filter { it.host.isNotBlank() || it.port.isNotBlank() }
        val next = if (exists) d.addresses else filled + DraftAddress(host = service.host, port = if (service.port == 445) "" else service.port.toString())
        s.copy(
            draft = d.copy(name = d.name.ifBlank { service.name }, addresses = if (s.manualOrder) next else AddressKind.sortByKind(next) { it.host }),
            results = null,
        )
    }

    /** "Re-pin": forget the server identity; the next connection pins it again. */
    suspend fun repin() {
        container.database.machines().setServerGuid(_state.value.id, null)
        container.routes.forget(_state.value.id)
    }

    /** Deletes the machine with its favourites, shares, transfers and culling rows, and its password. */
    suspend fun delete() {
        val id = _state.value.id
        container.database.machines().deleteMachine(id)
        container.gateway.deleteCredentials(id)
        container.routes.forget(id)
        saved = true
    }

    override fun onCleared() {
        // A new machine whose password "Test connection" stored but which was never saved: forget it.
        if (_state.value.isNew && credentialsStored && !saved) {
            val id = _state.value.id
            container.scope.launch { runCatching { container.gateway.deleteCredentials(id) } }
        }
    }

    companion object {
        fun factory(container: AppContainer, machineId: String?): ViewModelProvider.Factory =
            viewModelFactory { initializer { MachineFormViewModel(container, machineId) } }

        fun draftOf(machine: MachineWithAddresses) = MachineDraft(
            name = machine.machine.name,
            icon = machine.machine.icon ?: "hard-drives",
            color = machine.machine.color,
            username = machine.machine.username,
            password = "",
            domain = machine.machine.domain.orEmpty(),
            addresses = machine.ordered.map { DraftAddress(it.id, it.host, if (it.port == 445) "" else it.port.toString(), it.label) },
        )
    }
}
