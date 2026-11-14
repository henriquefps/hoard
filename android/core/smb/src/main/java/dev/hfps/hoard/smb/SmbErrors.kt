package dev.hfps.hoard.smb

import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException

/** Converts anything smbj, the JDK or Android throws into a [HoardSmbException] with a contract code. */
object SmbErrors {

    /** NT status codes (MS-ERREF 2.3.1) and the contract code each one maps to. */
    private val STATUS_CODES: Map<Long, ErrorCode> = buildMap {
        // Credentials rejected (or account unusable): the user has to fix the login.
        for (status in listOf(
            0xC000006DL, // STATUS_LOGON_FAILURE
            0xC000006AL, // STATUS_WRONG_PASSWORD
            0xC0000064L, // STATUS_NO_SUCH_USER
            0xC000006EL, // STATUS_ACCOUNT_RESTRICTION
            0xC000006FL, // STATUS_INVALID_LOGON_HOURS
            0xC0000070L, // STATUS_INVALID_WORKSTATION
            0xC0000071L, // STATUS_PASSWORD_EXPIRED
            0xC0000072L, // STATUS_ACCOUNT_DISABLED
            0xC0000193L, // STATUS_ACCOUNT_EXPIRED
            0xC0000224L, // STATUS_PASSWORD_MUST_CHANGE
            0xC0000234L, // STATUS_ACCOUNT_LOCKED_OUT
            0xC000015BL, // STATUS_LOGON_TYPE_NOT_GRANTED
        )) put(status, ErrorCode.AUTH_FAILED)
        for (status in listOf(
            0xC0000022L, // STATUS_ACCESS_DENIED
            0xC00000CAL, // STATUS_NETWORK_ACCESS_DENIED
            0xC0000043L, // STATUS_SHARING_VIOLATION (file in use)
            0xC0000054L, // STATUS_FILE_LOCK_CONFLICT
            0xC0000056L, // STATUS_DELETE_PENDING
            0xC0000061L, // STATUS_PRIVILEGE_NOT_HELD
            0xC0000101L, // STATUS_DIRECTORY_NOT_EMPTY (contract: non-empty folder without `recursive`)
            0xC0000121L, // STATUS_CANNOT_DELETE (read-only)
            0xC00000A2L, // STATUS_MEDIA_WRITE_PROTECTED
        )) put(status, ErrorCode.ACCESS_DENIED)
        for (status in listOf(
            0xC0000034L, // STATUS_OBJECT_NAME_NOT_FOUND
            0xC000003AL, // STATUS_OBJECT_PATH_NOT_FOUND
            0xC0000033L, // STATUS_OBJECT_NAME_INVALID
            0xC000000FL, // STATUS_NO_SUCH_FILE
            0xC0000225L, // STATUS_NOT_FOUND
            0xC00000CCL, // STATUS_BAD_NETWORK_NAME (share)
            0xC00000BEL, // STATUS_BAD_NETWORK_PATH
            0xC00000C9L, // STATUS_NETWORK_NAME_DELETED (share removed)
            0xC0000103L, // STATUS_NOT_A_DIRECTORY
            0xC0000203L, // STATUS_USER_SESSION_DELETED (session expired)
            0xC000035CL, // STATUS_NETWORK_SESSION_EXPIRED
        )) put(status, ErrorCode.NOT_FOUND)
        put(0xC0000035L, ErrorCode.ALREADY_EXISTS) // STATUS_OBJECT_NAME_COLLISION
        put(0xC000007FL, ErrorCode.NO_SPACE) // STATUS_DISK_FULL
        put(0xC0000044L, ErrorCode.NO_SPACE) // STATUS_QUOTA_EXCEEDED
        put(0xC00000B5L, ErrorCode.TIMEOUT) // STATUS_IO_TIMEOUT
        put(0x00000102L, ErrorCode.TIMEOUT) // STATUS_TIMEOUT
        put(0xC0000120L, ErrorCode.CANCELLED) // STATUS_CANCELLED
        put(0xC000020CL, ErrorCode.UNREACHABLE) // STATUS_CONNECTION_DISCONNECTED
        put(0xC000020DL, ErrorCode.UNREACHABLE) // STATUS_CONNECTION_RESET
    }

