package dev.hfps.hoard.server.compress

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The app gives the worker its replace engine (manual DI, ADR 0019) and the foreground notification of a long run. */
interface CompressHost {
    suspend fun runCompress(stopped: () -> Boolean, foreground: suspend (CompressProgress) -> Unit): CompressRunResult

    /** Downloads only on unmetered networks (the default: outputs are a fifth of the uploads). */
    suspend fun compressUnmetered(): Boolean = true

    /** The `dataSync` foreground notification of a run (strings are the app's). */
    fun compressForeground(progress: CompressProgress): ForegroundInfo
}

/**
 * The phone's replace worker (ADR 0025 "Phone replace flow (Hoard)"): a run after each Gallery sync that saw items of
 * this phone waiting for it, after a backup pass that stored new videos of a folder compressed automatically, after a
 * restore was asked, and every [LATER_MINUTES] while the server still compresses videos of this phone. Constraints:
 * an unmetered network by default, battery not low. A long download runs as a foreground `dataSync` service; a run
 * Android ends early resumes from the journal.
 */
class CompressWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? CompressHost ?: return Result.success()
        val result = try {
            host.runCompress({ isStopped }) { progress -> runCatching { setForeground(host.compressForeground(progress)) } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        val unmetered = runCatching { host.compressUnmetered() }.getOrDefault(true)
        when {
            result.outcome == CompressOutcome.Offline -> return if (runAttemptCount < 3) Result.retry() else Result.success()
            result.retryAt != null -> later(applicationContext, unmetered, ((result.retryAt - System.currentTimeMillis()) / 60_000).coerceIn(1, 360))
            result.serverPending -> later(applicationContext, unmetered, LATER_MINUTES)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = (applicationContext as CompressHost).compressForeground(CompressProgress(running = true))

    companion object {
        const val SOON = "phone-compress-soon"
        const val NOW = "phone-compress-now"
        const val LATER = "phone-compress-later"
        const val LATER_MINUTES = 5L

        fun constraints(unmetered: Boolean): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (unmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** A run [delayMinutes] from now (debounced: a waiting one starts its wait again; one more follows a running one). */
        fun soon(context: Context, unmetered: Boolean = true, delayMinutes: Long = 0) {
            enqueue(context, SOON, unmetered, delayMinutes)
        }

        /** The follow-up while the server still works on this phone's videos (one waits at a time). */
        fun later(context: Context, unmetered: Boolean = true, delayMinutes: Long = LATER_MINUTES) {
            enqueue(context, LATER, unmetered, delayMinutes)
        }

        private fun enqueue(context: Context, name: String, unmetered: Boolean, delayMinutes: Long) {
            val manager = WorkManager.getInstance(context)
            val states = runCatching { manager.getWorkInfosForUniqueWork(name).get().map { it.state } }.getOrDefault(emptyList())
            val running = WorkInfo.State.RUNNING in states
            val waiting = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            if (running && waiting) return
            val request = OneTimeWorkRequestBuilder<CompressWorker>()
                .setConstraints(constraints(unmetered))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork(name, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }

        /** At once (a restore from the Trash screen, "Replace now"): expedited, any network. */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<CompressWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            for (name in listOf(SOON, NOW, LATER)) manager.cancelUniqueWork(name)
        }
    }
}
