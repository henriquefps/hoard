package dev.hfps.hoard.app.route

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * The reachability engine (ADR 0002), a Kotlin port of `src/features/route/engine.ts` with the same rules and the
 * same tests (RouteEngineTest):
 *
 * Per machine: probe every address in parallel (pickRoute), connect to the winner with the pinned
 * `expectedServerGuid`, and on GUID_MISMATCH (somebody else's server on that address) or a connect-time
 * UNREACHABLE / TIMEOUT move on to the next reachable address. The first successful connect of a machine without a
 * GUID pins it (saveGuid). The session is cached per machine and reused by every file call (withSession), which
 * reconnects once when the native side says the session is gone (NOT_FOUND). recheckAll (app resume, network change)
 * drops the sessions and checks again every machine the app has looked at.
 *
 * Threading: the engine is confined to its scope's dispatcher (the main thread in the app, the test dispatcher in
 * tests); the SMB calls switch to IO themselves.
 */

data class EngineAddress(val host: String, val port: Int? = null, val kind: AddressKind? = null)

data class EngineMachine(
    val id: String,
    val serverGuid: String?,
    /** In priority order. */
    val addresses: List<EngineAddress>,
)

enum class RouteStatus { Idle, Checking, Online, Offline, Error }

/** `probe`: TCP reachability of one address. */
data class ProbeResult(val reachable: Boolean, val latencyMs: Long? = null, val reason: ErrorCode? = null)

data class ConnectResult(val sessionId: String, val serverGuid: String, val serverName: String? = null, val dialect: String = "")

/** What happened with one address during the last check. */
data class AddressAttempt(
    val host: String,
    val port: Int,
    val kind: AddressKind,
    /** Probe answer; null while pending or when the decision came before it. */
    val probe: ProbeResult? = null,
    /** Connect outcome when one was tried: "ok" or the error code's name. */
    val connect: String? = null,
)

data class ActiveRoute(val host: String, val port: Int, val kind: AddressKind, val latencyMs: Long? = null)

data class RouteState(
    val status: RouteStatus,
    /** The address in use (online), or the one that answered before an auth error. */
    val route: ActiveRoute? = null,
    /** Set when offline (UNREACHABLE / TIMEOUT) or error (AUTH_FAILED, GUID_MISMATCH…). */
    val errorCode: ErrorCode? = null,
    val errorMessage: String? = null,
    /** The GUID the mismatching server reported, for the "re-pin" explanation. */
    val foreignGuid: String? = null,
    val serverName: String? = null,
    val attempts: List<AddressAttempt> = emptyList(),
    /** Epoch ms of the last finished check. */
    val checkedAt: Long? = null,
) {
    companion object {
        val IDLE = RouteState(RouteStatus.Idle)
    }
}

/** The SMB calls the engine needs ([dev.hfps.hoard.app.smb.SmbGateway] in the app, a fake in tests). */
interface SmbForEngine {
    suspend fun probe(host: String, port: Int, timeoutMs: Long): ProbeResult

    /** Throws [HoardSmbException] (GUID_MISMATCH carries `data["serverGuid"]`). */
    suspend fun connect(machineId: String, host: String, port: Int, expectedServerGuid: String?, timeoutMs: Long): ConnectResult

    fun disconnect(sessionId: String)
}

/** A route failure carried like a plugin rejection: [code] and the [state] (attempts) behind it. */
class RouteException(val state: RouteState) : Exception(state.errorMessage ?: (state.errorCode ?: ErrorCode.UNREACHABLE).name) {
    val code: ErrorCode get() = state.errorCode ?: ErrorCode.UNREACHABLE
}

/** The contract code of any failure the file and route calls throw. */
fun errorCodeOf(error: Throwable): ErrorCode = when (error) {
    is HoardSmbException -> error.code
    is RouteException -> error.code
    is CancellationException -> ErrorCode.CANCELLED
    else -> ErrorCode.UNKNOWN
}

data class EngineSession(val sessionId: String, val route: ActiveRoute)

private fun EngineAddress.portOrDefault() = port ?: 445

/**
 * Probe outcome in priority order (ADR 0002): the first reachable address wins as soon as every higher-priority
 * one has answered unreachable, so a reachable first address wins in its own RTT and a slow dead Tailscale probe
 * never delays a working local one. The probes are already running in parallel ([probes] are started deferreds).
 */
suspend fun <T> pickRoute(candidates: List<T>, probes: (T) -> Deferred<ProbeResult>): Pair<T, ProbeResult>? {
    val started = candidates.map { it to probes(it) }
    for ((candidate, probe) in started) {
        val result = probe.await()
        if (result.reachable) return candidate to result
    }
    return null
}

