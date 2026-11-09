package dev.hfps.hoard.smb.location

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.FolderListing
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.text.TextCodec
import dev.hfps.hoard.smb.text.TextFiles
import dev.hfps.hoard.smb.text.TextRead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/**
 * A local location read by plain file paths: the primary storage (`/storage/emulated/0`) or a
 * volume's directory (`/storage/1A2B-3C4D`), with all files access. Android routes these through
 * its FUSE daemon, which keeps MediaStore in sync on Android 11+; older versions get a media scan
 * after every change.
 *
 * Listing a 20,000-entry folder (DCIM) costs one `readdir` plus one `stat` per entry: Android 8+
 * reads the attributes with a single `lstat` each (NIO), older versions three calls per entry.
 */
class PathFs(
    private val context: Context,
    val root: File,
    override val cacheId: String,
    override val label: String,
    /** The volume is mounted read-only (or path writes are not allowed on this Android version). */
    private val readOnly: Boolean,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LocalFs {

    private fun resolve(path: String): File = LocalPaths.resolve(root, path)

    private fun checkRoot() {
        if (!root.isDirectory) smbFail(ErrorCode.UNAVAILABLE, "$label is not available")
    }

    private fun restrictedFail(path: String): Nothing = smbFail(
        ErrorCode.ACCESS_DENIED,
        "Android keeps ${LocalPaths.normalize(path)} to its apps",
        mapOf("restricted" to true),
    )

    private fun entryOf(file: File, path: String): SmbEntry {
        val attributes = if (sdkInt >= 26) {
            runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()
        } else {
            null
        }
        val isDirectory = attributes?.isDirectory ?: file.isDirectory
        val size = if (isDirectory) 0L else attributes?.size() ?: file.length()
        val modified = attributes?.lastModifiedTime()?.toMillis() ?: file.lastModified()
        return SmbEntry(
            name = file.name,
            path = path,
            isDirectory = isDirectory,
            size = size,
            modifiedAt = if (modified > 0) IsoTime.format(modified) else null,
            isHidden = file.name.startsWith("."),
            restricted = isDirectory && LocalPaths.isRestricted(path, sdkInt),
        )
    }

    private fun accessOf(dir: File): SmbAccess = if (readOnly || !dir.canWrite()) SmbAccess.READ else SmbAccess.READ_WRITE

    override fun list(path: String, cancellation: Cancellation): FolderListing = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
        checkRoot()
        val dir = resolve(clean)
        if (!dir.exists()) smbFail(ErrorCode.NOT_FOUND, "$clean does not exist")
        if (!dir.isDirectory) smbFail(ErrorCode.NOT_FOUND, "$clean is not a folder")
        val names = dir.list() ?: run {
            checkRoot() // unplugged mid-listing
            smbFail(ErrorCode.ACCESS_DENIED, "Cannot list $clean")
        }
        val entries = ArrayList<SmbEntry>(names.size)
        names.forEachIndexed { index, name ->
            if (index % 512 == 0) cancellation.check()
            entries += entryOf(File(dir, name), SmbPaths.join(clean, name))
        }
        if (entries.isEmpty()) checkRoot()
        FolderListing(entries, accessOf(dir))
    }

    override fun stat(path: String): SmbEntry = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        checkRoot()
        val file = resolve(clean)
        if (!file.exists()) smbFail(ErrorCode.NOT_FOUND, "$clean does not exist")
        entryOf(file, clean)
    }

    override fun file(path: String): LocalFile = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
        checkRoot()
        val file = resolve(clean)
        if (!file.exists()) smbFail(ErrorCode.NOT_FOUND, "$clean does not exist")
        LocalFile(file.name, if (file.isDirectory) 0 else file.length(), file.lastModified().takeIf { it > 0 }, file.isDirectory, file, null)
    }

    override fun openInput(file: LocalFile): InputStream = LocalErrors.guard(file.name) { FileInputStream(file.file!!) }

    private fun requireWritable(dir: File) {
        if (readOnly) smbFail(ErrorCode.ACCESS_DENIED, "$label is read-only")
        if (!dir.canWrite()) smbFail(ErrorCode.ACCESS_DENIED, "${dir.name.ifEmpty { label }} is read-only")
    }

    override fun createFolder(path: String, name: String): SmbEntry = LocalErrors.guard(name) {
        val clean = LocalPaths.normalize(path)
        val valid = LocalPaths.requireValidName(name, "folder name")
        if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
        checkRoot()
        val dir = resolve(clean)
        if (!dir.isDirectory) smbFail(ErrorCode.NOT_FOUND, "$clean is not a folder")
        requireWritable(dir)
        val target = File(dir, valid)
        if (target.exists()) smbFail(ErrorCode.ALREADY_EXISTS, "$valid already exists")
        if (!target.mkdir()) {
            if (target.exists()) smbFail(ErrorCode.ALREADY_EXISTS, "$valid already exists")
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Could not create $valid")
        }
        scan(target)
        entryOf(target, SmbPaths.join(clean, valid))
    }

    override fun rename(path: String, newName: String): SmbEntry = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot rename the root")
        if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
        val valid = LocalPaths.requireValidName(newName, "new name")
        checkRoot()
        val source = resolve(clean)
        if (!source.exists()) smbFail(ErrorCode.NOT_FOUND, "$clean does not exist")
        val parent = source.parentFile ?: smbFail(ErrorCode.UNKNOWN, "No parent folder")
        requireWritable(parent)
        val target = File(parent, valid)
        val caseOnly = valid != source.name && valid.equals(source.name, ignoreCase = true)
        if (target.exists() && !caseOnly) smbFail(ErrorCode.ALREADY_EXISTS, "$valid already exists")
        val moved = if (caseOnly) {
            // FAT/exFAT and sdcardfs compare names case-insensitively: go through a temporary name.
            val temp = File(parent, ".${valid}.hoard-${UUID.randomUUID().toString().take(8)}")
            source.renameTo(temp) && (temp.renameTo(target) || temp.renameTo(source).let { false })
        } else {
            source.renameTo(target)
        }
        if (!moved) {
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Could not rename ${source.name}")
        }
        scan(source, target)
        entryOf(target, SmbPaths.join(SmbPaths.parent(clean), valid))
    }

    override fun delete(path: String, recursive: Boolean, cancellation: Cancellation) = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot delete the root")
        if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
        checkRoot()
        val target = resolve(clean)
        if (!target.exists()) smbFail(ErrorCode.NOT_FOUND, "$clean does not exist")
        target.parentFile?.let(::requireWritable)
        if (target.isDirectory && !recursive && !target.list().isNullOrEmpty()) {
            smbFail(ErrorCode.ACCESS_DENIED, "${target.name} is not empty")
        }
        deleteTree(target, cancellation)
        scan(target)
    }

    /** Depth first. A symbolic link is deleted itself, never followed out of the tree. */
    private fun deleteTree(file: File, cancellation: Cancellation) {
        cancellation.check()
        val isLink = sdkInt >= 26 && Files.isSymbolicLink(file.toPath())
        if (!isLink && file.isDirectory) file.listFiles()?.forEach { deleteTree(it, cancellation) }
        if (!file.delete() && file.exists()) {
            checkRoot()
            smbFail(ErrorCode.ACCESS_DENIED, "Could not delete ${file.name}")
        }
    }

    override fun readText(path: String, maxBytes: Int, truncate: Boolean): TextRead = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
        val local = file(clean)
        if (local.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
        val (text, encoding) = LocalText.read(openInput(local), local.size, local.name, maxBytes, truncate)
        val writable = !readOnly && local.file!!.canWrite() && local.file.parentFile?.canWrite() == true
        TextRead(
            text = text,
            encoding = encoding,
            size = local.size,
            modifiedAt = local.modifiedMillis?.let(IsoTime::format),
            truncated = local.size > maxBytes.coerceIn(1, TextFiles.HARD_LIMIT),
            access = if (writable) SmbAccess.READ_WRITE else SmbAccess.READ,
        )
    }

    /**
     * Same rules as SMB: `create` refuses an existing name; `overwrite` checks the modified time,
     * writes a dot-named temporary file in the same folder and renames it over the target (one
     * `rename(2)`, atomic on every filesystem Android mounts), falling back to writing in place
     * when the folder refuses new files.
     */
    override fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?): SmbEntry =
        LocalErrors.guard(path) {
            val clean = LocalPaths.normalize(path)
            if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "A file path is required")
            if (LocalPaths.isRestricted(clean, sdkInt)) restrictedFail(clean)
            val name = LocalPaths.requireValidName(SmbPaths.name(clean), "file name")
            val bytes = TextCodec.encode(text, encoding)
            checkRoot()
            val target = resolve(clean)
            val dir = target.parentFile ?: smbFail(ErrorCode.UNKNOWN, "No parent folder")
            if (!dir.isDirectory) smbFail(ErrorCode.NOT_FOUND, "${SmbPaths.parent(clean)} is not a folder")
            requireWritable(dir)
            if (target.isDirectory) smbFail(ErrorCode.ALREADY_EXISTS, "$name is a folder")
            if (mode == "create") {
                if (target.exists()) smbFail(ErrorCode.ALREADY_EXISTS, "$name already exists")
                FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
            } else {
                val current = { target.lastModified().takeIf { target.exists() && it > 0 }?.let(IsoTime::format) }
                LocalText.checkUnchanged(current(), target.exists(), expectedModifiedAt)
                val temp = File(dir, ".$name.hoard-${UUID.randomUUID().toString().take(8)}.tmp")
                val viaTemp = runCatching {
                    FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
                    LocalText.checkUnchanged(current(), target.exists(), expectedModifiedAt)
                    temp.renameTo(target)
                }.onFailure { temp.delete() }.getOrThrow()
                if (!viaTemp) {
                    temp.delete()
                    FileOutputStream(target).use { it.write(bytes); it.fd.sync() }
                }
            }
            scan(target)
            entryOf(target, clean)
        }

    /** Android 10 and older: tell MediaStore (Android 11+ FUSE does it on every change). */
    private fun scan(vararg files: File) {
        if (sdkInt >= 30) return
        runCatching { MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null) }
    }
}
