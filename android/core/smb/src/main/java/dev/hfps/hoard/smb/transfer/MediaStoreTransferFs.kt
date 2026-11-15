package dev.hfps.hoard.smb.transfer

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbPaths
import dev.hfps.hoard.smb.location.LocalErrors
import dev.hfps.hoard.smb.smbFail
import java.io.FileOutputStream
import java.util.Locale

/**
 * Where a file copied for Reveal goes in MediaStore (ADR 0010, amendment "2.2.0: local copies"; pure, JVM-tested):
 * `MediaStore.Images` under `Pictures/Hoard/<folder>/` with the MIME type Android knows for the extension, or, for a
 * format Images refuses (Android's MIME map has no `image/…` type for it, as for CR3: `docs/testing/mediastore-raw.md`),
 * `MediaStore.Downloads` under `Download/Hoard/<folder>/` as `application/octet-stream` with its real name.
 */
object MediaStoreTarget {
    enum class Collection(val root: String) {
        IMAGES("Pictures"),
        DOWNLOADS("Download"),
    }

    data class Target(val collection: Collection, val mime: String)

    const val APP_FOLDER = "Hoard"
    const val OCTET_STREAM = "application/octet-stream"

    /**
     * The collection and MIME type of a file named [name]. [mimeOf] is Android's MIME map (`MimeTypeMap`), the table
     * MediaProvider checks an insert against: an `image/…` type there is accepted by Images, anything else is not.
     */
    fun forFormat(name: String, mimeOf: (String) -> String? = ::androidMime): Target {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = ext.takeIf { it.isNotEmpty() }?.let(mimeOf)?.lowercase(Locale.ROOT)
        return if (mime != null && mime.startsWith("image/") && mime != "image/*") {
            Target(Collection.IMAGES, mime)
        } else {
            Target(Collection.DOWNLOADS, OCTET_STREAM)
        }
    }

    /** `Pictures/Hoard/<folder>/` (MediaStore's `RELATIVE_PATH`, always with a trailing slash). */
    fun relativePath(collection: Collection, folder: String): String =
        if (folder.isEmpty()) "${collection.root}/$APP_FOLDER/" else "${collection.root}/$APP_FOLDER/$folder/"

    /**
     * A folder name for a batch (pure): the title without characters MediaStore or FAT refuse, trimmed, at most
     * [MAX_FOLDER] characters, else [fallback] (the day, `yyyy-MM-dd`).
     */
    fun folderName(title: String?, fallback: String): String {
        val clean = title.orEmpty()
            .map { c -> if (c < ' ' || c in "/\\:*?\"<>|") ' ' else c }
            .joinToString("")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .trim()
            .take(MAX_FOLDER)
            .trim()
        return clean.ifEmpty { fallback }
    }

    const val MAX_FOLDER = 80

    private fun androidMime(ext: String): String? = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
}

/**
 * MediaStore as a transfer destination (ADR 0007, `Endpoint.MediaStore`): the copies Hoard makes for Reveal (ADR
 * 0010). Paths are `<folder>/<name>`: the folder is the batch, the collection follows the format
 * ([MediaStoreTarget.forFormat]). Writes follow the engine's `.part` then rename: the part is a row inserted with
 * `IS_PENDING = 1` under its final name (invisible to other apps), the rename publishes it (`IS_PENDING = 0`). Rows
 * never published by the time the job ends (cancelled, failed, the worker stopped) are deleted on [close]: a
 * cancelled hand-off leaves no partial copy. Android 10+ (Reveal needs Android 10 too).
 */
@RequiresApi(Build.VERSION_CODES.Q)
class MediaStoreTransferFs(context: Context) : TransferFs {
    private val resolver: ContentResolver = context.contentResolver
    override val label: String = "Reveal copies"

    /** Part path → its pending row, for rows this job inserted and has not published yet. */
    private val pending = LinkedHashMap<String, Uri>()

    private class Parts(val folder: String, val name: String)

    private fun split(path: String): Parts? {
        val clean = SmbPaths.normalize(path)
        val folder = TransferPaths.parent(clean)
        if (clean.isEmpty() || folder.isEmpty() || folder.contains('/')) return null
        return Parts(folder, TransferPaths.name(clean))
    }

