package io.github.nightlemon.photobackup.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class ServerDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)

    suspend fun find(serverId: String, timeoutMillis: Long = 6_000): DiscoveredServer? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val resolving = AtomicBoolean(false)
                lateinit var listener: NsdManager.DiscoveryListener

                fun finish(result: DiscoveredServer?) {
                    if (finished.compareAndSet(false, true)) {
                        runCatching { nsd.stopServiceDiscovery(listener) }
                        if (continuation.isActive) continuation.resume(result)
                    }
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.startsWith(SERVICE_TYPE) || !resolving.compareAndSet(false, true)) return
                        @Suppress("DEPRECATION")
                        nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                resolving.set(false)
                                val discoveredId = info.attributes["id"]?.decodeToString()
                                @Suppress("DEPRECATION")
                                val host = info.host?.hostAddress
                                if (discoveredId == serverId && !host.isNullOrBlank()) {
                                    finish(DiscoveredServer(host, info.port))
                                }
                            }
                        })
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                }
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) runCatching { nsd.stopServiceDiscovery(listener) }
                }
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }

    private companion object { const val SERVICE_TYPE = "_home-photo-backup._tcp." }
}

data class DiscoveredServer(val host: String, val port: Int)

