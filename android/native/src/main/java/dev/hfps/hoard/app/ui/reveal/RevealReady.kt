package dev.hfps.hoard.app.ui.reveal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.hfps.hoard.app.R
import dev.hfps.hoard.smb.share.RevealHandoff

/**
 * "Ready to open in Reveal" (ADR 0010 amendment "2.2.0: local copies"): a copy that ends while Hoard is in the
 * background cannot start Reveal (Android 10+ blocks activity starts from the background), so the prepared hand-off
 * goes into a notification whose tap sends it (the same intents and grants, through a `PendingIntent`).
 */
object RevealReady {
    const val CHANNEL = "reveal_ready"
    const val NOTIFICATION_ID = 4210

    /** The intents as a `PendingIntent` starts them: the first one opens a new task. */
    fun pendingIntents(prepared: RevealHandoff.Prepared): Array<Intent> =
        prepared.intents.mapIndexed { i, intent -> Intent(intent).also { if (i == 0) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) } }.toTypedArray()

    /** Posts the notification; false when notifications are off (the screen then offers to send it when Hoard returns). */
    fun post(context: Context, prepared: RevealHandoff.Prepared, title: String?): Boolean {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.reveal_ready_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val pending = PendingIntent.getActivities(
            context, NOTIFICATION_ID, pendingIntents(prepared),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(context.getString(R.string.reveal_ready_title))
            .setContentText(title?.let { context.getString(R.string.reveal_ready_body_titled, it) } ?: context.getString(R.string.reveal_ready_body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
