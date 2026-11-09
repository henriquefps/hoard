package dev.hfps.hoard.smb.location

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.FolderListing
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SafDestinations
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.smbFail
import dev.hfps.hoard.smb.text.TextCodec
import dev.hfps.hoard.smb.text.TextFiles
import dev.hfps.hoard.smb.text.TextRead
import java.io.InputStream

/**
 * The SAF fallback for a removable volume Hoard cannot read by path (no all files access): folder
 * trees on the volume granted with `StorageVolume.createOpenDocumentTreeIntent` and kept as
 * persisted grants ([SafVolumes]). Android 11+ refuses the volume's root in that picker ("Can't
 * use this folder"), so a volume usually has one or more folder grants (`DCIM`, `DCIM/100MSDCF`);
 * the levels above them are shown as plain folders that lead to the granted ones, and anything
 * outside every grant rejects `STORAGE_PERMISSION`. Android 10 may grant the whole volume.
 *
 * The external storage provider names documents `<uuid>:<path inside the volume>`, so a contract
 * path maps to its document id directly ([SafVolumes.documentId]) instead of walking the tree
 * one query per level. Slower than paths (every listing is a provider query across processes) and
 * text saves are written in place (SAF has no atomic replace), which ADR 0005 accepts for a fallback.
 */
class SafFs(
    context: Context,
    private val uuid: String,
    /** Granted folders on this volume, as paths inside it (`""` = the whole volume). */
    private val grants: List<String>,
    override val label: String,
    private val readOnly: Boolean,
) : LocalFs {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    override val cacheId: String = "volume:$uuid"

    /** The tree of the grant covering [path]; `STORAGE_PERMISSION` when none does. */
    private fun tree(path: String): Uri {
        val grant = SafVolumes.grantFor(grants, LocalPaths.normalize(path))
            ?: smbFail(ErrorCode.STORAGE_PERMISSION, "Hoard has no access to ${LocalPaths.normalize(path).ifEmpty { label }}", mapOf("saf" to true))
        return SafVolumes.treeUri(uuid, grant)
    }

    private fun documentUri(path: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree(path), SafVolumes.documentId(uuid, path))

    /** A level above the granted folders: a folder that only leads to them (read-only). */
    private fun synthetic(path: String): List<String>? =
        SafVolumes.childrenTowardGrants(grants, LocalPaths.normalize(path)).takeIf { it.isNotEmpty() }

    private class Row(val name: String, val mime: String, val size: Long, val modified: Long, val flags: Int) {
        val isDirectory get() = mime == Document.MIME_TYPE_DIR
    }

    private fun query(uri: Uri): List<Row> {
        val rows = ArrayList<Row>()
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += Row(
                    name = cursor.getString(0) ?: continue,
                    mime = cursor.getString(1) ?: "",
                    size = if (cursor.isNull(2)) 0 else cursor.getLong(2),
                    modified = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                    flags = if (cursor.isNull(4)) 0 else cursor.getInt(4),
                )
            }
        } ?: smbFail(ErrorCode.UNAVAILABLE, "$label is not available")
        return rows
    }

    private fun row(path: String): Row = LocalErrors.guard(path) {
        query(documentUri(path)).firstOrNull() ?: smbFail(ErrorCode.NOT_FOUND, "${LocalPaths.normalize(path)} does not exist")
    }

    private fun entryOf(row: Row, path: String) = SmbEntry(
        name = row.name,
        path = path,
        isDirectory = row.isDirectory,
        size = if (row.isDirectory) 0 else row.size,
        modifiedAt = if (row.modified > 0) IsoTime.format(row.modified) else null,
        isHidden = row.name.startsWith("."),
        restricted = row.isDirectory && LocalPaths.isRestricted(path, android.os.Build.VERSION.SDK_INT),
    )

    private fun canWrite(row: Row): Boolean = !readOnly && (
        if (row.isDirectory) row.flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0 else row.flags and Document.FLAG_SUPPORTS_WRITE != 0
        )

    override fun list(path: String, cancellation: Cancellation): FolderListing = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (LocalPaths.isRestricted(clean, android.os.Build.VERSION.SDK_INT)) {
            smbFail(ErrorCode.ACCESS_DENIED, "Android keeps $clean to its apps", mapOf("restricted" to true))
        }
        if (SafVolumes.grantFor(grants, clean) == null) {
            val names = synthetic(clean) ?: tree(clean).let { emptyList() }
            return@guard FolderListing(
                names.map { SmbEntry(it, SmbPaths.join(clean, it), true, 0, null, it.startsWith(".")) },
                // Unknown: writing here needs another grant (STORAGE_PERMISSION), not "read-only".
                null,
            )
        }
        val folder = row(clean)
        if (!folder.isDirectory) smbFail(ErrorCode.NOT_FOUND, "$clean is not a folder")
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree(clean), SafVolumes.documentId(uuid, clean))
        val entries = query(children).map { entryOf(it, SmbPaths.join(clean, it.name)) }
        cancellation.check()
        FolderListing(entries, if (canWrite(folder)) SmbAccess.READ_WRITE else SmbAccess.READ)
    }

    override fun stat(path: String): SmbEntry {
        val clean = LocalPaths.normalize(path)
        if (SafVolumes.grantFor(grants, clean) == null && synthetic(clean) != null) {
            return SmbEntry(SmbPaths.name(clean), clean, true, 0, null, false)
        }
        return entryOf(row(clean), clean)
    }

    override fun file(path: String): LocalFile {
        val clean = LocalPaths.normalize(path)
        if (SafVolumes.grantFor(grants, clean) == null && synthetic(clean) != null) {
            return LocalFile(SmbPaths.name(clean), 0, null, true, null, null)
        }
        val row = row(clean)
        return LocalFile(row.name, if (row.isDirectory) 0 else row.size, row.modified.takeIf { it > 0 }, row.isDirectory, null, documentUri(clean))
    }

    override fun openInput(file: LocalFile): InputStream = LocalErrors.guard(file.name) {
        resolver.openInputStream(file.documentUri!!) ?: smbFail(ErrorCode.NOT_FOUND, "${file.name} could not be opened")
    }

    private fun exists(path: String): Boolean = runCatching { query(documentUri(path)).isNotEmpty() }.getOrDefault(false)

    private fun requireWritableFolder(path: String): Row {
        val folder = row(path)
        if (!folder.isDirectory) smbFail(ErrorCode.NOT_FOUND, "${LocalPaths.normalize(path)} is not a folder")
        if (!canWrite(folder)) smbFail(ErrorCode.ACCESS_DENIED, "${folder.name} is read-only")
        return folder
    }

    override fun createFolder(path: String, name: String): SmbEntry = LocalErrors.guard(name) {
        val clean = LocalPaths.normalize(path)
        val valid = LocalPaths.requireValidName(name, "folder name")
        requireWritableFolder(clean)
        val target = SmbPaths.join(clean, valid)
        // The provider would pick "name (1)" on a clash; the contract wants ALREADY_EXISTS.
        if (exists(target)) smbFail(ErrorCode.ALREADY_EXISTS, "$valid already exists")
        DocumentsContract.createDocument(resolver, documentUri(clean), Document.MIME_TYPE_DIR, valid)
            ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not create $valid")
        stat(target)
    }

    override fun rename(path: String, newName: String): SmbEntry = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot rename the root")
        val valid = LocalPaths.requireValidName(newName, "new name")
        requireWritableFolder(SmbPaths.parent(clean))
        val target = SmbPaths.join(SmbPaths.parent(clean), valid)
        val caseOnly = valid != SmbPaths.name(clean) && valid.equals(SmbPaths.name(clean), ignoreCase = true)
        if (!caseOnly && exists(target)) smbFail(ErrorCode.ALREADY_EXISTS, "$valid already exists")
        DocumentsContract.renameDocument(resolver, documentUri(clean), valid)
            ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not rename ${SmbPaths.name(clean)}")
        stat(target)
    }

    override fun delete(path: String, recursive: Boolean, cancellation: Cancellation) = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "Cannot delete the root")
        val target = row(clean)
        requireWritableFolder(SmbPaths.parent(clean))
        if (target.isDirectory && !recursive) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree(clean), SafVolumes.documentId(uuid, clean))
            if (query(children).isNotEmpty()) smbFail(ErrorCode.ACCESS_DENIED, "${target.name} is not empty")
        }
        // The external storage provider deletes folders recursively.
        if (!DocumentsContract.deleteDocument(resolver, documentUri(clean))) {
            smbFail(ErrorCode.ACCESS_DENIED, "Could not delete ${target.name}")
        }
    }

    override fun readText(path: String, maxBytes: Int, truncate: Boolean): TextRead = LocalErrors.guard(path) {
        val clean = LocalPaths.normalize(path)
        if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
        val row = row(clean)
        if (row.isDirectory) smbFail(ErrorCode.UNKNOWN, "Cannot read a folder as text")
        val local = file(clean)
        val (text, encoding) = LocalText.read(openInput(local), row.size, row.name, maxBytes, truncate)
        TextRead(
            text = text,
            encoding = encoding,
            size = row.size,
            modifiedAt = if (row.modified > 0) IsoTime.format(row.modified) else null,
            truncated = row.size > maxBytes.coerceIn(1, TextFiles.HARD_LIMIT),
            access = if (canWrite(row)) SmbAccess.READ_WRITE else SmbAccess.READ,
        )
    }

    override fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?): SmbEntry =
        LocalErrors.guard(path) {
            val clean = LocalPaths.normalize(path)
            if (clean.isEmpty()) smbFail(ErrorCode.UNKNOWN, "A file path is required")
            val name = LocalPaths.requireValidName(SmbPaths.name(clean), "file name")
            val bytes = TextCodec.encode(text, encoding)
            requireWritableFolder(SmbPaths.parent(clean))
            val existing = runCatching { row(clean) }.getOrNull()
            if (existing?.isDirectory == true) smbFail(ErrorCode.ALREADY_EXISTS, "$name is a folder")
            val uri = if (mode == "create") {
                if (existing != null) smbFail(ErrorCode.ALREADY_EXISTS, "$name already exists")
                DocumentsContract.createDocument(resolver, documentUri(SmbPaths.parent(clean)), SafDestinations.mimeTypeFor(name), name)
                    ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not create $name")
            } else {
                LocalText.checkUnchanged(existing?.modified?.takeIf { it > 0 }?.let(IsoTime::format), existing != null, expectedModifiedAt)
                if (existing == null) {
                    DocumentsContract.createDocument(resolver, documentUri(SmbPaths.parent(clean)), SafDestinations.mimeTypeFor(name), name)
                        ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not create $name")
                } else {
                    documentUri(clean)
                }
            }
            (resolver.openOutputStream(uri, "wt") ?: smbFail(ErrorCode.ACCESS_DENIED, "Could not write $name")).use { it.write(bytes) }
            stat(clean)
        }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