    /** Statuses meaning the SMB session itself is gone (the registry drops it). */
    private val SESSION_GONE = setOf(0xC0000203L, 0xC000035CL, 0xC00000C9L)

    /** Contract code for an NT status, or null when it has no specific meaning for the UI. */
    fun codeForStatus(status: Long): ErrorCode? = STATUS_CODES[status and 0xFFFFFFFFL]

    /** True when [error] says the session/connection is dead (as opposed to a failed operation). */
    fun isSessionGone(error: Throwable): Boolean = causes(error).any {
        (it is SMBApiException && (it.statusCode and 0xFFFFFFFFL) in SESSION_GONE) ||
            (it is TransportException && it.cause !is TimeoutException) ||
            it is SocketException || it is EOFException
    }

    /** Classify [error] (walking its cause chain) into a [HoardSmbException]. */
    fun map(error: Throwable, context: String? = null): HoardSmbException {
        if (error is HoardSmbException) return error
        val code = classify(error)
        val detail = describe(error)
        val message = if (context.isNullOrBlank()) detail else "$context: $detail"
        return HoardSmbException(code, message, error)
    }

    /** The first specific classification found in the cause chain; generic I/O falls back to UNREACHABLE. */
    fun classify(error: Throwable): ErrorCode {
        val chain = causes(error)
        for (cause in chain) {
            specific(cause)?.let { return it }
        }
        return if (chain.any { it is IOException }) ErrorCode.UNREACHABLE else ErrorCode.UNKNOWN
    }

    private fun specific(error: Throwable): ErrorCode? = when (error) {
        is HoardSmbException -> error.code
        is SMBApiException -> codeForStatus(error.statusCode)
        is TimeoutCancellationException -> ErrorCode.TIMEOUT
        is SocketTimeoutException -> ErrorCode.TIMEOUT
        is TimeoutException -> ErrorCode.TIMEOUT
        is UnknownHostException -> ErrorCode.UNREACHABLE
        is ConnectException -> ErrorCode.UNREACHABLE
        is NoRouteToHostException -> ErrorCode.UNREACHABLE
        is PortUnreachableException -> ErrorCode.UNREACHABLE
        is InterruptedException -> ErrorCode.CANCELLED
        is InterruptedIOException -> ErrorCode.TIMEOUT
        is CancellationException -> ErrorCode.CANCELLED
        is IOException -> if (isNoSpace(error)) ErrorCode.NO_SPACE else null
        else -> null
    }

    /** The failure comes from a thread interrupt / coroutine cancellation somewhere in the chain. */
    fun isInterruption(error: Throwable): Boolean = causes(error).any {
        it is InterruptedException || it is CancellationException || (it is InterruptedIOException && it !is SocketTimeoutException)
    }

    /** ENOSPC from a local write (SAF / cache). Matched by message so it also works without android.system. */
    fun isNoSpace(error: Throwable): Boolean = causes(error).any {
        val message = it.message ?: return@any false
        message.contains("ENOSPC") || message.contains("No space left", ignoreCase = true)
    }

    /** A short human message: the most specific cause that has one. */
    private fun describe(error: Throwable): String {
        val chain = causes(error)
        chain.firstOrNull { it is SMBApiException }?.let { api ->
            api as SMBApiException
            val name = api.status?.name?.takeIf { it != "STATUS_OTHER" }
                ?: "0x%08X".format(api.statusCode and 0xFFFFFFFFL)
            return "$name (${api.failedCommand ?: "SMB"})"
        }
        val root = chain.lastOrNull { !it.message.isNullOrBlank() } ?: error
        return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
    }

    private fun causes(error: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>(4)
        var current: Throwable? = error
        while (current != null && result.size < 12 && current !in result) {
            result += current
            current = current.cause
        }
        return result
    }
}
