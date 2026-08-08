/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear.data

import android.content.Context
import com.music.vivi.wearsync.LibrarySnapshot
import com.music.vivi.wearsync.SyncCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * The watch's library cache.
 *
 * Deliberately a single JSON file rather than a Room database: the watch never
 * queries or joins this data, it only renders lists of it, and the phone is the
 * only writer. A schema-migrating database would be pure overhead here.
 */
class LibraryStore(context: Context) {

    private val file = File(context.filesDir, "library.json")

    private val _snapshot = MutableStateFlow(LibrarySnapshot.EMPTY)
    val snapshot: StateFlow<LibrarySnapshot> = _snapshot.asStateFlow()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext
        runCatching { SyncCodec.decode<LibrarySnapshot>(file.readBytes()) }
            .onSuccess { _snapshot.value = it }
            .onFailure {
                Timber.w(it, "Library cache unreadable, discarding")
                file.delete()
            }
    }

    suspend fun save(snapshot: LibrarySnapshot) = withContext(Dispatchers.IO) {
        _snapshot.value = snapshot
        runCatching {
            // Write-then-rename so a watch that dozes off mid-write doesn't leave
            // a truncated cache that fails to parse on next boot.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(SyncCodec.encode(snapshot))
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure { Timber.w(it, "Could not persist library cache") }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        _snapshot.value = LibrarySnapshot.EMPTY
        file.delete()
    }
}
