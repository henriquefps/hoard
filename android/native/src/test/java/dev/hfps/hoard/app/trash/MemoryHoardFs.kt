package dev.hfps.hoard.app.trash

import dev.hfps.hoard.server.paths.SharePaths
import dev.hfps.hoard.smb.Cancellation
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.FolderListing
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.smb.SmbAccess
import dev.hfps.hoard.smb.SmbEntry
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.text.TextRead

/** The process was killed: escapes every `catch (e: Exception)` of the code under test. */
class Killed : Error("killed")

/**
 * An in-memory [dev.hfps.hoard.smb.location.HoardFs] for the trash (the counterpart of `:core:smb`'s `MemoryFs`, which
 * is a `TransferFs`): renames keep size and mtime, a move never replaces, and failures can be injected.
 */
class MemoryHoardFs : dev.hfps.hoard.smb.location.HoardFs {
    class Node(val isDirectory: Boolean, val size: Long = 0, val modified: Long = 1_000L)

    val nodes = LinkedHashMap<String, Node>().apply { put("", Node(true)) }

    /** Folders where nothing can be created (a read-only share root). */
    val readOnly = HashSet<String>()

    /** Targets under these folders are on another filesystem (`STATUS_NOT_SAME_DEVICE`). */
    val otherDevice = HashSet<String>()

    /** Moves of these source paths fail (`ACCESS_DENIED`: a file in use). */
    val busy = HashSet<String>()

    /** Throw [Killed] instead of the move that would make this many moves (the process dies mid-run). */
    var killAtMove: Int? = null
    var moves = 0

    fun file(path: String, size: Long = 10, modified: Long = 1_000L) {
        dirs(SharePaths.parent(path))
        nodes[path] = Node(false, size, modified)
    }

    fun dirs(path: String) {
        if (path.isEmpty()) return
        dirs(SharePaths.parent(path))
        nodes.getOrPut(path) { Node(true) }
    }

    fun exists(path: String) = nodes.containsKey(path)

    fun filesUnder(path: String): List<String> = nodes.keys.filter { (it == path || it.startsWith("$path/")) && !nodes[it]!!.isDirectory }.sorted()

    private fun missing(path: String): Nothing = throw HoardSmbException(ErrorCode.NOT_FOUND, "$path does not exist")

    private fun entry(path: String, node: Node) =
        SmbEntry(SharePaths.name(path), path, node.isDirectory, node.size, IsoTime.format(node.modified), SharePaths.name(path).startsWith("."))

    override fun list(path: String, cancellation: Cancellation): FolderListing {
        val node = nodes[path] ?: missing(path)
        if (!node.isDirectory) missing(path)
        val children = nodes.filterKeys { it.isNotEmpty() && SharePaths.parent(it) == path && it != path }
        return FolderListing(children.map { (p, n) -> entry(p, n) }, SmbAccess.READ_WRITE)
    }

    override fun stat(path: String): SmbEntry = nodes[path]?.let { entry(path, it) } ?: missing(path)

    override fun createFolder(path: String, name: String): SmbEntry {
        val target = SharePaths.join(path, name)
        if (nodes[path]?.isDirectory != true) missing(path)
        if (path in readOnly) throw HoardSmbException(ErrorCode.ACCESS_DENIED, "$path is read-only")
        if (nodes.containsKey(target)) throw HoardSmbException(ErrorCode.ALREADY_EXISTS, "$target already exists")
        nodes[target] = Node(true)
        return stat(target)
    }

    override fun rename(path: String, newName: String): SmbEntry = move(path, SharePaths.join(SharePaths.parent(path), newName))

    override fun move(path: String, targetPath: String): SmbEntry {
        if (!nodes.containsKey(path)) missing(path)
        if (nodes[SharePaths.parent(targetPath)]?.isDirectory != true) missing(SharePaths.parent(targetPath))
        if (nodes.containsKey(targetPath)) throw HoardSmbException(ErrorCode.ALREADY_EXISTS, "$targetPath already exists")
        if (otherDevice.any { SharePaths.rest(targetPath, it) != null }) {
            throw HoardSmbException(ErrorCode.UNAVAILABLE, "STATUS_NOT_SAME_DEVICE", data = mapOf(SmbErrors.REASON to SmbErrors.NOT_SAME_DEVICE))
        }
        if (path in busy) throw HoardSmbException(ErrorCode.ACCESS_DENIED, "$path is in use")
        if (killAtMove == moves + 1) throw Killed()
        moves++
        val moved = nodes.keys.filter { it == path || it.startsWith("$path/") }
        for (key in moved) nodes[targetPath + key.removePrefix(path)] = nodes.remove(key)!!
        return stat(targetPath)
    }

    override fun delete(path: String, recursive: Boolean, cancellation: Cancellation) {
        val node = nodes[path] ?: missing(path)
        val children = nodes.keys.filter { it.startsWith("$path/") }
        if (node.isDirectory && children.isNotEmpty() && !recursive) throw HoardSmbException(ErrorCode.ACCESS_DENIED, "$path is not empty")
        children.forEach { nodes.remove(it) }
        nodes.remove(path)
    }

    override fun readText(path: String, maxBytes: Int, truncate: Boolean): TextRead = throw UnsupportedOperationException()

    override fun writeText(path: String, text: String, encoding: String, mode: String, expectedModifiedAt: String?): SmbEntry {
        if (nodes[SharePaths.parent(path)]?.isDirectory != true) missing(SharePaths.parent(path))
        if (mode == "create" && nodes.containsKey(path)) throw HoardSmbException(ErrorCode.ALREADY_EXISTS, "$path already exists")
        nodes[path] = Node(false, text.length.toLong())
        return stat(path)
    }
}
