package com.sikamikaniko.sonora.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.sikamikaniko.sonora.data.NetworkMonitor
import com.sikamikaniko.sonora.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps music playing across a bad connection.
 *
 * Driving through patchy coverage breaks playback in two quite different ways, and the
 * app has to survive both:
 *
 *  1. **A real error.** The connection is refused or times out and [Player.Listener
 *     .onPlayerError] fires. This is scheduled a retry with a growing backoff, and — the
 *     important part — it never permanently gives up while the user still wants music.
 *  2. **A silent stall.** A half-open socket (what you typically get when a VPN tunnel
 *     dies in a valley) leaves the player sitting in `STATE_BUFFERING` with no callback
 *     at all. To the listener this is simply silence, forever. The watchdog below is the
 *     only thing that notices.
 *
 * On top of recovery it runs the *preload*, which is what actually makes a mountain road
 * survivable: while the signal is good, the current and next few tracks are pulled all
 * the way onto disk, so when coverage vanishes the network is no longer in the playback
 * path at all.
 *
 * This lives in the service, attached to the [ExoPlayer], rather than in a ViewModel:
 * playback regularly outlives the UI (Bluetooth autoplay starts the service with no
 * Activity), and resilience that dies with the UI is no resilience at all.
 */
class StreamGuard(private val context: Context, private val player: ExoPlayer) {

