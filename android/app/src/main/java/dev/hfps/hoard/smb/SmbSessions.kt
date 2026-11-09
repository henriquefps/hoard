package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.HoardOpens
import java.util.EnumSet
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Cooperative cancellation for long session work (downloads into the cache). */
fun interface Cancellation {
    fun isCancelled(): Boolean

    fun check() {
        if (isCancelled()) throw java.util.concurrent.CancellationException("Cancelled")
    }

    companion object {
        val NONE = Cancellation { false }
    }
}

/** Contract `SmbShare`: a listed share and the account's access to it (null: unknown). */
data class ShareListing(val info: ShareInfo, val access: SmbAccess?)

/** Contract `ListResult`: a folder's entries and the account's access to the folder. */
data class FolderListing(val entries: List<SmbEntry>, val access: SmbAccess?)

/** Contract `SmbEntry` as a Kotlin value (converted to JSObject by the plugin). */
data class SmbEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: String?,
    val isHidden: Boolean,
)

/** An open session in the registry, with its connected disk shares. */
class SmbSession(val id: String, val machineId: String, val handle: SmbHandle) {
    private val shares = ConcurrentHashMap<String, DiskShare>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Tree-connect to a disk share once and reuse it (reconnects if the server dropped the tree).
     * Locked per share name, so `listShares` can connect several shares at once.
     */
    fun share(name: String): DiskShare {
        val key = name.lowercase(Locale.ROOT)
        shares[key]?.takeIf { it.isConnected }?.let { return it }
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            shares[key]?.takeIf { it.isConnected }?.let { return it }
            val share = handle.session.connectShare(name) as? DiskShare
                ?: smbFail(ErrorCode.NOT_FOUND, "$name is not a disk share")
            shares[key] = share
            return share
        }
    }

    /** The share's access from its TREE_CONNECT response's MaximalAccess (null: no usable answer). */
    fun shareAccess(name: String): SmbAccess? = SmbAccess.fromMask(share(name).treeConnect.maximalAccess)

    /** Folders found read-only moments ago (`ReadOnlyHints`), so listing them again costs nothing extra. */
    val readOnlyHints = ReadOnlyHints()

    fun close() {
        // Polite TREE_DISCONNECT/LOGOFF only while the connection still works; otherwise each one
        // would just wait out the request timeout.
        if (handle.isAlive) shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        handle.close()
    }
}

/** Session registry: ids handed to JS, dropped when the connection dies. */
class SmbSessions {
    private val sessions = ConcurrentHashMap<String, SmbSession>()

    fun add(machineId: String, handle: SmbHandle): SmbSession {
        val session = SmbSession(UUID.randomUUID().toString(), machineId, handle)
        sessions[session.id] = session
        return session
    }

    /** The live session or NOT_FOUND (unknown id, or the connection dropped since). */
    fun get(sessionId: String?): SmbSession {
        val session = sessionId?.let { sessions[it] }
            ?: smbFail(ErrorCode.NOT_FOUND, "Session expired or unknown")
        if (!session.handle.isAlive) {
            remove(session.id)
            smbFail(ErrorCode.NOT_FOUND, "Session expired: the connection to ${session.handle.host} was lost")
        }
        return session
    }

    /**
     * Forget a session and close it in the background. On a dead link TREE_DISCONNECT and LOGOFF
     * each wait for smbj's request timeout; doing that inline turned one failed call into 90 s
     * before the UI could reconnect over another route.
     */
    fun remove(sessionId: String) {
        val session = sessions.remove(sessionId) ?: return
        closer.execute { session.close() }
    }

    private val closer = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hoard-smb-close").apply { isDaemon = true }
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::remove)
    }

    /**
     * Run [block] on a session, translating failures. A failure that killed the connection drops
     * the session and rejects NOT_FOUND, which tells the UI to reconnect.
     */
    inline fun <T> use(sessionId: String?, block: (SmbSession) -> T): T {
        val session = get(sessionId)
        try {
            return block(session)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            if (e !is HoardSmbException && (!session.handle.isAlive || SmbErrors.isSessionGone(e))) {
                remove(session.id)
                throw HoardSmbException(
                    ErrorCode.NOT_FOUND,
                    "Session expired: ${SmbErrors.map(e).message}",
                    e,
                )
            }
            throw SmbErrors.map(e)
        }
    }
}

