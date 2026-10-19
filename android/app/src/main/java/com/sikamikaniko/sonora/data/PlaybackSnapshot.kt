package com.sikamikaniko.sonora.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.google.gson.Gson
import com.sikamikaniko.sonora.playback.Streaming

/** A minimal, restorable description of one queued item. */
data class SavedTrack(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val artUri: String? = null
)

/** Snapshot of the whole playback state, persisted so the app can resume exactly. */
data class PlaybackSnapshot(
    val tracks: List<SavedTrack> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
    val wasPlaying: Boolean = false
)

/**
 * Rebuild the queue. Shared by the in-app resume and the Bluetooth auto-start, which
 * runs with no ViewModel and no UI — so this deliberately depends on nothing but prefs.
 */
fun PlaybackSnapshot.toMediaItems(): List<MediaItem> = tracks.map { t ->
    MediaItem.Builder()
        .setMediaId(t.mediaId)
        .setUri(t.uri)
        // The persisted URL carries the salt/token it was signed with, so it can never
        // match a freshly built one — key the cache on the song instead, or a resumed
        // queue re-downloads everything it already has on disk.
        .apply {
            if (t.uri.startsWith("http")) setCustomCacheKey(Streaming.cacheKey(t.uri))
        }
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artist)
                .apply { t.artUri?.let { setArtworkUri(Uri.parse(it)) } }
                .build()
        )
        .build()
}

/** The last persisted queue, or null if there is nothing to resume. */
fun Prefs.loadPlaybackSnapshot(gson: Gson = Gson()): PlaybackSnapshot? {
    val json = lastPlaybackJson ?: return null
    val snap = try { gson.fromJson(json, PlaybackSnapshot::class.java) } catch (_: Exception) { null }
    return snap?.takeIf { it.tracks.isNotEmpty() }
}