    private fun isFolder(path: String): Boolean {
        val clean = SmbPaths.normalize(path)
        return clean.isEmpty() || !clean.contains('/')
    }

    override fun stat(path: String): FileStat? {
        if (isFolder(path)) return FileStat(TransferPaths.name(SmbPaths.normalize(path)), true, 0, null)
        val parts = split(path) ?: return null
        pending[SmbPaths.normalize(path)]?.let { uri ->
            val size = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            return FileStat(parts.name, false, size, null)
        }
        val found = MediaStoreCopies.find(resolver, parts.folder, parts.name) ?: return null
        return FileStat(parts.name, false, found.size, found.modifiedMillis)
    }

    override fun list(path: String): List<FileStat> {
        val folder = SmbPaths.normalize(path)
        if (folder.isEmpty() || folder.contains('/')) return emptyList()
        return MediaStoreCopies.list(resolver, folder).map { FileStat(it.name, false, it.size, it.modifiedMillis) }
    }

    override fun mkdir(path: String) {} // MediaStore makes the folders of a RELATIVE_PATH itself.

    override fun openRead(path: String, offset: Long): ReadStream {
        val parts = split(path) ?: smbFail(ErrorCode.NOT_FOUND, "$path does not exist")
        val uri = pending[SmbPaths.normalize(path)] ?: MediaStoreCopies.find(resolver, parts.folder, parts.name)?.uri
            ?: smbFail(ErrorCode.NOT_FOUND, "${parts.name} does not exist")
        val input = resolver.openInputStream(uri) ?: smbFail(ErrorCode.NOT_FOUND, "${parts.name} does not exist")
        if (offset > 0) input.skip(offset)
        return StreamReader(input)
    }

    override fun openWrite(path: String, offset: Long): WriteStream {
        val key = SmbPaths.normalize(path)
        val parts = split(key) ?: smbFail(ErrorCode.UNKNOWN, "Copies go into a folder: $path")
        val existing = pending[key]
        val uri = existing ?: insert(parts).also { pending[key] = it }
        val fd = resolver.openFileDescriptor(uri, if (existing != null && offset > 0) "rw" else "rwt")
            ?: smbFail(ErrorCode.UNKNOWN, "Could not open ${parts.name}")
        val start = if (existing != null && offset > 0) offset else 0L
        return PfdWriter(fd, start)
    }

    private fun insert(parts: Parts): Uri {
        val name = finalName(parts.name)
        val target = MediaStoreTarget.forFormat(name)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaStoreTarget.relativePath(target.collection, parts.folder))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(MediaStoreCopies.collectionUri(target.collection), values)
            ?: smbFail(ErrorCode.UNKNOWN, "MediaStore refused $name")
    }

    /** The name a part stands for (`.photo.ARW.hoardpart` → `photo.ARW`); other names as they are. */
    private fun finalName(name: String): String =
        if (PartNames.isPart(name)) name.removePrefix(".").removeSuffix(PART_SUFFIX) else name

    override fun delete(path: String) {
        val key = SmbPaths.normalize(path)
        pending.remove(key)?.let { resolver.delete(it, null, null); return }
        val parts = split(key) ?: return
        MediaStoreCopies.find(resolver, parts.folder, parts.name)?.let { resolver.delete(it.uri, null, null) }
    }

    /** Publishes a part under its final name (`IS_PENDING = 0`); false for anything else (the engine copies then). */
    override fun rename(from: String, to: String, replace: Boolean): Boolean {
        val uri = pending[SmbPaths.normalize(from)] ?: return false
        val target = split(to) ?: return false
        val there = MediaStoreCopies.find(resolver, target.folder, target.name)
        if (there != null) {
            if (!replace) smbFail(ErrorCode.ALREADY_EXISTS, "${target.name} already exists")
            resolver.delete(there.uri, null, null)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
        }
        if (resolver.update(uri, values, null, null) <= 0) return false
        pending.remove(SmbPaths.normalize(from))
        // MediaProvider renames on a clash it alone can see (a file no row of Hoard's holds): not the name asked for.
        val stored = MediaStoreCopies.nameOf(resolver, uri)
        if (stored != null && stored != target.name) {
            resolver.delete(uri, null, null)
            return false
        }
        return true
    }

    override fun freeBytes(path: String): Long? = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrNull()

    override fun mapError(error: Throwable): HoardSmbException = when (error) {
        is HoardSmbException -> error
        is IllegalArgumentException, is IllegalStateException -> HoardSmbException(ErrorCode.UNKNOWN, error.message ?: "MediaStore refused the file", error)
        else -> LocalErrors.map(error)
    }

    /** Rows never published (the job was cancelled, failed or stopped) are partial copies: deleted. */
    override fun close() {
        pending.values.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
        pending.clear()
    }

    private class PfdWriter(private val fd: ParcelFileDescriptor, start: Long) : WriteStream {
        private val output = FileOutputStream(fd.fileDescriptor).also { if (start > 0) it.channel.position(start) }
        override var position: Long = start
            private set

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            output.write(buffer, offset, length)
            position += length
        }

        override fun finish() {
            output.flush()
            runCatching { fd.fileDescriptor.sync() }
        }

        override fun close() {
            runCatching { output.close() }
            fd.close()
        }
    }

    private companion object {
        const val PART_SUFFIX = ".hoardpart"
    }
}

