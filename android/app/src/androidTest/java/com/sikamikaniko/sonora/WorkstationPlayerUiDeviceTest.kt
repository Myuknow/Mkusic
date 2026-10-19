package com.sikamikaniko.sonora

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import coil.request.ImageRequest
import com.sikamikaniko.sonora.data.*
import com.sikamikaniko.sonora.playback.PlaybackService
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkstationPlayerUiDeviceTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun pausedPlayerAndQueueRender() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as SonoraApp
        val originalMode = app.standalone
        app.standalone = false
        val password = InstrumentationRegistry.getArguments().getString("workstationPassword")
        require(!password.isNullOrEmpty()) { "Provide workstationPassword at runtime" }
        val connection = ServerConnection.create(testServerAddress(), password)
        val credentials = CredentialStore(context)
        val repository = WorkstationRepository(context)
        val store = WorkstationPlaybackStore(context)
        var scenario: ActivityScenario<MainActivity>? = null
        fun screenshot(name: String) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("Screenshot unavailable")
            File(context.getExternalFilesDir(null), name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            credentials.save(connection); app.connection = connection
            val library = runBlocking { repository.fetch(connection) }
            repository.save(library)
            val tracks = library.tracks.filter { it.streamUrl(connection.baseUrl) != null }.take(2)
            store.saveQueue(connection, tracks); store.savePosition(0, 0, 0, false)
            runBlocking { app.imageLoader.execute(ImageRequest.Builder(context).data(tracks.first().coverUrl(connection.baseUrl)).build()) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("打开播放器").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("打开播放器").performClick()
            compose.onNodeWithText("正在播放").assertIsDisplayed()
            compose.waitForIdle(); screenshot("m2-player.png")
            compose.onNodeWithText("正在播放").assertIsDisplayed()
            compose.onNodeWithContentDescription("播放队列").performClick()
            compose.onNodeWithText("播放队列 · 2 首").assertIsDisplayed()
            compose.waitForIdle(); screenshot("m2-queue.png")
            compose.onNodeWithContentDescription("关闭队列").performClick()
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) {
                var portrait = false
                scenario.onActivity { portrait = it.window.decorView.width < it.window.decorView.height }
                portrait
            }
            compose.onNodeWithText("正在播放").assertIsDisplayed()
            compose.waitForIdle(); screenshot("m2-player-portrait.png")
            compose.onNodeWithContentDescription("播放队列（2 首）").performScrollTo().assertIsDisplayed()
        } finally {
            scenario?.close()
            context.stopService(Intent(context, PlaybackService::class.java))
            app.connection = null
            app.standalone = originalMode
            credentials.clear(); repository.clear(); store.clear()
        }
    }
}
