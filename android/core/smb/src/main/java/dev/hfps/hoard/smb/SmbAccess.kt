package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the account may change a share, folder or file, decided from the server's own answers:
 * the `MaximalAccess` of TREE_CONNECT and the share's security descriptor (share level), the
 * SMB2_CREATE_QUERY_MAXIMAL_ACCESS create context ("MxAc", folder or file level) and a write probe
 * (the listing / viewer CREATE also asks for a write right). Contract `SmbAccess`.
 *
 * The probe exists for macOS File Sharing: a share marked read-only there still answers full
 * access at tree level and POSIX-only rights in MxAc, but refuses any CREATE that asks for a write
 * right (docs/testing/read-only.md).
 */
enum class SmbAccess(val json: String) {
    READ("read"),
    READ_WRITE("readWrite"),
    ;

    companion object {
        /**
         * Rights that change the folder's contents or the file's data. On a folder FILE_ADD_FILE and
         * FILE_ADD_SUBDIRECTORY are the same bits as FILE_WRITE_DATA and FILE_APPEND_DATA.
         *
         * DELETE, WRITE_DAC/WRITE_OWNER, FILE_WRITE_ATTRIBUTES and FILE_WRITE_EA are left out on
         * purpose: Samba keeps DELETE (and the ownership rights for the owner) in the maximal access
         * of a `read only = yes` share, where every write still fails with ACCESS_DENIED. Generic
         * rights should not appear in a maximal-access answer (servers map them), but count when
         * they do. MAXIMUM_ALLOWED is a request flag, never a grant: alone it means nothing.
         */
        const val WRITE_BITS: Long =
            0x00000002L or // FILE_WRITE_DATA / FILE_ADD_FILE
                0x00000004L or // FILE_APPEND_DATA / FILE_ADD_SUBDIRECTORY
                0x00000040L or // FILE_DELETE_CHILD
                0x10000000L or // GENERIC_ALL
                0x40000000L // GENERIC_WRITE

        /** Rights that only make sense if something was granted at all (any read or write). */
        private const val MEANINGFUL_BITS: Long = WRITE_BITS or
            0x00000001L or // FILE_READ_DATA / FILE_LIST_DIRECTORY
            0x00000020L or // FILE_EXECUTE / FILE_TRAVERSE
            0x00000080L or // FILE_READ_ATTRIBUTES
            0x00000008L or // FILE_READ_EA
            0x00020000L or // READ_CONTROL
            0x80000000L or // GENERIC_READ
            0x20000000L // GENERIC_EXECUTE

        /** Null when the server gave no usable answer (no mask, 0, or only MAXIMUM_ALLOWED). */
        fun fromMask(mask: Long?): SmbAccess? = when {
            mask == null || mask and MEANINGFUL_BITS == 0L -> null
            mask and WRITE_BITS != 0L -> READ_WRITE
            else -> READ
        }

        fun fromMask(mask: Set<AccessMask>?): SmbAccess? = fromMask(mask?.let(::toLong))

        fun toLong(mask: Set<AccessMask>): Long = mask.fold(0L) { acc, bit -> acc or bit.value }

        /**
         * A folder or file inside a share: read-only if either level says so (a share-level
         * `read only` wins over a generous per-file answer), otherwise the most specific answer.
         */
        fun combine(share: SmbAccess?, item: SmbAccess?): SmbAccess? = when {
            share == READ || item == READ -> READ
            else -> item ?: share
        }

        /**
         * A folder or file: the write probe's answer when it gave one (a refused write right is
         * read-only whatever MxAc claims, a granted one is writable), otherwise MxAc; then combined
         * with the share level.
         */
        fun decide(share: SmbAccess?, mask: Long?, probe: WriteProbe?): SmbAccess? {
            val item = when (probe) {
                WriteProbe.DENIED -> READ
                WriteProbe.GRANTED -> READ_WRITE
                null -> fromMask(mask)
            }
            return combine(share, item)
        }

        /** Two share-level answers (TREE_CONNECT and the share's security descriptor): `read` wins. */
        fun both(a: SmbAccess?, b: SmbAccess?): SmbAccess? = when {
            a == READ || b == READ -> READ
            else -> a ?: b
        }

        /** The MaximalAccess of an "MxAc" response context: QueryStatus (u32) then the mask (u32). */
        fun parseMaximalAccessResponse(data: ByteArray?): Long? {
            if (data == null || data.size < 8) return null
            fun u32(offset: Int) = (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
            return if (u32(0) == 0L) u32(4) else null
        }
    }
}

/** Outcome of asking for a write right on the CREATE of a folder or file (never writes anything). */
enum class WriteProbe {
    GRANTED,
    DENIED,
    ;

    companion object {
        private const val STATUS_ACCESS_DENIED = 0xC0000022L
        private const val STATUS_MEDIA_WRITE_PROTECTED = 0xC00000A2L

        /** NTSTATUS values that mean "no write here"; anything else says nothing about access. */
        fun isDenial(status: Long): Boolean =
            status == STATUS_ACCESS_DENIED || status == STATUS_MEDIA_WRITE_PROTECTED
    }
}

/**
 * Folders of one session found read-only recently (key: lower-case share + path). Listing one
 * again within [ttlMs] skips the write right that would only be refused (one round trip saved)
 * and keeps the read-only answer: MxAc alone would call a macOS read-only share writable. A
 * folder found writable is forgotten at once (its probe costs nothing).
 */
class ReadOnlyHints(
    private val ttlMs: Long = 60_000L,
    private val maxEntries: Int = 2_000,
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private val hints = ConcurrentHashMap<String, Long>()

    private fun key(share: String, path: String) = share.lowercase(Locale.ROOT) + "\u0000" + path

    fun isReadOnly(share: String, path: String): Boolean {
        val key = key(share, path)
        val at = hints[key] ?: return false
        if (nowNanos() - at < ttlMs * 1_000_000) return true
        hints.remove(key, at)
        return false
    }

    fun remember(share: String, path: String, access: SmbAccess?) {
        val key = key(share, path)
        if (access != SmbAccess.READ) {
            hints.remove(key)
            return
        }
        if (hints.size >= maxEntries) hints.clear()
        hints[key] = nowNanos()
    }
}
