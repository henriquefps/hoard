package dev.hfps.hoard.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dev.hfps.hoard.app.R
import dev.hfps.hoard.server.backup.BackupProgress

/** The phone backup's foreground `dataSync` notification (ADR 0024 "`BackupWorker`": long uploads). */
object BackupNotifications {
    private const val CHANNEL = "phone-backup"
    private const val ID = 0x2400

    fun foreground(context: Context, progress: BackupProgress): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
        val text = if (progress.toUpload > 0) {
            context.getString(R.string.backup_notification_text, progress.uploaded.toString(), progress.toUpload.toString(), Formatter.formatShortFileSize(context, progress.bytesSent))
        } else {
            context.getString(R.string.backup_running_listing)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.backup_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(0, 0, progress.toUpload == 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(ID, notification)
    }
}
