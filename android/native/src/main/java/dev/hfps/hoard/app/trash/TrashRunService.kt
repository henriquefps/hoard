package dev.hfps.hoard.app.trash

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hfps.hoard.app.HoardApplication
import dev.hfps.hoard.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The foreground notification of a long trash run (ADR 0023 "a foreground job like transfers"): it keeps the process
 * running with the screen off and shows the progress, with Cancel (the run stops after the shot it is moving). It only
 * follows [TrashService]: the run itself lives in the process scope, and its rows make a kill safe anyway.
 */
class TrashRunService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trash = (application as? HoardApplication)?.container?.trash
        if (trash == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) {
            trash.cancel()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, null), type) }
            .onFailure { stopSelf(); return START_NOT_STICKY }
        scope.launch {
            val manager = getSystemService(NotificationManager::class.java)
            // Until the run ends: one update per progress step.
            trash.active.combine(trash.progress) { active, progress -> active to progress }.first { (active, progress) ->
                if (active) runCatching { manager?.notify(NOTIFICATION_ID, notification(this@TrashRunService, progress)) }
                !active
            }
            ServiceCompat.stopForeground(this@TrashRunService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "trash"
        private const val NOTIFICATION_ID = 0x7a54
        private const val ACTION_CANCEL = "dev.hfps.hoard.trash.CANCEL"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TrashRunService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.trash_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        fun notification(context: Context, progress: TrashProgress?): Notification {
            ensureChannel(context)
            val cancel = PendingIntent.getService(
                context,
                1,
                Intent(context, TrashRunService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val text = progress?.let { context.getString(R.string.trash_notification_progress, it.filesDone, it.filesTotal) }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setContentTitle(context.getString(R.string.trash_notification_title))
                .setContentText(text)
                .setSubText(progress?.currentName)
                .setProgress(progress?.filesTotal ?: 0, progress?.filesDone ?: 0, progress == null || progress.filesTotal == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(launch)
                .addAction(0, context.getString(R.string.t_common_cancel), cancel)
                .build()
        }
    }
}
