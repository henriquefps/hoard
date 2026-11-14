package dev.hfps.hoard.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A stored login, decrypted only for the duration of a connect. */
class SmbCredentials(val username: String, val password: CharArray, val domain: String?) {
    fun wipe() = password.fill('\u0000')
}

/** An authenticated SMB session plus the client/connection that own it. Closing tears all down. */
class SmbHandle(
    val client: SMBClient,
    val connection: Connection,
    val session: Session,
    val host: String,
    val port: Int,
    val serverGuid: String,
    val serverName: String?,
    val dialect: String,
) : Closeable {
    val isAlive: Boolean get() = connection.isConnected

    override fun close() {
        // LOGOFF only on a live connection; on a dead one it would wait out the request timeout.
        if (isAlive) runCatching { session.close() }
        runCatching { connection.close(true) }
        runCatching { client.close() }
    }
}

/**
 * Opens SMB sessions in the order the contract requires:
 * TCP → NEGOTIATE → compare the server GUID with the pinned one → SESSION_SETUP.
 * The password is only handed to smbj after the GUID matched.
 */
object SmbConnector {

    /**
     * Per-request timeout once connected (directory listings, reads, RPC). A server that stays
     * silent this long is treated as gone, so the app reconnects over another route; 20 s still
     * covers a NAS spinning its disks up.
     */
    const val OPERATION_TIMEOUT_MS = 20_000L

    /** Read size per SMB2 READ; servers cap it to their MaxReadSize. */
    const val READ_BUFFER_SIZE = 1 shl 20

    /** Lowercase canonical form of a GUID string (`{ABC-...}` → `abc-...`), or null if blank. */
    fun normalizeGuid(guid: String?): String? =
        guid?.trim()?.removePrefix("{")?.removeSuffix("}")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    fun guidString(uuid: UUID): String = uuid.toString().lowercase(Locale.ROOT)

    /** Contract dialect name for smbj's enum. */
    fun dialectName(dialect: SMB2Dialect?): String = when (dialect) {
        SMB2Dialect.SMB_2_1 -> "SMB_2_1"
        SMB2Dialect.SMB_3_0 -> "SMB_3_0"
        SMB2Dialect.SMB_3_0_2 -> "SMB_3_0_2"
        SMB2Dialect.SMB_3_1_1 -> "SMB_3_1_1"
        else -> "SMB_2_0_2"
    }

    /** `DOMAIN\user` in the username wins over an empty domain field. */
    fun splitUser(username: String, domain: String?): Pair<String, String?> {
        val cleanDomain = domain?.trim()?.takeIf { it.isNotEmpty() }
        val backslash = username.indexOf('\\')
        if (cleanDomain == null && backslash > 0) {
            return username.substring(backslash + 1) to username.substring(0, backslash)
        }
        return username.trim() to cleanDomain
    }

    private fun config(addresses: List<java.net.InetAddress>, connectTimeoutMs: Long): SmbConfig =
        SmbConfig.builder()
            .withSocketFactory(Net.ResolvedSocketFactory(addresses, connectTimeoutMs))
            .withTimeout(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            // 0 = no socket read timeout: the packet reader thread idles between requests.
            .withSoTimeout(0)
            .withBufferSize(READ_BUFFER_SIZE)
            .withDfsEnabled(false)
            .withMultiProtocolNegotiate(false)
            .withSigningEnabled(true)
            .withEncryptData(false)
            .build()

    /**
     * Connect and authenticate within [timeoutMs] in total. [credentials] are only read after the
     * GUID check and wiped afterwards by the caller.
     *
     * @throws HoardSmbException UNREACHABLE, TIMEOUT, GUID_MISMATCH (with `data.serverGuid`), AUTH_FAILED…
     */
    suspend fun open(
        host: String,
        port: Int,
        expectedServerGuid: String?,
        timeoutMs: Long,
        credentials: () -> SmbCredentials?,
    ): SmbHandle {
        val started = System.nanoTime()
        val addresses = try {
            Net.resolve(host, timeoutMs)
        } catch (e: Exception) {
            throw SmbErrors.map(e, "$host")
        }
        val remaining = timeoutMs - (System.nanoTime() - started) / 1_000_000
        if (remaining <= 0) smbFail(ErrorCode.TIMEOUT, "$host:$port did not answer in time")

        var client: SMBClient? = null
        try {
            return withTimeout(remaining) {
                runInterruptible(Dispatchers.IO) {
                    val smb = SMBClient(config(addresses, remaining))
                    client = smb
                    // TCP + NEGOTIATE (smbj negotiates inside connect).
                    val connection = smb.connect(host, port)
                    val context = connection.connectionContext
                    val serverGuid = context.serverGuid?.let(::guidString)
                        ?: smbFail(ErrorCode.UNKNOWN, "$host did not send a server GUID")
                    val expected = normalizeGuid(expectedServerGuid)
                    if (expected != null && expected != serverGuid) {
                        throw HoardSmbException(
                            ErrorCode.GUID_MISMATCH,
                            "$host:$port is a different server (GUID $serverGuid)",
                            data = mapOf("serverGuid" to serverGuid),
                        )
                    }
                    // SESSION_SETUP, only now with the password.
                    val stored = credentials()
                        ?: smbFail(ErrorCode.AUTH_FAILED, "No credentials stored for this machine")
                    val session = try {
                        val (user, domain) = splitUser(stored.username, stored.domain)
                        connection.authenticate(AuthenticationContext(user, stored.password, domain))
                    } finally {
                        stored.wipe()
                    }
                    if (session.isGuest && !stored.username.equals("guest", ignoreCase = true)) {
                        // The server silently mapped a bad login to Guest (Samba "map to guest").
                        runCatching { session.close() }
                        smbFail(ErrorCode.AUTH_FAILED, "The server rejected the username or password (guest fallback)")
                    }
                    SmbHandle(
                        client = smb,
                        connection = connection,
                        session = session,
                        host = host,
                        port = port,
                        serverGuid = serverGuid,
                        serverName = context.netBiosName?.takeIf { it.isNotBlank() },
                        dialect = dialectName(context.negotiatedProtocol?.dialect),
                    )
                }
            }
        } catch (e: Throwable) {
            runCatching { client?.close() }
            // withTimeout interrupts smbj, which may surface its own wrapped InterruptedException
            // instead of TimeoutCancellationException: past the deadline, that is a timeout too.
            val pastDeadline = System.nanoTime() - started >= (timeoutMs - 50) * 1_000_000
            if (e is kotlinx.coroutines.TimeoutCancellationException || (pastDeadline && SmbErrors.isInterruption(e))) {
                throw HoardSmbException(ErrorCode.TIMEOUT, "$host:$port did not complete the SMB handshake in ${timeoutMs} ms", e)
            }
            // The caller was cancelled: keep structured concurrency intact.
            if (e is java.util.concurrent.CancellationException) throw e
            throw SmbErrors.map(e, "$host:$port")
        }
    }
}
