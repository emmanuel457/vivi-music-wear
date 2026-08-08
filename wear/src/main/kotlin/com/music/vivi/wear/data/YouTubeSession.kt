/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeLocale
import com.music.vivi.wearsync.AuthPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

/**
 * Owns the credentials on the [YouTube] singleton.
 *
 * The watch never performs its own OAuth flow — signing into Google on a 45 mm
 * screen is miserable. Instead the phone hands over the session it already has,
 * and this class is the single place that pushes it into innertube.
 */
class YouTubeSession(
    private val prefs: WearPrefs,
    private val scope: CoroutineScope,
) {

    private val _accountName = MutableStateFlow<String?>(null)
    val accountName: StateFlow<String?> = _accountName.asStateFlow()

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    fun start() {
        applyLocale()
        scope.launch {
            combine(
                prefs.authCookie,
                prefs.visitorData,
                prefs.dataSyncId,
                prefs.accountName,
            ) { cookie, visitor, syncId, name -> Credentials(cookie, visitor, syncId, name) }
                .collect { apply(it) }
        }
    }

    private fun applyLocale() {
        val locale = Locale.getDefault()
        YouTube.locale = YouTubeLocale(
            gl = locale.country.takeIf { it.isNotBlank() } ?: "US",
            hl = locale.toLanguageTag(),
        )
    }

    private fun apply(credentials: Credentials) {
        YouTube.cookie = credentials.cookie
        YouTube.visitorData = credentials.visitorData
        YouTube.dataSyncId = credentials.dataSyncId

        // The phone fetches visitorData itself when it has none; the watch only
        // ever received whatever the phone happened to have cached at handoff
        // time, which is routinely nothing. Without it InnerTube's browse
        // endpoints return empty, which is what left Quick picks blank on a
        // watch that was otherwise correctly signed in.
        if (credentials.visitorData.isNullOrBlank()) {
            scope.launch {
                YouTube.refreshVisitorData()
                    .onSuccess {
                        Timber.i("Fetched visitorData for the watch")
                        prefs.saveVisitorData(it)
                    }
                    .onFailure { Timber.w(it, "Could not fetch visitorData") }
            }
        }
        // Browsing with the account attached is the whole point of the handoff:
        // it makes home, library and liked songs match what the phone shows.
        YouTube.useLoginForBrowse = credentials.cookie != null
        _accountName.value = credentials.accountName
        _signedIn.value = credentials.cookie != null
        Timber.d("YouTube session applied (signedIn=${credentials.cookie != null})")
    }

    /** Persists an [AuthPayload] delivered by the phone, or clears it if revoked. */
    suspend fun acceptFromPhone(payload: AuthPayload) {
        if (payload.revoked || payload.cookie == null) {
            Timber.i("Phone revoked the watch session")
            prefs.clearAuth()
            return
        }
        prefs.saveAuth(
            cookie = payload.cookie,
            visitorData = payload.visitorData,
            dataSyncId = payload.dataSyncId,
            accountName = payload.accountName ?: payload.accountEmail,
        )
        Timber.i("Accepted account handoff from phone")
    }

    suspend fun signOut() {
        prefs.clearAuth()
        YouTube.clearGuestSession()
    }

    private data class Credentials(
        val cookie: String?,
        val visitorData: String?,
        val dataSyncId: String?,
        val accountName: String?,
    )
}
