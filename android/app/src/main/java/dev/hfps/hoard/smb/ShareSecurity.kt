package dev.hfps.hoard.smb

/**
 * The share-level access a share's security descriptor (NetrShareEnum level 502,
 * `shi502_security_descriptor`, self-relative) grants to every account.
 *
 * macOS File Sharing reports a share marked read-only only here: TREE_CONNECT still answers full
 * access, but the descriptor's single Everyone ACE drops to read (`0x00120089`, against
 * `0x001f01ff` on a writable share; docs/testing/read-only.md).
 *
 * The descriptor is only evaluated when every ACE names Everyone (S-1-1-0) or the null SID
 * (S-1-0-0, matches nobody): then the answer holds for any account. An ACE for any other SID
 * (a user, Administrators, …) would need the account's own SIDs, which Hoard does not know, so
 * the answer is null (unknown) and the tree's MaximalAccess and the folder probe decide.
 */
object ShareSecurity {

    private const val SE_DACL_PRESENT = 0x0004
    private const val ACCESS_ALLOWED_ACE_TYPE = 0
    private const val ACCESS_DENIED_ACE_TYPE = 1
    private const val INHERIT_ONLY_ACE = 0x08

    /** Null when the descriptor is missing, malformed, has no or a NULL DACL, or names other SIDs. */
    fun accessForEveryone(sd: ByteArray?): SmbAccess? {
        if (sd == null || sd.size < 20) return null
        val control = u16(sd, 2)
        val daclOffset = u32(sd, 16).toInt()
        if (control and SE_DACL_PRESENT == 0 || daclOffset == 0) return null // NULL DACL: no restriction
        if (daclOffset < 0 || daclOffset + 8 > sd.size) return null
        val aceCount = u16(sd, daclOffset + 4)
        var offset = daclOffset + 8
        var granted = 0L
        var denied = 0L
        repeat(aceCount) {
            if (offset + 8 > sd.size) return null
            val type = sd[offset].toInt() and 0xFF
            val flags = sd[offset + 1].toInt() and 0xFF
            val size = u16(sd, offset + 2)
            if (size < 8 || offset + size > sd.size) return null
            val mask = u32(sd, offset + 4)
            val sid = sid(sd, offset + 8, offset + size) ?: return null
            if (flags and INHERIT_ONLY_ACE == 0 && sid != NULL_SID) {
                if (sid != EVERYONE) return null
                when (type) {
                    // Canonical order: the first ACE that mentions a right decides it.
                    ACCESS_ALLOWED_ACE_TYPE -> granted = granted or (mask and denied.inv())
                    ACCESS_DENIED_ACE_TYPE -> denied = denied or (mask and granted.inv())
                    else -> return null // object / callback ACEs: not evaluated
                }
            }
            offset += size
        }
        return SmbAccess.fromMask(granted)
    }

    private const val EVERYONE = "S-1-1-0"
    private const val NULL_SID = "S-1-0-0"

    private fun sid(bytes: ByteArray, start: Int, end: Int): String? {
        if (start + 8 > end) return null
        val revision = bytes[start].toInt() and 0xFF
        val count = bytes[start + 1].toInt() and 0xFF
        if (start + 8 + count * 4 > end) return null
        var authority = 0L
        for (i in 2 until 8) authority = (authority shl 8) or (bytes[start + i].toLong() and 0xFF)
        val sub = (0 until count).joinToString("") { "-" + u32(bytes, start + 8 + it * 4) }
        return "S-$revision-$authority$sub"
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
}
