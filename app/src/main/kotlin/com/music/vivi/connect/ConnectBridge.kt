/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.connect

import android.content.Context
import androidx.media3.common.Player
import androidx.datastore.preferences.core.edit
import androidx.media3.common.util.UnstableApi
import com.music.innertube.YouTube
import com.music.vivi.constants.AccountChannelHandleKey
import com.music.vivi.constants.AccountEmailKey
import com.music.vivi.constants.AccountNameKey
import com.music.vivi.constants.DataSyncIdKey
import com.music.vivi.constants.InnerTubeCookieKey
import com.music.vivi.constants.ConnectRelayEnabledKey
import com.music.vivi.constants.ConnectRelayRoomKey
import com.music.vivi.constants.ListenTogetherServerUrlKey
import com.music.vivi.listentogether.ListenTogetherServers
import com.music.vivi.playback.queues.ListQueue
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.getAsync
import com.music.vivi.wear.WearBridge
import com.music.vivi.wear.toMediaItem
import com.music.vivi.wearsync.LikeCommand
import com.music.vivi.wearsync.NowPlayingState
import com.music.vivi.wearsync.PlayTracksCommand
import com.music.vivi.wearsync.RepeatCommand
import com.music.vivi.wearsync.SeekCommand
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Owns the [ConnectManager] for this process and joins it to playback.
 *
 * Deliberately parallel to [WearBridge] rather than folded into it: the watch
 * link is a Google-managed, always-paired, one-peer channel, while Connect is a
 * self-managed many-peer LAN mesh. Their lifecycles and failure modes have
 * nothing in common — only the payloads are shared.
 */
@UnstableApi
object ConnectBridge {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var manager: ConnectManager? = null

    /** What a peer device is playing, for the picker and remote-control UI. */
    fun remoteState(): NowPlayingState = _remoteState.value

    private val _remoteState = MutableStateFlow(NowPlayingState.IDLE)

    /**
     * Peer playback, as a flow.
     *
     * Kept here rather than read off [ConnectManager] so it survives the manager
     * being null before startup and stays a single source whether the frame
     * arrived over the LAN or the relay.
     */
    val remoteStateFlow: StateFlow<NowPlayingState> = _remoteState.asStateFlow()

    /**
     * The Player that stands in for a peer.
     *
     * Owned here rather than by MusicService so the service's MediaSession and
     * the app's PlayerConnection hand out the *same* instance. Two instances
     * would drift, and the notification and the in-app screen would disagree —
     * which is exactly what happened when only the session was swapped.
     */
    val remotePlayer: ConnectRemotePlayer by lazy { ConnectRemotePlayer() }

    private val _remoteOwnsPlayback = MutableStateFlow(false)

    /**
     * True when a peer holds the audio and this device does not. Drives whether
     * the MediaSession is backed by the local player or by the remote one.
     */
    val remoteOwnsPlayback: StateFlow<Boolean> = _remoteOwnsPlayback.asStateFlow()

    /**
     * Every device the user can send audio to: LAN peers plus any paired watch.
     *
     * The two arrive over completely different transports — NSD for phones and
     * tablets, the Wear Data Layer for a watch — but that is an implementation
     * detail the picker should not expose.
     */
    private val peerDevices = MutableStateFlow<List<ConnectDevice>>(emptyList())

