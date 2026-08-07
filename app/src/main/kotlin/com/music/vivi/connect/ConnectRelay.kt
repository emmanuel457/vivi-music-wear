/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import com.music.vivi.listentogether.CreateRoomPayload
import com.music.vivi.listentogether.JoinApprovedPayload
import com.music.vivi.listentogether.JoinRequestPayload
import com.music.vivi.listentogether.JoinRoomPayload
import com.music.vivi.listentogether.MessageCodec
import com.music.vivi.listentogether.MessageFormat
import com.music.vivi.listentogether.MessageTypes
import com.music.vivi.listentogether.PlaybackActionPayload
import com.music.vivi.listentogether.RoomCreatedPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** What the relay is doing, for the settings UI. */
enum class RelayState { OFF, CONNECTING, HOSTING, JOINED, FAILED }

/**
 * Vivi Connect over the internet, as a fallback when devices are not on the
 * same network.
 *
 * Opt-in and off by default, because unlike the LAN transport this routes your
 * playback state through a third-party server — the same Listen Together relays
 * the phone app already ships, operated by the upstream author rather than by
 * you.
 *
 * ## Why this needs a pairing code
 *
 * Room codes are assigned *by the server* ([RoomCreatedPayload.roomCode]) and
 * joining goes through a request/approve handshake. A device therefore cannot
 * derive which room to join from the account alone, the way the LAN transport
 * derives peers from an mDNS fingerprint. So the first device to enable this
 * hosts and shows a code; the second joins with it once, and both then persist
 * the room and session token and rejoin silently forever after.
 *
 * ## Semantics
 *
 * Listen Together is a *social* feature: every member plays audio in sync. This
 * is the opposite — exactly one device plays and the rest are silent remotes.
 * We reuse the transport and the room, never the buffer-sync behaviour, so an
 * incoming state update only ever updates the UI here; it never starts audio.
 */
