package com.sikamikaniko.sonora.playback

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Process-wide media cache. Anything streamed is cached (up to [MAX_BYTES]),
 * so replays are instant and recently played music works with a flaky
 * connection. Only one [SimpleCache] instance may exist per directory, hence
 * the singleton.
 */
object PlayerCache {

    private const val MAX_BYTES = 1024L * 1024L * 1024L // 1 GB

    @Volatile private var instance: SimpleCache? = null

    @Synchronized
    fun get(context: Context): SimpleCache {
        return instance ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext)
        ).also { instance = it }
    }

    fun sizeBytes(): Long = instance?.cacheSpace ?: 0L

    fun clear() {
        instance?.let { c ->
            try { c.keys.toList().forEach { c.removeResource(it) } } catch (_: Exception) { }
        }
    }
}
