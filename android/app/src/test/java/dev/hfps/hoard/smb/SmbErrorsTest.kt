package dev.hfps.hoard.smb

import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.common.SMBRuntimeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

class SmbErrorsTest {

    private fun api(status: Long) = SMBApiException(status, SMB2MessageCommandCode.SMB2_CREATE, null)

    @Test
    fun `NT statuses map to contract codes`() {
        assertEquals(ErrorCode.AUTH_FAILED, SmbErrors.map(api(0xC000006DL)).code) // LOGON_FAILURE
        assertEquals(ErrorCode.AUTH_FAILED, SmbErrors.map(api(0xC0000234L)).code) // ACCOUNT_LOCKED_OUT
        assertEquals(ErrorCode.ACCESS_DENIED, SmbErrors.map(api(0xC0000022L)).code)
        assertEquals(ErrorCode.ACCESS_DENIED, SmbErrors.map(api(0xC0000101L)).code) // DIRECTORY_NOT_EMPTY
        assertEquals(ErrorCode.NOT_FOUND, SmbErrors.map(api(0xC0000034L)).code) // OBJECT_NAME_NOT_FOUND
        assertEquals(ErrorCode.NOT_FOUND, SmbErrors.map(api(0xC000003AL)).code) // OBJECT_PATH_NOT_FOUND
        assertEquals(ErrorCode.NOT_FOUND, SmbErrors.map(api(0xC00000CCL)).code) // BAD_NETWORK_NAME
        assertEquals(ErrorCode.ALREADY_EXISTS, SmbErrors.map(api(0xC0000035L)).code) // OBJECT_NAME_COLLISION
        assertEquals(ErrorCode.NO_SPACE, SmbErrors.map(api(0xC000007FL)).code) // DISK_FULL
        assertEquals(ErrorCode.TIMEOUT, SmbErrors.map(api(0xC00000B5L)).code) // IO_TIMEOUT
        assertEquals(ErrorCode.UNKNOWN, SmbErrors.map(api(0xC0000001L)).code) // UNSUCCESSFUL
    }

    @Test
    fun `network failures map to UNREACHABLE or TIMEOUT`() {
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(ConnectException("Connection refused")).code)
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(UnknownHostException("nas")).code)
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(NoRouteToHostException()).code)
        assertEquals(ErrorCode.TIMEOUT, SmbErrors.map(SocketTimeoutException()).code)
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(SocketException("Connection reset")).code)
    }

    @Test
    fun `cause chains are unwrapped to the most specific failure`() {
        val timeout = TransportException(TimeoutException("Timeout expired"))
        assertEquals(ErrorCode.TIMEOUT, SmbErrors.map(SMBRuntimeException(timeout)).code)
        val wrappedApi = SMBRuntimeException(TransportException(api(0xC000006DL)))
        assertEquals(ErrorCode.AUTH_FAILED, SmbErrors.map(wrappedApi).code)
        val refused = TransportException(ConnectException("refused"))
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(refused).code)
        assertEquals(ErrorCode.UNREACHABLE, SmbErrors.map(TransportException("Connection closed")).code)
    }

    @Test
    fun `local ENOSPC is NO_SPACE`() {
        assertEquals(ErrorCode.NO_SPACE, SmbErrors.map(IOException("write failed: ENOSPC (No space left on device)")).code)
        assertTrue(SmbErrors.isNoSpace(RuntimeException(IOException("No space left on device"))))
    }

    @Test
    fun `contract exceptions pass through untouched`() {
        val original = HoardSmbException(ErrorCode.GUID_MISMATCH, "other", data = mapOf("serverGuid" to "x"))
        assertSame(original, SmbErrors.map(original))
        assertEquals(ErrorCode.UNKNOWN, SmbErrors.map(IllegalStateException("boom")).code)
    }

    @Test
    fun `session-gone detection`() {
        assertTrue(SmbErrors.isSessionGone(api(0xC0000203L))) // USER_SESSION_DELETED
        assertTrue(SmbErrors.isSessionGone(TransportException("closed")))
        assertFalse(SmbErrors.isSessionGone(api(0xC0000034L)))
        assertFalse(SmbErrors.isSessionGone(TransportException(TimeoutException())))
    }

    @Test
    fun `messages name the NT status`() {
        val message = SmbErrors.map(api(0xC0000022L), "list").message!!
        assertTrue(message, message.startsWith("list: STATUS_ACCESS_DENIED"))
    }
}