    val allDevices: StateFlow<List<ConnectDevice>> =
        combine(peerDevices, WearBridge.watches) { peers, watches -> peers + watches }
            .stateIn(scope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun devices(): StateFlow<List<ConnectDevice>> = allDevices

    private val _activeDeviceName = MutableStateFlow<String?>(null)

    /**
     * Name of the device holding the audio, or null when it is this one.
     *
     * Drives the "Playing on …" strip. Spotify shows this permanently while a
     * remote device owns the session, and without it there is nothing on screen
     * to explain why the controls are driving something you cannot hear.
     */
    val activeDeviceName: StateFlow<String?> = _activeDeviceName.asStateFlow()

    private fun refreshActiveDeviceName() {
        val owner = activeDeviceId
        _activeDeviceName.value = when {
            owner == null || owner == selfDeviceId -> null
            else -> allDevices.value.firstOrNull { it.id == owner }?.name
                // Named devices are better, but "another device" beats silence
                // when a claim arrives before discovery has resolved the peer.
                ?: "another device"
        }
    }

    /**
     * One-line session state for the picker.
     *
     * Discovery already reports whether peers are *seen*; this reports whether
     * the session actually agrees on an owner. Without it, "linked but not
     * syncing" and "not linked" look identical on screen, which is exactly where
     * the last round of debugging stalled.
     */
    fun sessionDiagnostics(): String = buildString {
        append("me=").append(selfDeviceId.take(6).ifEmpty { "?" })
        append(" owner=")
        append(
            when (val owner = activeDeviceId) {
                null -> "none"
                selfDeviceId -> "me"
                else -> owner.take(6)
            }
        )
        append(" epoch=").append(epoch.get())
        append(" links=").append(manager?.linkCount() ?: 0)
        manager?.let { m ->
            append(" dials=").append(m.dialAttempts)
            m.lastDialError?.let { append(" err=").append(it) }
        }
        append(if (_remoteOwnsPlayback.value) " remote-session" else " local-session")
        _remoteState.value.track?.let { append(" peer=\"").append(it.title.take(18)).append('"') }
    }

    fun isRunning() = manager?.running?.value == true

    /** Observable form of [isRunning], so the picker reflects a late start. */
    fun runningState(): StateFlow<Boolean> = manager?.running ?: MutableStateFlow(false)

    /**
     * Starts Connect and keeps it started.
     *
     * Called from [com.music.vivi.App], not from MusicService: a device being
     * used purely as a remote never starts playback, so tying the lifecycle to
     * the player meant a tablet never advertised itself and never appeared to
     * anyone.
     */
    fun start(context: Context) {
        if (manager != null) return
        val app = context.applicationContext
        appContext = app
        val instance = ConnectManager(app)

        instance.snapshotProvider = { WearBridge.snapshot() }
        instance.queueProvider = { WearBridge.queueSnapshot() }
        instance.onPeerLost = { peerId -> onPeerLost(peerId) }
        instance.onCommand = { path, payload -> execute(path, payload) }

        manager = instance

        // Funnel LAN frames into the single remote-state flow, and recompute who
        // owns playback. "A peer is playing and we are not" is the whole
        // condition for handing our MediaSession to the remote player.
        setSelfDeviceId(instance.selfId())
        scope.launch {
            instance.remoteState.collect { state ->
                // Stale snapshots are dropped before they can touch the UI.
                if (!adoptOwnership(state)) return@collect
                _remoteState.value = state
                recomputeOwnership()
            }
        }

        scope.launch {
            // Sign-in can happen long after launch, and the identity fields are
            // populated lazily, so poll until one appears rather than giving up
            // on the single reading available at startup.
            while (true) {
                val identities = resolveIdentities(app)
                if (identities.isNotEmpty()) {
                    _identityMissing.value = false
                    instance.start(identities)
                    return@launch
                }
                _identityMissing.value = true
                delay(IDENTITY_RETRY_MS)
            }
        }

        // Feed the picker from the manager once it exists, rather than binding
        // to a null one at class-init time.
        scope.launch { instance.devices.collect { peerDevices.value = it } }
        scope.launch { allDevices.collect { refreshActiveDeviceName() } }
        WearBridge.startWatchDiscovery()
        restoreRelay(app)
    }

    /** Remembers the room so a rejoin needs no code, and reflects host/guest. */
    private fun persistRoom(app: Context, code: String?) {
        scope.launch {
            app.dataStore.edit { settings ->
                if (code.isNullOrBlank()) settings.remove(ConnectRelayRoomKey)
                else settings[ConnectRelayRoomKey] = code
            }
        }
    }

    private val _identityMissing = MutableStateFlow(true)

    /** True when Connect is idle purely because no account identity resolved. */
    val identityMissing: StateFlow<Boolean> = _identityMissing.asStateFlow()

    /**
     * Finds a value that is identical on every device signed into this account.
     *
     * `AccountEmailKey` alone was not enough: it is only ever written when the
     * user opens Account settings, so a perfectly signed-in device can have it
     * blank — which silently disabled Connect and also showed up as a bare
     * "Signed in as" on the watch.
     */
    private suspend fun resolveIdentities(app: Context): List<String?> {
        val store = app.dataStore
        val identities = mutableListOf<String?>()

        identities += store.getAsync(AccountEmailKey)
        identities += store.getAsync(AccountChannelHandleKey)

        // dataSyncId is "<account>||<session>"; only the leading segment is
        // stable across devices, so the session half must be dropped.
        identities += store.getAsync(DataSyncIdKey)?.substringBefore("||")

        // Always ask YouTube too, not only when the cache is empty. Two devices
        // can have different subsets of these fields cached, and returning just
        // the first hit meant they hashed different strings and silently refused
        // each other. Gathering everything and matching on any overlap fixes it.
        if (!store.getAsync(InnerTubeCookieKey).isNullOrBlank()) {
            YouTube.accountInfo().getOrNull()?.let { info ->
                identities += info.email
                identities += info.channelHandle
                identities += info.name
                // Cache it so later launches resolve without a network call and
                // the account screens stop showing blanks.
                runCatching {
                    store.edit { settings ->
                        settings[AccountNameKey] = info.name
                        info.email?.let { settings[AccountEmailKey] = it }
                        info.channelHandle?.let { settings[AccountChannelHandleKey] = it }
                    }
                }
            }
        }
        return identities.filterNot { it.isNullOrBlank() }
    }

    /** Discovery diagnostics for the picker. */
    fun status(): StateFlow<ConnectStatus> =
        manager?.status ?: MutableStateFlow(ConnectStatus())

    // ── Relay fallback ───────────────────────────────────────────────────────

    private val relay = ConnectRelay()

    val relayState: StateFlow<RelayState> get() = relay.state

    /** Pairing code to type into another device; null until hosting/joined. */
    val relayRoomCode: StateFlow<String?> get() = relay.roomCode

    /**
     * Brings the relay up. Off unless the user turns it on: this is the only
     * part of Vivi Connect that sends anything to a server the user does not
     * control.
     *
     * @param joinCode null to host, or a code shown by another device.
     */
    fun startRelay(context: Context, joinCode: String?) {
        val app = context.applicationContext
        scope.launch {
            val identities = resolveIdentities(app)
            val prints = ConnectProtocol.fingerprints(identities)
            if (prints.isEmpty()) {
                Timber.w("Relay needs a signed-in account to authenticate devices")
                return@launch
            }
            relay.fingerprints = prints
            relay.onFrame = { frame ->
                // Same dispatch as the LAN transport: state updates the mirror,
                // everything else acts on this device's player.
                if (frame.path == SyncPaths.STATE_NOW_PLAYING) {
                    SyncCodec.decodeOrNull<NowPlayingState>(frame.payloadBytes())?.let {
                        manager?.applyRemoteState(it)
                        // Also fed directly, so relay-only pairs (no LAN peer)
                        // still drive the session swap.
                        _remoteState.value = it
                        recomputeOwnership()
                    }
                } else {
                    execute(frame.path, frame.payloadBytes())
                }
            }

            val serverUrl = app.dataStore.getAsync(ListenTogetherServerUrlKey)
                ?.takeIf { it.isNotBlank() }
                ?: ListenTogetherServers.defaultServerUrl

            // The username doubles as proof of account: the host approves a join
            // only when it recognises the fingerprint embedded here.
            val username = "vivi-${prints.first()}"

            app.dataStore.edit { it[ConnectRelayEnabledKey] = true }
            relay.start(serverUrl, username, joinCode)

            // Persist whatever room we end up in, host or guest, so the next
            // launch rejoins without the user finding the code again.
            scope.launch {
                relay.roomCode.collect { code -> persistRoom(app, code) }
            }
        }
    }

    fun stopRelay(context: Context) {
        relay.stop()
        scope.launch {
            context.applicationContext.dataStore.edit { it[ConnectRelayEnabledKey] = false }
        }
    }

    /** Re-establishes the relay on launch if the user previously enabled it. */
    private fun restoreRelay(app: Context) {
        scope.launch {
            if (app.dataStore.getAsync(ConnectRelayEnabledKey) != true) return@launch
            val savedRoom = app.dataStore.getAsync(ConnectRelayRoomKey)
            Timber.i("Restoring relay (room=%s)", savedRoom ?: "host")
            startRelay(app, savedRoom)
        }
    }

    /** Broadcasts state over the relay too, when it is up. */
    private fun relayBroadcast(state: NowPlayingState) {
        relay.sendFrame(relayFrame(SyncPaths.STATE_NOW_PLAYING, SyncCodec.encode(state)))
    }

    private const val IDENTITY_RETRY_MS = 15_000L

    fun stop() {
        manager?.stop()
        manager = null
    }

    private val _remoteQueue = MutableStateFlow(com.music.vivi.wearsync.QueueSnapshot.EMPTY)

    /** The owning device's queue, so a remote can show and jump within it. */
    val remoteQueue: StateFlow<com.music.vivi.wearsync.QueueSnapshot> = _remoteQueue.asStateFlow()

    /** Broadcasts our queue when it changes. Called from WearBridge. */
    fun onQueueChanged(snapshot: com.music.vivi.wearsync.QueueSnapshot) {
        manager?.sendToActivePeer(
            SyncPaths.STATE_QUEUE,
            SyncCodec.encode(snapshot),
        )
        relay.sendFrame(relayFrame(SyncPaths.STATE_QUEUE, SyncCodec.encode(snapshot)))
    }

    /**
     * The queue to hand over when transferring playback.
     *
     * Whichever device owns the audio has the real list; a remote has only the
     * copy it was sent. Sending just the current track — which is what transfer
     * did before — moved the song but not the context, so the target played one
     * track and stopped.
     */
    fun queueForTransfer(): com.music.vivi.wearsync.QueueSnapshot =
        if (ownsPlayback()) WearBridge.queueSnapshot() else _remoteQueue.value

    /** Called from MusicService's event hook, alongside the watch publish. */
    fun onPlaybackStateChanged(rawState: NowPlayingState) {
        // Claim BEFORE broadcasting. Broadcasting first sent an unclaimed
        // snapshot — epoch 0, no owner — which a peer accepts and which drives
        // its ownership straight back to "nobody", so the two devices ignored
        // each other until some later frame happened to carry a claim.
        //
        // Guarded on a known identity: selfDeviceId is empty until the manager
        // reports it, and claiming as "" left ownsPlayback() permanently false,
        // so the device treated itself as a remote and drove a player that was
        // not the one making sound. That is the "acting independently" symptom.
        if (rawState.isPlaying && selfDeviceId.isNotEmpty() && activeDeviceId != selfDeviceId) {
            claimOwnership()
            manager?.sendToActivePeer(SyncPaths.NOTIFY_WATCH_PLAYING)
            relay.sendFrame(relayFrame(SyncPaths.NOTIFY_WATCH_PLAYING, ByteArray(0)))
            Timber.i("Claimed playback as %s (epoch %d)", selfDeviceId.take(6), epoch.get())
        }

        // One broadcast, always carrying the current claim.
        val state = stampOwnership(rawState)
        manager?.broadcastState(state)
        relayBroadcast(state)

        wasPlayingLocally = rawState.isPlaying
        recomputeOwnership()
    }

    @Volatile
    private var wasPlayingLocally = false

    /** Highest epoch seen from any device, including our own claims. */
    private val epoch = java.util.concurrent.atomic.AtomicLong(0L)

    /** Who currently owns playback. Null until any device has claimed it. */
    @Volatile
    private var activeDeviceId: String? = null

    /** Stable identity for this device, matching what ConnectManager advertises. */
    @Volatile
    private var selfDeviceId: String = ""

    fun setSelfDeviceId(id: String) {
        selfDeviceId = id
    }

    /** Stamps our outgoing snapshot with the current ownership claim. */
    fun stampOwnership(state: NowPlayingState): NowPlayingState =
        state.copy(activeDeviceId = activeDeviceId, epoch = epoch.get())

    /** Claims playback for this device, superseding every earlier claim. */
    private fun claimOwnership() {
        epoch.incrementAndGet()
        activeDeviceId = selfDeviceId
        recomputeOwnership()
    }

    /**
     * Adopts a peer's ownership claim if it is newer than anything seen.
     *
     * @return true when the claim was accepted.
     */
    fun adoptOwnership(state: NowPlayingState): Boolean {
        val incoming = state.epoch
        if (incoming <= 0L) return true // pre-epoch build; accept rather than stall
        val current = epoch.get()
        if (incoming < current) {
            // Stale. Discarding late snapshots is what stops a paused device
            // reverting to an older track.
            return false
        }
        epoch.set(incoming)
        activeDeviceId = state.activeDeviceId
        recomputeOwnership()
        return true
    }

    /**
     * Exactly one device owns playback, and it is whichever one most recently
     * claimed it — never inferred from whose `isPlaying` flag happened to
     * arrive last.
     */
    private fun recomputeOwnership() {
        val owner = activeDeviceId
        val remote = _remoteState.value
        _remoteOwnsPlayback.value = owner != null &&
            owner != selfDeviceId &&
            remote.track != null
    refreshActiveDeviceName()
    }

    /** True when this device is the designated owner. */
    fun ownsPlayback(): Boolean =
        activeDeviceId == null || activeDeviceId == selfDeviceId

    /**
     * Releases ownership held by a device that has gone away.
     *
     * Without this a peer that was playing and then dropped off the network left
     * every other device believing a dead device owned the session: their
     * transport sent commands into a closed socket and their screens showed a
     * track nobody could hear, with no way back short of restarting the app.
     */
    fun onPeerLost(peerId: String) {
        if (activeDeviceId != peerId) return
        Timber.i("Owner %s vanished; releasing the session", peerId.take(6))
        activeDeviceId = null
        _remoteState.value = NowPlayingState.IDLE
        _remoteQueue.value = com.music.vivi.wearsync.QueueSnapshot.EMPTY
        remotePlayer.update(NowPlayingState.IDLE)
        remotePlayer.updateQueue(com.music.vivi.wearsync.QueueSnapshot.EMPTY)
        recomputeOwnership()
    }

    /**
     * Routes a transport command to whichever device owns playback.
     *
     * The Devices screen always *sent* its commands, so on the device that
     * actually held the audio they went out to peers and nothing happened
     * locally — the buttons there did nothing at all. Everything transport
     * related should go through here.
     */
    fun dispatchTransport(path: String, payload: ByteArray = ByteArray(0)) {
        if (ownsPlayback()) execute(path, payload) else sendCommand(path, payload)
    }

    /**
     * Hands playback to one specific device, carrying the whole context.
     *
     * Targeted rather than broadcast: sending CMD_PLAY_TRACKS to everyone would
     * start the queue on every device at once, which with three devices is worse
     * than not transferring at all. Watches are reached over the Data Layer and
     * everything else over the LAN mesh, which is why the route is chosen from
     * the device kind rather than assumed.
     */
    fun transferPlaybackTo(device: ConnectDevice) {
        if (device.isSelf) return
        val queue = queueForTransfer()
        val playing = if (ownsPlayback()) WearBridge.snapshot() else _remoteState.value

        val tracks = queue.tracks.ifEmpty { listOfNotNull(playing.track) }
        if (tracks.isEmpty()) {
            Timber.i("Nothing to transfer to %s", device.name)
            return
        }
        val index = if (queue.tracks.isNotEmpty()) queue.currentIndex else 0

        val payload = SyncCodec.encode(
            PlayTracksCommand(
                tracks = tracks.take(PlayTracksCommand.MAX_TRACKS),
                startIndex = index.coerceIn(0, tracks.lastIndex),
                queueTitle = queue.queueTitle ?: playing.queueTitle,
                positionMs = playing.positionMs,
                shuffle = playing.shuffle,
                repeatMode = playing.repeatMode,
            )
        )

        when (device.kind) {
            DeviceKind.WATCH ->
                WearBridge.sendToWatchNode(device.id, SyncPaths.CMD_PLAY_TRACKS, payload)
            else -> {
                val sent = manager?.sendTo(device.id, SyncPaths.CMD_PLAY_TRACKS, payload) == true
                // Relay is the fallback when the target is not on this network.
                if (!sent) relay.sendFrame(relayFrame(SyncPaths.CMD_PLAY_TRACKS, payload))
            }
        }
        Timber.i("Transferred playback to %s", device.name)
    }

    /** Sends a transport command to the peer currently holding playback. */
    fun sendCommand(path: String, payload: ByteArray = ByteArray(0)) {
        manager?.sendToActivePeer(path, payload)
        // Sent over both transports. A device reachable on the LAN and via the
        // relay would otherwise miss the command whenever the LAN link is the
        // one that happens to be down.
        relay.sendFrame(relayFrame(path, payload))
    }

    /**
     * Applies a command that arrived from a peer.
     *
     * Mirrors the watch path in [com.music.vivi.wear.PhoneWearListenerService];
     * both act on the same live player through [WearBridge.musicService].
     */
    private fun execute(path: String, payload: ByteArray) {
        when (path) {
            SyncPaths.CMD_TOGGLE -> onPlayer { if (it.isPlaying) it.pause() else it.play() }
            SyncPaths.CMD_PLAY -> onPlayer { it.play() }
            SyncPaths.CMD_PAUSE -> onPlayer { it.pause() }
            SyncPaths.CMD_NEXT -> onPlayer { it.seekToNextMediaItem() }
            SyncPaths.CMD_PREVIOUS -> onPlayer { it.seekToPreviousMediaItem() }
            SyncPaths.CMD_TOGGLE_SHUFFLE -> onPlayer { it.shuffleModeEnabled = !it.shuffleModeEnabled }

            SyncPaths.CMD_SEEK -> SyncCodec.decodeOrNull<SeekCommand>(payload)
                ?.let { command -> onPlayer { it.seekTo(command.positionMs) } }

            SyncPaths.CMD_SET_REPEAT -> SyncCodec.decodeOrNull<RepeatCommand>(payload)
                ?.let { command -> onPlayer { it.repeatMode = command.mode } }

            SyncPaths.CMD_PLAY_TRACKS -> SyncCodec.decodeOrNull<PlayTracksCommand>(payload)
                ?.let { playTracks(it) }

            // Spotify keeps likes consistent because every device reads one
            // account library from its servers. Vivi's equivalent server is the
            // YouTube account, and the device that acted already wrote there —
            // so this side only has to bring its own local library into line
            // rather than issue a second, racing write.
            SyncPaths.CMD_TOGGLE_LIKE -> SyncCodec.decodeOrNull<LikeCommand>(payload)
                ?.let { applyLikeLocally(it) }

            SyncPaths.STATE_QUEUE ->
                SyncCodec.decodeOrNull<com.music.vivi.wearsync.QueueSnapshot>(payload)
                    ?.let {
                        _remoteQueue.value = it
                        remotePlayer.updateQueue(it)
                    }

            // A peer took over. Stop here so two devices in the same room are
            // not playing the same song a half-second apart.
            SyncPaths.NOTIFY_WATCH_PLAYING -> onPlayer { it.pause() }

            else -> Timber.d("Ignoring unknown Connect command %s", path)
        }
    }

    /**
     * Mirrors a peer's like into this device's Room library.
     *
     * Uses SongEntity.copy rather than toggleLike(), because toggleLike() also
     * fires its own YouTube call — the peer already made that write, and a
     * second one would race it and could land as an un-like.
     */
    private fun applyLikeLocally(command: LikeCommand) {
        val app = appContextOrNull() ?: return
        scope.launch {
            runCatching {
                val database = dagger.hilt.android.EntryPointAccessors
                    .fromApplication(app, ConnectEntryPoint::class.java)
                    .database()
                val song = database.song(command.trackId).first() ?: return@runCatching
                if (song.song.liked == command.liked) return@runCatching
                database.query {
                    update(
                        song.song.copy(
                            liked = command.liked,
                            likedDate = if (command.liked) java.time.LocalDateTime.now() else null,
                        )
                    )
                }
                Timber.i("Mirrored a peer's like for %s", command.trackId)
            }.onFailure { Timber.w(it, "Could not mirror a like for %s", command.trackId) }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface ConnectEntryPoint {
        fun database(): com.music.vivi.db.MusicDatabase
    }

    @Volatile
    private var appContext: Context? = null

    private fun appContextOrNull(): Context? = appContext

    private fun playTracks(command: PlayTracksCommand) {
        if (command.tracks.isEmpty()) return
        val service = WearBridge.musicService() ?: run {
            Timber.w("Connect asked us to play with no MusicService running")
            return
        }
        // Receiving a queue means we are now the active device.
        claimOwnership()
        scope.launch(Dispatchers.Main) {
            service.playQueue(
                ListQueue(
                    title = command.queueTitle,
                    items = command.tracks.map { it.toMediaItem() },
                    startIndex = command.startIndex.coerceIn(0, command.tracks.lastIndex),
                    // Resume where the other device was, rather than restarting
                    // the track — a transfer that rewinds is not a transfer.
                    position = command.positionMs,
                )
            )
            runCatching {
                service.player.shuffleModeEnabled = command.shuffle
                service.player.repeatMode = command.repeatMode
            }
        }
    }

    private fun onPlayer(block: (Player) -> Unit) {
        val service = WearBridge.musicService() ?: return
        scope.launch {
            withContext(Dispatchers.Main) {
                runCatching { block(service.player) }
                    .onFailure { Timber.w(it, "Connect command failed") }
            }
            WearBridge.onPlayerEvents(force = true)
        }
    }
}
