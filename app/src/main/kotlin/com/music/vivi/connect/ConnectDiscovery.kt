/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.core.content.getSystemService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Advertises this device and finds the others, using Android's built-in
 * Network Service Discovery (mDNS/Bonjour).
 *
 * NSD rather than Nearby Connections: no Play Services dependency, no location
 * permission, and on a stable Wi-Fi network discovery is both faster and far
 * less battery-hungry than Nearby's Bluetooth scanning.
 */
class ConnectDiscovery(
    context: Context,
    private val selfId: String,
    private val selfName: String,
) {

    private val nsdManager = context.getSystemService<NsdManager>()

    private val _peers = MutableStateFlow<Map<String, ConnectDevice>>(emptyMap())
    val peers: StateFlow<Map<String, ConnectDevice>> = _peers.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Guards against the resolver's documented "already active" failure. */
    private val resolving = mutableSetOf<String>()

    fun start(localPort: Int, fingerprint: String) {
        val manager = nsdManager ?: run {
            Timber.w("No NsdManager; Vivi Connect unavailable")
            return
        }
        register(manager, localPort, fingerprint)
        discover(manager, fingerprint)
    }

    private fun register(manager: NsdManager, localPort: Int, fingerprint: String) {
        val info = NsdServiceInfo().apply {
            // mDNS instance names must be unique on the network and are capped
            // at 63 bytes; a long device name would be silently rejected.
            serviceName = "Vivi ${selfName.take(24)} ${selfId.take(6)}"
            serviceType = ConnectProtocol.SERVICE_TYPE
            port = localPort
            setAttribute(ConnectProtocol.ATTR_DEVICE_ID, selfId)
            setAttribute(ConnectProtocol.ATTR_DEVICE_NAME, selfName)
            setAttribute(ConnectProtocol.ATTR_FINGERPRINT, fingerprint)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Timber.i("Connect advertising as %s", info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Timber.w("Connect registration failed: %d", errorCode)
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { Timber.w(it, "Could not register Connect service") }
    }

    private fun discover(manager: NsdManager, fingerprint: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Timber.d("Connect discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("vivimusic") != true) return
                resolve(manager, info, fingerprint)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                // The TXT record is gone by the time a service is lost, so match
                // on the advertised instance name we cached at resolve time.
                _peers.value = _peers.value.filterValues { !name.contains(it.id.take(6)) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Timber.w("Connect discovery failed to start: %d", errorCode)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(
                ConnectProtocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        }.onFailure { Timber.w(it, "Could not start Connect discovery") }
    }

    private fun resolve(manager: NsdManager, info: NsdServiceInfo, fingerprint: String) {
        val key = info.serviceName ?: return
        synchronized(resolving) {
            if (!resolving.add(key)) return
        }

        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(resolving) { resolving.remove(key) }
                Timber.d("Connect resolve failed for %s: %d", key, errorCode)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                synchronized(resolving) { resolving.remove(key) }

                fun attr(name: String) = resolved.attributes[name]
                    ?.toString(Charsets.UTF_8)

                val peerId = attr(ConnectProtocol.ATTR_DEVICE_ID) ?: return
                val peerFingerprint = attr(ConnectProtocol.ATTR_FINGERPRINT)

                // Never surface a device on a different account. Showing it and
                // failing at handshake time would just look broken.
                if (peerFingerprint != fingerprint) {
                    Timber.d("Ignoring Connect peer on a different account")
                    return
                }
                if (peerId == selfId) return

                val host = resolved.host?.hostAddress ?: return
                _peers.value = _peers.value + (peerId to ConnectDevice(
                    id = peerId,
                    name = attr(ConnectProtocol.ATTR_DEVICE_NAME) ?: "Vivi device",
                    host = host,
                    port = resolved.port,
                ))
                Timber.i("Connect found %s at %s:%d", peerId.take(6), host, resolved.port)
            }
        })
    }

    fun markConnected(peerId: String, connected: Boolean) {
        _peers.value[peerId]?.let {
            _peers.value = _peers.value + (peerId to it.copy(connected = connected))
        }
    }

    fun stop() {
        val manager = nsdManager ?: return
        registrationListener?.let { runCatching { manager.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        _peers.value = emptyMap()
    }
}
