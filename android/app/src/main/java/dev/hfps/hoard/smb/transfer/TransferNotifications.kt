package dev.hfps.hoard.smb.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import dev.hfps.hoard.R
import dev.hfps.hoard.smb.SafDestinations

/** The ongoing transfer notification (foreground service) and the final "done/failed" one. */
object TransferNotifications {

    private const val CHANNEL_PROGRESS = "transfers"
    private const val CHANNEL_RESULT = "transfer_results"
    const val ACTION_CANCEL = "dev.hfps.hoard.action.CANCEL_TRANSFER"
    const val EXTRA_TRANSFER_ID = "transferId"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.transfer_channel_progress),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.transfer_channel_progress_description)
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                context.getString(R.string.transfer_channel_results),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.transfer_channel_results_description)
            },
        )
    }

    /** Stable per-transfer ids: even for the ongoing notification, odd for the result. */
    fun progressId(transferId: String) = 0x4000 + (transferId.hashCode() and 0x3FFFFF) * 2
    private fun resultId(transferId: String) = progressId(transferId) + 1

    private fun openAppIntent(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun cancelIntent(context: Context, transferId: String): PendingIntent {
        val intent = Intent(context, TransferCancelReceiver::class.java)
            .setAction(ACTION_CANCEL)
            .setData(Uri.parse("hoard-transfer:$transferId")) // distinct PendingIntent per job
            .putExtra(EXTRA_TRANSFER_ID, transferId)
        return PendingIntent.getBroadcast(context, progressId(transferId), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun progress(context: Context, job: TransferJob, bytesPerSecond: Long?): Notification {
        ensureChannels(context)
        val text = when (job.state) {
            TransferStates.QUEUED -> context.getString(R.string.transfer_waiting)
            TransferStates.RETRYING -> context.getString(R.string.transfer_reconnecting)
            else -> {
                val done = Formatter.formatShortFileSize(context, job.bytesDone)
                val total = Formatter.formatShortFileSize(context, job.totalBytes)
                if (bytesPerSecond != null && bytesPerSecond > 0) {
                    context.getString(R.string.transfer_progress_speed, done, total, Formatter.formatShortFileSize(context, bytesPerSecond))
                } else {
                    context.getString(R.string.transfer_progress, done, total)
                }
            }
        }
        val percent = if (job.totalBytes > 0) ((job.bytesDone * 100) / job.totalBytes).toInt() else 0
        val indeterminate = job.state != TransferStates.RUNNING || job.totalBytes <= 0
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(job.destName)
            .setContentText(text)
            .setSubText(job.activeHost)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.transfer_cancel), cancelIntent(context, job.transferId))
            .build()
    }

    fun foregroundInfo(context: Context, job: TransferJob, bytesPerSecond: Long?): ForegroundInfo {
        val notification = progress(context, job, bytesPerSecond)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(progressId(job.transferId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressId(job.transferId), notification)
        }
    }

    /** Update the ongoing notification in place (no-op without the notification permission). */
    fun update(context: Context, job: TransferJob, bytesPerSecond: Long?) {
        notify(context, progressId(job.transferId), progress(context, job, bytesPerSecond))
    }

    /** "Downloaded" (tap opens the file) or "Download failed"; nothing for cancel/skip. */
    fun result(context: Context, job: TransferJob) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(progressId(job.transferId))
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle(job.destName)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        when (job.state) {
            TransferStates.COMPLETED -> {
                builder.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText(context.getString(R.string.transfer_completed, Formatter.formatShortFileSize(context, job.totalBytes)))
                    .setContentIntent(viewIntent(context, job) ?: openAppIntent(context))
            }
            TransferStates.FAILED -> {
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText(context.getString(R.string.transfer_failed, job.errorMessage ?: job.errorCode?.name ?: ""))
                    .setContentIntent(openAppIntent(context))
            }
            else -> return
        }
        notify(context, resultId(job.transferId), builder.build())
    }

    fun cancelAll(context: Context, transferId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(progressId(transferId))
        manager.cancel(resultId(transferId))
    }

    private fun viewIntent(context: Context, job: TransferJob): PendingIntent? {
        val uri = job.destUri?.let(Uri::parse) ?: return null
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, SafDestinations.mimeTypeFor(job.destName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        if (view.resolveActivity(context.packageManager) == null) return null
        return PendingIntent.getActivity(context, resultId(job.transferId), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    @Suppress("MissingPermission")
    private fun notify(context: Context, id: Int, notification: Notification) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+: the transfer still runs.
        }
    }
}

/** The notification's Cancel action. Not exported: only our own PendingIntent can reach it. */
class TransferCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TransferNotifications.ACTION_CANCEL) return
        val transferId = intent.getStringExtra(TransferNotifications.EXTRA_TRANSFER_ID) ?: return
        val pending = goAsync()
        Thread {
            try {
                TransferManager.get(context).cancel(transferId)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