class RouteEngine(
    private val scope: CoroutineScope,
    private val smb: SmbForEngine,
    /** Persist the GUID pinned on the first connect (`machines.server_guid`). */
    private val saveGuid: suspend (machineId: String, guid: String) -> Unit,
    /** Probe timeout in ms (a setting), read on every check. */
    private val probeTimeoutMs: () -> Long = { 1500L },
    private val connectTimeoutMs: Long = 5_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _states = MutableStateFlow<Map<String, RouteState>>(emptyMap())
    val states: StateFlow<Map<String, RouteState>> = _states.asStateFlow()

    private val sessions = HashMap<String, EngineSession>()
    private val inflight = HashMap<String, Deferred<RouteState>>()

    /** Last machine seen per id (for rechecks), with any GUID pinned since. */
    private val machines = LinkedHashMap<String, EngineMachine>()
    private val generation = HashMap<String, Int>()
    private var recheckJob: Job? = null

    fun state(machineId: String): RouteState = _states.value[machineId] ?: RouteState.IDLE

    fun observe(machineId: String): Flow<RouteState> =
        states.map { it[machineId] ?: RouteState.IDLE }.distinctUntilChanged()

    private fun set(machineId: String, state: RouteState) {
        _states.update { it + (machineId to state) }
    }

    /** Remember the latest machine data; a GUID pinned by this engine wins over a stale row the caller still holds. */
    private fun remember(machine: EngineMachine): EngineMachine {
        val known = machines[machine.id]
        val merged = machine.copy(serverGuid = machine.serverGuid ?: known?.serverGuid)
        machines[machine.id] = merged
        return merged
    }

    /**
     * Find the route and open a session. Concurrent calls share one check; [force] starts a new one even when a
     * session exists (a stale in-flight check is then ignored).
     */
    fun check(machine: EngineMachine, force: Boolean = false): Deferred<RouteState> {
        val current = remember(machine)
        inflight[machine.id]?.takeIf { it.isActive }?.let { if (!force) return it }
        val state = _states.value[machine.id]
        if (!force && state?.status == RouteStatus.Online && sessions.containsKey(machine.id)) {
            return scope.async { state }
        }
        val next = (generation[machine.id] ?: 0) + 1
        generation[machine.id] = next
        val deferred = scope.async { run(current, next) }
        inflight[machine.id] = deferred
        deferred.invokeOnCompletion { if (inflight[machine.id] === deferred) inflight.remove(machine.id) }
        return deferred
    }

    /** Check only when nothing is known yet. */
    fun ensure(machine: EngineMachine): Deferred<RouteState> {
        val state = state(machine.id)
        if (state.status == RouteStatus.Idle) return check(machine)
        remember(machine)
        return inflight[machine.id]?.takeIf { it.isActive } ?: scope.async { state }
    }

    private suspend fun run(machine: EngineMachine, gen: Int): RouteState {
        val stale = { generation[machine.id] != gen }
        val previous = _states.value[machine.id]
        sessions.remove(machine.id)?.let { old -> runCatching { smb.disconnect(old.sessionId) } }
        val addresses = machine.addresses.map {
            ActiveRoute(it.host, it.portOrDefault(), it.kind ?: AddressKind.detect(it.host))
        }
        val attempts = addresses.map { AddressAttempt(it.host, it.port, it.kind) }.toMutableList()
        // Keep showing the last known route while checking (the line says "checking").
        set(machine.id, (previous ?: RouteState.IDLE).copy(status = RouteStatus.Checking, attempts = attempts.toList()))

        val timeoutMs = probeTimeoutMs()
        val probes = HashMap<Int, Deferred<ProbeResult>>()
        fun probeAt(index: Int): Deferred<ProbeResult> = probes.getOrPut(index) {
            val address = addresses[index]
            scope.async {
                val result = try {
                    smb.probe(address.host, address.port, timeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    ProbeResult(false, reason = ErrorCode.UNREACHABLE)
                }
                attempts[index] = attempts[index].copy(probe = result)
                result
            }
        }

        var guid = machine.serverGuid
        var lastCode: ErrorCode? = null
        var lastMessage: String? = null
        var lastForeign: String? = null
        var start = 0
        while (start < addresses.size) {
            val winner = pickRoute((start until addresses.size).toList()) { probeAt(it) }
            if (stale()) return state(machine.id)
            if (winner == null) break
            val (index, probe) = winner
            val address = addresses[index]
            val route = address.copy(latencyMs = probe.latencyMs)
            val result = try {
                smb.connect(machine.id, address.host, address.port, guid, connectTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (stale()) return state(machine.id)
                val code = errorCodeOf(e)
                attempts[index] = attempts[index].copy(connect = code.name)
                lastCode = code
                lastMessage = e.message
                lastForeign = (e as? HoardSmbException)?.data?.get("serverGuid") as? String
                if (code == ErrorCode.GUID_MISMATCH || code == ErrorCode.UNREACHABLE || code == ErrorCode.TIMEOUT) {
                    start = index + 1
                    continue
                }
                // AUTH_FAILED and anything else is not an address problem: failing over would not help.
                return finish(
                    machine.id,
                    RouteState(RouteStatus.Error, route = route, errorCode = code, errorMessage = e.message, attempts = attempts.toList()),
                )
            }
            if (stale()) {
                runCatching { smb.disconnect(result.sessionId) }
                return state(machine.id)
            }
            attempts[index] = attempts[index].copy(connect = "ok")
            if (guid == null) {
                guid = result.serverGuid.lowercase()
                machines[machine.id] = machine.copy(serverGuid = guid)
                runCatching { saveGuid(machine.id, guid) }
            }
            sessions[machine.id] = EngineSession(result.sessionId, route)
            return finish(
                machine.id,
                RouteState(RouteStatus.Online, route = route, serverName = result.serverName, attempts = attempts.toList()),
            )
        }

        if (stale()) return state(machine.id)
        if (lastCode == ErrorCode.GUID_MISMATCH) {
            return finish(
                machine.id,
                RouteState(
                    RouteStatus.Error,
                    errorCode = ErrorCode.GUID_MISMATCH,
                    errorMessage = lastMessage,
                    foreignGuid = lastForeign,
                    attempts = attempts.toList(),
                ),
            )
        }
        val timedOut = attempts.isNotEmpty() && attempts.all { it.probe?.reason == ErrorCode.TIMEOUT }
        return finish(
            machine.id,
            RouteState(
                RouteStatus.Offline,
                errorCode = lastCode ?: if (timedOut) ErrorCode.TIMEOUT else ErrorCode.UNREACHABLE,
                errorMessage = lastMessage,
                attempts = attempts.toList(),
            ),
        )
    }

    private fun finish(machineId: String, state: RouteState): RouteState {
        val full = state.copy(checkedAt = now())
        set(machineId, full)
        return full
    }

    // --- Sessions -----------------------------------------------------------------------------------------------

    /** An open session for the machine, checking the route first when needed. */
    suspend fun session(machine: EngineMachine): EngineSession {
        sessions[machine.id]?.let {
            remember(machine)
            return it
        }
        val state = check(machine).await()
        return sessions[machine.id] ?: throw RouteException(state)
    }

    /**
     * Run [fn] with the machine's session. When native reports NOT_FOUND (the session expired, or the path really is
     * gone) reconnect once and retry; a second NOT_FOUND is real.
     */
    suspend fun <T> withSession(machine: EngineMachine, fn: suspend (sessionId: String) -> T): T {
        val session = session(machine)
        return try {
            fn(session.sessionId)
        } catch (e: HoardSmbException) {
            if (e.code != ErrorCode.NOT_FOUND) throw e
            if (sessions[machine.id]?.sessionId == session.sessionId) sessions.remove(machine.id)
            fn(session(machine).sessionId)
        }
    }

    /** The current route, for ordering transfer addresses (current first). */
    fun activeRoute(machineId: String): ActiveRoute? = sessions[machineId]?.route ?: _states.value[machineId]?.route

    /** The machine's GUID as far as the engine knows (pinned during this run included). */
    fun knownGuid(machineId: String): String? = machines[machineId]?.serverGuid

    // --- Invalidation -------------------------------------------------------------------------------------------

    /** Forget a machine (edited, deleted, re-pinned): the next use checks from scratch. */
    fun forget(machineId: String) {
        sessions.remove(machineId)?.let { runCatching { smb.disconnect(it.sessionId) } }
        machines.remove(machineId)
        inflight.remove(machineId)
        generation[machineId] = (generation[machineId] ?: 0) + 1
        _states.update { it - machineId }
    }

    /** Recheck every machine seen so far (resume, network change). Debounced: both fire together. */
    fun recheckAll(delayMs: Long = 250) {
        recheckJob?.cancel()
        recheckJob = scope.launch {
            delay(delayMs)
            machines.values.toList().forEach { check(it, force = true) }
        }
    }

    /** Every machine id the engine has state for (tests, debugging). */
    fun knownMachines(): List<String> = machines.keys.toList()

    /**
     * What native needs to open its own connection (transfers, streaming, share out, Reveal): the pinned GUID
     * (connecting first when it is not pinned yet) and the addresses, the route in use first.
     */
    suspend fun nativeRoute(machine: EngineMachine): NativeRoute {
        var guid = machine.serverGuid ?: knownGuid(machine.id)
        if (guid == null) {
            session(machine)
            guid = knownGuid(machine.id)
        }
        if (guid == null) throw RouteException(state(machine.id))
        return NativeRoute(guid, orderForTransfer(machine.addresses, activeRoute(machine.id)))
    }
}

data class NativeRoute(val expectedServerGuid: String, val addresses: List<EngineAddress>)

/** Addresses for a transfer: the route in use first, then the rest in priority order. */
fun orderForTransfer(addresses: List<EngineAddress>, active: ActiveRoute?): List<EngineAddress> {
    if (active == null) return addresses
    val index = addresses.indexOfFirst { it.host.equals(active.host, ignoreCase = true) && it.portOrDefault() == active.port }
    if (index <= 0) return addresses
    return listOf(addresses[index]) + addresses.subList(0, index) + addresses.subList(index + 1, addresses.size)
}
