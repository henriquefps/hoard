package dev.hfps.hoard.smb

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException

/** Contract `DestinationFolder`. */
data class DestinationFolder(val uri: String, val name: String)

/** A document in a destination tree. */
data class DestinationDocument(val uri: Uri, val name: String, val size: Long)

/** Storage Access Framework helpers: persisted tree grants and documents inside them. */
class SafDestinations(context: Context) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Intent for ACTION_OPEN_DOCUMENT_TREE that asks for a persistable read/write grant. */
    fun pickIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
    )

    /** Keep the grant across reboots and return the folder. */
    fun persist(treeUri: Uri): DestinationFolder {
        try {
            resolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The folder did not grant write access")
        }
        return DestinationFolder(treeUri.toString(), displayName(treeUri))
    }

    /** Persisted read+write tree grants whose folder still exists; stale ones are released. */
    fun list(): List<DestinationFolder> = resolver.persistedUriPermissions
        .filter { it.isReadPermission && it.isWritePermission && DocumentsContract.isTreeUri(it.uri) }
        .mapNotNull { permission ->
            if (exists(permission.uri)) {
                DestinationFolder(permission.uri.toString(), displayName(permission.uri))
            } else {
                // A whole-volume grant (USB drive, SD card: the locations' SAF fallback) is kept
                // while the volume is unplugged; it works again when it comes back.
                val onRemovable = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                    ?.let { !it.startsWith("primary:") && !it.startsWith("home:") && it.contains(':') } == true
                if (!onRemovable) release(permission.uri.toString())
                null
            }
        }
        .sortedBy { it.name.lowercase() }

    fun release(uri: String) {
        runCatching {
            resolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /** PERMISSION_REVOKED unless [treeUri] is a tree we still hold read+write on and that exists. */
    fun requireWritableTree(treeUri: String): Uri {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull()
        if (uri == null || !DocumentsContract.isTreeUri(uri)) smbFail(ErrorCode.PERMISSION_REVOKED, "Not a folder: $treeUri")
        val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!held) smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is no longer accessible")
        if (!exists(uri)) smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is gone")
        return uri
    }

    /**
     * Human name for a tree. Primary/SD storage trees use their relative path (`Movies/Hoard`),
     * everything else the provider's display name.
     */
    fun displayName(treeUri: Uri): String {
        val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        if (treeUri.authority == EXTERNAL_STORAGE_AUTHORITY && documentId != null) {
            val relative = documentId.substringAfter(':', "")
            if (relative.isNotEmpty()) return relative.trimEnd('/')
        }
        return queryString(rootDocument(treeUri), DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            ?: documentId?.substringAfterLast('/')
            ?: treeUri.lastPathSegment
            ?: treeUri.toString()
    }

    fun rootDocument(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun exists(treeUri: Uri): Boolean = try {
        resolver.query(rootDocument(treeUri), arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false
    } catch (_: Exception) {
        false
    }

    /** Every child of the tree root (name → document), for conflict handling. */
    fun children(treeUri: Uri): List<DestinationDocument> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return try {
            resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        add(
                            DestinationDocument(
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = cursor.getString(1) ?: continue,
                                size = if (cursor.isNull(2)) 0 else cursor.getLong(2),
                            ),
                        )
                    }
                }
            } ?: emptyList()
        } catch (e: SecurityException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is no longer accessible")
        }
    }

    fun find(treeUri: Uri, name: String): DestinationDocument? = children(treeUri).firstOrNull { it.name == name }

    /** Create an empty file; the provider may adjust the name, so read it back. */
    fun create(treeUri: Uri, name: String): DestinationDocument {
        val uri = try {
            DocumentsContract.createDocument(resolver, rootDocument(treeUri), mimeTypeFor(name), name)
        } catch (e: SecurityException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is no longer accessible")
        } catch (e: FileNotFoundException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is gone")
        } catch (e: Exception) {
            if (SmbErrors.isNoSpace(e)) smbFail(ErrorCode.NO_SPACE, "The destination is full")
            throw SmbErrors.map(e, "Could not create $name")
        } ?: smbFail(ErrorCode.UNKNOWN, "The destination folder refused to create $name")
        val actualName = queryString(uri, DocumentsContract.Document.COLUMN_DISPLAY_NAME) ?: name
        return DestinationDocument(uri, actualName, 0)
    }

    fun delete(documentUri: Uri) {
        runCatching { DocumentsContract.deleteDocument(resolver, documentUri) }
    }

    fun size(documentUri: Uri): Long? = try {
        resolver.query(documentUri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Open for writing: `"wa"` appends (resume), `"wt"` truncates. */
    fun openForWrite(documentUri: Uri, append: Boolean): ParcelFileDescriptor {
        val mode = if (append) "wa" else "wt"
        return try {
            resolver.openFileDescriptor(documentUri, mode)
                ?: smbFail(ErrorCode.PERMISSION_REVOKED, "The destination file could not be opened")
        } catch (e: SecurityException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The destination folder is no longer accessible")
        } catch (e: FileNotFoundException) {
            smbFail(ErrorCode.PERMISSION_REVOKED, "The destination file is gone")
        }
    }

    /**
     * Free bytes on the volume holding [treeUri], or null when unknown. Providers that let us read
     * their roots report it there; otherwise (external storage only answers that query to holders of
     * MANAGE_DOCUMENTS) ask the file system of [documentUri], a document we created in the tree.
     */
    fun availableBytes(treeUri: Uri, documentUri: Uri? = null): Long? =
        fromRoots(treeUri) ?: documentUri?.let(::fromFileSystem)

    private fun fromRoots(treeUri: Uri): Long? = try {
        val authority = treeUri.authority ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).substringBefore(':')
        resolver.query(
            DocumentsContract.buildRootsUri(authority),
            arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
            null,
            null,
            null,
        )?.use { cursor ->
            var result: Long? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == rootId && !cursor.isNull(1)) result = cursor.getLong(1)
            }
            result
        }
    } catch (_: Exception) {
        null
    }

    private fun fromFileSystem(documentUri: Uri): Long? = try {
        resolver.openFileDescriptor(documentUri, "r")?.use { pfd ->
            val stat = android.system.Os.fstatvfs(pfd.fileDescriptor)
            (stat.f_bavail * stat.f_frsize).takeIf { it > 0 }
        }
    } catch (_: Exception) {
        null
    }

    private fun queryString(uri: Uri, column: String): String? = try {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        fun mimeTypeFor(name: String): String {
            val ext = SmbPaths.extension(name)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }
}
