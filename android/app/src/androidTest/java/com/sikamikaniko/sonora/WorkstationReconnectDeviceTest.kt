package com.sikamikaniko.sonora

import android.app.ActivityManager
import android.content.Intent
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.platform.app.InstrumentationRegistry
import com.sikamikaniko.sonora.data.*
import com.sikamikaniko.sonora.playback.PlaybackService
import com.sikamikaniko.sonora.ui.WorkstationViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class WorkstationReconnectDeviceTest {
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

    // Deliberately release the live connection without calling the ViewModel's cleanup.
    // Media3 delivers the same onDisconnected callback used for a lost service connection.
    private fun controller(vm: WorkstationViewModel): MediaController =
        WorkstationViewModel::class.java.getDeclaredField("controller").apply { isAccessible = true }
            .get(vm) as MediaController

    private fun stopService() {
        main { context.stopService(Intent(context, PlaybackService::class.java)) }
        await("service stopped", 15_000) {
            @Suppress("DEPRECATION")
            context.getSystemService(ActivityManager::class.java).getRunningServices(50)
                .none { it.service.className == PlaybackService::class.java.name }
        }
    }

    @Test fun nextAndForegroundReconnectAfterServiceLoss() {
        val password = InstrumentationRegistry.getArguments().getString("workstationPassword")
        require(!password.isNullOrEmpty()) { "Provide workstationPassword at runtime" }
        val app = context.applicationContext as SonoraApp
        val originalMode = app.standalone
        app.standalone = false
        val connection = ServerConnection.create(testServerAddress(), password)
        val repository = WorkstationRepository(context)
        val credentials = CredentialStore(context)
        val playbackStore = WorkstationPlaybackStore(context)
        val vmStore = ViewModelStore()
        var vm: WorkstationViewModel? = null
        try {
            credentials.save(connection); app.connection = connection
            val library = runBlocking { repository.fetch(connection) }
            repository.save(library)
            val tracks = library.tracks.filter { it.streamUrl(connection.baseUrl) != null }.take(2)
            assertEquals(2, tracks.size)
            playbackStore.saveQueue(connection, tracks)
            playbackStore.savePosition(0, 0, 0, false)
            lateinit var model: WorkstationViewModel
            main {
                model = ViewModelProvider(vmStore, ViewModelProvider.AndroidViewModelFactory(app))[WorkstationViewModel::class.java]
                vm = model
            }
            await("initial queue restored") { model.playback.value.ready && model.playback.value.queue.size == 2 }
            main { controller(model).volume = 0f; model.togglePlay() }
            await("first song plays") {
                assertNull(model.playback.value.error)
                controller(model).isPlaying && controller(model).currentPosition > 500
            }
            main { model.togglePlay() }
            await("paused progress saved") { playbackStore.load(connection)?.positionMs?.let { it > 0 } == true }
            main { controller(model).release() }
            await("disconnect reported, queue preserved") {
                !model.playback.value.ready && model.playback.value.queue.size == 2 &&
                    model.playback.value.error?.contains("断开") == true
            }
            stopService()
            // Queue a command before a new MediaController exists. It must run after binding.
            main { model.next() }
            await("next reconnects and plays second song") {
                val state = model.playback.value
                if (state.ready) controller(model).volume = 0f
                state.ready && state.track?.id == tracks[1].id && controller(model).isPlaying
            }
            main { model.togglePlay(); controller(model).release() }
            await("second disconnect") { !model.playback.value.ready }
            stopService()
            main { model.onForeground() }
            await("foreground restores connection") { model.playback.value.ready && model.playback.value.track?.id == tracks[1].id }
            main {
                assertFalse("Foreground rebind must not autoplay a paused queue", controller(model).playWhenReady)
                assertNull(model.playback.value.error)
                controller(model).volume = 0f
                model.togglePlay()
            }
            await("play works after foreground rebind") { controller(model).isPlaying }
            main {
                val c = controller(model)
                c.pause()
                c.repeatMode = Player.REPEAT_MODE_OFF
                c.shuffleModeEnabled = true
            }
            await("shuffle mode and pause applied") {
                model.playback.value.shuffle && model.playback.value.repeat == Player.REPEAT_MODE_OFF &&
                    !controller(model).isPlaying
            }
            main {
                val c = controller(model)
                c.seekTo(c.currentTimeline.getLastWindowIndex(true), 0)
            }
            await("shuffle end disables next", 15_000) {
                val c = controller(model)
                !c.hasNextMediaItem() && !model.playback.value.canSkipNext
            }
            main { controller(model).repeatMode = Player.REPEAT_MODE_ALL }
            await("repeat all enables next at shuffle end") { model.playback.value.canSkipNext }
        } finally {
            main { vm?.disconnect(); vmStore.clear(); context.stopService(Intent(context, PlaybackService::class.java)) }
            app.connection = null
            app.standalone = originalMode
            credentials.clear(); repository.clear(); playbackStore.clear()
        }
    }
}
