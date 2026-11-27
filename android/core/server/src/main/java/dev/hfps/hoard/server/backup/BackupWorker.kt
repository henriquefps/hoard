package dev.hfps.hoard.server.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The app gives the worker its backup (manual DI, ADR 0019), and the foreground notification of a long run. */
interface BackupHost {
    /** [manual]: "Back up now" (it tries uploads at once even while the server's backup volume is full). */
    suspend fun runBackup(stopped: () -> Boolean, foreground: suspend (BackupProgress) -> Unit, manual: Boolean = false): BackupRunResult

    suspend fun backupRules(): BackupRules

    /** The `dataSync` foreground notification of a run (strings are the app's). */
    fun backupForeground(progress: BackupProgress): ForegroundInfo
}

/**
 * The phone backup's worker (ADR 0024 "`BackupWorker`"): a periodic run every 6 hours, a debounced one-off 2 minutes
 * after the device index sees new media, and an expedited "Back up now"; constraints from the rules (unmetered or any
 * network, charging, always battery not low). Long uploads run as a foreground `dataSync` service; a run Android ends
 * early (the daily `dataSync` budget, a lost constraint) resumes from the stored offsets next time.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? BackupHost ?: return Result.success()
        val result = try {
            host.runBackup({ isStopped }, { progress ->
                // Android 12+ refuses a foreground service started from the background in some states: the run goes on.
                runCatching { setForeground(host.backupForeground(progress)) }
            }, manual = inputData.getBoolean(KEY_MANUAL, false))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        if (result.waitingForCharging) runCatching { whileCharging(applicationContext, host.backupRules()) }
        // The server's backup volume is full: one run when the wait is due (no retry loop; ADR 0024 Amendment (2.9.1)).
        result.retryAt?.let { due -> runCatching { at(applicationContext, host.backupRules(), due - System.currentTimeMillis()) } }
        return when (result.outcome) {
            BackupOutcome.Offline -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    /** Expedited work on Android 11 and older runs as a foreground service from the start. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val host = applicationContext as BackupHost
        return host.backupForeground(BackupProgress(running = true))
    }

    companion object {
        const val PERIODIC = "phone-backup"
        const val SOON = "phone-backup-soon"
        const val NOW = "phone-backup-now"
        const val CHARGING = "phone-backup-charging"
        const val LATER = "phone-backup-later"
        const val KEY_MANUAL = "manual"
        const val SOON_DELAY_MINUTES = 2L

        /** The rules as WorkManager constraints: unmetered (or any) network, charging when asked, battery not low. */
        fun constraints(rules: BackupRules, charging: Boolean = rules.photosCharging): Constraints = Constraints.Builder()
            .setRequiredNetworkType(if (rules.photosUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(charging)
            .setRequiresBatteryNotLow(true)
            .build()

        /** The periodic run (every 6 hours); [rules] changed: the constraints are replaced. */
        fun schedule(context: Context, rules: BackupRules) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints(rules))
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /**
         * A run [SOON_DELAY_MINUTES] after new media, debounced: a pending one starts its wait again; while one runs,
         * one more follows it (what arrived during the run is backed up too).
         */
        fun soon(context: Context, rules: BackupRules) {
            val manager = WorkManager.getInstance(context)
            val states = runCatching { manager.getWorkInfosForUniqueWork(SOON).get().map { it.state } }.getOrDefault(emptyList())
            val running = WorkInfo.State.RUNNING in states
            val waiting = states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }
            if (running && waiting) return
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(rules))
                .setInitialDelay(SOON_DELAY_MINUTES, TimeUnit.MINUTES)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            manager.enqueueUniqueWork(SOON, if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * "Back up now": expedited, network only (expedited work takes no charging or battery constraint); the run
         * applies the rules itself, file by file, so it still waits for Wi-Fi or charging where they ask.
         */
        fun now(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(androidx.work.workDataOf(KEY_MANUAL to true))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Videos (or everything, with "Only while charging") wait for the charger: one run once it is plugged in. One
         * waits at a time; when the charging run itself leaves files waiting (unplugged again), one more follows it.
         */
        fun whileCharging(context: Context, rules: BackupRules = BackupRules()) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(rules, charging = true))
                .build()
            val manager = WorkManager.getInstance(context)
            val states = runCatching { manager.getWorkInfosForUniqueWork(CHARGING).get().map { it.state } }.getOrDefault(emptyList())
            // One waiting is enough; called from the charging run itself (unplugged again meanwhile), one follows it.
            if (states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED }) return
            manager.enqueueUniqueWork(CHARGING, if (WorkInfo.State.RUNNING in states) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE, request)
        }

        /** One run after [delayMs] (the end of a wait for room on the server), under the rules; replaces an earlier one. */
        fun at(context: Context, rules: BackupRules, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(rules))
                .setInitialDelay(delayMs.coerceAtLeast(60_000L), TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(LATER, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            val manager = WorkManager.getInstance(context)
            for (name in listOf(PERIODIC, SOON, NOW, CHARGING, LATER)) manager.cancelUniqueWork(name)
        }
    }
}
