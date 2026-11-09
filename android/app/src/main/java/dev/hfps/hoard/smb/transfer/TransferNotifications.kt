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

/** The ongoing transfer notification (foreground service), "files already exist", and the final "done/failed" one. */
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

    private fun title(context: Context, job: TransferJob): String {
        val counts = job.counts()
        return if (counts.total > 1) {
            context.resources.getQuantityString(R.plurals.transfer_files, counts.total, counts.total)
        } else {
            job.displayName()
        }
    }

    /** Upload when the bytes go to a server, download otherwise. */
    private fun icon(job: TransferJob?, done: Boolean = false): Int = when {
        job?.destination is Endpoint.Smb -> if (done) android.R.drawable.stat_sys_upload_done else android.R.drawable.stat_sys_upload
        else -> if (done) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_sys_download
    }

    fun progress(context: Context, transferId: String, job: TransferJob?, bytesPerSecond: Long?): Notification {
        ensureChannels(context)
        val counts = job?.counts()
        val totalBytes = job?.totalBytes() ?: 0
        val doneBytes = job?.bytesDone() ?: 0
        val text = when (job?.state) {
            null, TransferStates.QUEUED -> context.getString(R.string.transfer_waiting)
            TransferStates.SCANNING -> context.getString(R.string.transfer_scanning)
            TransferStates.RETRYING -> context.getString(R.string.transfer_reconnecting)
            else -> {
                val done = Formatter.formatShortFileSize(context, doneBytes)
                val total = Formatter.formatShortFileSize(context, totalBytes)
                val base = if (counts != null && counts.total > 1) {
                    context.getString(R.string.transfer_items_progress, counts.done + counts.skipped + counts.failed, counts.total, done, total)
                } else {
                    context.getString(R.string.transfer_progress, done, total)
                }
                if (bytesPerSecond != null && bytesPerSecond > 0) "$base · ${Formatter.formatShortFileSize(context, bytesPerSecond)}/s" else base
            }
        }
        val percent = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt() else 0
        val indeterminate = job?.state != TransferStates.RUNNING || totalBytes <= 0
        return NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(icon(job))
            .setContentTitle(job?.let { title(context, it) } ?: "Hoard")
            .setContentText(text)
            .setSubText(job?.currentName?.takeIf { counts != null && counts.total > 1 } ?: job?.activeHost)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.transfer_cancel), cancelIntent(context, transferId))
            .build()
    }

    fun foregroundInfo(context: Context, transferId: String, job: TransferJob?, bytesPerSecond: Long?): ForegroundInfo {
        val notification = progress(context, transferId, job, bytesPerSecond)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(progressId(transferId), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressId(transferId), notification)
        }
    }

    /** Update the ongoing notification in place (no-op without the notification permission). */
    fun update(context: Context, job: TransferJob, bytesPerSecond: Long?) {
        notify(context, progressId(job.transferId), progress(context, job.transferId, job, bytesPerSecond))
    }

    /** "Some files already exist": the job waits for the user's answer in the app. */
    fun conflict(context: Context, job: TransferJob) {
        ensureChannels(context)
        NotificationManagerCompat.from(context).cancel(progressId(job.transferId))
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(context.getString(R.string.transfer_conflict_title))
            .setContentText(context.getString(R.string.transfer_conflict_text, title(context, job)))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
        notify(context, resultId(job.transferId), builder.build())
    }

    /** "Done" (tap opens a single file) or "Failed"; nothing for cancel/skip. */
    fun result(context: Context, job: TransferJob) {
        ensureChannels(context)
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(progressId(job.transferId))
        val counts = job.counts()
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle(title(context, job))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        when (job.state) {
            TransferStates.COMPLETED -> {
                builder.setSmallIcon(icon(job, done = true))
                    .setContentText(context.getString(R.string.transfer_completed, Formatter.formatShortFileSize(context, job.totalBytes())))
                    .setContentIntent(openAppIntent(context))
            }
            TransferStates.FAILED -> {
                val reason = if (counts.total > 1 && counts.failed < counts.total) {
                    context.getString(R.string.transfer_partly_failed, counts.failed, counts.total)
                } else {
                    job.errorMessage ?: job.errorCode?.name ?: ""
                }
                builder.setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentText(context.getString(R.string.transfer_failed, reason))
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