/**
 * A CREATE that also asks for one write right (FILE_ADD_FILE on a folder, FILE_WRITE_DATA on a
 * file), so the server tells whether the account may write there without anything being written:
 * the handle is used for reading and closed, never for SET_INFO or WRITE. Checked against macOS
 * and Samba (`stat` of the folder or file identical before and after, docs/testing/read-only.md).
 *
 * Refused with ACCESS_DENIED or MEDIA_WRITE_PROTECTED: the CREATE is sent again without the write
 * right (one extra round trip, only on read-only items) and the probe says DENIED. Any other
 * refusal (sharing violation, …) retries the same way but says nothing about access. A missing
 * item fails at once, as before.
 */
object AccessProbe {

    class Result(val opened: HoardOpens.Opened, val probe: WriteProbe?)

    fun open(
        share: DiskShare,
        smbPath: String,
        readMask: Set<AccessMask>,
        writeRight: AccessMask?,
        shareAccess: Set<SMB2ShareAccess>,
        createOptions: Set<SMB2CreateOptions>,
    ): Result {
        fun create(mask: Set<AccessMask>) = HoardOpens.openWithMaximalAccess(
            share,
            smbPath,
            mask,
            shareAccess,
            SMB2CreateDisposition.FILE_OPEN,
            createOptions,
        )
        if (writeRight == null) return Result(create(readMask), null)
        val withWrite = EnumSet.copyOf(readMask).apply { add(writeRight) }
        return try {
            Result(create(withWrite), WriteProbe.GRANTED)
        } catch (e: SMBApiException) {
            val status = e.statusCode and 0xFFFFFFFFL
            if (SmbErrors.codeForStatus(status) == ErrorCode.NOT_FOUND) throw e
            Result(create(readMask), if (WriteProbe.isDenial(status)) WriteProbe.DENIED else null)
        }
    }
}

/** Folder operations on a session (list, stat, createFolder, rename, delete, listShares). */
object SmbBrowser {

    private fun isSet(attributes: Long, flag: FileAttributes) = (attributes and flag.value) != 0L