class ConnectRelay(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    /** Delivers a decoded Vivi frame from a peer. */
    var onFrame: ((ConnectFrame) -> Unit)? = null

    /** Supplies the fingerprint set used to auto-approve join requests. */
    var fingerprints: Set<String> = emptySet()

    private val codec = MessageCodec(format = MessageFormat.JSON, compressionEnabled = false)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    private val _state = MutableStateFlow(RelayState.OFF)
    val state: StateFlow<RelayState> = _state.asStateFlow()

    private val _roomCode = MutableStateFlow<String?>(null)

    /** The code to type into another device. Null until hosting or joined. */
    val roomCode: StateFlow<String?> = _roomCode.asStateFlow()

    private var isHost = false
    private var reconnectAttempt = 0
    private var wantConnected = false

    private var serverUrl: String = ""
    private var username: String = ""
    private var joinCode: String? = null

    /**
     * @param joinCode null to host a new room, or a code from another device.
     * @param username carries our account fingerprint so a host can tell our
     *   devices from a stranger who guessed the room code.
     */
    fun start(serverUrl: String, username: String, joinCode: String?) {
        this.serverUrl = serverUrl
        this.username = username
        this.joinCode = joinCode
        wantConnected = true
        reconnectAttempt = 0
        connect()
    }

    fun stop() {
        wantConnected = false
        runCatching { webSocket?.close(1000, "stopped") }
        webSocket = null
        _state.value = RelayState.OFF
        _roomCode.value = null
    }

    private fun connect() {
        if (!wantConnected) return
        _state.value = RelayState.CONNECTING

        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                val code = joinCode
                if (code.isNullOrBlank()) {
                    send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username = username))
                } else {
                    send(MessageTypes.JOIN_ROOM, JoinRoomPayload(roomCode = code, username = username))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handle(bytes.toByteArray())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(text.toByteArray(Charsets.UTF_8))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "Relay socket failed")
                _state.value = RelayState.FAILED
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (wantConnected) scheduleReconnect() else _state.value = RelayState.OFF
            }
        })
    }

    private fun scheduleReconnect() {
        if (!wantConnected) return
        scope.launch {
            // Capped exponential backoff: these are free-tier servers that sleep
            // when idle, so a cold start can take many seconds and hammering it
            // only makes that worse.
            val delayMs = (INITIAL_RECONNECT_MS shl minOf(reconnectAttempt, 5))
                .coerceAtMost(MAX_RECONNECT_MS)
            reconnectAttempt++
            delay(delayMs)
            connect()
        }
    }

    private fun handle(data: ByteArray) {
        val (type, payloadBytes) = runCatching { codec.decode(data) }
            .onFailure { Timber.w(it, "Undecodable relay message") }
            .getOrNull() ?: return

        when (type) {
            MessageTypes.ROOM_CREATED -> {
                val payload = decode<RoomCreatedPayload>(payloadBytes) ?: return
                isHost = true
                _roomCode.value = payload.roomCode
                _state.value = RelayState.HOSTING
                Timber.i("Relay hosting room %s", payload.roomCode)
            }

            MessageTypes.JOIN_APPROVED -> {
                val payload = decode<JoinApprovedPayload>(payloadBytes) ?: return
                isHost = false
                _roomCode.value = payload.roomCode
                _state.value = RelayState.JOINED
                Timber.i("Relay joined room %s", payload.roomCode)
            }

            MessageTypes.JOIN_REQUEST -> {
                val payload = decode<JoinRequestPayload>(payloadBytes) ?: return
                // Auto-approve our own devices so the pairing code is a one-time
                // step rather than a prompt on every reconnect. A stranger who
                // guessed the code carries no fingerprint and is ignored.
                if (fingerprints.any { payload.username.contains(it) }) {
                    send(
                        MessageTypes.APPROVE_JOIN,
                        com.music.vivi.listentogether.ApproveJoinPayload(userId = payload.userId),
                    )
                    Timber.i("Relay approved a device on this account")
                } else {
                    Timber.w("Relay ignored a join from an unrecognised device")
                }
            }

            MessageTypes.JOIN_REJECTED -> {
                _state.value = RelayState.FAILED
                Timber.w("Relay join rejected")
            }

            MessageTypes.PLAYBACK_ACTION, MessageTypes.SYNC_PLAYBACK -> {
                val payload = decode<PlaybackActionPayload>(payloadBytes) ?: return
                if (payload.action != VIVI_ACTION) return
                val frame = payload.queueTitle
                    ?.let { runCatching { json.decodeFromString<ConnectFrame>(it) }.getOrNull() }
                    ?: return
                onFrame?.invoke(frame)
            }
        }
    }

    /**
     * Sends a Vivi frame through the room.
     *
     * The relay protocol has no generic passthrough, so the frame rides inside
     * [PlaybackActionPayload.queueTitle] under a private action name. That is a
     * deliberate envelope-within-an-envelope: it keeps the relay from needing to
     * understand Vivi's playback model, and keeps our payloads from being
     * mistaken for real Listen Together playback commands by other clients.
     */
    fun sendFrame(frame: ConnectFrame) {
        if (_state.value != RelayState.HOSTING && _state.value != RelayState.JOINED) return
        send(
            MessageTypes.PLAYBACK_ACTION,
            PlaybackActionPayload(
                action = VIVI_ACTION,
                queueTitle = json.encodeToString(frame),
            ),
        )
    }

    private inline fun <reified T> send(type: String, payload: T) {
        runCatching {
            val data = codec.encode(type, payload)
            webSocket?.send(ByteString.of(*data))
        }.onFailure { Timber.w(it, "Relay send failed for %s", type) }
    }

    private inline fun <reified T> decode(bytes: ByteArray): T? =
        runCatching { json.decodeFromString<T>(bytes.decodeToString()) }
            .onFailure { Timber.w(it, "Bad relay payload") }
            .getOrNull()

    private companion object {
        const val VIVI_ACTION = "vivi_connect_frame"
        const val INITIAL_RECONNECT_MS = 2_000L
        const val MAX_RECONNECT_MS = 60_000L
    }
}

/** Builds a relay frame from a Vivi path and its payload bytes. */
internal fun relayFrame(path: String, payload: ByteArray): ConnectFrame =
    ConnectFrame(
        path = path,
        data = if (payload.isEmpty()) "" else java.util.Base64.getEncoder().encodeToString(payload),
    )

/** Kept adjacent to [relayFrame] so both sides of the conversion are visible. */
internal fun ConnectFrame.payloadBytes(): ByteArray =
    if (data.isEmpty()) ByteArray(0)
    else runCatching { java.util.Base64.getDecoder().decode(data) }.getOrDefault(ByteArray(0))
