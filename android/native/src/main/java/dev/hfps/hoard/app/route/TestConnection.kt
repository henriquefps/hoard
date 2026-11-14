package dev.hfps.hoard.app.route

import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One address of "Test connection": its probe, and the sign-in when it answered. */
data class AddressTestResult(
    val host: String,
    val port: Int,
    val kind: AddressKind,
    val probe: ProbeResult,
    val connect: ConnectTest? = null,
)

/** `guid`: `match` (same server as the pinned or first one), `first` (the reference), `mismatch` (another server). */
data class ConnectTest(val ok: Boolean, val code: ErrorCode? = null, val serverName: String? = null, val serverGuid: String? = null, val guid: String? = null)

/**
 * The machine form's "Test connection" (src/features/machines/address.ts `testConnection`): probe every address in
 * parallel, then sign in to each one that answered, in priority order, with the pinned GUID (or, before pinning, the
 * GUID of the first server that accepted the login). Reports every address, unlike the route engine which stops at
 * the first that works. Sessions are closed again; nothing is pinned.
 */
suspend fun testConnection(
    smb: SmbForEngine,
    machineId: String,
    addresses: List<EngineAddress>,
    pinnedGuid: String?,
    timeoutMs: Long,
    connectTimeoutMs: Long = 5_000,
): List<AddressTestResult> {
    val probed = coroutineScope {
        addresses.map { address ->
            async {
                val port = address.port ?: 445
                val probe = try {
                    smb.probe(address.host, port, timeoutMs)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    ProbeResult(false, reason = ErrorCode.UNREACHABLE)
                }
                AddressTestResult(address.host, port, AddressKind.detect(address.host), probe)
            }
        }.awaitAll()
    }
    var reference = pinnedGuid
    return probed.map { result ->
        if (!result.probe.reachable) return@map result
        try {
            val session = smb.connect(machineId, result.host, result.port, reference, connectTimeoutMs)
            val test = ConnectTest(true, serverName = session.serverName, serverGuid = session.serverGuid, guid = if (reference != null) "match" else "first")
            if (reference == null) reference = session.serverGuid
            runCatching { smb.disconnect(session.sessionId) }
            result.copy(connect = test)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val code = errorCodeOf(e)
            result.copy(
                connect = ConnectTest(
                    ok = false,
                    code = code,
                    guid = if (code == ErrorCode.GUID_MISMATCH) "mismatch" else null,
                    serverGuid = (e as? HoardSmbException)?.data?.get("serverGuid") as? String,
                ),
            )
        }
    }
}
