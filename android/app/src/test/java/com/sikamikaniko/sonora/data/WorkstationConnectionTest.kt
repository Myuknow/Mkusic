package com.sikamikaniko.sonora.data

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

class WorkstationConnectionTest {
    @Test fun connectionSeparatesCredentialsAndRequiresHttpsForPublicServers() {
        val connection = ServerConnection.create(" https://example.com/music ", "test-password")
        assertEquals("https://example.com/music/", connection.baseUrl.toString())
        assertFalse(connection.toString().contains("test-password"))
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/?password=x", "https://example.com/#x")
            .forEach { address -> assertThrows(IllegalArgumentException::class.java) { ServerConnection.create(address, "test") } }
        assertThrows(IllegalArgumentException::class.java) { ServerConnection.create("https://example.com", " ") }
    }

    @Test fun connectionSupportsPrivateHttpAddressesOnly() {
        listOf("192.168.1.100", "10.0.2.2", "172.16.0.1", "172.31.255.254", "127.0.0.1", "localhost", "[::1]")
            .forEach { host ->
                val address = "http://$host:4066/"
                assertEquals(address, ServerConnection.create(address, "test").baseUrl.toString())
            }
        listOf("203.0.113.10", "172.15.255.255", "172.32.0.1", "192.169.0.1", "192.168.1.100.example.com", "0.0.0.0")
            .forEach { host -> assertThrows(IllegalArgumentException::class.java) {
                ServerConnection.create("http://$host:4066/", "test")
            } }
    }

    @Test fun localHttpLoadsPlaylistAudioAndCoversWithAuthentication() {
        val server = MockWebServer()
        server.start()
        try {
            val connection = ServerConnection.create(server.url("/").toString(), "local-test-password")
            server.enqueue(MockResponse().setBody("""[{"id":"1","kind":"local","audioId":"song-1","title":"局域网歌曲"}]"""))
            val track = runBlocking { WorkstationHttp.api(connection).playlist().single() }
            assertEquals("局域网歌曲", track.displayTitle)
            val client = WorkstationHttp.client { connection }
            val cover = WorkstationTrack(cover = "https://i0.hdslb.com/test.jpg").coverUrl(connection.baseUrl)!!.toHttpUrl()
            assertEquals("http", cover.scheme)
            assertEquals("/api/bilibili/cover", cover.encodedPath)
            assertEquals(server.url("/api/cover.jpg").toString(), WorkstationTrack(cover = "/api/cover.jpg").coverUrl(connection.baseUrl))
            assertNull(WorkstationTrack(cover = "http://192.168.0.2/cover.jpg").coverUrl(connection.baseUrl))
            listOf(track.streamUrl(connection.baseUrl)!!, cover).forEach { url ->
                server.enqueue(MockResponse().setBody("media-data"))
                client.newCall(Request.Builder().url(url).build()).execute().use {
                    assertEquals("media-data", it.body!!.string())
                }
            }
            listOf("/api/playlist", "/api/audio/song-1/open", "/api/bilibili/cover").forEach { path ->
                val request = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertTrue(request.path!!.startsWith(path))
                assertEquals(Credentials.basic("", "local-test-password"), request.getHeader("Authorization"))
            }
        } finally { server.shutdown() }
    }

