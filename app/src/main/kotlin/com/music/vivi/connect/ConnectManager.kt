/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.Collections

/**
 * Vivi Connect: peer-to-peer playback control across full Android devices on
 * one network.
 *
 * Every device runs the same code and is simultaneously a controller and a
 * target — exactly the Spotify Connect shape, minus the cloud. Whichever device
 * holds audio broadcasts its [NowPlayingState] to every connected peer, so a
 * tablet can show and drive what a phone is playing.
 */
class ConnectManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Handles an inbound payload. Wired to the shared playback command executor. */
    var onCommand: ((path: String, payload: ByteArray) -> Unit)? = null

    /** Supplies the current playback snapshot when a peer connects or state moves. */
    var snapshotProvider: (() -> NowPlayingState)? = null

    private val selfId: String by lazy {
        @Suppress("HardwareIds")
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "vivi-${Build.MODEL.hashCode()}"
    }

    private val selfName: String by lazy {
        Settings.Global.getString(context.contentResolver, "device_name")
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private val discovery by lazy { ConnectDiscovery(context, selfId, selfName) }

    private var serverSocket: ServerSocket? = null

    private val links = Collections.synchronizedMap(mutableMapOf<String, PeerLink>())

    private val _remoteState = MutableStateFlow(NowPlayingState.IDLE)

    /** What a *peer* is playing, when this device is acting as a remote. */
    val remoteState: StateFlow<NowPlayingState> = _remoteState.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Devices to show in the picker, this one included and marked [ConnectDevice.isSelf]. */
    val devices: StateFlow<List<ConnectDevice>> by lazy {
        combine(discovery.peers, _remoteState) { peers, remote ->
            val self = ConnectDevice(
                id = selfId,
                name = selfName,
                host = "127.0.0.1",
                port = 0,
                isSelf = true,
                isPlaying = snapshotProvider?.invoke()?.phonePlaybackActive == true,
                connected = true,
            )
            listOf(self) + peers.values.sortedBy { it.name }.map {
                it.copy(isPlaying = remote.phonePlaybackActive && it.connected)
            }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    }

    /** Discovery diagnostics, surfaced in the picker so failures are readable. */
    val status: StateFlow<ConnectStatus> get() = discovery.status

    /**
     * @param accountIdentities every identifier this device knows for the
     *   signed-in account. All of them are advertised and a peer matches on any
     *   overlap — see [ConnectProtocol.fingerprints]. An empty result disables
     *   Connect.
     */
    fun start(accountIdentities: List<String?>) {
        if (_running.value) return
        val fingerprints = ConnectProtocol.fingerprints(accountIdentities)
        if (fingerprints.isEmpty()) {
            Timber.i("Vivi Connect stays off: no signed-in account to authenticate peers with")
            return
        }

        val server = runCatching { ServerSocket(0) }.getOrElse {
            Timber.w(it, "Could not open a Connect listener")
            return
        }
        serverSocket = server
        _running.value = true

        Timber.i("Vivi Connect listening on %d as %s", server.localPort, selfName)
        discovery.start(server.localPort, fingerprints)

        scope.launch { acceptLoop(server, fingerprints) }
        scope.launch { dialLoop(fingerprints) }
        scope.launch { keepAliveLoop() }
    }

    fun stop() {
        _running.value = false
        discovery.stop()
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(links) {
            links.values.forEach { it.close() }
            links.clear()
        }
    }

    // ── Connection management ────────────────────────────────────────────────

    private suspend fun acceptLoop(server: ServerSocket, fingerprints: Set<String>) {
        while (scope.isActive && _running.value) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                // Only a deliberate shutdown should end the loop. Treating any
                // exception as terminal meant one transient error stopped this
                // device accepting connections for the rest of the process, with
                // nothing logged and the picker still claiming to be listening.
                if (server.isClosed || !_running.value) break
                Timber.w(e, "Connect accept failed; retrying")
                delay(ACCEPT_RETRY_MS)
                continue
            }
            scope.launch { handleInbound(socket, fingerprints) }
        }
    }

    private fun handleInbound(socket: Socket, fingerprints: Set<String>) {
        runCatching {
            val link = PeerLink(socket)
            val hello = link.readFrame()
            if (hello?.path != ConnectProtocol.PATH_HELLO) {
                link.close()
                return
            }
            val payload = SyncCodec.decodeOrNull<ConnectHello>(decode(hello.data))
            val peerFingerprints = ConnectProtocol.decodeFingerprints(payload?.fingerprint)
            if (payload == null || peerFingerprints.none { it in fingerprints }) {
                Timber.w("Refused a Connect peer with a bad fingerprint")
                link.close()
                return
            }
            link.peerId = payload.deviceId
            link.send(ConnectFrame(ConnectProtocol.PATH_WELCOME))
            adopt(link)
        }.onFailure { Timber.d(it, "Inbound Connect handshake failed") }
    }

    /** Periodically dials any discovered peer we are not already linked to. */
    private suspend fun dialLoop(fingerprints: Set<String>) {
        while (scope.isActive && _running.value) {
            val known = discovery.peers.value
            for ((peerId, device) in known) {
                if (links.containsKey(peerId)) continue
                // Only the lower id dials, so two devices discovering each other
                // simultaneously don't end up with a redundant pair of sockets.
                if (selfId > peerId) continue
                scope.launch { dial(device, fingerprints) }
            }
            delay(DIAL_INTERVAL_MS)
        }
    }

    private fun dial(device: ConnectDevice, fingerprints: Set<String>) {
        runCatching {
            val socket = Socket().apply {
                connect(InetSocketAddress(device.host, device.port), CONNECT_TIMEOUT_MS)
            }
            val link = PeerLink(socket)
            link.send(
                ConnectFrame(
                    path = ConnectProtocol.PATH_HELLO,
                    data = encode(
                        SyncCodec.encode(
                            ConnectHello(
                                deviceId = selfId,
                                deviceName = selfName,
                                fingerprint = ConnectProtocol.encodeFingerprints(fingerprints),
                            )
                        )
                    ),
                )
            )
            if (link.readFrame()?.path != ConnectProtocol.PATH_WELCOME) {
                link.close()
                return
            }
            link.peerId = device.id
            adopt(link)
        }.onFailure { Timber.d(it, "Could not dial Connect peer %s", device.name) }
    }

    private fun adopt(link: PeerLink) {
        val peerId = link.peerId ?: return
        links.put(peerId, link)?.close()
        discovery.markConnected(peerId, true)
        Timber.i("Connect linked to %s", peerId.take(6))

        // A peer that just arrived has no idea what is playing here.
        snapshotProvider?.invoke()?.let { broadcastState(it) }

        scope.launch {
            runCatching {
                while (scope.isActive) {
                    val frame = link.readFrame() ?: break
                    dispatch(frame)
                }
            }.onFailure { Timber.w(it, "Connect link to %s failed", peerId.take(6)) }
            links.remove(peerId)
            discovery.markConnected(peerId, false)
            link.close()
            Timber.i("Connect dropped %s", peerId.take(6))
        }
    }

    private suspend fun keepAliveLoop() {
        while (scope.isActive && _running.value) {
            delay(KEEPALIVE_INTERVAL_MS)
            broadcast(ConnectFrame(ConnectProtocol.PATH_PING))
        }
    }

    // ── Frames ───────────────────────────────────────────────────────────────

    private fun dispatch(frame: ConnectFrame) {
        when (frame.path) {
            ConnectProtocol.PATH_PING, ConnectProtocol.PATH_WELCOME -> Unit

            // A peer told us what it is playing; mirror it.
            SyncPaths.STATE_NOW_PLAYING -> {
                SyncCodec.decodeOrNull<NowPlayingState>(decode(frame.data))
                    ?.let { _remoteState.value = it }
            }

            else -> onCommand?.invoke(frame.path, decode(frame.data))
        }
    }

    /** Pushes this device's playback state to every linked peer. */
    fun broadcastState(state: NowPlayingState) {
        broadcast(
            ConnectFrame(
                path = SyncPaths.STATE_NOW_PLAYING,
                data = encode(SyncCodec.encode(state)),
            )
        )
    }

    /** Sends a transport command to whichever peer currently holds playback. */
    fun sendToActivePeer(path: String, payload: ByteArray = ByteArray(0)) {
        broadcast(ConnectFrame(path, encode(payload)))
    }

    private fun broadcast(frame: ConnectFrame) {
        val snapshot = synchronized(links) { links.values.toList() }
        for (link in snapshot) {
            runCatching { link.send(frame) }
                .onFailure { Timber.d(it, "Connect send failed; dropping link") }
        }
    }

    private fun encode(bytes: ByteArray): String =
        if (bytes.isEmpty()) "" else Base64.getEncoder().encodeToString(bytes)

    private fun decode(data: String): ByteArray =
        if (data.isEmpty()) ByteArray(0)
        else runCatching { Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))

    /**
     * A live socket to one peer. Newline-delimited JSON in both directions;
     * writes are synchronised because playback events and keep-alives can race.
     */
    private inner class PeerLink(private val socket: Socket) {
        var peerId: String? = null

        private val reader: BufferedReader = socket.getInputStream()
            .bufferedReader(Charsets.UTF_8)
        private val writer: BufferedWriter = socket.getOutputStream()
            .bufferedWriter(Charsets.UTF_8)
        private val writeLock = Any()

        init {
            socket.soTimeout = READ_TIMEOUT_MS
            // Transport commands are tiny and latency-sensitive; Nagle would sit
            // on them waiting for more bytes that never come.
            socket.tcpNoDelay = true
        }

        /** Returns null only at genuine end-of-stream. */
        fun readFrame(): ConnectFrame? {
            while (true) {
                val line = reader.readLine() ?: return null
                val frame = runCatching {
                    SyncCodec.json.decodeFromString<ConnectFrame>(line)
                }.getOrNull()
                if (frame != null) return frame
                // A frame this build doesn't understand is not a dead socket.
                // Returning null here made one malformed line indistinguishable
                // from EOF and tore down an otherwise healthy link.
                Timber.w("Skipping a malformed Connect frame")
            }
        }

        fun send(frame: ConnectFrame) {
            synchronized(writeLock) {
                writer.write(SyncCodec.json.encodeToString(frame))
                writer.newLine()
                writer.flush()
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val ACCEPT_RETRY_MS = 1_000L
        const val DIAL_INTERVAL_MS = 10_000L
        const val KEEPALIVE_INTERVAL_MS = 25_000L
        const val CONNECT_TIMEOUT_MS = 4_000
        // Comfortably longer than the keep-alive, so a quiet link is not mistaken
        // for a dead one.
        const val READ_TIMEOUT_MS = 70_000
    }
}
