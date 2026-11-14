package dev.hfps.hoard.app.smb

import android.content.Context
import dev.hfps.hoard.app.route.ConnectResult
import dev.hfps.hoard.app.route.ProbeResult
import dev.hfps.hoard.app.route.SmbForEngine
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.Prober
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbSession
import dev.hfps.hoard.smb.SmbSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * The SMB side of the app over :core:smb, what `HoardSmbPlugin`'s probe / connect / disconnect / credentials methods
 * do for the Capacitor app: TCP probes, smbj sessions (one registry per process), and the Keystore credentials
 * (`CredentialStore`, alias `dev.hfps.hoard.credentials.v1`, the same passwords after the swap).
 */
class SmbGateway(context: Context) : SmbForEngine {
    val credentials = CredentialStore(context.applicationContext)
    val sessions = SmbSessions()

    override suspend fun probe(host: String, port: Int, timeoutMs: Long): ProbeResult {
        val result = Prober.probe(host, port, timeoutMs)
        return ProbeResult(result.reachable, result.latencyMs, result.reason)
    }

    override suspend fun connect(machineId: String, host: String, port: Int, expectedServerGuid: String?, timeoutMs: Long): ConnectResult {
        val handle = SmbConnector.open(host, port, expectedServerGuid, timeoutMs) { credentials.get(machineId) }
        val session = sessions.add(machineId, handle)
        return ConnectResult(session.id, handle.serverGuid, handle.serverName, handle.dialect)
    }

    override fun disconnect(sessionId: String) = sessions.remove(sessionId)

    /**
     * Blocking SMB work on a session, never interrupted (smbj closes the connection when a thread is interrupted
     * mid-request); long loops check [Cancellation]. A dead session rejects NOT_FOUND, which the route engine turns
     * into one reconnect.
     */
    suspend fun <T> onSession(sessionId: String, block: (SmbSession, Cancellation) -> T): T = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()[Job]
        val cancellation = Cancellation { job?.isActive == false }
        sessions.use(sessionId) { block(it, cancellation) }
    }

    suspend fun setCredentials(machineId: String, username: String, password: String, domain: String?) =
        withContext(Dispatchers.IO) { credentials.set(machineId, username, password, domain?.takeIf { it.isNotBlank() }) }

    suspend fun hasCredentials(machineId: String): Boolean = withContext(Dispatchers.IO) { credentials.has(machineId) }

    suspend fun deleteCredentials(machineId: String) = withContext(Dispatchers.IO) { credentials.delete(machineId) }
}
