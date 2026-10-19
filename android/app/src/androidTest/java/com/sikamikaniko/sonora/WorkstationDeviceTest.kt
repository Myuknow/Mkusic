package com.sikamikaniko.sonora

import android.graphics.Bitmap
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.sikamikaniko.sonora.data.CredentialStore
import com.sikamikaniko.sonora.data.WorkstationRepository
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Live integration test: credentials arrive only as runner arguments, never in source. */
class WorkstationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun forgetTestCredentials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        CredentialStore(context).clear()
        WorkstationRepository(context).clear()
        (context.applicationContext as SonoraApp).connection = null
    }

    @Test fun connectionBrowseAndEncryptedStorage() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val password = InstrumentationRegistry.getArguments().getString("workstationPassword")
        require(!password.isNullOrEmpty()) { "Provide workstationPassword as an instrumentation argument" }
        val context = instrumentation.targetContext
        if ((context.applicationContext as SonoraApp).connection != null) {
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("断开连接并清除保存的密码").performScrollTo().performClick()
        }
        compose.onNode(hasSetTextAction() and hasText("服务器地址")).performTextReplacement(testServerAddress())
        val passwordField = hasSetTextAction() and hasText("访问密码")
        compose.onNode(passwordField).performTextReplacement("invalid-test-password")
        compose.onNodeWithText("连接音乐库").performScrollTo().performClick()
        compose.waitUntil(60_000) {
            compose.onAllNodesWithText("访问密码错误或已失效，请重新连接").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(passwordField).performScrollTo().performTextReplacement(password)
        compose.onNodeWithText("连接音乐库").performScrollTo().performClick()
        compose.waitUntil(60_000) { compose.onAllNodesWithText("我的音乐库").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("播放当前列表").assertIsDisplayed()

        val saved = CredentialStore(context).load()
        assertTrue("Keystore must restore the connection", saved != null && saved.password == password)
        val stored = context.getSharedPreferences("workstation_connection", 0).all.values.joinToString()
        assertTrue("Preferences must not contain the plain password", !stored.contains(password))
        val library = WorkstationRepository(context).cached(saved!!)
        assertTrue("Live server library must be available", library != null && library.tracks.isNotEmpty())

        val first = library!!.tracks.first()
        val app = context.applicationContext as SonoraApp
        val cover = runBlocking {
            app.imageLoader.execute(ImageRequest.Builder(context).data(first.coverUrl(saved.baseUrl)).build())
        }
        assertTrue("Server cover must load through the authenticated HTTPS proxy", cover is SuccessResult)
        val search = hasSetTextAction()
        val query = first.bvid?.takeIf { it.isNotBlank() } ?: first.displayTitle
        compose.onNode(search).performTextReplacement(query)
        compose.onNodeWithText(first.displayTitle).assertIsDisplayed()
        compose.onNode(search).performTextClearance()
        compose.activity.runOnUiThread {
            val input = context.getSystemService(InputMethodManager::class.java)
            input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(context.getExternalFilesDir(null), "library-check.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("断开连接并清除保存的密码").performScrollTo().performClick()
        compose.onNodeWithText("连接音乐库").assertExists()
        assertTrue("Disconnect must remove saved credentials", CredentialStore(context).load() == null)
    }
}
