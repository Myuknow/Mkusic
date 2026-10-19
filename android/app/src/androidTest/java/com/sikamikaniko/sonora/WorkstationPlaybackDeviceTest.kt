package com.sikamikaniko.sonora

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import com.sikamikaniko.sonora.data.*
import com.sikamikaniko.sonora.playback.PlaybackService
import com.sikamikaniko.sonora.playback.WorkstationMedia
import com.sikamikaniko.sonora.playback.WorkstationStreaming
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class WorkstationPlaybackDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(label: String, timeout: Long = 60_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            main { ready = predicate() }
            if (ready) return
            Thread.sleep(100)
        }
        fail("Timed out: $label")
    }

    private fun controller(): MediaController {
        lateinit var future: ListenableFuture<MediaController>
        main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        return future.get(20, TimeUnit.SECONDS)
    }

    @Test fun serverStreamSeekQueueNotificationAndServiceRestore() {
        val password = InstrumentationRegistry.getArguments().getString("workstationPassword")
        require(!password.isNullOrEmpty()) { "Provide workstationPassword at runtime" }
        val app = context.applicationContext as SonoraApp
        val connection = ServerConnection.create(testServerAddress(), password)
        val credentials = CredentialStore(context)
        val store = WorkstationPlaybackStore(context)
        val repository = WorkstationRepository(context)
        var current: MediaController? = null
        app.connection = connection
        try {
            credentials.save(connection)
            store.clear()
            val library = runBlocking { repository.fetch(connection) }
            repository.save(library)
            val tracks = library.tracks.filter { it.kind == "bilibili" && it.streamUrl(connection.baseUrl) != null }.take(2)
            assertEquals(2, tracks.size)
            var c = controller(); current = c
            main {
                c.volume = 0f // Only this player's volume; do not change the device volume.
                c.setMediaItems(tracks.map { WorkstationMedia.item(it, connection) }, 0, 0)
                c.prepare(); c.play()
            }
            await("first server song playing") {
                c.playerError?.let { fail(WorkstationStreaming.errorMessage(it)) }
                c.isPlaying && c.duration > 0 && c.currentPosition > 1_000
            }
            main { assertTrue("Server stream must support seeking", c.isCurrentMediaItemSeekable) }
            await("media notification") {
                context.getSystemService(NotificationManager::class.java).activeNotifications
                    .any { it.notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) }
            }
            main { c.seekTo(c.duration / 2) }
            await("seek advances to middle") { c.currentPosition > c.duration / 3 && c.isPlaying }
            main { c.pause() }
            await("pause") { !c.isPlaying && !c.playWhenReady }
            main { c.seekTo((c.duration - 1_500).coerceAtLeast(0)); c.play() }
            await("automatic next song") {
                c.playerError?.let { fail(WorkstationStreaming.errorMessage(it)) }
                c.currentMediaItemIndex == 1 && c.isPlaying
            }
            main { c.pause(); c.seekTo(20_000) }
            await("queue snapshot saved") { store.load(connection)?.let { it.index == 1 && it.positionMs >= 19_000 } == true }
            main { c.release(); context.stopService(Intent(context, PlaybackService::class.java)) }
            current = null
            await("service stopped", 15_000) {
                @Suppress("DEPRECATION")
                val running = context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                running.none { it.service.className == PlaybackService::class.java.name }
            }
            c = controller(); current = c
            await("service restores selected song and progress") { c.mediaItemCount == 2 && c.currentMediaItemIndex == 1 && c.currentPosition >= 19_000 }
            main { assertFalse("Restoration must stay paused", c.playWhenReady); c.volume = 0f; c.play() }
            await("resumed service plays") {
                c.playerError?.let { fail(WorkstationStreaming.errorMessage(it)) }
                c.isPlaying && c.currentPosition >= 20_000
            }

            // A distinct cache key ensures a real HTTP request for the invalid-password case.
            val uncached = tracks.first().copy(id = "auth-test-${SystemClock.uptimeMillis()}")
            app.connection = connection.copy(password = "invalid-test-password")
            main { c.setMediaItems(listOf(WorkstationMedia.item(uncached, connection), WorkstationMedia.item(tracks.last(), connection))); c.prepare(); c.play() }
            await("401 is surfaced without skipping queue") { c.playerError != null }
            main {
                assertTrue(WorkstationStreaming.errorMessage(c.playerError!!).contains("密码"))
                assertEquals(0, c.currentMediaItemIndex)
                assertEquals(uncached.id, c.currentMediaItem?.mediaId)
            }
        } finally {
            main { current?.let { it.pause(); it.clearMediaItems(); it.release() }; context.stopService(Intent(context, PlaybackService::class.java)) }
            app.connection = null
            credentials.clear(); store.clear(); repository.clear()
        }
    }
}