    fun entry(name: String, path: String, attributes: Long, size: Long, modifiedMillis: Long?): SmbEntry {
        val isDirectory = isSet(attributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
        return SmbEntry(
            name = name,
            path = path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else size.coerceAtLeast(0),
            modifiedAt = IsoTime.fromSmbMillis(modifiedMillis),
            isHidden = isSet(attributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) || name.startsWith("."),
        )
    }

    private fun fromInfo(name: String, path: String, info: FileAllInformation) = entry(
        name = name,
        path = path,
        attributes = info.basicInformation.fileAttributes,
        size = info.standardInformation.endOfFile,
        modifiedMillis = info.basicInformation.lastWriteTime?.toEpochMillis(),
    )

    /**
     * Every entry of a directory (including `.`/`..`), always read from the server.
     *
     * Never use smbj's `DiskShare.list`/`openDirectory` here: on servers that grant SMB3 directory
     * leases (macOS smbd, Windows) smbj 0.15.0 keeps a connection-wide listing cache and lease-key
     * table keyed by the path *inside* the share only, without host, share or tree. Once share A's
     * root was listed, listing the root of share B on the same connection returned A's cached
     * entries (every share showed the same folder), and so did any path both shares have. A plain
     * CREATE without a lease context plus QUERY_DIRECTORY has no such cache.
     */
    fun listDirectory(share: DiskShare, smbPath: String): List<FileIdBothDirectoryInformation> =
        listDirectoryWithAccess(share, smbPath, probeWrite = false).entries

    /** A folder's raw entries, its "MxAc" mask (null: no answer) and the write probe's answer. */
    class DirectoryAccess(
        val entries: List<FileIdBothDirectoryInformation>,
        val mask: Long?,
        val probe: WriteProbe?,
    )

    /**
     * [listDirectory] plus the folder's access for this account, asked for on the same CREATE:
     * the "MxAc" create context (`HoardOpens`), so per-folder ACLs (Windows NTFS, macOS, POSIX
     * permissions on Samba) show up, and, with [probeWrite], FILE_ADD_FILE (`AccessProbe`), so a
     * share macOS marks read-only shows up too. Free on writable folders; one extra CREATE on
     * read-only ones.
     */
    fun listDirectoryWithAccess(share: DiskShare, smbPath: String, probeWrite: Boolean): DirectoryAccess {
        val result = AccessProbe.open(
            share,
            smbPath,
            EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
            if (probeWrite) AccessMask.FILE_ADD_FILE else null,
            SMB2ShareAccess.ALL,
            EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
        )
        return result.opened.entry.use {
            val listing = (it as? Directory ?: smbFail(ErrorCode.NOT_FOUND, "Not a folder: $smbPath"))
                .list(FileIdBothDirectoryInformation::class.java)
            DirectoryAccess(listing, SmbAccess.parseMaximalAccessResponse(result.opened.maximalAccessData), result.probe)
        }
    }

    fun listShares(session: SmbSession): List<ShareInfo> =
        SrvSvc.listShares(session.handle.session, session.handle.host)
            .filter { it.isDisk && it.name.isNotEmpty() && !it.name.equals("IPC$", ignoreCase = true) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    /** At most this many TREE_CONNECTs in flight while `listShares` asks every share's access. */
    const val ACCESS_PARALLELISM = 4

    /** `listShares` waits this long in total for the access answers; slower shares stay unknown. */
    const val ACCESS_DEADLINE_MS = 4_000L

    /** At most this many shares get an access check (a server with hundreds stays fast). */
    const val ACCESS_MAX_SHARES = 64

    private val accessPool = java.util.concurrent.Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hoard-smb-access").apply { isDaemon = true }
    }

    /**
     * The shares plus each one's access, from a TREE_CONNECT per share (bounded, in parallel) and
     * the share's security descriptor when the server sent one (`ShareSecurity`: macOS shares
     * marked read-only). The trees stay connected in the session, so opening a share afterwards
     * costs nothing extra. A share that fails or does not answer before [ACCESS_DEADLINE_MS] keeps
     * only the descriptor's answer (often null); it never fails the whole list.
     */
    fun listSharesWithAccess(session: SmbSession): List<ShareListing> {
        val shares = listShares(session)
        val gate = java.util.concurrent.Semaphore(ACCESS_PARALLELISM)
        val futures = shares.take(ACCESS_MAX_SHARES).associate { info ->
            info.name to accessPool.submit<SmbAccess?> {
                gate.acquire()
                try {
                    if (!session.handle.isAlive) null else runCatching { session.shareAccess(info.name) }.getOrNull()
                } finally {
                    gate.release()
                }
            }
        }
        val deadline = System.nanoTime() + ACCESS_DEADLINE_MS * 1_000_000
        return shares.map { info ->
            val access = futures[info.name]?.let { future ->
                val left = deadline - System.nanoTime()
                runCatching { future.get(left.coerceAtLeast(0), java.util.concurrent.TimeUnit.NANOSECONDS) }.getOrNull()
            }
            ShareListing(info, SmbAccess.both(access, info.everyoneAccess))
        }
    }

    /** The share's access, null when it cannot be told (never fails the caller). */
    fun shareAccessOrNull(session: SmbSession, shareName: String): SmbAccess? =
        runCatching { session.shareAccess(shareName) }.getOrNull()

    fun list(session: SmbSession, shareName: String, path: String): List<SmbEntry> =
        listWithAccess(session, shareName, path).entries

    fun listWithAccess(session: SmbSession, shareName: String, path: String): FolderListing {
        val clean = SmbPaths.normalize(path)
        val share = session.share(shareName)
        // Found read-only moments ago: skip the write right (it would only be refused again) and
        // keep the answer; MxAc alone would call a macOS read-only share writable.
        val knownReadOnly = session.readOnlyHints.isReadOnly(shareName, clean)
        val folder = listDirectoryWithAccess(share, SmbPaths.toSmb(clean), probeWrite = !knownReadOnly)
        val entries = folder.entries
            .asSequence()
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                entry(
                    name = it.fileName,
                    path = SmbPaths.join(clean, it.fileName),
                    attributes = it.fileAttributes,
                    size = it.endOfFile,
                    modifiedMillis = it.lastWriteTime?.toEpochMillis(),
                )
            }
            .toList()
        val probe = if (knownReadOnly) WriteProbe.DENIED else folder.probe
        val access = SmbAccess.decide(shareAccessOrNull(session, shareName), folder.mask, probe)
        session.readOnlyHints.remember(shareName, clean, access)
        return FolderListing(entries, access)
    }

