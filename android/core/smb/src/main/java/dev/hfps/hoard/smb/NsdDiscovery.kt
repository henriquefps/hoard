package dev.hfps.hoard.smb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** Contract `DiscoveredService`. */
data class DiscoveredService(val name: String, val host: String, val port: Int)

/**
 * Browses `_smb._tcp` with NsdManager for a fixed time and resolves every hit (Android 14+:
 * ServiceInfoCallback, which can run in parallel; older: resolveService, one at a time since the
 * framework rejects concurrent resolves). Holds a Wi-Fi multicast lock while browsing so mDNS
 * answers are not filtered by the Wi-Fi chip's power save.
 *
 * Permissions: INTERNET, ACCESS_NETWORK_STATE and CHANGE_WIFI_MULTICAST_STATE. NEARBY_WIFI_DEVICES is
 * not needed (it guards Wi-Fi scanning / Wi-Fi Direct / Aware, not NSD).
 */
class NsdDiscovery(private val context: Context) {

    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    suspend fun discover(timeoutMs: Long): List<DiscoveredService> {
        val manager = nsd ?: return emptyList()
        val found = ConcurrentHashMap<String, DiscoveredService>()
        val resolver: Resolver = if (Build.VERSION.SDK_INT >= 34) CallbackResolver(manager) else SerialResolver(manager)
        val started = CompletableDeferred<Unit>()
        val lock = multicastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                started.complete(Unit)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started.completeExceptionally(
                    HoardSmbException(ErrorCode.UNKNOWN, "Network discovery could not start (NSD error $errorCode)"),
                )
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                resolver.resolve(info) { name, addresses, port ->
                    val host = pickAddress(addresses) ?: return@resolve
                    val key = name.lowercase(Locale.ROOT)
                    val service = DiscoveredService(name, host, if (port > 0) port else Net.DEFAULT_PORT)
                    // Keep the IPv4 answer if one service resolved twice.
                    found.merge(key, service) { old, new -> if (old.host.contains(':') && !new.host.contains(':')) new else old }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            lock?.acquire()
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            val startedAt = System.currentTimeMillis()
            withTimeoutOrNull(timeoutMs) { started.await() }
            delay((timeoutMs - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0))
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
            resolver.close()
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        return found.values.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    @SuppressLint("WifiManagerPotentialLeak")
    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("hoard-nsd")
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()

    private interface Resolver {
        fun resolve(info: NsdServiceInfo, onResolved: (String, List<InetAddress>, Int) -> Unit)
        fun close()
    }

    /** Android 14+: one ServiceInfoCallback per service, unregistered after the first answer. */
    @RequiresApi(34)
    private class CallbackResolver(private val manager: NsdManager) : Resolver {
        // Never shut down: NsdManager keeps delivering late callbacks (e.g. "unregistered") after
        // discovery ended, and a rejected task crashes the process. The callbacks are tiny, so run
        // them inline on NSD's own thread.
        private val executor = Executor { it.run() }
        private val callbacks = ConcurrentHashMap.newKeySet<NsdManager.ServiceInfoCallback>()
        @Volatile private var closed = false

        override fun resolve(info: NsdServiceInfo, onResolved: (String, List<InetAddress>, Int) -> Unit) {
            if (closed) return
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(updated: NsdServiceInfo) {
                    if (updated.hostAddresses.isEmpty()) return
                    onResolved(updated.serviceName ?: info.serviceName, updated.hostAddresses, updated.port)
                    if (callbacks.remove(this)) runCatching { manager.unregisterServiceInfoCallback(this) }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    callbacks.remove(this)
                }

                override fun onServiceLost() = Unit
                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            callbacks += callback
            runCatching { manager.registerServiceInfoCallback(info, executor, callback) }
                .onFailure { callbacks.remove(callback) }
        }

        override fun close() {
            closed = true
            callbacks.toList().forEach { runCatching { manager.unregisterServiceInfoCallback(it) } }
            callbacks.clear()
        }
    }

    /** Before Android 14: resolveService allows one resolve at a time, so queue them. */
    @Suppress("DEPRECATION")
    private class SerialResolver(private val manager: NsdManager) : Resolver {
        private val queue = ArrayDeque<Pair<NsdServiceInfo, (String, List<InetAddress>, Int) -> Unit>>()
        private var busy = false
        private var closed = false

        @Synchronized
        override fun resolve(info: NsdServiceInfo, onResolved: (String, List<InetAddress>, Int) -> Unit) {
            if (closed) return
            queue.addLast(info to onResolved)
            if (!busy) next()
        }

        @Synchronized
        private fun next() {
            val (info, onResolved) = queue.removeFirstOrNull() ?: run { busy = false; return }
            if (closed) return
            busy = true
            val listener = object : NsdManager.ResolveListener {
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    resolved.host?.let { onResolved(resolved.serviceName ?: info.serviceName, listOf(it), resolved.port) }
                    next()
                }

                override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                    next()
                }
            }
            runCatching { manager.resolveService(info, listener) }.onFailure { next() }
        }

        @Synchronized
        override fun close() {
            closed = true
            queue.clear()
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_smb._tcp."

        /** IPv4 first, then a global/ULA IPv6, never a link-local IPv6 without a scope. */
        fun pickAddress(addresses: List<InetAddress>): String? {
            addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.let { return it.hostAddress }
            return addresses.firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }?.hostAddress
                ?.substringBefore('%')
        }
    }
}
