package com.sikamikaniko.sonora.data

import com.google.gson.JsonParser
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalBilibiliTest {
    private val bvid = "BV1GJ411x7h7"
    private val view = """{"code":0,"data":{"bvid":"$bvid","aid":123,"title":"Song","pic":"https://i0.hdslb.com/cover.jpg","owner":{"name":"Artist"},"pages":[{"page":1,"cid":111,"part":"First"},{"page":2,"cid":222,"part":"Second"}]}}"""
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun client(server: MockWebServer) = LocalBilibiliClient(apiRoot = server.url("/"), audioRoot = server.url("/"))

    @Test fun importUnderstandsSharesPartsAndPublicFavorites() {
        assertEquals(BiliInput.Video(bvid), BiliInputs.parse("【歌曲分享】 $bvid"))
        assertEquals(BiliInput.Video(bvid, page = 2), BiliInputs.parse("https://www.bilibili.com/video/$bvid?p=2"))
        assertEquals(BiliInput.Video(aid = "123"), BiliInputs.parse("av123"))
        assertEquals(BiliInput.Audio("456"), BiliInputs.parse("https://www.bilibili.com/audio/au456"))
        assertEquals(BiliInput.Favorite("789"), BiliInputs.parse("https://space.bilibili.com/1/favlist?fid=789"))
        assertEquals(BiliInput.Favorite("789"), BiliInputs.parse("ml789"))
        assertThrows(IllegalArgumentException::class.java) { BiliInputs.parse("https://example.com/$bvid") }
        assertThrows(IllegalArgumentException::class.java) { BiliInputs.parse("not-a-song") }
    }

    @Test fun cacheIdentitySurvivesRenameAndSourceRetainsSelectedPart() {
        val track = WorkstationTrack(id = "part2", kind = "bilibili", bvid = bvid, cid = "222", title = "Old")
        val source = LocalMusicSources.fromUri(LocalMusicSources.uri(track))
        assertEquals("222", source.cid)
        assertEquals(bvid, source.bvid)
        assertEquals(LocalMusicSources.key(track), LocalMusicSources.key(track.copy(title = "New", cover = "https://i0.hdslb.com/new.jpg")))
        assertFalse(LocalMusicSources.playable(WorkstationTrack(kind = "local", audioId = "server-file")))
        assertEquals("https://i0.hdslb.com/a.jpg", LocalMusicSources.artwork(track.copy(cover = "http://i0.hdslb.com/a.jpg")))
        assertNull(LocalMusicSources.artwork(track.copy(cover = "https://example.com/a.jpg")))
        assertThrows(IllegalArgumentException::class.java) { LocalMusicSources.fromUri("https://audio/track.m4a?bvid=$bvid") }
    }

    @Test fun prefersCompatibleIndependentAudioAndNeverFallsBackToVideo() {
        val data = JsonParser.parseString("""{"dash":{"audio":[{"bandwidth":999999,"mimeType":"audio/webm","baseUrl":"https://cdn.example/opus"},{"bandwidth":192000,"mimeType":"audio/mp4","baseUrl":"https://cdn.example/aac"},{"bandwidth":64000,"mimeType":"audio/mp4","baseUrl":"https://cdn.example/low"}],"video":[{"baseUrl":"https://cdn.example/video"}]}}""").asJsonObject
        assertEquals("https://cdn.example/aac", LocalBilibiliClient.pickAudio(data))
        val videoOnly = JsonParser.parseString("""{"dash":{"video":[{"baseUrl":"https://cdn.example/video"}]}}""").asJsonObject
        assertThrows(BilibiliException::class.java) { LocalBilibiliClient.pickAudio(videoOnly) }
    }

    @Test fun rejectsInsecureMediaButCanSelectSecureBackup() {
        val data = JsonParser.parseString("""{"dash":{"audio":[{"baseUrl":"http://cdn.example/audio","backupUrl":["https://cdn.example/backup"]}]}}""").asJsonObject
        assertEquals("https://cdn.example/backup", LocalBilibiliClient.pickAudio(data))
    }

    @Test fun previewCannotBeMarkedAsAFullSongDownload() {
        val data = JsonParser.parseString("""{"is_preview":1,"dash":{"audio":[{"baseUrl":"https://cdn.example/preview"}]}}""").asJsonObject
        assertTrue(assertThrows(BilibiliException::class.java) { LocalBilibiliClient.pickAudio(data) }.message!!.contains("试听"))
    }

    @Test fun importKeepsMetadataAndRequestedPartWithoutDownloadingAudio() {
        MockWebServer().use { server ->
            server.enqueue(response(view))
            val track = client(server).tracks("https://www.bilibili.com/video/$bvid?p=2").single()
            assertEquals("Song - Second", track.title)
            assertEquals("Artist", track.artist)
            assertEquals("222", track.cid)
            assertEquals("bili:$bvid:p2", track.id)
            val request = server.takeRequest()
            assertEquals(bvid, request.requestUrl!!.queryParameter("bvid"))
            assertEquals("https://www.bilibili.com/", request.getHeader("Referer"))
            assertNull(request.getHeader("Authorization"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun opensFreshCdnUrlEachTimeAndPreservesPart() {
        MockWebServer().use { server ->
            for (suffix in listOf("first", "renewed")) {
                server.enqueue(response(view))
                server.enqueue(response("""{"code":0,"data":{"dash":{"audio":[{"baseUrl":"https://cdn.example/$suffix","mimeType":"audio/mp4"}]}}}"""))
            }
            val client = client(server)
            val track = WorkstationTrack(kind = "bilibili", bvid = bvid, cid = "222")
            assertEquals("https://cdn.example/first", client.resolve(track))
            assertEquals("https://cdn.example/renewed", client.resolve(track))
            server.takeRequest()
            assertEquals("222", server.takeRequest().requestUrl!!.queryParameter("cid"))
        }
    }

    @Test fun publicFavoriteIsPagedDeduplicatedAndCarriesGroupNames() {
        MockWebServer().use { server ->
            val item = """{"type":2,"bvid":"$bvid","id":123,"title":"Favorite song","cover":"https://i0.hdslb.com/cover.jpg","upper":{"name":"Singer"}}"""
            server.enqueue(response("""{"code":0,"data":{"info":{"title":"My favorites"},"medias":[$item],"has_more":true}}"""))
            server.enqueue(response("""{"code":0,"data":{"info":{"title":"My favorites"},"medias":[$item,{"type":12,"id":456,"title":"Audio"}],"has_more":false}}"""))
            val tracks = client(server).tracks("ml789")
            assertEquals(2, tracks.size)
            assertEquals("My favorites", tracks.first().group)
            assertEquals("Singer", tracks.first().artist)
            assertEquals("456", tracks.last().sid)
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("pn"))
            assertEquals("2", server.takeRequest().requestUrl!!.queryParameter("pn"))
        }
    }

    @Test fun requestRestrictionRemainsAnActionableError() {
        MockWebServer().use { server ->
            server.enqueue(response("""{"code":-412,"message":"blocked"}"""))
            val error = assertThrows(BilibiliException::class.java) { client(server).tracks(bvid) }
            assertTrue(error.message!!.contains("稍后"))
        }
    }

    @Test fun invalidJsonIsNotTreatedAsAnEmptyLibrary() {
        MockWebServer().use { server ->
            server.enqueue(response("<html>blocked</html>"))
            assertThrows(BilibiliException::class.java) { client(server).tracks(bvid) }
        }
    }

    @Test fun selectedPartCannotSilentlyPlayAnotherPart() {
        MockWebServer().use { server ->
            server.enqueue(response(view))
            assertThrows(BilibiliException::class.java) { client(server).resolve(WorkstationTrack(kind = "bilibili", bvid = bvid, cid = "999")) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun localQueueRestorationExcludesDeletedAndServerOnlyTracks() {
        val one = WorkstationTrack(id = "one", kind = "bilibili", bvid = bvid)
        val two = one.copy(id = "two", cid = "222")
        val saved = WorkstationPlaybackSnapshot(listOf(one, two, WorkstationTrack(id = "server", kind = "local", audioId = "x")), index = 1, positionMs = 1234)
        val kept = saved.playable(null, setOf("two"))!!
        assertEquals(listOf(two), kept.tracks)
        assertEquals(1234L, kept.positionMs)
        assertEquals(0L, saved.copy(index = 0).playable(null, setOf("two"))!!.positionMs)
        assertNull(saved.playable(null, emptySet()))
    }

    @Test fun livePublicAudioSmoke() {
        val source = System.getenv("MKUSIC_BILI_SMOKE_BVID")
        assumeTrue("Live smoke is opt-in; ordinary tests never contact Bilibili", !source.isNullOrBlank())
        val client = LocalBilibiliClient()
        val track = client.tracks(source!!).first()
        val url = client.resolve(track)
        val request = Request.Builder().url(url).header("Range", "bytes=0-16383")
            .apply { LocalBilibiliClient.headers.forEach { (k, v) -> header(k, v) } }.build()
        LocalBilibiliClient.httpClient().newCall(request).execute().use { response ->
            assertTrue("Audio HTTP ${response.code}", response.isSuccessful)
            assertTrue(response.body!!.byteStream().read(ByteArray(1024)) > 0)
            assertFalse(response.header("Content-Type").orEmpty().contains("json"))
        }
    }
}
