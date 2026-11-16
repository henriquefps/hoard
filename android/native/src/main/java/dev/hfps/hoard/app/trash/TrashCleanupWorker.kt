package dev.hfps.hoard.app.trash

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.hfps.hoard.app.HoardApplication
import java.util.concurrent.TimeUnit

/**
 * Auto-empty (ADR 0023): once a day, the day folders older than Settings › Trash's setting (30 days by default; 7, 90
 * or off) are deleted in every reachable trash root, including those another device made. No network constraint:
 * local volumes need none, and an unreachable share is skipped until the next day.
 */
class TrashCleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? HoardApplication ?: return Result.success()
        runCatching { app.container.trash.autoEmpty() }
        return Result.success()
    }

    companion object {
        private const val NAME = "trash-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
