package dev.hfps.hoard.smb.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.PipelinedFileReader
import dev.hfps.hoard.smb.ErrorCode
import dev.hfps.hoard.smb.HoardSmbException
import dev.hfps.hoard.smb.SmbConnector
import dev.hfps.hoard.smb.SmbErrors
import dev.hfps.hoard.smb.SmbHandle
import dev.hfps.hoard.smb.SmbPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumSet
import kotlinx.coroutines.currentCoroutineContext

/**
 * Runs one download SMB → SAF as a WorkManager job promoted to a `dataSync` foreground service.
 * Bytes stream from a pipelined SMB reader straight into the destination file descriptor; nothing
 * goes through the WebView. Connection loss → retry/failover per [FailoverPlan], re-checking the
 * server GUID before every authentication and resuming at the byte already written.
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val manager = TransferManager.get(context)
    private val transferId = params.inputData.getString(KEY_TRANSFER_ID).orEmpty()
    private val speed = SpeedMeter()
    private val throttle = ProgressThrottle()
    private var lastPersistAt = 0L
    private var lastNotificationAt = 0L

    /** A failure the failover plan must not retry (the job is wrong, not the route). */
    private class Fatal(code: ErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause) {
        val error = HoardSmbException(code, message, cause)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val job = manager.store.get(transferId)
            ?: return TransferNotifications.foregroundInfo(applicationContext, placeholder(), null)
        return TransferNotifications.foregroundInfo(applicationContext, job, null)
    }

    private fun placeholder() = TransferJob(
        transferId = transferId, machineId = "", addresses = emptyList(), expectedServerGuid = "",
        share = "", remotePath = "", destTreeUri = "", destName = "Hoard", destUri = null, startedAt = "",
    )

    override suspend fun doWork(): Result {
        val initial = manager.store.get(transferId) ?: return Result.success()
        if (initial.isTerminal) return Result.success()
        promote(initial)
        return try {
            manager.slots.withPermit {
                val job = manager.store.get(transferId)
                if (job != null && !job.isTerminal) transfer(job)
            }
            Result.success()
        } catch (e: CancellationException) {
            // Stopped by the system (constraints, quota, timeout) or by cancelTransfer. A user cancel
            // already marked the job; otherwise WorkManager reschedules and we resume from the file.
            withContext(NonCancellable) {
                val job = manager.store.get(transferId)
                if (job != null && !job.isTerminal) {
                    manager.store.update(transferId) { it.copy(state = TransferStates.QUEUED) }?.let { manager.emit(it) }
                }
                manager.store.flush()
            }
            throw e
        }
    }

    /** Become a foreground service; Android 12+ may refuse when started from the background. */
    private suspend fun promote(job: TransferJob) {
        try {
            setForeground(TransferNotifications.foregroundInfo(applicationContext, job, null))
        } catch (e: Exception) {
            Log.w(TAG, "Running $transferId without a foreground service: ${e.message}")
        }
    }

    /** The destination file stays open across reconnects; only the SMB side is re-established. */
    private var output: Output? = null

    private suspend fun transfer(initial: TransferJob) {
        val plan = FailoverPlan(initial.addresses.size)
        try {
            while (attempt(plan)) {
                currentCoroutineContext().ensureActive()
            }
        } finally {
            closeOutput(sync = false)
        }
    }

    /** One connect + copy over the plan's current address. Returns true to try again. */
    private suspend fun attempt(plan: FailoverPlan): Boolean {
        val job = current() ?: return false
        val address = job.addresses[plan.addressIndex]
        val bytesAtConnect = output?.position ?: job.bytesDone
        var handle: SmbHandle? = null
        val failure: Throwable = try {
            handle = SmbConnector.open(address.host, address.port, job.expectedServerGuid, CONNECT_TIMEOUT_MS) {
                manager.credentials.get(job.machineId)
            }
            copy(handle, address.host)
            closeOutput(sync = true)
            manager.finish(transferId, TransferStates.COMPLETED)?.let {
                manager.emit(it)
                TransferNotifications.result(applicationContext, it)
            }
            return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        } finally {
            handle?.let(::closeInBackground)
        }
        return onFailure(plan, job, address.host, failure, (output?.position ?: 0) > bytesAtConnect)
    }

    private suspend fun onFailure(plan: FailoverPlan, job: TransferJob, host: String, failure: Throwable, progressed: Boolean): Boolean {
        var error = if (failure is Fatal) failure.error else SmbErrors.map(failure)
        if (error.code == ErrorCode.CANCELLED && current() != null) {
            // Not a user cancel (that marks the job terminal first): an interrupted I/O, retry it.
            error = HoardSmbException(ErrorCode.UNKNOWN, error.message ?: "Interrupted", failure)
        }
        Log.w(TAG, "Transfer $transferId via $host: ${error.code} ${error.message}")
        val decision = plan.onFailure(error.code, progressed, failure is Fatal)
        if (decision !is FailoverPlan.Decision.Retry) {
            val written = output?.position ?: current()?.bytesDone ?: 0
            closeOutput(sync = false)
            // Nothing written into a file this job created: don't leave an empty file behind.
            if (written == 0L && job.createdDocument) job.destUri?.let { manager.destinations.delete(Uri.parse(it)) }
            manager.finish(transferId, TransferStates.FAILED, error)?.let {
                manager.emit(it)
                TransferNotifications.result(applicationContext, it)
            }
            return false
        }
        val next = job.addresses[decision.addressIndex]
        val position = output?.position
        val updated = manager.store.update(transferId) {
            if (it.isTerminal) it else it.copy(
                state = TransferStates.RETRYING,
                bytesDone = position ?: it.bytesDone,
                errorCode = error.code,
                errorMessage = error.message,
                activeHost = next.host,
            )
        }
        if (updated == null || updated.isTerminal) return false
        speed.reset()
        manager.emit(updated)
        TransferNotifications.update(applicationContext, updated, null)
        delay(decision.delayMs)
        return true
    }

    private fun closeOutput(sync: Boolean) {
        output?.close(sync)
        output = null
    }

    /** Logoff/close can block on a dead link; never hold the worker for it. */
    private fun closeInBackground(handle: SmbHandle) {
        Dispatchers.IO.asExecutor().execute { handle.close() }
    }

    private fun current(): TransferJob? = manager.store.get(transferId)?.takeIf { !it.isTerminal }

    /** Destination file descriptor plus a stream on it; kept open across SMB reconnects. */
    private class Output(val pfd: ParcelFileDescriptor, offset: Long) {
        val stream = FileOutputStream(pfd.fileDescriptor)

        /** Exact number of bytes in the destination file (the resume point). */
        var position: Long = offset
            private set

        fun write(chunk: ByteArray) {
            stream.write(chunk)
            position += chunk.size
        }

        fun close(sync: Boolean) {
            runCatching {
                stream.flush()
                if (sync) pfd.fileDescriptor.sync()
            }
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
    }

    /** Open the remote file, (re)open the destination at the right offset and stream to the end. */
    private suspend fun copy(handle: SmbHandle, host: String): Unit = runInterruptible(Dispatchers.IO) {
        val job = current() ?: throw CancellationException("Transfer is no longer active")
        val share = try {
            handle.session.connectShare(job.share) as? DiskShare
        } catch (e: Exception) {
            throw fatalIfDefinite(e)
        } ?: throw Fatal(ErrorCode.NOT_FOUND, "${job.share} is not a disk share")

        val file = try {
            share.openFile(
                SmbPaths.toSmb(job.remotePath),
                EnumSet.of(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE),
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE, SMB2CreateOptions.FILE_SEQUENTIAL_ONLY),
            )
        } catch (e: Exception) {
            if ((e as? SMBApiException)?.statusCode?.and(0xFFFFFFFFL) == 0xC00000BAL) { // STATUS_FILE_IS_A_DIRECTORY
                throw Fatal(ErrorCode.UNKNOWN, "Folder downloads are not supported yet", e)
            }
            throw fatalIfDefinite(e)
        }

        file.use { remote ->
            val info = remote.fileInformation
            val total = info.standardInformation.endOfFile
            val modified = info.basicInformation.lastWriteTime?.toEpochMillis()
            val destUri = Uri.parse(job.destUri ?: throw Fatal(ErrorCode.UNKNOWN, "No destination document"))

            val remoteChanged = job.started && (job.totalBytes != total || (job.remoteModifiedMillis != null && job.remoteModifiedMillis != modified))
            if (remoteChanged) {
                // The source changed between attempts: the bytes we have belong to another version.
                closeOutput(sync = false)
            }
            // Stored in the field right away, so a failure later in this attempt cannot leak it.
            val output = this@TransferWorker.output ?: run {
                if (!job.started) {
                    manager.destinations.availableBytes(Uri.parse(job.destTreeUri), destUri)?.let { free ->
                        val needed = total - job.resumeFromBytes
                        if (free in 0 until needed) throw Fatal(ErrorCode.NO_SPACE, "Not enough space for ${job.destName}")
                    }
                }
                openOutput(job, destUri, total, remoteChanged).also { this@TransferWorker.output = it }
            }
            val start = output.position

            val started = manager.store.update(transferId) {
                if (it.isTerminal) it else it.copy(
                    state = TransferStates.RUNNING,
                    totalBytes = total,
                    bytesDone = start,
                    activeHost = host,
                    errorCode = null,
                    errorMessage = null,
                    remoteModifiedMillis = modified,
                    started = true,
                )
            } ?: throw CancellationException("Transfer removed")
            if (started.isTerminal) throw CancellationException("Transfer cancelled")
            manager.emit(started)
            TransferNotifications.update(applicationContext, started, null)
            throttle.mark(SystemClock.elapsedRealtime(), start)

            val reader = PipelinedFileReader(remote, start, total, depth = PIPELINE_DEPTH, timeoutMs = STALL_TIMEOUT_MS)
            while (output.position < total) {
                if (Thread.interrupted()) throw InterruptedException()
                val chunk = reader.next() ?: break
                try {
                    output.write(chunk)
                } catch (e: IOException) {
                    if (SmbErrors.isNoSpace(e)) throw Fatal(ErrorCode.NO_SPACE, "The destination is full", e)
                    throw Fatal(ErrorCode.PERMISSION_REVOKED, "Could not write ${job.destName}: ${e.message}", e)
                }
                progress(output.position, total)
            }
            if (output.position < total) throw IOException("The remote file ended at ${output.position} of $total bytes")
        }
    }

    /** Open the destination for this run, deciding where to resume. */
    private fun openOutput(job: TransferJob, destUri: Uri, total: Long, restart: Boolean): Output {
        val wanted = when {
            restart -> 0L
            job.started -> ResumeRules.restart(manager.destinations.size(destUri), total)
            else -> job.resumeFromBytes.coerceAtMost(total)
        }
        if (wanted > 0) {
            val append = try {
                manager.destinations.openForWrite(destUri, append = true)
            } catch (e: HoardSmbException) {
                throw Fatal(e.code, e.message ?: "Destination unavailable", e)
            } catch (e: Exception) {
                null // Provider without append support: start over.
            }
            if (append != null) {
                val size = append.statSize
                if (size == wanted) return Output(append, wanted)
                Log.i(TAG, "Partial file has $size bytes, expected $wanted: restarting $transferId")
                runCatching { append.close() }
            }
        }
        val truncate = try {
            manager.destinations.openForWrite(destUri, append = false)
        } catch (e: HoardSmbException) {
            throw Fatal(e.code, e.message ?: "Destination unavailable", e)
        }
        return Output(truncate, 0)
    }

    /** Errors that are about the job, not the route (no access, no such file): don't fail over. */
    private fun fatalIfDefinite(e: Exception): Exception {
        val code = SmbErrors.classify(e)
        return if (code in FailoverPlan.FATAL) Fatal(code, SmbErrors.map(e).message ?: code.name, e) else e
    }

    private fun progress(done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        speed.add(now, done)
        val persist = now - lastPersistAt >= PERSIST_INTERVAL_MS
        if (persist) lastPersistAt = now
        if (!throttle.shouldEmit(now, done, total) && !persist) return
        val job = manager.store.update(transferId, persist) { if (it.isTerminal) it else it.copy(bytesDone = done) } ?: return
        if (job.isTerminal) throw InterruptedException("Transfer cancelled")
        if (throttle.shouldEmit(now, done, total)) {
            throttle.mark(now, done)
            val rate = speed.bytesPerSecond()
            manager.emit(job, rate)
            if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
                lastNotificationAt = now
                TransferNotifications.update(applicationContext, job, rate)
            }
        }
    }

    companion object {
        const val KEY_TRANSFER_ID = "transferId"
        private const val TAG = "HoardSmb"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PIPELINE_DEPTH = 4

        /**
         * A READ that has not answered in this long means the route died silently (Wi-Fi gone, VPN
         * down: no FIN ever arrives), so fail over instead of waiting for smbj's 30 s timeout. At
         * 1 MiB per READ this still allows links down to ~70 KB/s; a false alarm only costs a
         * reconnect, since the job resumes at the exact byte written.
         */
        private const val STALL_TIMEOUT_MS = 15_000L
        private const val PERSIST_INTERVAL_MS = 2_000L
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
