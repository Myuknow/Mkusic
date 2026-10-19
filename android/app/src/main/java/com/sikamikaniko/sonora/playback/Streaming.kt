package com.sikamikaniko.sonora.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything that decides how bytes get from the server to the speaker.
 *
 * Shared by [PlaybackService] (the player itself) and [StreamGuard]'s preloader, so both
 * write into — and read from — the same [PlayerCache] under the same keys.
 */
object Streaming {

    /** How many upcoming queue entries to pull down ahead of time. */
    const val PREFETCH_AHEAD = 2

    /**
     * Subsonic signs every URL with a fresh random salt, so the *URL* of a song differs
     * on every single play. Media3's default cache key is the URI — meaning the 1 GB
     * cache never once produced a hit and was pure write-only garbage. Key on the song
     * id instead, so a track played yesterday is on disk today.
     */
    fun cacheKey(uri: Uri): String {
        val id = runCatching { uri.getQueryParameter("id") }.getOrNull()
        val path = uri.path.orEmpty()
        return if (!id.isNullOrBlank() && path.contains("stream")) "song:$id" else uri.toString()
    }

    fun cacheKey(uri: String): String = cacheKey(Uri.parse(uri))

    /** Honours an explicit MediaItem custom cache key, falls back to the song id. */
    private val cacheKeyFactory = CacheKeyFactory { spec -> spec.key ?: cacheKey(spec.uri) }

    /**
     * Short timeouts on purpose. A half-open socket — what you get when a VPN tunnel
     * dies in a valley — is indistinguishable from a slow server, and the old 30 s
     * read timeout meant half a minute of silence before anything could react.
     * With a deep buffer behind us, failing fast and reconnecting is strictly better.
     */
    private fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)

    fun cacheFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(PlayerCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory()))
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /**
     * More patience than the default three tries before a load error is escalated to
     * the player. Most mobile dropouts are over inside ~20 s, and retries at this level
     * are invisible to the listener — the buffer covers them. Genuinely fatal causes
     * (404, unparseable media) still return [C.TIME_UNSET] from the base policy and are
     * escalated immediately, so a dead track can't trap us in a retry loop.
     */
    fun loadErrorPolicy(): LoadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(8)

    /**
     * Pull a whole track into the cache ahead of the player. This is what actually makes
     * a mountain road survivable: by the time coverage vanishes, the next couple of songs
     * are already on disk and the network is no longer in the playback path at all.
     *
     * Cheap to call on an already-cached track — [CacheWriter] skips spans it already has.
     * Cancelling the calling coroutine cancels the download.
     */
    suspend fun warm(context: Context, uri: String, key: String): Unit = coroutineScope {
        val cache = PlayerCache.get(context)
        val known = runCatching { ContentMetadata.getContentLength(cache.getContentMetadata(key)) }
            .getOrDefault(C.LENGTH_UNSET.toLong())
        if (known > 0 && cache.getCachedBytes(key, 0, known) >= known) return@coroutineScope

        val source = cacheFactory(context).createDataSourceForDownloading()
        val spec = DataSpec.Builder().setUri(Uri.parse(uri)).setKey(key).build()
        val writer = CacheWriter(source, spec, null, null)

        // CacheWriter.cache() blocks, so coroutine cancellation alone would not stop it.
        // This sentinel turns "the caller gave up" (skipped track, preload switched off)
        // into an actual cancelled download instead of a runaway one.
        val sentinel = launch(Dispatchers.Default) {
            try { awaitCancellation() } finally { writer.cancel() }
        }
        try {
            withContext(Dispatchers.IO) {
                // Best effort: a failed preload just means we stream it the normal way.
                runCatching { writer.cache() }
            }
        } finally {
            sentinel.cancel()
        }
    }
}