    companion object {
        private const val TICK_MS = 500L

        /** Nothing has advanced for this long while we're meant to be playing -> stalled. */
        private const val STALL_TIMEOUT_MS = 12_000L

        /** Give up stepping over unplayable tracks after this many in a row. */
        private const val MAX_BAD_TRACK_SKIPS = 5

        /** Let the player claim bandwidth for what's actually playing before preloading. */
        private const val PRELOAD_DELAY_MS = 4_000L

        /** The guard belonging to the running service, so the UI can nudge it. */
        @Volatile
        var active: StreamGuard? = null
            private set
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { Prefs(context) }

    private var reconnectJob: Job? = null
    private var preloadJob: Job? = null
    private var attempt = 0
    private var badTrackSkips = 0
    private var lastPositionMs = -1L
    private var lastBufferedMs = -1L
    private var lastProgressAt = SystemClock.elapsedRealtime()

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    fun start() {
        player.addListener(listener)
        handler.postDelayed(watchdog, TICK_MS)
        scope.launch {
            var wasOffline = false
            NetworkMonitor.online(context).collect { up ->
                if (up && wasOffline) {
                    // Signal is back: don't sit out the rest of a 20 s backoff and don't
                    // wait for the watchdog either — reconnect the stream right now.
                    if (PlaybackHealth.reconnecting.value || player.playbackState == Player.STATE_IDLE) {
                        reconnectJob?.cancel()
                        reconnectJob = null
                        attempt = 0
                        scheduleReconnect(immediate = true)
                    }
                }
                wasOffline = !up
            }
        }
        active = this
    }

    fun release() {
        if (active === this) active = null
        handler.removeCallbacks(watchdog)
        player.removeListener(listener)
        scope.cancel()
        PlaybackHealth.setReconnecting(false)
    }

    /** The UI changed something (transport control, preload setting) — start clean. */
    fun onUserAction() {
        clearReconnect()
        badTrackSkips = 0
    }

    // -----------------------------------------------------------------------
    // Reacting to the player
    // -----------------------------------------------------------------------

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            clearReconnect()
            refreshPreload()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // The user hitting pause is not a dropout — stop trying to reconnect.
            if (!isPlaying && !player.playWhenReady) clearReconnect()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (isTrackFatal(error)) {
                // A genuinely dead track (gone from the server, unplayable) must not trap
                // the drive in a retry loop — step over it and keep the music going.
                // Bounded, so a queue of broken files can't machine-gun through itself.
                clearReconnect()
                if (player.hasNextMediaItem() && badTrackSkips < MAX_BAD_TRACK_SKIPS) {
                    badTrackSkips++
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                } else {
                    PlaybackHealth.report(
                        if (badTrackSkips >= MAX_BAD_TRACK_SKIPS) "Several tracks in a row wouldn't play."
                        else "Couldn't play this track."
                    )
                }
                return
            }
            scheduleReconnect()
        }
    }

    /**
     * Errors that no amount of retrying will fix — the file is gone or unplayable.
     * Everything else, including anything unrecognised, is treated as "the network is
     * bad right now", which is overwhelmingly the common case on the road.
     */
    private fun isTrackFatal(error: PlaybackException): Boolean {
        val http = generateSequence(error.cause) { it.cause }
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
        if (http != null) {
            // 5xx and 429 are the server having a moment; other 4xx is this track being wrong.
            return http.responseCode !in 500..599 && http.responseCode != 429
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> true
            // Media3 groups error codes by thousands: 3xxx = content parsing,
            // 4xxx = decoding. Both mean the bytes are wrong, not the link.
            else -> error.errorCode in 3000..4999
        }
    }

    // -----------------------------------------------------------------------
    // Reconnect
    // -----------------------------------------------------------------------

    /** 0s, 1s, 2s, 4s, 8s, 15s, then every 20s for as long as it takes. */
    private fun backoffMs(attempt: Int): Long = when (attempt) {
        0 -> 0L
        1 -> 1_000L
        2 -> 2_000L
        3 -> 4_000L
        4 -> 8_000L
        5 -> 15_000L
        else -> 20_000L
    }

    /** Back on a healthy stream (or the user stopped caring) — forget the retry state. */
    private fun clearReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        attempt = 0
        markProgress(reset = true)
        PlaybackHealth.setReconnecting(false)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        if (!player.playWhenReady) return            // paused: nothing to recover
        if (reconnectJob?.isActive == true) return
        PlaybackHealth.setReconnecting(true)
        val wait = if (immediate) 0L else backoffMs(attempt)
        attempt++
        reconnectJob = scope.launch {
            delay(wait)
            if (!player.playWhenReady) { clearReconnect(); return@launch }
            val live = player.currentMediaItem?.mediaId?.startsWith("radio:") == true
            val position = player.currentPosition.coerceAtLeast(0)
            player.prepare()
            // Live radio has no meaningful position to return to — rejoin at the edge.
            if (!live && position > 0) player.seekTo(position)
            player.play()
            // Give this attempt a fair shot before the watchdog can fire again.
            markProgress(reset = true)
            // Whatever we can still reach should go to disk while we can reach it.
            refreshPreload()
        }
    }

    // -----------------------------------------------------------------------
    // Stall watchdog
    // -----------------------------------------------------------------------

    private val watchdog = object : Runnable {
        override fun run() {
            runCatching { tick() }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private fun markProgress(reset: Boolean = false) {
        lastProgressAt = SystemClock.elapsedRealtime()
        if (reset) {
            lastPositionMs = -1L
            lastBufferedMs = -1L
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (!player.playWhenReady || player.playbackState == Player.STATE_ENDED) {
            markProgress(reset = true)
            return
        }

        val position = player.currentPosition
        if (player.playbackState == Player.STATE_READY && player.isPlaying && position != lastPositionMs) {
            lastPositionMs = position
            lastBufferedMs = player.bufferedPosition
            lastProgressAt = now
            // Audio is genuinely moving: both failure budgets are earned back here and
            // nowhere else. (Refilling them on track *transition* is what made the old
            // code die on every song after the first one you skipped to.)
            badTrackSkips = 0
            if (attempt != 0 || PlaybackHealth.reconnecting.value) clearReconnect()
            return
        }

        // A slow link that is still downloading is not a stall — tearing that down and
        // re-preparing every 12 s would thrash a connection that was going to make it.
        val buffered = player.bufferedPosition
        if (buffered > lastBufferedMs) {
            lastBufferedMs = buffered
            lastProgressAt = now
            return
        }

        if (now - lastProgressAt >= STALL_TIMEOUT_MS && reconnectJob?.isActive != true) {
            lastProgressAt = now
            // The first stall gets an instant retry; repeats back off.
            scheduleReconnect(immediate = attempt == 0)
        }
    }

    // -----------------------------------------------------------------------
    // Preload
    // -----------------------------------------------------------------------

    fun refreshPreload() {
        preloadJob?.cancel()
        if (!prefs.preloadEnabled) return

        // Collected here because ExoPlayer is main-thread only.
        val targets = ArrayList<Pair<String, String>>()
        fun consider(index: Int) {
            if (index < 0 || index >= player.mediaItemCount) return
            val item = player.getMediaItemAt(index)
            if (item.mediaId.startsWith("radio:")) return           // live stream, nothing to cache
            val uri = item.localConfiguration?.uri ?: return
            if (uri.scheme?.startsWith("http") != true) return      // already on the device
            val key = item.localConfiguration?.customCacheKey ?: Streaming.cacheKey(uri)
            if (targets.none { it.second == key }) targets += uri.toString() to key
        }

        val current = player.currentMediaItemIndex
        consider(current)
        // Honours shuffle/repeat for the track that's genuinely next, then walks forward.
        val next = player.nextMediaItemIndex
        if (next != C.INDEX_UNSET) consider(next)
        val from = if (next != C.INDEX_UNSET) next else current
        for (i in 1..Streaming.PREFETCH_AHEAD) consider(from + i)

        if (targets.isEmpty()) return
        preloadJob = scope.launch {
            delay(PRELOAD_DELAY_MS)
            for ((uri, key) in targets) {
                if (!isActive) return@launch
                Streaming.warm(context, uri, key)
            }
        }
    }
}
