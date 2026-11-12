package dev.hfps.hoard.smb.transfer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipelinedFileReader
import dev.hfps.hoard.smb.CredentialStore
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbBrowser
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbHandle
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.location.LocalErrors
import dev.hfps.hoard.smb.location.LocalPaths
import dev.hfps.hoard.smb.location.Location
import dev.hfps.hoard.smb.location.SafVolumes
import dev.hfps.hoard.smb.location.VolumeRegistry
import dev.hfps.hoard.smb.share.IncomingShares
import dev.hfps.hoard.smb.share.SharedUris
import dev.hfps.hoard.smb.smbFail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.EnumSet
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/** Builds the [TransferFs] of each endpoint kind (ADR 0007). */
class TransferEndpoints(private val context: Context, private val credentials: CredentialStore) {
    private val volumes = VolumeRegistry(context)

    fun open(endpoint: Endpoint): TransferFs = when (endpoint) {
        is Endpoint.Smb -> SmbTransferFs(endpoint, credentials)
        Endpoint.Device -> local(Location.Device)
        is Endpoint.Volume -> local(Location.Volume(endpoint.uuid))
        is Endpoint.Tree -> DocumentTransferFs.forTree(context, Uri.parse(endpoint.uri))
        is Endpoint.Incoming -> IncomingTransferFs(context, endpoint.shareId)
    }

    /** A device or volume: by path with all files access, else its SAF grants. */
    fun local(location: Location.Local): TransferFs {
        val fs = volumes.fs(location)
        return when (fs) {
            is dev.hfps.hoard.smb.location.PathFs -> PathTransferFs(context, fs.root, fs.label, fs.readOnly)
            else -> {
                val uuid = (location as Location.Volume).uuid
                val info = volumes.find(uuid)
                DocumentTransferFs.forVolume(context, uuid, info?.label ?: uuid)
            }
        }
    }
}

// --- Local files by path -------------------------------------------------------------------------

/** The phone's storage or a volume read by path (all files access). Contiguous writes. */
class PathTransferFs(
    private val context: Context,
    private val root: File,
    override val label: String,
    private val readOnly: Boolean,
) : TransferFs {

    private fun file(path: String): File {
        val clean = LocalPaths.normalize(path)
        if (LocalPaths.isRestricted(clean, Build.VERSION.SDK_INT)) smbFail(ErrorCode.ACCESS_DENIED, "Android keeps $clean to its apps")
        return LocalPaths.resolve(root, clean)
    }

    private fun checkRoot() {
        if (!root.isDirectory) smbFail(ErrorCode.UNAVAILABLE, "$label is not available")
    }

    private fun writable() {
        checkRoot()
        if (readOnly) smbFail(ErrorCode.ACCESS_DENIED, "$label is read-only")
    }

    private fun statOf(f: File) = FileStat(f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified().takeIf { it > 0 })

    override fun stat(path: String): FileStat? {
        checkRoot()
        val f = file(path)
        return if (f.exists()) statOf(f) else null
    }

    override fun list(path: String): List<FileStat> {
        checkRoot()
        val dir = file(path)
        val children = dir.listFiles() ?: run {
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Cannot list ${dir.name}")
        }
        return children.map(::statOf)
    }

    override fun mkdir(path: String) {
        writable()
        val dir = file(path)
        if (!dir.mkdir() && !dir.isDirectory) {
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Could not create ${dir.name}")
        }
    }

    override fun openRead(path: String, offset: Long): ReadStream {
        checkRoot()
        val input = FileInputStream(file(path))
        if (offset > 0) input.channel.position(offset)
        return StreamReader(input)
    }

    override fun openWrite(path: String, offset: Long): WriteStream {
        writable()
        val f = file(path)
        if (offset > 0) RandomAccessFile(f, "rw").use { it.setLength(offset) }
        return FileWriter(FileOutputStream(f, offset > 0), offset)
    }

    override fun delete(path: String) {
        writable()
        val f = file(path)
        if (f.exists() && !f.delete()) {
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Could not delete ${f.name}")
        }
        scan(f)
    }

    override fun rename(from: String, to: String, replace: Boolean): Boolean {
        writable()
        val source = file(from)
        val target = file(to)
        if (!source.exists()) smbFail(ErrorCode.NOT_FOUND, "${source.name} does not exist")
        if (target.exists() && !replace) smbFail(ErrorCode.ALREADY_EXISTS, "${target.name} already exists")
        // rename(2): atomic, replaces a file; fails across filesystems (the caller copies then).
        val moved = source.renameTo(target)
        if (moved) scan(source, target)
        return moved
    }

    override fun setModified(path: String, millis: Long): Boolean = file(path).setLastModified(millis)

    override fun canWrite(path: String): Boolean = !readOnly && file(path).canWrite()

    override fun freeBytes(path: String): Long? = runCatching { StatFs(root.path).availableBytes }.getOrNull()

    override fun mapError(error: Throwable): HoardSmbException {
        if (error !is HoardSmbException && !root.isDirectory) return HoardSmbException(ErrorCode.UNAVAILABLE, "$label was disconnected", error)
        return LocalErrors.map(error)
    }

    /** Android 10 and older: tell MediaStore (Android 11+ FUSE does it itself). */
    private fun scan(vararg files: File) {
        if (Build.VERSION.SDK_INT >= 30) return
        runCatching { MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null) }
    }
}