/** Tree URIs and document ids of the external storage provider (pure string logic is JVM-tested). */
object SafVolumes {
    const val AUTHORITY = "com.android.externalstorage.documents"

    /** `<uuid>:<path>`: the provider's document id for a path inside a volume. */
    fun documentId(uuid: String, path: String): String = "$uuid:${LocalPaths.normalize(path)}"

    /** The tree URI of a grant: the folder [sub] on the volume (`""` = the root). */
    fun treeUri(uuid: String, sub: String = ""): Uri =
        DocumentsContract.buildTreeDocumentUri(AUTHORITY, documentId(uuid, sub))

    /** `(uuid, folder inside the volume)` of a persisted tree's document id; null for the primary storage. */
    fun grantOf(treeDocumentId: String?): Pair<String, String>? {
        val id = treeDocumentId ?: return null
        val colon = id.indexOf(':')
        if (colon <= 0) return null
        val uuid = id.substring(0, colon)
        if (uuid == "primary" || uuid == "home") return null
        val sub = runCatching { LocalPaths.normalize(id.substring(colon + 1)) }.getOrNull() ?: return null
        return uuid to sub
    }

    /** The longest granted folder that contains [path] (or is it), or null. */
    fun grantFor(grants: List<String>, path: String): String? =
        grants.filter { it.isEmpty() || path == it || path.startsWith("$it/") }.maxByOrNull { it.length }

    /** Names one level below [path] on the way to granted folders below it (sorted, distinct). */
    fun childrenTowardGrants(grants: List<String>, path: String): List<String> =
        grants.mapNotNull { grant ->
            when {
                path.isEmpty() && grant.isNotEmpty() -> grant.substringBefore('/')
                grant.startsWith("$path/") -> grant.removePrefix("$path/").substringBefore('/')
                else -> null
            }
        }.distinct().sorted()
}
