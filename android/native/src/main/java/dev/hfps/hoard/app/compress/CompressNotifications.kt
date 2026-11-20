package dev.hfps.hoard.app.compress

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import dev.hfps.hoard.app.R
import dev.hfps.hoard.server.compress.CompressEngine
import dev.hfps.hoard.server.compress.CompressProgress

/** The phone replace's notifications (ADR 0025 A2): the worker's foreground `dataSync` one, and "allow the replace". */
object CompressNotifications {
    private const val CHANNEL = "phone-compress"
    /**
     * "Ready to replace" (ADR 0025 Amendment (2.8.1)): its own channel at the default importance, so it shows in the
     * shade and on the status bar (the run's channel is low and silent; a channel's importance cannot be raised later).
     */
    const val CHANNEL_CONSENT = "phone-compress-consent"
    private const val ID_RUN = 0x2500
    private const val ID_CONSENT = 0x2501

    /** Opens Settings › Compress videos, where the consent step is. */
    const val EXTRA_OPEN = "dev.hfps.hoard.open"
    const val OPEN_COMPRESS = "compress"

    private fun channel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.compress_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }
    }

    private fun consentChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_CONSENT, context.getString(R.string.compress_consent_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }

    fun foreground(context: Context, progress: CompressProgress): ForegroundInfo {
        channel(context)
        val text = when (progress.phase) {
            CompressEngine.PHASE_DOWNLOADING -> context.getString(
                R.string.compress_notification_downloading,
                progress.name.orEmpty(),
                Formatter.formatShortFileSize(context, progress.done),
                Formatter.formatShortFileSize(context, progress.size),
            )
            CompressEngine.PHASE_WRITING -> context.getString(R.string.compress_notification_writing, progress.name.orEmpty())
            else -> context.getString(R.string.compress_notification_checking)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.compress_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(progress.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), progress.done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), progress.size <= 0)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(ID_RUN, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(ID_RUN, notification)
    }

    /** Videos are ready to replace and wait for the owner's consent (the system dialog needs Hoard on screen). */
    fun consent(context: Context, count: Int) {
        if (count <= 0) return
        consentChannel(context)
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN, OPEN_COMPRESS)
        } ?: return
        val pending = PendingIntent.getActivity(context, ID_CONSENT, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_CONSENT)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentTitle(context.resources.getQuantityString(R.plurals.compress_phone_consent_title, count, count))
            .setContentText(context.getString(R.string.compress_phone_consent_text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(ID_CONSENT, notification) }
    }

    fun cancelConsent(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID_CONSENT) }
    }
}
