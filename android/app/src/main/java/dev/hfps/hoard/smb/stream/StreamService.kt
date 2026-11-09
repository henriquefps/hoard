package dev.hfps.hoard.smb.stream

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.hfps.hoard.R

/**
 * Keeps Hoard's process running (and un-frozen) while a stream or the local web server is live:
 * the other app (VLC, Chrome) is in front, so without a foreground service Android would cache and
 * freeze Hoard and every read of the proxy FD / every HTTP request would hang. A `dataSync` service
 * (serving file bytes to another app is a data transfer); Android 15+ caps that type at 6 h a day,
 * after which [onTimeout] ends the streams.
 *
 * Started by [StreamRegistry] when its first token appears (always from a user action in the app,
 * so the foreground-start rules are met) and stopped with the last one. The notification lists
 * what is being served and has a Stop action. Swiping Hoard away keeps only streams in use.
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // As early as possible: a startForegroundService answered late (or stopped before the
        // answer) is an ANR/crash.
        promote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            promote() // startForegroundService must always be answered with startForeground
            StreamRegistry.clear()
            stop()
            return START_NOT_STICKY
        }
        promote()
        if (StreamRegistry.all().isEmpty()) stop()
        return START_NOT_STICKY
    }

    private fun promote() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(this),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
            running = true
        } catch (e: Exception) {
            Log.w(TAG, "Streaming without a foreground service: ${e.message}")
        }
    }

    private fun stop() {
        running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        StreamRegistry.trimIdle()
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "dataSync time limit reached: ending streams")
        StreamRegistry.clear()
        stop()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HoardStream"
        private const val CHANNEL = "hoard_streaming"
        private const val NOTIFICATION_ID = 0x3A00
        const val ACTION_STOP = "dev.hfps.hoard.action.STOP_STREAMS"

        @Volatile private var running = false

        /** Start, refresh or stop the service to match the registry. */
        fun sync(context: Context) {
            val active = StreamRegistry.all().isNotEmpty()
            when {
                active && !running -> try {
                    ensureChannel(context)
                    ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
                    running = true
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException when we are in the background.
                    Log.w(TAG, "Could not start the streaming service: ${e.message}")
                }
                active -> if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context)) }
                }
                running -> context.stopService(Intent(context, StreamService::class.java)).also { running = false }
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.stream_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = context.getString(R.string.stream_channel_description)
                    setShowBadge(false)
                },
            )
        }

        private fun notification(context: Context): Notification {
            ensureChannel(context)
            val entries = StreamRegistry.all()
            val text = when (entries.size) {
                0 -> context.getString(R.string.stream_idle)
                1 -> entries[0].name
                else -> context.resources.getQuantityString(R.plurals.stream_count, entries.size, entries.size)
            }
            val stop = PendingIntent.getService(
                context,
                1,
                Intent(context, StreamService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(context.getString(R.string.stream_title))
                .setContentText(text)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentIntent(launch)
                .addAction(0, context.getString(R.string.stream_stop), stop)
                .build()
        }
    }
}
