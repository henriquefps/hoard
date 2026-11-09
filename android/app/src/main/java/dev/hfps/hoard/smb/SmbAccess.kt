package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask

/**
 * Whether the account may change a share, folder or file, decided from the server's own answer:
 * the `MaximalAccess` of TREE_CONNECT (share level) or of the SMB2_CREATE_QUERY_MAXIMAL_ACCESS
 * create context ("MxAc", folder or file level). Contract `SmbAccess`.
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
