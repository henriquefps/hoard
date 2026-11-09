package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ImpersonationLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.NamedPipe
import com.hierynomus.smbj.share.PipeShare
import java.io.ByteArrayOutputStream
import java.util.EnumSet

/** One row of NetrShareEnum level 1 (SHARE_INFO_1). */
data class ShareInfo(val name: String, val type: Long, val remark: String?) {
    /** STYPE_DISKTREE in the low bits (not a printer, device or IPC). */
    val isDisk: Boolean get() = (type and 0x0FFFFFFFL) == 0L
    val isHidden: Boolean get() = name.endsWith("$")
}

/**
 * Minimal MS-SRVS client: DCE/RPC (connection-oriented, NDR 32-bit) over the `\PIPE\srvsvc` named
 * pipe, just enough for NetrShareEnum level 1. Written here instead of pulling the rapid7 dcerpc
 * library, which drags in Guava + commons and was built against an older smbj.
 */
object SrvSvc {

    private const val PTYPE_REQUEST = 0
    private const val PTYPE_RESPONSE = 2
    private const val PTYPE_FAULT = 3
    private const val PTYPE_BIND = 11
    private const val PTYPE_BIND_ACK = 12
    private const val PTYPE_BIND_NAK = 13
    private const val PFC_FIRST_FRAG = 0x01
    private const val PFC_LAST_FRAG = 0x02
    private const val MAX_FRAG = 4280
    private const val OPNUM_NETR_SHARE_ENUM = 15

    /** srvsvc interface 4b324fc8-1670-01d3-1278-5a47bf6ee188 v3.0. */
    private const val SRVSVC_UUID = "4b324fc8-1670-01d3-1278-5a47bf6ee188"

    /** NDR transfer syntax 8a885d04-1ceb-11c9-9fe8-08002b104860 v2.0. */
    private const val NDR_UUID = "8a885d04-1ceb-11c9-9fe8-08002b104860"

