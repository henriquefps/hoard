package dev.hfps.hoard.app.ui.files

import android.content.Context
import dev.hfps.hoard.smb.location.VolumeInfo
import dev.hfps.hoard.smb.location.VolumeMonitor
import dev.hfps.hoard.smb.location.VolumeRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone's storage and the USB drives / SD cards attached now, from :core:smb's [VolumeRegistry] (the same code
 * the Capacitor plugin's `listVolumes` uses), refreshed by [VolumeMonitor] on every attach / mount / eject.
 */
class VolumesSource(context: Context) {
    private val appContext = context.applicationContext
    private val registry = VolumeRegistry(appContext)

    fun observe(): Flow<List<VolumeInfo>> = callbackFlow {
        // A volume query that fails (a storage service hiccup) keeps the last list rather than ending the flow.
        var last = emptyList<VolumeInfo>()
        suspend fun refresh() {
            last = withContext(Dispatchers.IO) { runCatching { registry.list() }.getOrDefault(last) }
            send(last)
        }
        refresh()
        val monitor = VolumeMonitor(appContext) { launch { refresh() } }
        monitor.start()
        awaitClose { monitor.stop() }
    }
}
