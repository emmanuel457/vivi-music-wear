/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.wear

import android.util.Log
import timber.log.Timber

/**
 * Forwards warnings and errors to logcat in release builds.
 *
 * Only a DebugTree was planted, and only under `BuildConfig.DEBUG` — so on the
 * signed APKs actually installed on a watch, every Timber call in this app went
 * nowhere. Three separate failures were diagnosed by reading screenshots
 * because of it. Debug and info stay dropped; those are noise on a device this
 * constrained, and warnings are where the useful signal is.
 */
class ReleaseLogTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean =
        priority >= Log.WARN

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        val resolvedTag = tag ?: "ViviWear"
        Log.println(priority, resolvedTag, message)
        // Stack traces are the whole reason a warning is worth reading.
        t?.let { Log.println(priority, resolvedTag, Log.getStackTraceString(it)) }
    }
}
