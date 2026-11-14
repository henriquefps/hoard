package dev.hfps.hoard.smb.transfer

import dev.hfps.hoard.smb.ConflictNames
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.IsoTime
import dev.hfps.hoard.xmp.SidecarNames
import dev.hfps.hoard.xmp.SidecarTool
import dev.hfps.hoard.xmp.SidecarWrite
import dev.hfps.hoard.xmp.Sidecars
import java.util.Locale

/** What the runner needs from Android (clock, events, persistence, sleeping). Fakes in tests. */
interface RunnerHooks {
    /** Monotonic milliseconds. */
    fun now(): Long

    /** Report the job (state change, finished item, throttled progress). */
    fun emit(job: TransferJob, bytesPerSecond: Long?)

    /** Save the job (items included). */
    fun persist(job: TransferJob)

    /** Wait before a retry; throws when the worker is stopped meanwhile. */
    fun sleep(ms: Long)

    /** The user cancelled the job, or the worker is being stopped. */
    fun isStopped(): Boolean

    fun log(message: String) {}
}

/** A failure that already knows which endpoint it came from. */
class EndpointFailure(val fs: TransferFs, val error: HoardSmbException) : Exception(error.message, error)

/** Runs [block] on [fs], turning any failure into an [EndpointFailure] of that endpoint. */
inline fun <T> TransferFs.call(block: TransferFs.() -> T): T = try {
    block()
} catch (e: EndpointFailure) {
    throw e
} catch (e: InterruptedException) {
    throw e
} catch (e: java.util.concurrent.CancellationException) {
    throw e
} catch (e: Throwable) {
    throw EndpointFailure(this, mapError(e))
}

/**
 * Executes one job (ADR 0007): scan → conflicts → free space → items in order → folder cleanup.
 * Single-threaded; everything that blocks goes through a [TransferFs]. The job's state is the
 * truth: a restarted runner continues where the persisted items say.
 */