    fun stat(session: SmbSession, shareName: String, path: String): SmbEntry {
        val clean = SmbPaths.normalize(path)
        val share = session.share(shareName)
        val info = share.getFileInformation(SmbPaths.toSmb(clean))
        val name = if (clean.isEmpty()) shareName else SmbPaths.name(clean)
        return fromInfo(name, clean, info)
    }

    fun createFolder(session: SmbSession, shareName: String, path: String, name: String): SmbEntry {
        val folderName = SmbPaths.requireValidName(name, "folder name")
        val target = SmbPaths.join(path, folderName)
        val share = session.share(shareName)
        // FILE_CREATE fails with OBJECT_NAME_COLLISION (→ ALREADY_EXISTS) when the name is taken.
        share.mkdir(SmbPaths.toSmb(target))
        return stat(session, shareName, target)
    }

    fun rename(session: SmbSession, shareName: String, path: String, newName: String): SmbEntry {
        val clean = SmbPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot rename the share root")
        val name = SmbPaths.requireValidName(newName, "new name")
        val target = SmbPaths.join(SmbPaths.parent(clean), name)
        if (target == clean) return stat(session, shareName, clean)
        val share = session.share(shareName)
        share.open(
            SmbPaths.toSmb(clean),
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { entry ->
            // Case-only renames (a.txt → A.txt) are the same name to the server: allow replacing "itself".
            val caseOnly = target.equals(clean, ignoreCase = true)
            entry.rename(SmbPaths.toSmb(target), caseOnly)
        }
        return stat(session, shareName, target)
    }

    fun delete(session: SmbSession, shareName: String, path: String, recursive: Boolean) {
        val clean = SmbPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot delete the share root")
        val share = session.share(shareName)
        val smbPath = SmbPaths.toSmb(clean)
        val info = share.getFileInformation(smbPath)
        if (info.standardInformation.isDirectory) {
            if (recursive) deleteTree(share, smbPath) else share.rmdir(smbPath, false)
        } else {
            share.rm(smbPath)
        }
    }

    /** Depth-first delete that also clears the read-only attribute that would block it. */
    private fun deleteTree(share: DiskShare, smbPath: String) {
        for (child in listDirectory(share, smbPath)) {
            if (child.fileName == "." || child.fileName == "..") continue
            val childPath = "$smbPath\\${child.fileName}"
            if (isSet(child.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)) {
                deleteTree(share, childPath)
            } else {
                deleteFile(share, childPath, child.fileAttributes)
            }
        }
        share.rmdir(smbPath, false)
    }

    private fun deleteFile(share: DiskShare, smbPath: String, attributes: Long) {
        if (isSet(attributes, FileAttributes.FILE_ATTRIBUTE_READONLY)) {
            runCatching {
                share.open(
                    smbPath,
                    EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_ATTRIBUTES),
                    null,
                    EnumSet.allOf(SMB2ShareAccess::class.java),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                ).use { file ->
                    val basic = file.fileInformation.basicInformation
                    file.setFileInformation(
                        com.hierynomus.msfscc.fileinformation.FileBasicInformation(
                            basic.creationTime,
                            basic.lastAccessTime,
                            basic.lastWriteTime,
                            basic.changeTime,
                            (attributes and FileAttributes.FILE_ATTRIBUTE_READONLY.value.inv())
                                .takeIf { it != 0L } ?: FileAttributes.FILE_ATTRIBUTE_NORMAL.value,
                        ),
                    )
                }
            }
        }
        share.rm(smbPath)
    }
}
