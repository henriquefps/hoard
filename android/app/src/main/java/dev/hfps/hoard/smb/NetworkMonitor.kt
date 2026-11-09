package dev.hfps.hoard.smb

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper

/** Contract `NetworkChangedEvent`. */
data class NetworkState(val transport: String, val vpn: Boolean, val networkId: String?)

/**
 * Watches the default network and reports changes (Wi-Fi joined/left, VPN up/down, switching to
 * another Wi-Fi) after a short debounce, so a flapping handover produces one event.
 */
class NetworkMonitor(context: Context, private val onChange: (NetworkState) -> Unit) {

    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var lastEmitted: NetworkState? = null
    private var pending: NetworkState? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
            schedule(stateOf(network, capabilities))

        override fun onLost(network: Network) = schedule(NetworkState("none", false, null))
    }

    private val emit = Runnable {
        val state = pending ?: return@Runnable
        val previous = lastEmitted
        lastEmitted = state
        // The first callback after registering is the current state, not a change.
        if (previous != null && previous != state) onChange(state)
    }

    fun start() {
        if (registered || connectivity == null) return
        lastEmitted = current()
        runCatching { connectivity.registerDefaultNetworkCallback(callback, handler) }
            .onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacks(emit)
        runCatching { connectivity?.unregisterNetworkCallback(callback) }
        registered = false
    }

    /** The default network right now. */
    fun current(): NetworkState {
        val network = connectivity?.activeNetwork ?: return NetworkState("none", false, null)
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return NetworkState("none", false, null)
        return stateOf(network, capabilities)
    }

    private fun schedule(state: NetworkState) {
        pending = state
        handler.removeCallbacks(emit)
        handler.postDelayed(emit, DEBOUNCE_MS)
    }

    companion object {
        private const val DEBOUNCE_MS = 750L

        /** VPN networks also carry their underlying transport (Android 12+), so check that first. */
        fun transportOf(capabilities: NetworkCapabilities): String = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }

        fun stateOf(network: Network, capabilities: NetworkCapabilities) = NetworkState(
            transport = transportOf(capabilities),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            networkId = network.toString(),
        )
    }
}
