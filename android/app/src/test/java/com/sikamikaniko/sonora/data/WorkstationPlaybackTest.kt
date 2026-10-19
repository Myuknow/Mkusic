package com.sikamikaniko.sonora.data

import org.junit.Assert.*
import org.junit.Test

class WorkstationPlaybackTest {
    private val connection = ServerConnection.create("https://example.com/music/", "test-only-password")
    private val base = connection.baseUrl
    private val video = WorkstationTrack(id = "video", kind = "bilibili", bvid = "BVExample", cid = "987654321")

    @Test fun videoAndMultiPartAlwaysUseStableServerProxy() {
        val source = video.streamUrl(base)!!
        assertEquals("/music/api/bilibili/audio", source.encodedPath)
        assertEquals("BVExample", source.queryParameter("bvid"))
        assertEquals("987654321", source.queryParameter("cid"))
        assertEquals("123", video.copy(bvid = null, aid = "123").streamUrl(base)!!.queryParameter("aid"))
        assertEquals("456", WorkstationTrack(kind = "biliaudio", sid = "456").streamUrl(base)!!.queryParameter("sid"))
        assertNull(video.copy(bvid = null, cid = null).streamUrl(base))
    }

    @Test fun serverFilesAndExternalAudioRemainDistinct() {
        assertEquals("/music/api/audio/file%2Fname/open", WorkstationTrack(kind = "local", audioId = "file/name").streamUrl(base)!!.encodedPath)
        assertEquals("/music/api/audio/old-id/open", WorkstationTrack(kind = "local", blobId = "old-id").streamUrl(base)!!.encodedPath)
        assertEquals("https://cdn.example/audio.mp3", WorkstationTrack(kind = "url", url = "https://cdn.example/audio.mp3").streamUrl(base).toString())
        assertNull(WorkstationTrack(kind = "url", url = "http://cdn.example/audio.mp3").streamUrl(base))
        assertNull(WorkstationTrack(kind = "url", url = "https://user:pass@cdn.example/audio.mp3").streamUrl(base))
        assertNull(WorkstationTrack(kind = "local", url = "https://cdn.example/audio.mp3").streamUrl(base))
        assertNull(WorkstationTrack(kind = "local", filename = "only-in-browser.mp3").streamUrl(base))
    }

    @Test fun cacheSeparatesServerAndBilibiliPartButNotConnectionPassword() {
        val key = video.cacheKey(base)
        assertFalse(key.contains(connection.password))
        assertNotEquals(key, video.copy(cid = "other-part").cacheKey(base))
        assertNotEquals(key, video.cacheKey(ServerConnection.create("https://other.example/music/", "test").baseUrl))
        assertEquals(key, video.cacheKey(ServerConnection.create(base.toString(), "another-password").baseUrl))
    }

    @Test fun resumeKeepsSelectedTrackWhenUnsupportedItemsDisappear() {
        val missing = WorkstationTrack(id = "missing", kind = "local")
        val saved = WorkstationPlaybackSnapshot(listOf(missing, video), index = 1, positionMs = 42_000, repeatMode = 2, shuffle = true)
        val restored = saved.playable(connection)!!
        assertEquals(0, restored.index)
        assertEquals("video", restored.tracks.single().id)
        assertEquals(42_000L, restored.positionMs)
        assertTrue(restored.shuffle)
        assertEquals(2, restored.repeatMode)
        val unavailable = saved.copy(index = 0).playable(connection)!!
        assertEquals(0L, unavailable.positionMs)
        assertNull(WorkstationPlaybackSnapshot(listOf(missing)).playable(connection))
    }

    @Test fun malformedProgressCannotRestoreNegativePositionOrInvalidMode() {
        val restored = WorkstationPlaybackSnapshot(listOf(video), positionMs = -2, repeatMode = 7).playable(connection)!!
        assertEquals(0L, restored.positionMs)
        assertEquals(0, restored.repeatMode)
    }
}
