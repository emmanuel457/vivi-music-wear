/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import android.content.Context
import android.os.SystemClock
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.music.vivi.wearsync.AuthPayload
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.SyncCapabilities
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncKeys
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import timber.log.Timber

/**
 * The watch's half of the phone link.
 *
 * Holds the last state the phone published, tracks whether a phone running Vivi
 * Music is actually reachable, and sends transport commands back. Everything is
 * best-effort: a watch out of Bluetooth range and off Wi-Fi simply reports
 * [phoneReachable] as false, and the rest of the app falls back to local
 * playback.
 */
class PhoneLink(private val context: Context) : CapabilityClient.OnCapabilityChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val capabilityClient by lazy { Wearable.getCapabilityClient(context) }

    private val _phoneNodes = MutableStateFlow<Set<Node>>(emptySet())

    private val _nowPlaying = MutableStateFlow(NowPlayingState.IDLE)
    val nowPlaying: StateFlow<NowPlayingState> = _nowPlaying.asStateFlow()

    /**
     * Our own clock reading when the last snapshot arrived. The phone stamps its
     * snapshot with *its* `elapsedRealtime`, which is measured from the phone's
     * boot and so is meaningless here — we extrapolate from local receipt time.
     */
    @Volatile
    private var receivedAtElapsedRealtime: Long = SystemClock.elapsedRealtime()

    private val _auth = MutableStateFlow<AuthPayload?>(null)
    val auth: StateFlow<AuthPayload?> = _auth.asStateFlow()

    /** True when at least one paired node advertises the phone-side capability. */
    val phoneReachable: StateFlow<Boolean> = _phoneNodes
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True only when a phone is reachable *and* it is actively holding a player.
     * This is the signal the router uses to default to remote control.
     */
    val phonePlaying: StateFlow<Boolean> = combine(phoneReachable, _nowPlaying) { reachable, state ->
        reachable && state.phonePlaybackActive
    }.stateIn(scope, SharingStarted.Eagerly, false)

    fun start() {
        capabilityClient.addListener(this, SyncCapabilities.PHONE)
        scope.launch {
            refreshNodes()
            replayRetainedState()
        }
    }

    fun stop() {
        capabilityClient.removeListener(this, SyncCapabilities.PHONE)
    }

    override fun onCapabilityChanged(info: CapabilityInfo) {
        _phoneNodes.value = info.nodes
        Timber.d("Phone capability changed: ${info.nodes.size} node(s)")
    }

    private suspend fun refreshNodes() {
        runCatching {
            capabilityClient
                .getCapability(SyncCapabilities.PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
        }.onSuccess { _phoneNodes.value = it.nodes }
            .onFailure { Timber.w(it, "Could not query phone capability") }
    }

    /**
     * DataItems survive across process death and disconnects, so on cold start we
     * read whatever the phone last published instead of waiting for a change
     * event that may never come.
     */
    private suspend fun replayRetainedState() {
        runCatching {
            val buffer = dataClient.dataItems.await()
            try {
                buffer.forEach { item ->
                    val map = DataMapItem.fromDataItem(item).dataMap
                    when (item.uri.path) {
                        SyncPaths.STATE_NOW_PLAYING -> applyNowPlaying(map)
                        SyncPaths.STATE_AUTH -> applyAuth(map)
                    }
                }
            } finally {
                buffer.release()
            }
        }.onFailure { Timber.w(it, "Could not replay retained data items") }
    }

    // ── Inbound, called by WearSyncListenerService ───────────────────────────

    fun applyNowPlaying(map: DataMap) {
        val payload = map.getByteArray(SyncKeys.PAYLOAD) ?: return
        SyncCodec.decodeOrNull<NowPlayingState>(payload)?.let {
            receivedAtElapsedRealtime = SystemClock.elapsedRealtime()
            _nowPlaying.value = it
            Timber.d("Now-playing from phone: ${it.track?.title} playing=${it.isPlaying}")
        }
    }

    fun applyAuth(map: DataMap) {
        val payload = map.getByteArray(SyncKeys.PAYLOAD) ?: return
        SyncCodec.decodeOrNull<AuthPayload>(payload)?.let { _auth.value = it }
    }

    fun onPhoneStateCleared() {
        _nowPlaying.value = NowPlayingState.IDLE
    }

    // ── Outbound ─────────────────────────────────────────────────────────────

    /**
     * Sends [path] to every reachable phone node. Returns true if at least one
     * node accepted it, so callers can fall back to local playback on failure
     * rather than leaving the user staring at a button that did nothing.
     */
    suspend fun send(path: String, payload: ByteArray = ByteArray(0)): Boolean {
        val nodes = _phoneNodes.value.ifEmpty {
            refreshNodes()
            _phoneNodes.value
        }
        if (nodes.isEmpty()) {
            Timber.d("send($path) skipped: no phone node")
            return false
        }
        var delivered = false
        for (node in nodes) {
            runCatching { messageClient.sendMessage(node.id, path, payload).await() }
                .onSuccess { delivered = true }
                .onFailure { Timber.w(it, "send($path) failed for node ${node.id}") }
        }
        return delivered
    }

    fun sendAsync(path: String, payload: ByteArray = ByteArray(0)) {
        scope.launch { send(path, payload) }
    }

    /**
     * Position as of *now*, extrapolated from the last snapshot. The phone only
     * publishes on state changes — polling it for a progress bar would hold the
     * Bluetooth link open and cost both devices real battery.
     */
    fun extrapolatedPositionMs(state: NowPlayingState = _nowPlaying.value): Long {
        if (!state.isPlaying) return state.positionMs
        val sinceReceipt = SystemClock.elapsedRealtime() - receivedAtElapsedRealtime
        val upperBound = state.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        return (state.positionMs + sinceReceipt).coerceIn(0L, upperBound)
    }
}
