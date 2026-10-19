package com.sikamikaniko.sonora.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.gson.Gson
import com.sikamikaniko.sonora.data.ServerConnection
import com.sikamikaniko.sonora.data.WorkstationTrack
import com.sikamikaniko.sonora.data.LocalMusicSources

object WorkstationMedia {
    private const val TRACK = "workstation.track"
    private val gson = Gson()

    fun item(track: WorkstationTrack, connection: ServerConnection?): MediaItem {
        if (connection == null) return MediaItem.Builder().setMediaId(track.id).setUri(LocalMusicSources.uri(track))
            .setCustomCacheKey(LocalMusicSources.key(track))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.displayTitle).setArtist(track.artist)
                .setIsPlayable(true).setArtworkUri(LocalMusicSources.artwork(track)?.let(Uri::parse))
                .setExtras(Bundle().apply { putString(TRACK, gson.toJson(track)) }).build()).build()
        val url = requireNotNull(track.streamUrl(connection.baseUrl)) { "这首歌曲缺少可播放的服务器音源" }
        return MediaItem.Builder().setMediaId(track.id).setUri(url.toString())
            .setCustomCacheKey(track.cacheKey(connection.baseUrl))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(track.displayTitle)
                .setArtist(track.artist).setIsPlayable(true)
                .setArtworkUri(track.coverUrl(connection.baseUrl)?.let(Uri::parse))
                .setExtras(Bundle().apply { putString(TRACK, gson.toJson(track)) }).build()).build()
    }

    fun track(item: MediaItem?): WorkstationTrack? = try {
        item?.mediaMetadata?.extras?.getString(TRACK)?.let { gson.fromJson(it, WorkstationTrack::class.java) }
    } catch (_: RuntimeException) { null }
}
