/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.wearDataStore: DataStore<Preferences> by preferencesDataStore(name = "vivi_wear")

/** Which device the user last chose to play on. */
enum class PlaybackRoute { AUTO, WATCH, PHONE }

/** Mirrors `com.music.vivi.constants.AudioQuality` without pulling in :app. */
enum class WearAudioQuality { AUTO, HIGH, LOW }

class WearPrefs(private val context: Context) {

    private val store get() = context.wearDataStore

    val authCookie: Flow<String?> = store.data.map { it[KeyCookie] }
    val visitorData: Flow<String?> = store.data.map { it[KeyVisitorData] }
    val dataSyncId: Flow<String?> = store.data.map { it[KeyDataSyncId] }
    val accountName: Flow<String?> = store.data.map { it[KeyAccountName] }
    val lastSyncedAt: Flow<Long> = store.data.map { it[KeyLastSynced] ?: 0L }
    val setupComplete: Flow<Boolean> = store.data.map { it[KeySetupComplete] ?: false }

    val route: Flow<PlaybackRoute> = store.data.map {
        runCatching { PlaybackRoute.valueOf(it[KeyRoute] ?: PlaybackRoute.AUTO.name) }
            .getOrDefault(PlaybackRoute.AUTO)
    }

    val audioQuality: Flow<WearAudioQuality> = store.data.map {
        runCatching { WearAudioQuality.valueOf(it[KeyAudioQuality] ?: WearAudioQuality.AUTO.name) }
            .getOrDefault(WearAudioQuality.AUTO)
    }

    suspend fun saveAuth(
        cookie: String?,
        visitorData: String?,
        dataSyncId: String?,
        accountName: String?,
    ) = store.edit { prefs ->
        prefs.setOrRemove(KeyCookie, cookie)
        prefs.setOrRemove(KeyVisitorData, visitorData)
        prefs.setOrRemove(KeyDataSyncId, dataSyncId)
        prefs.setOrRemove(KeyAccountName, accountName)
        prefs[KeySetupComplete] = true
    }

    suspend fun clearAuth() = store.edit { prefs ->
        prefs.remove(KeyCookie)
        prefs.remove(KeyVisitorData)
        prefs.remove(KeyDataSyncId)
        prefs.remove(KeyAccountName)
    }

    suspend fun setSetupComplete(value: Boolean) = store.edit { it[KeySetupComplete] = value }

    suspend fun setRoute(value: PlaybackRoute) = store.edit { it[KeyRoute] = value.name }

    suspend fun setAudioQuality(value: WearAudioQuality) =
        store.edit { it[KeyAudioQuality] = value.name }

    suspend fun setLastSyncedAt(epochMs: Long) = store.edit { it[KeyLastSynced] = epochMs }

    private fun <T> androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<T>,
        value: T?,
    ) {
        if (value == null) remove(key) else set(key, value)
    }

    private companion object {
        val KeyCookie = stringPreferencesKey("innerTubeCookie")
        val KeyVisitorData = stringPreferencesKey("visitorData")
        val KeyDataSyncId = stringPreferencesKey("dataSyncId")
        val KeyAccountName = stringPreferencesKey("accountName")
        val KeyRoute = stringPreferencesKey("playbackRoute")
        val KeyAudioQuality = stringPreferencesKey("audioQuality")
        val KeyLastSynced = longPreferencesKey("lastSyncedAt")
        val KeySetupComplete = booleanPreferencesKey("setupComplete")
    }
}
