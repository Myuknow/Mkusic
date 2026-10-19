package com.sikamikaniko.sonora.playback

import android.app.Notification
import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.sikamikaniko.sonora.R
import com.sikamikaniko.sonora.data.LocalBilibiliClient
import com.sikamikaniko.sonora.data.LocalMusicSources
import com.sikamikaniko.sonora.data.WorkstationTrack
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

object LocalMusicDownloads {
    private const val CHANNEL = "mkusic-downloads"
    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    private var manager: DownloadManager? = null
    private val resolver = LocalBilibiliClient()

    @Synchronized private fun downloadCache(context: Context): SimpleCache {
        val provider = database ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }
        return cache ?: SimpleCache(File(context.applicationContext.filesDir, "audio-downloads"), NoOpCacheEvictor(), provider).also { cache = it }
    }
    fun networkFactory(context: Context): DataSource.Factory {
        val http = OkHttpDataSource.Factory(LocalBilibiliClient.httpClient().newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).build())
        val upstream = DefaultDataSource.Factory(context.applicationContext, http)
        return ResolvingDataSource.Factory(upstream) { spec ->
            if (spec.uri.scheme == LocalMusicSources.SCHEME) {
                val url = resolver.resolve(LocalMusicSources.fromUri(spec.uri.toString()))
                spec.buildUpon().setUri(url).setHttpRequestHeaders(spec.httpRequestHeaders + LocalBilibiliClient.headers).build()
            } else spec
        }
    }
    fun streamingFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(PlayerCache.get(context)).setUpstreamDataSourceFactory(networkFactory(context))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or CacheDataSource.FLAG_BLOCK_ON_CACHE)
    fun playbackFactory(context: Context): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(downloadCache(context)).setCacheWriteDataSinkFactory(null)
        .setUpstreamDataSourceFactory(streamingFactory(context)).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Synchronized fun manager(context: Context): DownloadManager {
        val cached = downloadCache(context)
        return manager ?: DownloadManager(context.applicationContext, database!!, cached, streamingFactory(context), Executor { it.run() }).also { downloads ->
            downloads.maxParallelDownloads = 1
            downloads.addListener(object : DownloadManager.Listener {
                override fun onDownloadRemoved(manager: DownloadManager, download: Download) {
                    // The downloader has stopped before its streaming cache is removed.
                    PlayerCache.get(context).removeResource(download.request.customCacheKey ?: download.request.id)
                }
            })
            manager = downloads
        }
    }
    fun enqueue(context: Context, track: WorkstationTrack) {
        val key = LocalMusicSources.key(track)
        val request = DownloadRequest.Builder(key, Uri.parse(LocalMusicSources.uri(track)))
            .setCustomCacheKey(key).setMimeType(MimeTypes.AUDIO_MP4).build()
        DownloadService.sendAddDownload(context, LocalMusicDownloadService::class.java, request, false)
        resume(context)
    }
    fun resume(context: Context) = DownloadService.sendResumeDownloads(context, LocalMusicDownloadService::class.java, false)
    fun remove(context: Context, track: WorkstationTrack) {
        PlayerCache.get(context).removeResource(LocalMusicSources.key(track))
        DownloadService.sendRemoveDownload(context, LocalMusicDownloadService::class.java, LocalMusicSources.key(track), false)
    }
    fun clear(context: Context) {
        DownloadService.sendRemoveAllDownloads(context, LocalMusicDownloadService::class.java, false)
        PlayerCache.get(context).keys.filter { it.startsWith("mkusic-local:") }.forEach { PlayerCache.get(context).removeResource(it) }
    }
    fun statuses(context: Context): Map<String, String> {
        val result = mutableMapOf<String, String>()
        manager(context).downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) {
                val download = cursor.download
                val label = when (download.state) {
                    Download.STATE_COMPLETED -> "已完整缓存"
                    Download.STATE_DOWNLOADING -> "缓存中 ${download.percentDownloaded.coerceAtLeast(0f).toInt()}%"
                    Download.STATE_FAILED -> "缓存失败，点击重试"
                    Download.STATE_REMOVING, Download.STATE_RESTARTING -> "清理缓存中"
                    Download.STATE_STOPPED -> "缓存已暂停"
                    else -> "等待缓存"
                }
                result[download.request.id.removePrefix("mkusic-local:")] = label
            }
        }
        return result
    }
    fun sizeBytes(context: Context): Long = downloadCache(context).cacheSpace
    fun notificationHelper(context: Context) = DownloadNotificationHelper(context, CHANNEL)
}

class LocalMusicDownloadService : DownloadService(2301, DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    "mkusic-downloads", R.string.mkusic_downloads, 0) {
    override fun getDownloadManager(): DownloadManager = LocalMusicDownloads.manager(this)
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification =
        LocalMusicDownloads.notificationHelper(this).buildProgressNotification(this, android.R.drawable.stat_sys_download,
            null, "正在缓存音乐到手机", downloads, notMetRequirements)
}
