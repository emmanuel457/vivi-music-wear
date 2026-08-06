/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Wire protocol shared by the phone app (:app) and the watch app (:wear).
 * Both sides must be signed with the same key for the Wearable Data Layer to
 * route anything between them.
 */

package com.music.vivi.wearsync

/**
 * Capabilities advertised through `res/values/wear.xml` on each side. Each app
 * discovers the other by querying for the capability the *other* side declares.
 */
object SyncCapabilities {
    /** Declared by the phone app. The watch looks for this to find its phone. */
    const val PHONE = "vivi_music_phone"

    /** Declared by the watch app. The phone looks for this to find the watch. */
    const val WATCH = "vivi_music_wear"
}

/**
 * Data Layer paths.
 *
 * `state/…` paths are DataClient items: retained, replayed on reconnect, and
 * deduplicated by content. `cmd/…` and `req/…` are MessageClient payloads:
 * fire-and-forget, low latency, dropped when the peer is unreachable.
 * `channel/…` are ChannelClient streams, used where a payload can exceed the
 * Data Layer's 100 KB item ceiling.
 */
object SyncPaths {
    // ── Phone -> watch state (DataClient) ────────────────────────────────────
    const val STATE_NOW_PLAYING = "/vivi/state/np"
    const val STATE_AUTH = "/vivi/state/auth"

    // ── Phone -> watch bulk (ChannelClient) ──────────────────────────────────
    const val CHANNEL_LIBRARY = "/vivi/channel/library"

    // ── Watch -> phone transport commands (MessageClient) ────────────────────
    const val CMD_PLAY = "/vivi/cmd/play"
    const val CMD_PAUSE = "/vivi/cmd/pause"
    const val CMD_TOGGLE = "/vivi/cmd/toggle"
    const val CMD_NEXT = "/vivi/cmd/next"
    const val CMD_PREVIOUS = "/vivi/cmd/previous"
    const val CMD_SEEK = "/vivi/cmd/seek"
    const val CMD_TOGGLE_SHUFFLE = "/vivi/cmd/shuffle"
    const val CMD_SET_REPEAT = "/vivi/cmd/repeat"
    const val CMD_TOGGLE_LIKE = "/vivi/cmd/like"
    const val CMD_VOLUME = "/vivi/cmd/volume"
    const val CMD_PLAY_TRACKS = "/vivi/cmd/play_tracks"

    // ── Watch -> phone requests (MessageClient) ──────────────────────────────
    /** Ask the phone to re-publish [STATE_NOW_PLAYING] immediately. */
    const val REQ_STATE = "/vivi/req/state"

    /** Ask the phone to open the library channel and stream a snapshot. */
    const val REQ_LIBRARY = "/vivi/req/library"

    /**
     * Ask the phone to hand the signed-in YouTube session to the watch. The
     * phone must show a confirmation UI before honouring this — the payload
     * contains account cookies.
     */
    const val REQ_AUTH = "/vivi/req/auth"

    /** Watch tells the phone it took over playback, so the phone should stop. */
    const val NOTIFY_WATCH_PLAYING = "/vivi/notify/watch_playing"

    /** Watch tells the phone it stopped local playback. */
    const val NOTIFY_WATCH_STOPPED = "/vivi/notify/watch_stopped"

    /**
     * Phone tells the watch it took over playback, so the watch should drop its
     * local queue.
     *
     * The mirror of [NOTIFY_WATCH_PLAYING]. Without it, "last actor wins" only
     * holds in one direction: the watch would keep its queue forever and both
     * devices would play at once.
     */
    const val NOTIFY_PHONE_PLAYING = "/vivi/notify/phone_playing"
}

/** Keys inside the DataMap wrappers used for DataClient items. */
object SyncKeys {
    const val PAYLOAD = "payload"

    /**
     * Monotonic counter appended to every DataItem. The Data Layer suppresses
     * writes whose bytes are unchanged, which would otherwise swallow a
     * "re-publish the same state" request.
     */
    const val REVISION = "revision"
}

/** Mirrors `androidx.media3.common.Player` repeat modes so :wear needn't depend on media3 to read state. */
object SyncRepeatMode {
    const val OFF = 0
    const val ONE = 1
    const val ALL = 2
}
