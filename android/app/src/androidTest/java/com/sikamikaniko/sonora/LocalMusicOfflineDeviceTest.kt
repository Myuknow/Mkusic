package com.sikamikaniko.sonora

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.sikamikaniko.sonora.data.LocalMusicSources
import com.sikamikaniko.sonora.data.WorkstationTrack
import com.sikamikaniko.sonora.playback.LocalMusicDownloadService
import com.sikamikaniko.sonora.playback.LocalMusicDownloads
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** No Bilibili or personal server needed: the upstream URI is deliberately unresolvable. */
class LocalMusicOfflineDeviceTest {
    @Test fun completeDownloadPlaysWithoutResolverAndDeletionRemovesAllAudioCopies() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val track = WorkstationTrack(id = "offline-test-${UUID.randomUUID()}", kind = "bilibili", bvid = "BV1GJ411x7h7")
        val key = LocalMusicSources.key(track)
        val bytes = ByteArray(96 * 1024) { (it % 251).toByte() }
        val source = File(context.cacheDir, "${track.id}.bin").apply { writeBytes(bytes) }
        ActivityScenario.launch(MainActivity::class.java).use {
            try {
                val request = DownloadRequest.Builder(key, Uri.fromFile(source)).setCustomCacheKey(key).build()
                DownloadService.sendAddDownload(context, LocalMusicDownloadService::class.java, request, false)
                DownloadService.sendResumeDownloads(context, LocalMusicDownloadService::class.java, false)
                val deadline = System.currentTimeMillis() + 30_000
                while (LocalMusicDownloads.statuses(context)[track.id] != "已完整缓存" && System.currentTimeMillis() < deadline) Thread.sleep(100)
                assertEquals("已完整缓存", LocalMusicDownloads.statuses(context)[track.id])
                source.delete()
                // Missing all IDs: resolving this URI would throw, even if the device is online.
                val spec = DataSpec.Builder().setUri("mkusic://audio/track.m4a").setKey(key).build()
                val dataSource = LocalMusicDownloads.playbackFactory(context).createDataSource()
                val result = java.io.ByteArrayOutputStream()
                try {
                    dataSource.open(spec)
                    val buffer = ByteArray(8192)
                    while (true) { val count = dataSource.read(buffer, 0, buffer.size); if (count < 0) break; result.write(buffer, 0, count) }
                } finally { dataSource.close() }
                assertArrayEquals(bytes, result.toByteArray())
                LocalMusicDownloads.remove(context, track)
                val removeDeadline = System.currentTimeMillis() + 10_000
                while (LocalMusicDownloads.statuses(context).containsKey(track.id) && System.currentTimeMillis() < removeDeadline) Thread.sleep(100)
                assertFalse(LocalMusicDownloads.statuses(context).containsKey(track.id))
                val removed = LocalMusicDownloads.playbackFactory(context).createDataSource()
                try { assertThrows(Exception::class.java) { removed.open(spec) } } finally { removed.close() }
            } finally {
                LocalMusicDownloads.remove(context, track)
                source.delete()
            }
        }
    }
}
