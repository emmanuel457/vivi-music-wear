/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.music.vivi.wear.data.LibraryStore
import com.music.vivi.wear.data.PhoneLink
import com.music.vivi.wear.data.WearDownloadManager
import com.music.vivi.wear.data.WearPrefs
import com.music.vivi.wear.data.YouTubeSession
import com.music.vivi.wear.playback.PlaybackRouter
import com.music.vivi.wearsync.SyncCodec
import com.music.vivi.wearsync.SyncPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph.
 *
 * Deliberately not Hilt: the watch module has five singletons and no injection
 * points beyond them, and both KSP codegen at build time and the component
 * initialisation at startup are real costs on a device this constrained. The
 * phone app keeps Hilt, where it earns its place.
 *
 * [ensureInitialized] exists because Wear starts [com.music.vivi.wear.data.WearSyncListenerService]
 * and [com.music.vivi.wear.playback.WearMusicService] as independent process
 * entry points; neither can assume the Activity ran first.
 */
@UnstableApi
object WearGraph {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var prefs: WearPrefs
        private set
    lateinit var phoneLink: PhoneLink
        private set
    lateinit var library: LibraryStore
        private set
    lateinit var session: YouTubeSession
        private set
    lateinit var router: PlaybackRouter
        private set
    lateinit var downloads: WearDownloadManager
        private set

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context) {
        if (initialized) return
        val app = context.applicationContext

        prefs = WearPrefs(app)
        library = LibraryStore(app)
        phoneLink = PhoneLink(app)
        session = YouTubeSession(prefs, scope)
        router = PlaybackRouter(app, phoneLink, prefs, scope)
        downloads = WearDownloadManager(app, prefs, scope).apply {
            onDownloadedToWatch = { track ->
                phoneLink.sendAsync(SyncPaths.CMD_DOWNLOAD, SyncCodec.encode(track))
            }
        }

        // Flip the flag before starting anything. router.start() binds a
        // MediaController, which brings up WearMusicService, whose onCreate
        // calls back into this method — and @Synchronized is reentrant, so a
        // flag set at the end would let that callback re-run the whole
        // constructor block and recurse.
        initialized = true

        session.start()
        phoneLink.start()
        router.start()

        scope.launch { library.load() }
        scope.launch { downloads.load() }
    }

    /** Asks the phone for a fresh library snapshot and current transport state. */
    fun requestSync() {
        phoneLink.sendAsync(SyncPaths.REQ_LIBRARY)
        phoneLink.sendAsync(SyncPaths.REQ_STATE)
    }

    /**
     * Asks the phone to hand over the signed-in account. The phone will not act
     * on this silently — it prompts the user before releasing any cookie.
     */
    fun requestAccount() {
        phoneLink.sendAsync(SyncPaths.REQ_AUTH)
    }
}
