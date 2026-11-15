package dev.hfps.hoard.server.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** The app gives the worker its one [SyncEngine] (manual DI, ADR 0019). */
interface GallerySyncHost {
    /** Runs a sync; false when there is nothing to do (not paired). */
    suspend fun syncGallery(): Boolean

    /** Fills the offline thumbnails (ADR 0021) until done or [stopped]; true when every item has one. */
    suspend fun prefetchThumbs(stopped: () -> Boolean): Boolean
}

/** The offline thumbnails job: unmetered networks only, optionally while charging (ADR 0021). */
class OfflineThumbsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? GallerySyncHost ?: return Result.success()
        return try {
            val done = host.prefetchThumbs { isStopped }
            // Thumbs the server has not made yet: back off and ask again (not in 6 hours).
            if (done || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        } catch (e: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "gallery-offline-thumbs"
        private const val MAX_ATTEMPTS = 12

        fun schedule(context: Context, chargingOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresCharging(chargingOnly)
                .build()
            val request = PeriodicWorkRequestBuilder<OfflineThumbsWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Once now (after a sync brought new items), with the same constraints. */
        fun soon(context: Context, chargingOnly: Boolean) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).setRequiresCharging(chargingOnly).build()
            val request = OneTimeWorkRequestBuilder<OfflineThumbsWorker>().setConstraints(constraints)
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork("$NAME-now", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            WorkManager.getInstance(context).cancelUniqueWork("$NAME-now")
        }
    }
}

/**
 * Background sync of the Gallery (ADR 0021): every hour with a network, plus a one-off soon after a decision or when
 * the app starts. Pushes the culling queue, pulls the items feed, pushes again.
 */
class GallerySyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? GallerySyncHost ?: return Result.success()
        return try {
            host.syncGallery()
            Result.success()
        } catch (e: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val PERIODIC = "gallery-sync"
        private const val SOON = "gallery-sync-now"
        private val NETWORK = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<GallerySyncWorker>(1, TimeUnit.HOURS).setConstraints(NETWORK).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** A sync in [delaySeconds] (decisions are pushed in a batch shortly after the user stops). */
        fun soon(context: Context, delaySeconds: Long = 5) {
            val request = OneTimeWorkRequestBuilder<GallerySyncWorker>().setConstraints(NETWORK).setInitialDelay(delaySeconds, TimeUnit.SECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(SOON)
        }
    }
}