    @Test fun localHttpRedirectCannotSendRequestsToAnotherOrigin() {
        val first = MockWebServer(); val second = MockWebServer()
        first.start(); second.start()
        try {
            val connection = ServerConnection.create(first.url("/").toString(), "test-password")
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/cover")))
            val error = assertThrows(java.io.IOException::class.java) {
                WorkstationHttp.client { connection }.newCall(Request.Builder().url(first.url("/cover")).build()).execute().close()
            }
            assertTrue(error.message!!.contains("HTTP 仅用于"))
            assertEquals(Credentials.basic("", "test-password"), first.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertNull(second.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { first.shutdown(); second.shutdown() }
    }

    @Test fun mixedServerFieldsCanBeReadWithoutLosingBilibiliIds() {
        val track = Gson().fromJson("""{"id":"song-1","kind":"bilibili","cid":1234567890,"bvid":"BVTest","title":"歌曲","group":" 古典 "}""", WorkstationTrack::class.java)
        assertEquals("1234567890", track.cid)
        assertEquals("古典", track.groupName)
        assertTrue(track.matches("bvtest"))
        assertTrue(track.matches("古典"))
        assertFalse(track.matches("电子"))
        assertEquals("服务器音乐", WorkstationTrack(kind = "local").groupName)
        assertEquals("音频.flac", WorkstationTrack(filename = "音频.flac").displayTitle)
    }

    @Test fun bilibiliCoversUseAuthenticatedServerProxy() {
        val base = ServerConnection.create("https://example.com/music/", "test").baseUrl
        val url = "https://i0.hdslb.com/bfs/archive/cover.jpg?a=1&b=2"
        val proxy = WorkstationTrack(cover = url).coverUrl(base)!!.toHttpUrl()
        assertEquals("/music/api/bilibili/cover", proxy.encodedPath)
        assertEquals(url, proxy.queryParameter("url"))
        val legacyCover = WorkstationTrack(cover = "http://i2.hdslb.com/cover.jpg").coverUrl(base)!!.toHttpUrl()
        assertEquals("https://i2.hdslb.com/cover.jpg", legacyCover.queryParameter("url"))
        assertEquals("https://other.example/cover.jpg", WorkstationTrack(cover = "https://other.example/cover.jpg").coverUrl(base))
        assertNull(WorkstationTrack(cover = "http://other.example/cover.jpg").coverUrl(base))
    }

    private fun withServers(block: (MockWebServer, MockWebServer, HandshakeCertificates) -> Unit) {
        val held = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        val first = MockWebServer(); val second = MockWebServer()
        first.useHttps(serverTls.sslSocketFactory(), false); second.useHttps(serverTls.sslSocketFactory(), false)
        first.start(); second.start()
        try { block(first, second, clientTls) } finally { first.shutdown(); second.shutdown() }
    }

    @Test fun redirectToAnotherOriginDoesNotForwardPassword() = withServers { first, second, tls ->
        val connection = ServerConnection.create(first.url("/").toString(), "test-password")
        val client = WorkstationHttp.client { connection }.newBuilder()
            .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager!!).build()
        first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/cover")))
        second.enqueue(MockResponse().setBody("image"))
        client.newCall(Request.Builder().url(first.url("/cover")).build()).execute().use { assertEquals(200, it.code) }
        assertEquals(Credentials.basic("", "test-password"), first.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertNull(second.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        assertFalse(WorkstationAuth.sameOrigin(first.url("/"), second.url("/")))
    }

    @Test fun playlistContractAndAuthenticationErrors() = withServers { first, _, tls ->
        val connection = ServerConnection.create(first.url("/").toString(), "test-password")
        val client = WorkstationHttp.client { connection }.newBuilder()
            .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager!!).build()
        val api = Retrofit.Builder().baseUrl(connection.baseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build().create(WorkstationApi::class.java)
        first.enqueue(MockResponse().setBody("""[{"id":"1","kind":"bilibili","title":"测试音乐","cid":123}]"""))
        runBlocking { assertEquals("123", api.playlist().single().cid) }
        assertEquals("/api/playlist", first.takeRequest(5, TimeUnit.SECONDS)!!.path)
        first.enqueue(MockResponse().setResponseCode(401))
        val error = assertThrows(HttpException::class.java) { runBlocking { api.playlist() } }
        assertTrue(WorkstationErrors.message(error).contains("密码"))
    }

    @Test fun changingConnectionRevokesAuthorizationForOldOrigin() = withServers { first, second, tls ->
        var current: ServerConnection? = ServerConnection.create(first.url("/").toString(), "test-password")
        val client = WorkstationHttp.client { current }.newBuilder()
            .sslSocketFactory(tls.sslSocketFactory(), tls.trustManager!!).build()
        current = ServerConnection.create(second.url("/").toString(), "new-test-password")
        first.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(first.url("/cover")).header("Authorization", "stale").build()).execute().close()
        assertNull(first.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        current = null
        second.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(second.url("/cover")).build()).execute().close()
        assertNull(second.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
    }

    @Test fun certificateAndTimeoutFailuresHaveDifferentMessages() {
        assertTrue(WorkstationErrors.message(SSLHandshakeException("invalid certificate")).contains("证书"))
        assertTrue(WorkstationErrors.message(SocketTimeoutException()).contains("超时"))
    }
}
