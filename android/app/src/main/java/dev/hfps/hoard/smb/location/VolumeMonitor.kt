package dev.hfps.hoard.smb.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

/** A volume event as the plugin emits it (`VolumesChangedEvent.change`). */
data class VolumeChange(val uuid: String?, val state: String)

/**
 * `volumesChanged`: USB drives and SD cards attached, mounted, ejected or pulled out. Android 11+
 * reports them through `StorageManager.StorageVolumeCallback`; older versions through the
 * `ACTION_MEDIA_*` broadcasts (system broadcasts, delivered to a runtime receiver). Bursts (a drive
 * goes checking → mounted within a second) are coalesced: [onChange] runs 400 ms after the last
 * event with the last change.
 */
class VolumeMonitor(private val context: Context, private val onChange: (VolumeChange?) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private var pending: VolumeChange? = null
    private val flush = Runnable {
        val change = pending
        pending = null
        onChange(change)
    }
    private var receiver: BroadcastReceiver? = null
    private var callback: Any? = null

    private fun post(change: VolumeChange?) {
        if (change != null) pending = change
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, DEBOUNCE_MS)
    }

    fun start() {
        val storage = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val cb = object : StorageManager.StorageVolumeCallback() {
                override fun onStateChanged(volume: StorageVolume) {
                    post(VolumeChange(if (volume.isPrimary) null else volume.uuid, VolumeMapping.state(volume.state) ?: "removed"))
                }
            }
            storage.registerStorageVolumeCallback(Executor { handler.post(it) }, cb)
            callback = cb
            return
        }
        val filter = IntentFilter().apply {
            ACTIONS.forEach(::addAction)
            addDataScheme("file")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val path = intent.data?.path
                val volume = path?.let { p -> storage.storageVolumes.firstOrNull { volumePath(it) == p } }
                val uuid = volume?.uuid ?: path?.substringAfterLast('/')
                post(VolumeChange(uuid, stateFor(intent.action)))
            }
        }
        ContextCompat.registerReceiver(context, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    fun stop() {
        handler.removeCallbacks(flush)
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (callback as? StorageManager.StorageVolumeCallback)?.let {
                runCatching { context.getSystemService(StorageManager::class.java).unregisterStorageVolumeCallback(it) }
            }
        }
        callback = null
    }

    private fun volumePath(volume: StorageVolume): String? =
        runCatching { volume.javaClass.getMethod("getPath").invoke(volume) as? String }.getOrNull()

    companion object {
        private const val DEBOUNCE_MS = 400L

        private val ACTIONS = listOf(
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_CHECKING,
            Intent.ACTION_MEDIA_UNMOUNTABLE,
        )

        /** Broadcast action → contract state (pure). */
        fun stateFor(action: String?): String = when (action) {
            Intent.ACTION_MEDIA_MOUNTED -> "mounted"
            Intent.ACTION_MEDIA_CHECKING -> "checking"
            Intent.ACTION_MEDIA_EJECT -> "ejecting"
            Intent.ACTION_MEDIA_UNMOUNTABLE -> "unmountable"
            Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL -> "removed"
            else -> "unmounted"
        }
    }
}