class TransferRunner(
    private val job: TransferJob,
    private val fsFor: (Endpoint) -> TransferFs,
    private val hooks: RunnerHooks,
    private val bufferSize: Int = 1 shl 20,
) {
    private class JobStop(val error: HoardSmbException) : Exception(error.message, error)
    private class ItemFailure(val error: HoardSmbException) : Exception(error.message, error)
    private class Stopped : Exception("Stopped")

    private val opened = LinkedHashMap<String, TransferFs>()
    private val plans = HashMap<TransferFs, FailoverPlan>()
    private val throttle = ProgressThrottle()
    private val speed = SpeedMeter()
    private var lastPersist = 0L

    /** Destination folder → lowercase names in it (for renames), filled lazily. */
    private val destNames = HashMap<String, MutableSet<String>>()

    /** Sidecar names per destination folder (lower case → as listed), listed once per job (ADR 0011). */
    private val sidecarNames = HashMap<String, HashMap<String, String>>()

    /** The endpoint's filesystem; one that cannot be opened (drive gone, grant revoked) stops the job. */
    private fun fs(endpoint: Endpoint): TransferFs = opened.getOrPut(endpoint.key) {
        try {
            fsFor(endpoint)
        } catch (e: HoardSmbException) {
            throw JobStop(e)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JobStop(HoardSmbException(ErrorCode.UNKNOWN, e.message ?: e.javaClass.simpleName, e))
        }
    }
    private val dest: TransferFs get() = fs(job.destination)
    private fun sourceFs(item: TransferItem) = fs(job.sources[item.source].endpoint)
    private fun sameEndpoint(item: TransferItem) = job.sources[item.source].endpoint.key == job.destination.key

    /** Runs until the job ends, waits for a conflict answer, or is stopped. Closes every endpoint. */
    fun run() {
        try {
            if (!job.scanned) {
                setState(TransferStates.SCANNING)
                scan()
                job.scanned = true
                hooks.persist(job)
            }
            if (job.counts().conflicts > 0) {
                setState(TransferStates.CONFLICT)
                return
            }
            if (!job.spaceChecked) checkSpace()
            setState(TransferStates.RUNNING)
            var i = 0
            while (true) {
                val item = synchronized(job) { job.items.getOrNull(i) } ?: break
                i++
                if (item.isFinished || item.superseded) continue
                checkStopped()
                runItem(item)
                afterItem()
            }
            if (job.isMove) cleanUpMovedFolders()
            finish()
        } catch (e: Stopped) {
            // Cancelled (the manager marked the job) or the worker is stopping: items stay as they are.
        } catch (e: JobStop) {
            stopWith(e.error)
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never leave a job "running" without a worker: an unexpected failure fails it (Retry resumes).
            hooks.log("Unexpected failure: $e")
            if (hooks.isStopped()) throw e
            stopWith(HoardSmbException(ErrorCode.UNKNOWN, e.message ?: e.javaClass.simpleName, e))
        } finally {
            opened.values.forEach { runCatching { it.close() } }
            opened.clear()
        }
    }

    // --- Scan --------------------------------------------------------------------------------------

    private var lastScanEmit = 0L

    private fun scan() {
        synchronized(job) { job.items.clear() }
        for ((index, source) in job.sources.withIndex()) {
            checkStopped()
            val sfs = fs(source.endpoint)
            val stat = guarded { sfs.call { stat(source.path) } }
            if (stat == null) {
                add(TransferItem(nextIndex(), index, source.path, TransferPaths.join(job.destPath, TransferPaths.name(source.path)), ItemKinds.FILE, 0, null).apply {
                    fail(HoardSmbException(ErrorCode.NOT_FOUND, "${source.path} does not exist"))
                })
                continue
            }
            val top = TransferPaths.join(job.destPath, stat.name)
            val same = source.endpoint.key == job.destination.key
            if (same && stat.isDirectory && TransferPaths.isSameOrInside(source.path, job.destPath)) {
                throw JobStop(HoardSmbException(ErrorCode.UNKNOWN, "${stat.name} cannot go into itself"))
            }
            if (same && job.isMove && TransferPaths.parent(source.path) == job.destPath) {
                // Moving something into the folder it is already in: nothing to do.
                add(TransferItem(nextIndex(), index, source.path, source.path, ItemKinds.RENAME, if (stat.isDirectory) -1 else stat.size, stat.modifiedMillis, state = TransferStates.SKIPPED))
                continue
            }
            if (same && job.isMove && guarded { dest.call { stat(top) } } == null) {
                // One rename, however big the folder (ADR 0007: never copy + delete when a move is cheap).
                add(TransferItem(nextIndex(), index, source.path, top, ItemKinds.RENAME, if (stat.isDirectory) -1 else stat.size, stat.modifiedMillis))
                continue
            }
            if (stat.isDirectory) walk(index, sfs, source.path, top) else addFile(index, source.path, top, stat)
        }
    }

    private fun walk(source: Int, sfs: TransferFs, from: String, to: String) {
        add(TransferItem(nextIndex(), source, from, to, ItemKinds.FOLDER, 0, null))
        val children = guarded { sfs.call { list(from) } }.sortedBy { it.name.lowercase(Locale.ROOT) }
        for (child in children) {
            checkStopped()
            if (PartNames.isPart(child.name)) continue
            val childFrom = TransferPaths.join(from, child.name)
            val childTo = TransferPaths.join(to, child.name)
            if (child.isDirectory) walk(source, sfs, childFrom, childTo) else addFile(source, childFrom, childTo, child)
        }
    }

    private fun addFile(source: Int, from: String, to: String, stat: FileStat) {
        val item = TransferItem(nextIndex(), source, from, to, ItemKinds.FILE, stat.size, stat.modifiedMillis, name = stat.name.takeIf { job.sources[source].endpoint is Endpoint.Incoming } ?: TransferPaths.name(to))
        if (job.conflict == Conflicts.ASK && existsAtDestination(TransferPaths.parent(to), TransferPaths.name(to))) {
            item.conflict = Conflicts.ASK
        }
        add(item)
    }

    /** Ask mode: whether the name exists, listing each destination folder once. */
    private fun existsAtDestination(folder: String, name: String): Boolean {
        val names = destNames.getOrPut(folder) {
            val listing = guarded { dest.call { if (stat(folder)?.isDirectory == true) list(folder) else emptyList() } }
            listing.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
        }
        return name.lowercase(Locale.ROOT) in names
    }

    private fun nextIndex() = synchronized(job) { job.items.size }

    private fun add(item: TransferItem) {
        synchronized(job) { job.items += item }
        val now = hooks.now()
        if (now - lastScanEmit >= 250) {
            lastScanEmit = now
            hooks.emit(job, null)
        }
    }

    // --- Free space --------------------------------------------------------------------------------

    private fun checkSpace() {
        val needed = synchronized(job) {
            job.items.sumOf {
                if (it.kind != ItemKinds.FILE || it.isFinished || it.superseded || (job.isMove && sameEndpoint(it))) 0L
                else (it.size - it.bytesDone).coerceAtLeast(0)
            }
        }
        if (needed > 0) {
            val free = guarded { dest.call { freeBytes(job.destPath) } }
            if (free != null && free in 0 until needed) {
                throw JobStop(
                    HoardSmbException(
                        ErrorCode.NO_SPACE,
                        "Needs $needed bytes, $free free at the destination",
                        data = mapOf("needed" to needed, "free" to free),
                    ),
                )
            }
        }
        job.spaceChecked = true
    }

    // --- Items -------------------------------------------------------------------------------------

    private fun runItem(item: TransferItem) {
        job.currentName = item.name
        job.currentBytes = 0
        synchronized(job) {
            item.state = TransferStates.RUNNING
            item.errorCode = null
            item.errorMessage = null
        }
        try {
            when (item.kind) {
                ItemKinds.FOLDER -> {
                    guarded { dest.call { ensureFolder(item.destPath) } }
                    complete(item)
                }
                ItemKinds.RENAME -> renameItem(item)
                else -> fileItem(item)
            }
        } catch (e: ItemFailure) {
            synchronized(job) { item.fail(e.error) }
            hooks.log("${item.sourcePath}: ${e.error.code} ${e.error.message}")
        }
    }

    private fun TransferFs.ensureFolder(path: String) {
        val existing = stat(path)
        if (existing?.isDirectory == true) return
        if (existing != null) throw ItemFailure(HoardSmbException(ErrorCode.ALREADY_EXISTS, "${TransferPaths.name(path)} exists and is not a folder"))
        mkdir(path)
    }

    private fun renameItem(item: TransferItem) {
        val renamed = guarded { dest.call { rename(item.sourcePath, item.destPath, false) } }
        if (renamed) {
            complete(item)
            return
        }
        // The endpoint cannot move it in one step (another filesystem behind the same root, a SAF
        // provider without move support): copy it instead, then delete the source as usual.
        synchronized(job) { item.superseded = true; item.state = TransferStates.SKIPPED }
        val sfs = sourceFs(item)
        val stat = guarded { sfs.call { stat(item.sourcePath) } } ?: throw ItemFailure(HoardSmbException(ErrorCode.NOT_FOUND, "${item.sourcePath} does not exist"))
        if (stat.isDirectory) walk(item.source, sfs, item.sourcePath, item.destPath) else addFile(item.source, item.sourcePath, item.destPath, stat)
        job.spaceChecked = false
        checkSpace()
    }

    private fun fileItem(item: TransferItem) {
        val sfs = sourceFs(item)
        val same = sameEndpoint(item)
        val src = guarded { sfs.call { stat(item.sourcePath) } }
        if (src == null || src.isDirectory) {
            // Moved already (the job was persisted just before the source was deleted), or copied by
            // a previous run of a lost job: the destination holds it.
            if (job.isMove || job.resumeExisting) {
                val there = guarded { dest.call { stat(item.destPath) } }
                if (there != null && !there.isDirectory && there.size == item.size) return complete(item)
            }
            throw ItemFailure(HoardSmbException(ErrorCode.NOT_FOUND, "${item.sourcePath} does not exist"))
        }
        val unchanged = item.bytesDone == 0L || (src.size == item.size && (item.modifiedMillis == null || src.modifiedMillis == item.modifiedMillis))
        synchronized(job) {
            item.size = src.size
            item.modifiedMillis = src.modifiedMillis
            if (!unchanged) item.bytesDone = 0
        }

        // Where it lands, per the conflict policy.
        var replace = false
        if (!item.direct) {
            val existing = guarded { dest.call { stat(item.destPath) } }
            if (existing != null) {
                if (job.resumeExisting && !existing.isDirectory && existing.size == src.size && item.bytesDone == 0L) return complete(item)
                val policy = when {
                    existing.isDirectory -> Conflicts.RENAME
                    item.conflict in Conflicts.POLICIES -> item.conflict!!
                    job.conflict in Conflicts.POLICIES -> job.conflict
                    else -> Conflicts.RENAME
                }
                when (policy) {
                    Conflicts.SKIP -> {
                        synchronized(job) { item.state = TransferStates.SKIPPED }
                        return
                    }
                    Conflicts.OVERWRITE -> replace = true
                    else -> synchronized(job) { item.destPath = freeName(item.destPath) }
                }
            }
        }

        if (same && job.isMove && !item.direct) {
            if (guarded { dest.call { rename(item.sourcePath, item.destPath, replace) } }) return complete(item)
        }

        val part = if (item.direct) item.destPath else TransferPaths.join(TransferPaths.parent(item.destPath), PartNames.of(TransferPaths.name(item.destPath)))
        copyBytes(item, sfs, part, same && !job.isMove, unchanged)

        // Verify before publishing (and before a move deletes anything).
        val written = guarded { dest.call { stat(part) } }
        if (written == null || (src.size >= 0 && written.size != src.size)) {
            runCatching { dest.delete(part) }
            synchronized(job) { item.bytesDone = 0 }
            throw ItemFailure(HoardSmbException(ErrorCode.UNKNOWN, "${item.name}: wrote ${written?.size ?: 0} of ${src.size} bytes"))
        }
        if (src.size < 0) synchronized(job) { item.size = written.size }
        if (!item.direct) {
            val published = guarded { dest.call { rename(part, item.destPath, replace) } }
            if (!published) throw ItemFailure(HoardSmbException(ErrorCode.UNKNOWN, "Could not name ${item.name}"))
            rememberName(item.destPath)
        }
        src.modifiedMillis?.let { millis -> runCatching { dest.call { setModified(item.destPath, millis) } } }
        writeSidecar(item)

        if (job.isMove) {
            // Only a source that is still exactly what was copied is deleted.
            val now = guarded { sfs.call { stat(item.sourcePath) } }
            if (now != null && now.size == src.size && now.modifiedMillis == src.modifiedMillis) {
                guarded { sfs.call { delete(item.sourcePath) } }
            } else {
                hooks.log("${item.sourcePath} changed during the move; the source was kept")
            }
        }
        complete(item)
    }

    /**
     * The XMP sidecar of a copied file (ADR 0011, "Copy picks"), next to it at the destination, through the shared
     * sidecar module ([Sidecars]): merged into the shot's existing sidecar there (`<stem>.xmp`, `<file>.xmp` or
     * their hidden forms, ADR 0011 "Naming"), else a new `<its destination stem>.xmp` (`.<stem>.xmp` with the
     * hidden option), so it follows a conflict rename (`DSC1 (1).ARW` → `DSC1 (1).xmp`). A malformed sidecar is
     * left as it is. Best effort: the photo is already safe, so a failure is logged, not an item error.
     */
    private fun writeSidecar(item: TransferItem) {
        val fields = job.sidecars[item.sourcePath] ?: return
        val folder = TransferPaths.parent(item.destPath)
        val name = TransferPaths.name(item.destPath)
        val stem = name.substringBeforeLast('.', name)
        try {
            val names = sidecarNames.getOrPut(folder) {
                dest.call { list(folder) }.filter { !it.isDirectory && SidecarNames.isSidecar(it.name) }
                    .associateTo(HashMap()) { it.name.lowercase(Locale.ROOT) to it.name }
            }
            val existing = SidecarNames.find(stem, listOf(name)) { names[it] }
            when (val result = dest.call { Sidecars.write(this, folder, stem, existing, fields, job.hiddenSidecars, SidecarTool.creatorTool, IsoTime.now()) }) {
                is SidecarWrite.Written -> {
                    names[result.name.lowercase(Locale.ROOT)] = result.name
                    rememberName(TransferPaths.join(folder, result.name))
                }
                is SidecarWrite.Malformed -> hooks.log("${item.name}: ${result.name} is not well-formed XMP; left as it was")
                is SidecarWrite.Failed -> hooks.log("${item.name}: sidecar not written: ${result.error.message}")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            hooks.log("${item.name}: sidecar not written: ${e.message}")
        }
    }

    /** `photo.jpg` → `photo (1).jpg` against the destination folder's names (and those this job added). */
    private fun freeName(path: String): String {
        val folder = TransferPaths.parent(path)
        val names = destNames.getOrPut(folder) {
            guarded { dest.call { list(folder) } }.mapTo(HashSet()) { it.name.lowercase(Locale.ROOT) }
        }
        val free = ConflictNames.firstFree(TransferPaths.name(path), names)
        names += free.lowercase(Locale.ROOT)
        return TransferPaths.join(folder, free)
    }

    private fun rememberName(path: String) {
        destNames[TransferPaths.parent(path)]?.add(TransferPaths.name(path).lowercase(Locale.ROOT))
    }

    /**
     * Stream (or server-copy) the source into [part], resuming where the bytes on disk allow.
     * Re-entered after every reconnect, so the offset is worked out again each time.
     */
    private fun copyBytes(item: TransferItem, sfs: TransferFs, part: String, sameShareCopy: Boolean, unchanged: Boolean) {
        var sourceUnchanged = unchanged
        guarded {
            val partSize = dest.call { stat(part) }?.takeIf { !it.isDirectory }?.size
            val offset = ResumeRules.offset(item.bytesDone, partSize, item.size, sourceUnchanged, contiguous = !dest.isNetwork)
            sourceUnchanged = true
            if (offset > 0) hooks.log("${item.name}: resuming at $offset of ${item.size} bytes")
            synchronized(job) { item.bytesDone = offset }
            job.currentBytes = offset
            if (sameShareCopy && dest.call { serverCopySlices(part, item, offset) }) return@guarded
            val writer = dest.call { openWrite(part, offset) }
            try {
                val reader = sfs.call { openRead(item.sourcePath, offset) }
                try {
                    val buffer = ByteArray(bufferSize)
                    while (true) {
                        checkStopped()
                        val n = sfs.call { reader.read(buffer, 0, buffer.size) }
                        if (n < 0) break
                        if (n == 0) continue
                        dest.call { writer.write(buffer, 0, n) }
                        progress(item, writer.position)
                    }
                } finally {
                    runCatching { reader.close() }
                }
                dest.call { writer.finish() }
                progress(item, writer.position)
            } finally {
                runCatching { writer.close() }
            }
        }
    }

    /** SMB server-side copy in 32 MiB slices (progress between them); false when unsupported. */
    private fun TransferFs.serverCopySlices(part: String, item: TransferItem, offset: Long): Boolean {
        var at = offset
        if (at == 0L && !serverCopy(item.sourcePath, part, 0, 0)) return false
        while (at < item.size) {
            checkStopped()
            val length = minOf(SERVER_COPY_SLICE, item.size - at)
            if (!serverCopy(item.sourcePath, part, at, length)) return false
            at += length
            progress(item, at)
        }
        return true
    }

    private fun progress(item: TransferItem, position: Long) {
        synchronized(job) { item.bytesDone = position }
        job.currentBytes = position
        val now = hooks.now()
        val done = job.bytesDone()
        speed.add(now, done)
        if (now - lastPersist >= PERSIST_INTERVAL_MS) {
            lastPersist = now
            hooks.persist(job)
        }
        if (throttle.shouldEmit(now, done, job.totalBytes())) {
            throttle.mark(now, done)
            hooks.emit(job, speed.bytesPerSecond())
        }
    }

    private fun complete(item: TransferItem) {
        synchronized(job) {
            item.state = TransferStates.COMPLETED
            if (item.kind == ItemKinds.FILE) item.bytesDone = item.size
        }
    }

    private fun afterItem() {
        job.currentBytes = 0
        if (job.state == TransferStates.RETRYING) setState(TransferStates.RUNNING)
        val now = hooks.now()
        if (now - lastPersist >= PERSIST_INTERVAL_MS) {
            lastPersist = now
            hooks.persist(job)
        }
        hooks.emit(job, speed.bytesPerSecond())
    }

    // --- Moves: empty source folders ----------------------------------------------------------------

    /** Delete the source folders whose every item was moved, deepest first, only when empty. */
    private fun cleanUpMovedFolders() {
        val folders = synchronized(job) { job.items.filter { it.kind == ItemKinds.FOLDER && !it.superseded } }
        if (folders.isEmpty()) return
        val keep = HashSet<String>()
        synchronized(job) {
            for (item in job.items) {
                if (item.kind == ItemKinds.FOLDER || item.superseded || item.state == TransferStates.COMPLETED) continue
                var parent = TransferPaths.parent(item.sourcePath)
                while (true) {
                    keep += "${item.source}:$parent"
                    if (parent.isEmpty()) break
                    parent = TransferPaths.parent(parent)
                }
            }
        }
        for (folder in folders.sortedByDescending { it.sourcePath.count { c -> c == '/' } }) {
            if ("${folder.source}:${folder.sourcePath}" in keep) continue
            val sfs = sourceFs(folder)
            runCatching {
                if (sfs.list(folder.sourcePath).isEmpty()) sfs.delete(folder.sourcePath)
            }.onFailure { hooks.log("Kept ${folder.sourcePath}: ${it.message}") }
        }
    }

    // --- Retries and failures ------------------------------------------------------------------------

    /**
     * Runs [block], retrying dropped routes per the endpoint's [FailoverPlan] (reconnecting to the
     * address it picks); a failure about the item or the job is rethrown as such.
     */
    private fun <T> guarded(block: () -> T): T {
        while (true) {
            val before = job.currentBytes
            try {
                return block()
            } catch (e: EndpointFailure) {
                // An interrupted request while the worker stops is not the request's fault.
                if (hooks.isStopped() || job.isTerminal) throw Stopped()
                val error = e.error
                when (FailureScope.of(error.code, e.fs.isNetwork)) {
                    FailureScope.Scope.ITEM -> throw ItemFailure(error)
                    FailureScope.Scope.JOB -> throw JobStop(error)
                    FailureScope.Scope.RETRY -> {
                        val plan = plans.getOrPut(e.fs) { FailoverPlan(e.fs.addressCount) }
                        val decision = plan.onFailure(error.code, job.currentBytes > before)
                        if (decision !is FailoverPlan.Decision.Retry) throw JobStop(error)
                        hooks.log("${e.fs.label}: ${error.code} ${error.message}; retrying")
                        job.errorCode = error.code
                        job.errorMessage = error.message
                        setState(TransferStates.RETRYING)
                        runCatching { e.fs.reconnect(decision.addressIndex) }
                        speed.reset()
                        hooks.sleep(decision.delayMs)
                        checkStopped()
                    }
                }
            }
        }
    }

    private fun checkStopped() {
        if (hooks.isStopped() || job.isTerminal) throw Stopped()
    }

    private fun setState(state: String) {
        job.state = state
        if (state == TransferStates.RUNNING) {
            job.errorCode = null
            job.errorMessage = null
        }
        job.activeHost = opened.values.firstNotNullOfOrNull { it.activeHost } ?: job.activeHost
        hooks.persist(job)
        lastPersist = hooks.now()
        hooks.emit(job, null)
    }

    /** The job cannot go on: every item left fails with [error] (Retry runs them again). */
    private fun stopWith(error: HoardSmbException) {
        synchronized(job) {
            job.items.forEach { if (!it.isFinished && !it.superseded) it.fail(error) }
        }
        job.errorCode = error.code
        job.errorMessage = error.message
        end(TransferStates.FAILED)
    }

    private fun finish() {
        val counts = job.counts()
        val state = when {
            counts.failed > 0 -> TransferStates.FAILED
            counts.total > 0 && counts.skipped == counts.total -> TransferStates.SKIPPED
            else -> TransferStates.COMPLETED
        }
        if (state == TransferStates.FAILED && job.errorCode == null) {
            val first = synchronized(job) { job.items.firstOrNull { it.state == TransferStates.FAILED } }
            job.errorCode = first?.errorCode ?: ErrorCode.UNKNOWN
            job.errorMessage = if (counts.failed == 1) first?.errorMessage else "${counts.failed} items failed; first: ${first?.errorMessage}"
        }
        end(state)
    }

    private fun end(state: String) {
        if (job.isTerminal) return
        job.currentName = null
        job.currentBytes = 0
        job.finishedAt = dev.hfps.hoard.smb.IsoTime.now()
        if (state != TransferStates.FAILED) {
            job.errorCode = null
            job.errorMessage = null
        }
        job.state = state
        hooks.persist(job)
        hooks.emit(job, null)
    }

    companion object {
        const val PERSIST_INTERVAL_MS = 2_000L
        const val SERVER_COPY_SLICE = 32L shl 20
    }
}