    /** List every share on the server the session is connected to. */
    fun listShares(session: Session, serverName: String): List<ShareInfo> {
        val ipc = session.connectShare("IPC$") as? PipeShare
            ?: smbFail(ErrorCode.UNKNOWN, "IPC$ is not a pipe share")
        try {
            val pipe = ipc.open(
                "srvsvc",
                SMB2ImpersonationLevel.Impersonation,
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            pipe.use {
                call(pipe, encodeBind(callId = 1)).let(::checkBindAck)
                val response = call(pipe, encodeRequest(callId = 2, OPNUM_NETR_SHARE_ENUM, encodeShareEnum(serverName)))
                return decodeShareEnum(responseStub(response))
            }
        } finally {
            runCatching { ipc.close() }
        }
    }

    /** Send one PDU and collect every fragment of the answer (first by transact, then plain reads). */
    private fun call(pipe: NamedPipe, request: ByteArray): List<ByteArray> {
        val fragments = ArrayList<ByteArray>()
        val pending = ByteArrayOutputStream()
        pending.write(pipe.transact(request))
        while (true) {
            val bytes = pending.toByteArray()
            var offset = 0
            var last = false
            while (bytes.size - offset >= 16) {
                val fragLength = u16(bytes, offset + 8)
                if (fragLength < 16) smbFail(ErrorCode.UNKNOWN, "Malformed RPC fragment")
                if (bytes.size - offset < fragLength) break
                fragments += bytes.copyOfRange(offset, offset + fragLength)
                last = (bytes[offset + 3].toInt() and PFC_LAST_FRAG) != 0
                offset += fragLength
                if (last) break
            }
            if (last) return fragments
            pending.reset()
            pending.write(bytes, offset, bytes.size - offset)
            val chunk = ByteArray(65_536)
            val read = pipe.read(chunk)
            if (read <= 0) smbFail(ErrorCode.UNKNOWN, "RPC response ended early")
            pending.write(chunk, 0, read)
        }
    }

    // --- PDUs ------------------------------------------------------------------------------

    private fun header(out: Le, type: Int, flags: Int, callId: Int) {
        out.u8(5).u8(0).u8(type).u8(flags)
        out.u8(0x10).u8(0).u8(0).u8(0) // data representation: little-endian, ASCII, IEEE
        out.u16(0) // frag_length, patched in finish()
        out.u16(0) // auth_length
        out.u32(callId.toLong())
    }

    private fun finish(out: Le): ByteArray {
        val bytes = out.bytes()
        bytes[8] = (bytes.size and 0xFF).toByte()
        bytes[9] = ((bytes.size shr 8) and 0xFF).toByte()
        return bytes
    }

    internal fun encodeBind(callId: Int): ByteArray {
        val out = Le()
        header(out, PTYPE_BIND, PFC_FIRST_FRAG or PFC_LAST_FRAG, callId)
        out.u16(MAX_FRAG).u16(MAX_FRAG).u32(0) // max xmit, max recv, assoc group
        out.u8(1).u8(0).u16(0) // one presentation context
        out.u16(0).u8(1).u8(0) // context id 0, one transfer syntax
        out.uuid(SRVSVC_UUID).u16(3).u16(0)
        out.uuid(NDR_UUID).u32(2)
        return finish(out)
    }

    internal fun encodeRequest(callId: Int, opnum: Int, stub: ByteArray): ByteArray {
        val out = Le()
        header(out, PTYPE_REQUEST, PFC_FIRST_FRAG or PFC_LAST_FRAG, callId)
        out.u32(stub.size.toLong()).u16(0).u16(opnum) // alloc hint, context id, opnum
        out.raw(stub)
        return finish(out)
    }

    private fun checkBindAck(fragments: List<ByteArray>) {
        val pdu = fragments.first()
        when (pdu[2].toInt()) {
            PTYPE_BIND_ACK -> Unit
            PTYPE_BIND_NAK -> smbFail(ErrorCode.UNKNOWN, "srvsvc bind rejected (reason ${u16(pdu, 16)})")
            else -> smbFail(ErrorCode.UNKNOWN, "Unexpected RPC packet type ${pdu[2]} for bind")
        }
        // max xmit(2) max recv(2) assoc group(4), secondary address (len + string), align 4, results.
        var offset = 24
        val secAddrLength = u16(pdu, offset)
        offset += 2 + secAddrLength
        offset = (offset + 3) and 3.inv()
        val results = pdu[offset].toInt() and 0xFF
        if (results < 1) smbFail(ErrorCode.UNKNOWN, "srvsvc bind returned no result")
        val result = u16(pdu, offset + 4)
        if (result != 0) smbFail(ErrorCode.UNKNOWN, "srvsvc bind not accepted (result $result)")
    }

    /** Concatenated stub data of a (possibly fragmented) response, or the fault as an error. */
    private fun responseStub(fragments: List<ByteArray>): ByteArray {
        val stub = ByteArrayOutputStream()
        for (pdu in fragments) {
            when (pdu[2].toInt()) {
                PTYPE_RESPONSE -> stub.write(pdu, 24, u16(pdu, 8) - 24 - u16(pdu, 10).let { if (it > 0) it + 8 else 0 })
                PTYPE_FAULT -> {
                    val status = u32(pdu, 24)
                    // nca_s_fault_access_denied / ERROR_ACCESS_DENIED
                    if (status == 0x00000005L) smbFail(ErrorCode.ACCESS_DENIED, "Share listing denied by the server")
                    smbFail(ErrorCode.UNKNOWN, "srvsvc fault 0x%08X".format(status))
                }
                else -> smbFail(ErrorCode.UNKNOWN, "Unexpected RPC packet type ${pdu[2]}")
            }
        }
        return stub.toByteArray()
    }

    /** NetrShareEnum(ServerName, InfoStruct{Level 1, empty container}, MAXDWORD, ResumeHandle=0). */
    internal fun encodeShareEnum(serverName: String): ByteArray {
        val out = Le()
        out.u32(0x00020000) // ServerName: unique pointer referent
        out.conformantString("\\\\" + serverName)
        out.u32(1) // InfoStruct.Level
        out.u32(1) // union switch
        out.u32(0x00020004) // pointer to SHARE_INFO_1_CONTAINER
        out.u32(0) // EntriesRead
        out.u32(0) // Buffer (null)
        out.u32(0xFFFFFFFFL) // PreferedMaximumLength
        out.u32(0x00020008) // ResumeHandle pointer
        out.u32(0) // *ResumeHandle
        return out.bytes()
    }

    internal fun decodeShareEnum(stub: ByteArray): List<ShareInfo> {
        val input = NdrReader(stub)
        val level = input.u32()
        input.u32() // switch
        if (level != 1L) smbFail(ErrorCode.UNKNOWN, "Unexpected share info level $level")
        val containerRef = input.u32()
        if (containerRef == 0L) return emptyList()
        val count = input.u32().toInt()
        val bufferRef = input.u32()
        val shares = ArrayList<ShareInfo>(count)
        if (bufferRef != 0L && count > 0) {
            val maxCount = input.u32().toInt()
            if (maxCount < count) smbFail(ErrorCode.UNKNOWN, "Malformed share array")
            val rows = (0 until maxCount).map { Triple(input.u32(), input.u32(), input.u32()) }
            for ((nameRef, type, remarkRef) in rows.take(count)) {
                val name = if (nameRef != 0L) input.conformantString() else ""
                val remark = if (remarkRef != 0L) input.conformantString() else null
                shares += ShareInfo(name, type, remark?.takeIf { it.isNotBlank() })
            }
        }
        input.u32() // TotalEntries
        if (input.u32() != 0L) input.u32() // ResumeHandle
        val status = input.u32()
        if (status == 5L) smbFail(ErrorCode.ACCESS_DENIED, "Share listing denied by the server")
        if (status != 0L && status != 234L /* ERROR_MORE_DATA */) {
            smbFail(ErrorCode.UNKNOWN, "NetrShareEnum failed with WERROR $status")
        }
        return shares
    }

    // --- Little-endian / NDR helpers -------------------------------------------------------

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (u16(bytes, offset).toLong()) or (u16(bytes, offset + 2).toLong() shl 16)

    internal class Le {
        private val out = ByteArrayOutputStream()
        fun u8(value: Int) = apply { out.write(value and 0xFF) }
        fun u16(value: Int) = apply { u8(value); u8(value shr 8) }
        fun u32(value: Long) = apply { u16((value and 0xFFFF).toInt()); u16(((value shr 16) and 0xFFFF).toInt()) }
        fun raw(bytes: ByteArray) = apply { out.write(bytes) }

        fun align4() = apply { while (out.size() % 4 != 0) out.write(0) }

        /** UUID in NDR order: first three fields little-endian, the last 8 bytes as written. */
        fun uuid(text: String) = apply {
            val hex = text.replace("-", "")
            u32(hex.substring(0, 8).toLong(16))
            u16(hex.substring(8, 12).toInt(16))
            u16(hex.substring(12, 16).toInt(16))
            for (i in 16 until 32 step 2) u8(hex.substring(i, i + 2).toInt(16))
        }

        /** Conformant varying UTF-16LE string with its terminating NUL. */
        fun conformantString(text: String) = apply {
            val count = text.length + 1L
            u32(count).u32(0).u32(count)
            for (char in text) u16(char.code)
            u16(0)
            align4()
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    internal class NdrReader(private val bytes: ByteArray) {
        private var offset = 0

        private fun align4() {
            offset = (offset + 3) and 3.inv()
        }

        fun u32(): Long {
            align4()
            if (offset + 4 > bytes.size) smbFail(ErrorCode.UNKNOWN, "Truncated RPC response")
            return u32(bytes, offset).also { offset += 4 }
        }

        fun conformantString(): String {
            u32() // max count
            u32() // offset
            val actual = u32().toInt()
            if (actual < 0 || offset + actual * 2 > bytes.size) smbFail(ErrorCode.UNKNOWN, "Truncated RPC string")
            val chars = CharArray(actual) { i -> u16(bytes, offset + i * 2).toChar() }
            offset += actual * 2
            return String(chars).trimEnd('\u0000')
        }
    }
}
