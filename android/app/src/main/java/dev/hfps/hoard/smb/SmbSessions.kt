package dev.hfps.hoard.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileAllInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
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
        listDirectoryWithAccess(share, smbPath).first

    /**
     * [listDirectory] plus the folder's maximal access for this account, asked for on the same
     * CREATE with the "MxAc" create context (`HoardOpens`), so per-folder ACLs (Windows NTFS,
     * macOS, POSIX permissions on Samba) show up without an extra round trip. The mask is null when
     * the server does not answer the context.
     */
    fun listDirectoryWithAccess(share: DiskShare, smbPath: String): Pair<List<FileIdBothDirectoryInformation>, Long?> {
        val opened = HoardOpens.openWithMaximalAccess(
            share,
            smbPath,
            EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_READ_EA),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
        )
        return opened.entry.use {
            val listing = (it as? Directory ?: smbFail(ErrorCode.NOT_FOUND, "Not a folder: $smbPath"))
                .list(FileIdBothDirectoryInformation::class.java)
            listing to SmbAccess.parseMaximalAccessResponse(opened.maximalAccessData)
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
     * The shares plus each one's access, from a TREE_CONNECT per share (bounded, in parallel). The
     * trees stay connected in the session, so opening a share afterwards costs nothing extra. A
     * share that fails or does not answer before [ACCESS_DEADLINE_MS] is listed with a null access;
     * it never fails the whole list.
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
            ShareListing(info, access)
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
        val (listing, mask) = listDirectoryWithAccess(share, SmbPaths.toSmb(clean))
        val entries = listing
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
        val access = SmbAccess.combine(shareAccessOrNull(session, shareName), SmbAccess.fromMask(mask))
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
