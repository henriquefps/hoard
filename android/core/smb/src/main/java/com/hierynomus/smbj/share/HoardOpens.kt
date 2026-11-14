package com.hierynomus.smbj.share

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2OplockLevel
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.messages.create.SMB2CreateContext
import com.hierynomus.smbj.common.SmbPath

/**
 * Hoard's one extension of smbj (0.15.0), living in smbj's package on purpose.
 *
 * smbj builds CREATE requests with create contexts (`SMB2CreateRequest` and
 * `SMB2CreateContext` are public) and parses the response's contexts
 * (`SMB2CreateResponse.getCreateContexts`), but the only entry point that sends a CREATE with
 * contexts and wraps the handle, `Share.createFile(…, contexts)` plus the `Directory` / `File`
 * constructors, is package-private; the public `DiskShare.open` always sends an empty list. This
 * object calls them from the same package, so the CREATE still goes through smbj's own signing,
 * encryption, credits, timeouts and status handling, and the returned handle is a normal
 * `Directory` / `File` closed with `use {}`.
 *
 * It sends exactly one context, SMB2_CREATE_QUERY_MAXIMAL_ACCESS_REQUEST ("MxAc", no timestamp),
 * and never a lease context, so it stays clear of smbj 0.15's directory-lease cache (see
 * `SmbBrowser.listDirectory`, docs/testing/shares-macos.md). DFS is disabled in Hoard's
 * `SmbConfig`, so skipping `DiskShare`'s path resolver changes nothing.
 */
object HoardOpens {

    private val MXAC = "MxAc".toByteArray(Charsets.US_ASCII)

    /** An open handle plus the raw "MxAc" response context data (null when the server sent none). */
    class Opened(val entry: DiskEntry, val maximalAccessData: ByteArray?)

    fun openWithMaximalAccess(
        share: DiskShare,
        path: String,
        accessMask: Set<AccessMask>,
        shareAccess: Set<SMB2ShareAccess>,
        disposition: SMB2CreateDisposition,
        createOptions: Set<SMB2CreateOptions>?,
    ): Opened {
        val smbPath = SmbPath(share.smbPath, path)
        val response = share.createFile(
            smbPath,
            null,
            accessMask,
            null,
            shareAccess,
            disposition,
            createOptions,
            SMB2OplockLevel.SMB2_OPLOCK_LEVEL_NONE,
            listOf(SMB2CreateContext(MXAC, ByteArray(0))),
        )
        val entry: DiskEntry =
            if (response.fileAttributes.contains(FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
                Directory(response.fileId, share, smbPath)
            } else {
                File(response.fileId, share, smbPath)
            }
        val data = response.createContexts.firstOrNull { it.name.contentEquals(MXAC) }?.data
        return Opened(entry, data)
    }
}
