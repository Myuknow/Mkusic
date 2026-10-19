package com.sikamikaniko.sonora

import androidx.test.platform.app.InstrumentationRegistry
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.sikamikaniko.sonora.data.ServerConnection
import com.sikamikaniko.sonora.data.WorkstationHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises Android TLS and Coil without bringing an Activity to the foreground. */
class WorkstationCoverDeviceTest {
    @Test fun legacyCoverLoadsThroughServerHttpsProxy() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val password = InstrumentationRegistry.getArguments().getString("workstationPassword")
        require(!password.isNullOrEmpty()) { "Provide workstationPassword as an instrumentation argument" }
        val context = instrumentation.targetContext
        val app = context.applicationContext as SonoraApp
        val connection = ServerConnection.create(testServerAddress(), password)
        app.connection = connection
        try {
            runBlocking {
                val track = WorkstationHttp.api(connection).playlist().first { !it.cover.isNullOrBlank() }
                val cover = track.coverUrl(connection.baseUrl)
                assertTrue("Device must request the cover over server HTTPS", cover?.startsWith(connection.baseUrl.toString()) == true)
                val result = app.imageLoader.execute(ImageRequest.Builder(context).data(cover).build())
                assertTrue("Coil must decode the authenticated server cover", result is SuccessResult)
            }
        } finally {
            app.connection = null
        }
    }
}
