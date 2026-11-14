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
        suspend fun refresh() = send(withContext(Dispatchers.IO) { registry.list() })
        refresh()
        val monitor = VolumeMonitor(appContext) { launch { refresh() } }
        monitor.start()
        awaitClose { monitor.stop() }
    }
}