/** A plain [InputStream] as a [ReadStream]. */
class StreamReader(private val input: InputStream) : ReadStream {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = input.read(buffer, offset, length)
    override fun close() = input.close()
}

/** A local [FileOutputStream] (in order, so the file size is the resume point). */
class FileWriter(private val output: FileOutputStream, start: Long) : WriteStream {
    override var position: Long = start
        private set

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
        position += length
    }

    override fun finish() {
        output.flush()
        output.fd.sync()
    }

    override fun close() = output.close()
}

// --- SAF documents (a volume's grants, or a picked folder) -----------------------------------------

/**
 * Storage Access Framework endpoint: a volume through its persisted folder grants, or a folder
 * picked with `pickDestinationFolder`. The external storage provider names documents
 * `<root>:<path>`, so paths map to ids directly; other providers are walked by name (cached).
 */
class DocumentTransferFs private constructor(
    context: Context,
    override val label: String,
    /** The tree to address [path] through, or null when no grant covers it. */
    private val treeFor: (String) -> Uri?,
    /** Document id of [path] when it can be built without a query, else null (walk). */
    private val composeId: ((String) -> String)?,
    private val treeUri: Uri?,
) : TransferFs {
    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val destinations = SafDestinations(context)
    private val walked = HashMap<String, String>()

    private fun tree(path: String): Uri = treeFor(LocalPaths.normalize(path))
        ?: smbFail(ErrorCode.STORAGE_PERMISSION, "Hoard has no access to ${LocalPaths.normalize(path).ifEmpty { label }}")

    private fun id(path: String): String? {
        val clean = LocalPaths.normalize(path)
        composeId?.let { return it(clean) }
        val t = tree(clean)
        if (clean.isEmpty()) return DocumentsContract.getTreeDocumentId(t)
        walked[clean]?.let { return it }
        val parentId = id(TransferPaths.parent(clean)) ?: return null
        val name = TransferPaths.name(clean)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(t, parentId)
        resolver.query(children, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val childName = c.getString(1) ?: continue
                walked[TransferPaths.join(TransferPaths.parent(clean), childName)] = c.getString(0)
            }
        }
        return walked[clean]
    }

    private fun uri(path: String): Uri? = id(path)?.let { DocumentsContract.buildDocumentUriUsingTree(tree(path), it) }

    private fun requireUri(path: String): Uri = uri(path) ?: smbFail(ErrorCode.NOT_FOUND, "${TransferPaths.name(path)} does not exist")

    private class Row(val name: String, val mime: String, val size: Long, val modified: Long)

    private fun query(uri: Uri): List<Row> {
        val rows = ArrayList<Row>()
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                rows += Row(c.getString(0) ?: continue, c.getString(1) ?: "", if (c.isNull(2)) 0 else c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3))
            }
        } ?: smbFail(ErrorCode.UNAVAILABLE, "$label is not available")
        return rows
    }

    private fun Row.stat() = FileStat(name, mime == Document.MIME_TYPE_DIR, if (mime == Document.MIME_TYPE_DIR) 0 else size, modified.takeIf { it > 0 })

    override fun stat(path: String): FileStat? {
        val uri = uri(path) ?: return null
        return try {
            query(uri).firstOrNull()?.stat()
        } catch (_: FileNotFoundException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The document's own flags (`FLAG_SUPPORTS_WRITE`): what the storage provider allows. */
    override fun canWrite(path: String): Boolean {
        val uri = uri(path) ?: return false
        return resolver.query(uri, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { c ->
            c.moveToFirst() && (c.getInt(0) and Document.FLAG_SUPPORTS_WRITE) != 0
        } ?: false
    }

    override fun list(path: String): List<FileStat> {
        val parentId = id(path) ?: smbFail(ErrorCode.NOT_FOUND, "${TransferPaths.name(path)} does not exist")
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(tree(path), parentId)).map { it.stat() }
    }

    private fun create(path: String, mime: String): Uri {
        val parent = requireUri(TransferPaths.parent(path))
        val name = TransferPaths.name(path)
        val created = DocumentsContract.createDocument(resolver, parent, mime, name)
            ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not create $name")
        val actual = query(created).firstOrNull()?.name
        if (actual != null && actual != name) {
            DocumentsContract.deleteDocument(resolver, created)
            smbFail(ErrorCode.UNKNOWN, "The folder would not take the name $name")
        }
        walked[LocalPaths.normalize(path)] = DocumentsContract.getDocumentId(created)
        return created
    }

    override fun mkdir(path: String) {
        if (stat(path)?.isDirectory == true) return
        create(path, Document.MIME_TYPE_DIR)
    }

    override fun openRead(path: String, offset: Long): ReadStream {
        val pfd = resolver.openFileDescriptor(requireUri(path), "r") ?: smbFail(ErrorCode.NOT_FOUND, "${TransferPaths.name(path)} could not be opened")
        val input = ParcelFileDescriptor.AutoCloseInputStream(pfd)
        if (offset > 0) input.channel.position(offset)
        return StreamReader(input)
    }

    override fun openWrite(path: String, offset: Long): WriteStream {
        val uri = uri(path)?.takeIf { stat(path) != null } ?: create(path, "application/octet-stream")
        val pfd = resolver.openFileDescriptor(uri, if (offset > 0) "rw" else "wt") ?: smbFail(ErrorCode.PERMISSION_REVOKED, "Could not write ${TransferPaths.name(path)}")
        val output = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
        if (offset > 0) {
            output.channel.truncate(offset)
            output.channel.position(offset)
        }
        return FileWriter(output, offset)
    }

    override fun delete(path: String) {
        val uri = uri(path) ?: return
        if (stat(path) == null) return
        DocumentsContract.deleteDocument(resolver, uri)
        walked.remove(LocalPaths.normalize(path))
    }

    override fun rename(from: String, to: String, replace: Boolean): Boolean {
        val source = requireUri(from)
        if (stat(to) != null) {
            if (!replace) smbFail(ErrorCode.ALREADY_EXISTS, "${TransferPaths.name(to)} already exists")
            delete(to)
        }
        var current = source
        if (TransferPaths.parent(from) != TransferPaths.parent(to)) {
            current = try {
                DocumentsContract.moveDocument(resolver, source, requireUri(TransferPaths.parent(from)), requireUri(TransferPaths.parent(to)))
            } catch (_: UnsupportedOperationException) {
                null
            } catch (_: IllegalStateException) {
                null
            } ?: return false
        }
        val name = TransferPaths.name(to)
        if (TransferPaths.name(from) != name) {
            current = DocumentsContract.renameDocument(resolver, current, name) ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not rename to $name")
            if (query(current).firstOrNull()?.name != name) smbFail(ErrorCode.UNKNOWN, "The folder would not take the name $name")
        }
        walked.remove(LocalPaths.normalize(from))
        walked[LocalPaths.normalize(to)] = DocumentsContract.getDocumentId(current)
        return true
    }

    override fun freeBytes(path: String): Long? {
        val tree = runCatching { tree(path) }.getOrNull() ?: treeUri ?: return null
        return destinations.availableBytes(tree, uri(path))
    }

    override fun mapError(error: Throwable): HoardSmbException = when {
        error is HoardSmbException -> error
        error is SecurityException -> HoardSmbException(ErrorCode.PERMISSION_REVOKED, "Access to $label was revoked", error)
        else -> LocalErrors.map(error)
    }

    companion object {
        private const val EXTERNAL = "com.android.externalstorage.documents"

        fun forTree(context: Context, treeUri: Uri): DocumentTransferFs {
            val destinations = SafDestinations(context)
            destinations.requireWritableTree(treeUri.toString())
            val rootId = DocumentsContract.getTreeDocumentId(treeUri)
            val compose: ((String) -> String)? = if (treeUri.authority == EXTERNAL) {
                { path -> if (path.isEmpty()) rootId else if (rootId.endsWith(":")) "$rootId$path" else "$rootId/$path" }
            } else null
            return DocumentTransferFs(context, destinations.displayName(treeUri), { treeUri }, compose, treeUri)
        }

        fun forVolume(context: Context, uuid: String, label: String): DocumentTransferFs {
            val grants = context.contentResolver.persistedUriPermissions
                .filter { it.isWritePermission && it.uri.authority == SafVolumes.AUTHORITY }
                .mapNotNull { SafVolumes.grantOf(runCatching { DocumentsContract.getTreeDocumentId(it.uri) }.getOrNull()) }
                .filter { it.first.equals(uuid, ignoreCase = true) }
                .map { it.second }
            return DocumentTransferFs(
                context,
                label,
                { path -> SafVolumes.grantFor(grants, path)?.let { SafVolumes.treeUri(uuid, it) } },
                { path -> SafVolumes.documentId(uuid, path) },
                null,
            )
        }
    }
}

// --- Files shared by another app --------------------------------------------------------------------

/** Read-only: the URIs of one incoming share; a path is the item's index. */
class IncomingTransferFs(context: Context, private val shareId: String) : TransferFs {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    override val label = "Shared files"

    /** The item, re-checked before every use (defense in depth: the persisted list could be stale or edited). */
    private fun item(path: String): dev.hfps.hoard.smb.share.IncomingItem {
        val item = IncomingShares.get(shareId)?.items?.getOrNull(path.toIntOrNull() ?: -1)
            ?: smbFail(ErrorCode.NOT_FOUND, "The shared file is no longer available")
        if (!SharedUris.isAllowed(appContext, item.uri)) smbFail(ErrorCode.ACCESS_DENIED, "Hoard does not read this kind of shared file")
        return item
    }

    override fun stat(path: String): FileStat? {
        val item = runCatching { item(path) }.getOrNull() ?: return null
        val size = item.size.takeIf { it >= 0 }
            ?: runCatching { resolver.openAssetFileDescriptor(item.uri, "r")?.use { it.length } }.getOrNull()?.takeIf { it >= 0 }
            ?: -1
        return FileStat(item.name, false, size, null)
    }

    override fun list(path: String): List<FileStat> = emptyList()
    override fun mkdir(path: String) = smbFail(ErrorCode.ACCESS_DENIED, "Shared files are read-only")
    override fun openWrite(path: String, offset: Long): WriteStream = smbFail(ErrorCode.ACCESS_DENIED, "Shared files are read-only")
    override fun delete(path: String) {} // A move from another app keeps its file: Hoard cannot delete it.
    override fun rename(from: String, to: String, replace: Boolean) = false

    override fun openRead(path: String, offset: Long): ReadStream {
        val input = resolver.openInputStream(item(path).uri) ?: smbFail(ErrorCode.NOT_FOUND, "The shared file could not be opened")
        var skipped = 0L
        while (skipped < offset) {
            val n = input.skip(offset - skipped)
            if (n <= 0) break
            skipped += n
        }
        if (skipped < offset) smbFail(ErrorCode.UNKNOWN, "The shared file is shorter than expected")
        return StreamReader(input)
    }

    override fun mapError(error: Throwable): HoardSmbException = when (error) {
        is HoardSmbException -> error
        is SecurityException -> HoardSmbException(ErrorCode.PERMISSION_REVOKED, "The other app no longer lets Hoard read the shared file", error)
        else -> LocalErrors.map(error)
    }
}

// --- SMB ---------------------------------------------------------------------------------------

/**
 * An SMB share over Hoard's own connection (never a UI session): connects on first use to
 * [addressIndex] (the engine's failover plan picks it), re-checking the GUID before credentials.
 * Reads are pipelined (4 READs in flight), writes too (4 WRITEs), so [WriteStream.position] is the
 * contiguous prefix the server acknowledged.
 */
class SmbTransferFs(private val endpoint: Endpoint.Smb, private val credentials: CredentialStore) : SmbShareFs(endpoint.share) {
    override val addressCount get() = endpoint.addresses.size

    private var addressIndex = 0
    private var handle: SmbHandle? = null
    private var share: DiskShare? = null

    override val activeHost: String? get() = endpoint.addresses.getOrNull(addressIndex)?.host

    override fun share(): DiskShare {
        share?.takeIf { it.isConnected && handle?.isAlive == true }?.let { return it }
        drop()
        val address = endpoint.addresses[addressIndex]
        val opened = runBlocking {
            SmbConnector.open(address.host, address.port, endpoint.expectedServerGuid, CONNECT_TIMEOUT_MS) { credentials.get(endpoint.machineId) }
        }
        handle = opened
        val disk = try {
            opened.session.connectShare(endpoint.share) as? DiskShare
        } catch (e: Exception) {
            throw SmbErrors.map(e, endpoint.share)
        } ?: smbFail(ErrorCode.NOT_FOUND, "${endpoint.share} is not a disk share")
        share = disk
        return disk
    }

    private fun drop() {
        val old = handle
        share = null
        handle = null
        old?.let { Dispatchers.IO.asExecutor().execute { it.close() } }
    }

    override fun reconnect(addressIndex: Int) {
        drop()
        this.addressIndex = addressIndex.coerceIn(0, endpoint.addresses.size - 1)
    }

    override fun close() = drop()
}

/**
 * A share of a session the UI already holds (culling's sidecars, ADR 0011): the same file operations as
 * [SmbTransferFs] over [session]'s tree connection, which this endpoint never closes.
 */
class SessionShareFs(private val session: dev.hfps.hoard.smb.SmbSession, shareName: String) : SmbShareFs(shareName) {
    override fun share(): DiskShare = session.share(label)
}

/** The file operations of an SMB share for the transfer engine; [share] gives the tree connection to use. */
abstract class SmbShareFs(final override val label: String) : TransferFs {
    override val isNetwork = true

    protected abstract fun share(): DiskShare

    private fun smb(path: String) = SmbPaths.toSmb(path)

    private fun isMissing(e: Throwable) = e is SMBApiException && SmbErrors.codeForStatus(e.statusCode) == ErrorCode.NOT_FOUND

    override fun stat(path: String): FileStat? {
        val clean = SmbPaths.normalize(path)
        return try {
            val info = share().getFileInformation(smb(clean))
            FileStat(
                if (clean.isEmpty()) label else SmbPaths.name(clean),
                info.standardInformation.isDirectory,
                if (info.standardInformation.isDirectory) 0 else info.standardInformation.endOfFile,
                info.basicInformation.lastWriteTime?.toEpochMillis()?.takeIf { it > 315_532_800_000L },
            )
        } catch (e: SMBApiException) {
            if (isMissing(e)) null else throw e
        }
    }

    override fun list(path: String): List<FileStat> =
        SmbBrowser.listDirectory(share(), smb(path)).filter { it.fileName != "." && it.fileName != ".." }.map {
            val dir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
            FileStat(it.fileName, dir, if (dir) 0 else it.endOfFile, it.lastWriteTime?.toEpochMillis()?.takeIf { m -> m > 315_532_800_000L })
        }

    override fun mkdir(path: String) {
        try {
            share().mkdir(smb(path))
        } catch (e: SMBApiException) {
            if (SmbErrors.codeForStatus(e.statusCode) == ErrorCode.ALREADY_EXISTS && stat(path)?.isDirectory == true) return
            throw e
        }
    }

    private fun openFile(path: String, access: Set<AccessMask>, disposition: SMB2CreateDisposition): SmbFile =
        share().openFile(
            smb(path),
            EnumSet.copyOf(access),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            disposition,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )

    override fun openRead(path: String, offset: Long): ReadStream {
        val file = openFile(path, setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN)
        val size = file.fileInformation.standardInformation.endOfFile
        return SmbReader(file, PipelinedFileReader(file, offset, size, depth = 4, timeoutMs = STALL_TIMEOUT_MS))
    }

    override fun openWrite(path: String, offset: Long): WriteStream {
        val file = openFile(
            path,
            setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_DATA),
            if (offset > 0) SMB2CreateDisposition.FILE_OPEN else SMB2CreateDisposition.FILE_OVERWRITE_IF,
        )
        if (offset > 0) file.setLength(offset)
        return SmbWriter(file, offset)
    }

    override fun delete(path: String) {
        val info = stat(path) ?: return
        if (info.isDirectory) share().rmdir(smb(path), false) else share().rm(smb(path))
    }

    override fun rename(from: String, to: String, replace: Boolean): Boolean {
        if (!replace && stat(to) != null) smbFail(ErrorCode.ALREADY_EXISTS, "${SmbPaths.name(to)} already exists")
        share().open(
            smb(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
            SMB2CreateDisposition.FILE_OPEN,
            null,
        ).use { it.rename(smb(to), replace) }
        return true
    }

    override fun setModified(path: String, millis: Long): Boolean {
        openFile(path, setOf(AccessMask.FILE_WRITE_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN).use {
            it.setFileInformation(FileBasicInformation(FileBasicInformation.DONT_SET, FileBasicInformation.DONT_SET, FileTime.ofEpochMillis(millis), FileBasicInformation.DONT_SET, 0))
        }
        return true
    }

    override fun freeBytes(path: String): Long? = runCatching { share().shareInformation.callerFreeSpace }.getOrNull()

    /** Opened for writing and closed again (nothing written): the server says whether this login may. */
    override fun canWrite(path: String): Boolean = try {
        openFile(path, setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN).closeNoWait()
        true
    } catch (e: SMBApiException) {
        if (SmbErrors.codeForStatus(e.statusCode) == ErrorCode.ACCESS_DENIED) false else throw e
    }

    /**
     * FSCTL_SRV_COPYCHUNK (smbj `remoteCopyTo`): the server copies inside the share, nothing
     * crosses the network. `length == 0` prepares the destination (created, truncated at 0).
     */
    override fun serverCopy(from: String, to: String, offset: Long, length: Long): Boolean {
        val dest = openFile(to, setOf(AccessMask.FILE_WRITE_DATA, AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN_IF)
        dest.use { target ->
            if (length == 0L) {
                if (offset == 0L) target.setLength(0)
                return true
            }
            openFile(from, setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES), SMB2CreateDisposition.FILE_OPEN).use { source ->
                try {
                    source.remoteCopyTo(offset, target, offset, length)
                } catch (e: SMBApiException) {
                    val status = e.statusCode and 0xFFFFFFFFL
                    // NOT_SUPPORTED / INVALID_DEVICE_REQUEST / INVALID_PARAMETER: stream instead.
                    if (status == 0xC00000BBL || status == 0xC0000010L || status == 0xC000000DL) return false
                    throw e
                }
            }
        }
        return true
    }

    override fun mapError(error: Throwable): HoardSmbException = SmbErrors.map(error, label)

    private class SmbReader(private val file: SmbFile, private val reader: PipelinedFileReader) : ReadStream {
        private var chunk: ByteArray? = null
        private var at = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            var current = chunk
            if (current == null || at >= current.size) {
                current = reader.next() ?: return -1
                chunk = current
                at = 0
            }
            val n = minOf(length, current.size - at)
            System.arraycopy(current, at, buffer, offset, n)
            at += n
            return n
        }

        override fun close() = file.closeNoWait()
    }

    /** Up to [DEPTH] WRITEs of [CHUNK] in flight; [position] only moves over acknowledged bytes in order. */
    private class SmbWriter(private val file: SmbFile, start: Long) : WriteStream {
        private class Pending(val end: Long, val future: Future<Long>)

        private val pending = ArrayDeque<Pending>()
        private var buffer = ByteArray(CHUNK)
        private var filled = 0
        private var next = start

        override var position: Long = start
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            var from = offset
            var left = length
            while (left > 0) {
                val n = minOf(left, CHUNK - filled)
                System.arraycopy(buffer, from, this.buffer, filled, n)
                filled += n
                from += n
                left -= n
                if (filled == CHUNK) send()
            }
        }

        private fun send() {
            if (filled == 0) return
            while (pending.size >= DEPTH) await()
            val data = buffer
            val length = filled
            pending.addLast(Pending(next + length, file.writeAsync(data, next, 0, length)))
            next += length
            buffer = ByteArray(CHUNK)
            filled = 0
        }

        private fun await() {
            val first = pending.pollFirst() ?: return
            first.future.get(STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            position = first.end
        }

        override fun finish() {
            send()
            while (pending.isNotEmpty()) await()
            file.flush()
        }

        override fun close() {
            pending.forEach { runCatching { it.future.cancel(false) } }
            pending.clear()
            runCatching { file.closeNoWait() }
        }
    }

    companion object {
        internal const val CONNECT_TIMEOUT_MS = 10_000L
        /** A READ or WRITE unanswered this long means the route died silently: fail over. */
        const val STALL_TIMEOUT_MS = 15_000L
        private const val CHUNK = 1 shl 20
        private const val DEPTH = 4
    }
}
