package com.sikamikaniko.sonora.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.sikamikaniko.sonora.MainActivity
import com.sikamikaniko.sonora.SonoraApp
import com.sikamikaniko.sonora.data.WorkstationHttp
import com.sikamikaniko.sonora.data.WorkstationPlaybackStore
import com.sikamikaniko.sonora.data.WorkstationRepository

/**
 * Foreground media service. Media3 handles the notification and lock-screen
 * controls automatically once a [MediaSession] wraps the [ExoPlayer].
 * Streams go through a caching data source so playback is smooth and cached.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private lateinit var store: WorkstationPlaybackStore
    private val handler = Handler(Looper.getMainLooper())
    private val saveProgress = object : Runnable {
        override fun run() {
            persistPosition()
            handler.postDelayed(this, 5_000)
        }
    }

    private fun persistPosition() {
        mediaSession?.player?.let { p ->
            if (p.mediaItemCount > 0) store.savePosition(p.currentMediaItemIndex,
                p.currentPosition, p.repeatMode, p.shuffleModeEnabled)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as SonoraApp
        store = WorkstationPlaybackStore(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Buffer deeply so a coverage gap is ridden out from RAM and disk instead of
        // stopping playback. Five minutes of audio comfortably covers a tunnel or a
        // valley; `prioritizeTimeOverSizeThresholds` keeps filling toward the minimum
        // even on a big file, and the byte cap keeps that honest on low-RAM phones.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 300_000,
                /* bufferForPlaybackMs = */ 2_000,
                /* bufferForPlaybackAfterRebufferMs = */ 4_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(32 * 1024 * 1024)
            // Keep a minute behind us so re-preparing after a dropout resumes from
            // memory rather than re-fetching what we already played.
            .setBackBuffer(/* backBufferDurationMs = */ 60_000, /* retainBackBufferFromKeyframe = */ true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(WorkstationStreaming.cacheFactory(this))
            .setLoadErrorHandlingPolicy(WorkstationStreaming.loadErrorPolicy())

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Screen off in a phone mount is the normal case in the car: hold the CPU
            // and wifi locks while playing, or the radio sleeps and the stream stalls.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Tapping the media notification / lock-screen controls opens the app.
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val artwork = CacheBitmapLoader(DataSourceBitmapLoader(
            DataSourceBitmapLoader.DEFAULT_EXECUTOR_SERVICE.get(),
            DefaultDataSource.Factory(this, OkHttpDataSource.Factory(WorkstationHttp.client { app.connection }))
        ))
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(artwork)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
                    mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                    val connection = app.connection ?: return Futures.immediateFailedFuture(IllegalStateException("请先连接服务器"))
                    return try {
                        val known = WorkstationRepository(this@PlaybackService).cached(connection)?.tracks.orEmpty().associateBy { it.id }
                        val items = mediaItems.map { item ->
                            val track = WorkstationMedia.track(item)?.takeIf { it.id == item.mediaId } ?: known[item.mediaId]
                            WorkstationMedia.item(requireNotNull(track) { "未找到这首歌曲" }, connection)
                        }
                        Futures.immediateFuture(items)
                    } catch (error: Exception) { Futures.immediateFailedFuture(error) }
                }

                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo):
                    ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val connection = app.connection ?: return Futures.immediateFailedFuture(IllegalStateException("请先连接服务器"))
                    val saved = store.load(connection) ?: return Futures.immediateFailedFuture(IllegalStateException("没有可恢复的播放队列"))
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(
                        saved.tracks.map { WorkstationMedia.item(it, connection) }, saved.index, saved.positionMs))
                }
            })
            .build()
        val saved = app.connection?.let(store::load)
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val connection = app.connection ?: return
                val queue = (0 until player.mediaItemCount).mapNotNull { WorkstationMedia.track(player.getMediaItemAt(it)) }
                if (queue.isEmpty()) store.clear() else store.saveQueue(connection, queue)
                persistPosition()
            }

            override fun onEvents(player: Player, events: Player.Events) { persistPosition() }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady && player.playbackState == Player.STATE_IDLE && player.playerError == null && player.mediaItemCount > 0) {
                    player.prepare()
                }
            }
        })
        if (saved != null && app.connection != null) {
            player.repeatMode = saved.repeatMode
            player.shuffleModeEnabled = saved.shuffle
            player.setMediaItems(saved.tracks.map { WorkstationMedia.item(it, app.connection!!) }, saved.index, saved.positionMs)
            // Restore paused. Playback starts only after an explicit play command.
        }
        handler.postDelayed(saveProgress, 5_000)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playerError != null) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveProgress)
        persistPosition()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
