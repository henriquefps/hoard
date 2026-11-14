package dev.hfps.hoard.smb.transfer

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Runs one job as a WorkManager job promoted to a `dataSync` foreground service (ADR 0007). The
 * work itself is [TransferRunner]; this class adds the Android parts: the slot (two jobs at once),
 * the notification, persistence and progress events. A system stop (constraints, quota) leaves the
 * job `queued`; WorkManager runs it again and the runner resumes from the persisted items.
 */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val manager = TransferManager.get(context)
    private val transferId = params.inputData.getString(KEY_TRANSFER_ID).orEmpty()
    private var lastNotificationAt = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val job = manager.store.get(transferId)
        return TransferNotifications.foregroundInfo(applicationContext, transferId, job, null)
    }

    override suspend fun doWork(): Result {
        val initial = manager.store.get(transferId) ?: return Result.success()
        if (initial.isTerminal || initial.state == TransferStates.CONFLICT) return Result.success()
        promote(initial)
        return try {
            manager.slots.withPermit {
                val job = manager.store.get(transferId)
                if (job != null && !job.isTerminal) runInterruptible(Dispatchers.IO) { run(job) }
            }
            Result.success()
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val job = manager.store.get(transferId)
                if (job != null && !job.isTerminal) {
                    job.state = TransferStates.QUEUED
                    manager.store.save(job)
                    manager.emit(job)
                }
            }
            throw e
        }
    }

    private fun run(job: TransferJob) {
        val hooks = object : RunnerHooks {
            override fun now() = SystemClock.elapsedRealtime()

            override fun emit(job: TransferJob, bytesPerSecond: Long?) {
                manager.emit(job, bytesPerSecond)
                val now = now()
                if (job.isTerminal) {
                    TransferNotifications.result(applicationContext, job)
                } else if (now - lastNotificationAt >= NOTIFICATION_INTERVAL_MS || job.state != TransferStates.RUNNING) {
                    lastNotificationAt = now
                    TransferNotifications.update(applicationContext, job, bytesPerSecond)
                }
            }

            override fun persist(job: TransferJob) = manager.store.save(job)

            override fun sleep(ms: Long) = Thread.sleep(ms)

            override fun isStopped() = isStopped || Thread.currentThread().isInterrupted

            override fun log(message: String) {
                Log.i(TAG, "Transfer $transferId: $message")
            }
        }
        TransferRunner(job, manager.endpoints::open, hooks).run()
        if (job.state == TransferStates.CONFLICT) TransferNotifications.conflict(applicationContext, job)
    }

    /** Become a foreground service; Android 12+ may refuse when started from the background. */
    private suspend fun promote(job: TransferJob) {
        try {
            setForeground(TransferNotifications.foregroundInfo(applicationContext, transferId, job, null))
        } catch (e: Exception) {
            Log.w(TAG, "Running $transferId without a foreground service: ${e.message}")
        }
    }

    companion object {
        const val KEY_TRANSFER_ID = "transferId"
        private const val TAG = "HoardSmb"
        private const val NOTIFICATION_INTERVAL_MS = 1_000L
    }
}