/**
 * Hoard's copies for Reveal in MediaStore (ADR 0010): find one by folder and name, check one still exists, how much
 * space they take, and delete them ("Delete copies" in Settings). Rows of other apps are never touched: a query of
 * Hoard's own rows is filtered by `OWNER_PACKAGE_NAME` where Android reports it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
object MediaStoreCopies {
    data class Row(val uri: Uri, val name: String, val size: Long, val modifiedMillis: Long?, val relativePath: String, val owner: String?)

    fun collectionUri(collection: MediaStoreTarget.Collection): Uri = when (collection) {
        MediaStoreTarget.Collection.IMAGES -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        MediaStoreTarget.Collection.DOWNLOADS -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    private val PROJECTION = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
    )

    private fun query(resolver: ContentResolver, collection: MediaStoreTarget.Collection, selection: String, args: Array<String>): List<Row> {
        val base = collectionUri(collection)
        val out = ArrayList<Row>()
        resolver.query(base, PROJECTION, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                out += Row(
                    uri = ContentUris.withAppendedId(base, c.getLong(0)),
                    name = c.getString(1) ?: continue,
                    size = c.getLong(2),
                    modifiedMillis = c.getLong(3).takeIf { it > 0 }?.times(1000),
                    relativePath = c.getString(4).orEmpty(),
                    owner = c.getString(5),
                )
            }
        }
        return out
    }

    /** The copy named [name] in [folder], in the collection its format goes to. */
    fun find(resolver: ContentResolver, folder: String, name: String): Row? {
        val target = MediaStoreTarget.forFormat(name)
        return query(
            resolver, target.collection,
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(MediaStoreTarget.relativePath(target.collection, folder), name),
        ).firstOrNull()
    }

    /** Every copy in [folder] (both collections). */
    fun list(resolver: ContentResolver, folder: String): List<Row> = MediaStoreTarget.Collection.entries.flatMap { c ->
        query(resolver, c, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(MediaStoreTarget.relativePath(c, folder)))
    }

    /** The row at [uri] when it still exists (published), else null. */
    fun get(resolver: ContentResolver, uri: Uri): Row? = runCatching {
        resolver.query(uri, PROJECTION, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            Row(uri, c.getString(1).orEmpty(), c.getLong(2), c.getLong(3).takeIf { it > 0 }?.times(1000), c.getString(4).orEmpty(), c.getString(5))
        }
    }.getOrNull()

    fun nameOf(resolver: ContentResolver, uri: Uri): String? = get(resolver, uri)?.name

    /** Every row under `Pictures/Hoard/` and `Download/Hoard/` that Hoard owns. */
    fun owned(context: Context): List<Row> = MediaStoreTarget.Collection.entries.flatMap { c ->
        query(
            context.contentResolver, c,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            arrayOf(MediaStoreTarget.relativePath(c, "") + "%", context.packageName),
        )
    }

    /** Deletes [uri]; true when a row went. */
    fun delete(resolver: ContentResolver, uri: Uri): Boolean = runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
}
