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
    private val selfKind: DeviceKind,
) {

    private val nsdManager = context.getSystemService<NsdManager>()

    private val _peers = MutableStateFlow<Map<String, ConnectDevice>>(emptyMap())
    val peers: StateFlow<Map<String, ConnectDevice>> = _peers.asStateFlow()

    private val _status = MutableStateFlow(ConnectStatus())
    val status: StateFlow<ConnectStatus> = _status.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    /** Guards against the resolver's documented "already active" failure. */
    private val resolving = mutableSetOf<String>()

    fun start(localPort: Int, fingerprints: Set<String>) {
        val manager = nsdManager ?: run {
            Timber.w("No NsdManager; Vivi Connect unavailable")
            return
        }
        register(manager, localPort, fingerprints)
        discover(manager, fingerprints)
    }

    private fun register(manager: NsdManager, localPort: Int, fingerprints: Set<String>) {
        val info = NsdServiceInfo().apply {
            // mDNS instance names must be unique on the network and are capped
            // at 63 bytes; a long device name would be silently rejected.
            serviceName = "Vivi ${selfName.take(24)} ${selfId.take(6)}"
            serviceType = ConnectProtocol.SERVICE_TYPE
            port = localPort
            setAttribute(ConnectProtocol.ATTR_DEVICE_ID, selfId)
            setAttribute(ConnectProtocol.ATTR_DEVICE_NAME, selfName)
            setAttribute(ConnectProtocol.ATTR_KIND, selfKind.name)

            setAttribute(
                ConnectProtocol.ATTR_FINGERPRINT,
                ConnectProtocol.encodeFingerprints(fingerprints),
            )
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                _status.value = _status.value.copy(advertising = true)
                Timber.i("Connect advertising as %s", info.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                _status.value = _status.value.copy(
                    advertising = false,
                    lastError = "advertise failed ($errorCode)",
                )
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

    private fun discover(manager: NsdManager, fingerprints: Set<String>) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = _status.value.copy(discovering = true)
                Timber.d("Connect discovery started")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("vivimusic") != true) return
                // Counted before any filtering, so "saw it but dropped it" is
                // distinguishable from "never saw it".
                _status.value = _status.value.copy(
                    servicesSeen = _status.value.servicesSeen + 1,
                )
                resolve(manager, info, fingerprints)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName ?: return
                // The TXT record is gone by the time a service is lost, so match
                // on the advertised instance name we cached at resolve time.
                _peers.value = _peers.value.filterValues { !name.contains(it.id.take(6)) }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = _status.value.copy(
                    discovering = false,
                    lastError = "discovery failed ($errorCode)",
                )
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

    private fun resolve(manager: NsdManager, info: NsdServiceInfo, fingerprints: Set<String>) {
        val key = info.serviceName ?: return
        synchronized(resolving) {
            if (!resolving.add(key)) return
        }

        @Suppress("DEPRECATION")
        manager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(resolving) { resolving.remove(key) }
                _status.value = _status.value.copy(
                    resolveFailures = _status.value.resolveFailures + 1,
                    lastError = "resolve failed ($errorCode)",
                )
                Timber.d("Connect resolve failed for %s: %d", key, errorCode)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                synchronized(resolving) { resolving.remove(key) }

                fun attr(name: String) = resolved.attributes[name]
                    ?.toString(Charsets.UTF_8)

                val peerId = attr(ConnectProtocol.ATTR_DEVICE_ID) ?: return
                val peerFingerprints = ConnectProtocol.decodeFingerprints(
                    attr(ConnectProtocol.ATTR_FINGERPRINT)
                )

                if (peerId == selfId) return

                // Discovery no longer rejects on this. The TXT record is the
                // least reliable part of the whole path — Android's NSD is known
                // to truncate and occasionally drop attributes — and dropping a
                // peer here made a valid device permanently invisible with no
                // way to tell that from "never saw it". Authentication now
                // happens in the TCP handshake, where the full identity set
                // travels over a stream we control. This is only a hint for the
                // UI and for diagnostics.
                val sameAccountHint = peerFingerprints.any { it in fingerprints }
                if (!sameAccountHint) {
                    _status.value = _status.value.copy(
                        rejectedDifferentAccount = _status.value.rejectedDifferentAccount + 1,
                    )
                    Timber.i(
                        "Peer %s advertised %s; we advertise %s — deferring to the handshake",
                        peerId.take(6),
                        peerFingerprints.joinToString(",") { it.take(4) },
                        fingerprints.joinToString(",") { it.take(4) },
                    )
                }

                val host = resolved.host?.hostAddress ?: return
                _peers.value = _peers.value + (peerId to ConnectDevice(
                    id = peerId,
                    name = attr(ConnectProtocol.ATTR_DEVICE_NAME) ?: "Vivi device",
                    host = host,
                    port = resolved.port,
                    sameAccountHint = sameAccountHint,
                    kind = runCatching {
                        DeviceKind.valueOf(attr(ConnectProtocol.ATTR_KIND) ?: "PHONE")
                    }.getOrDefault(DeviceKind.PHONE),
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
