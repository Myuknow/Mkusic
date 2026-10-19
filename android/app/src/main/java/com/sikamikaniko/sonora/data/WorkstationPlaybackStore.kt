package com.sikamikaniko.sonora.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WorkstationPlaybackSnapshot(
    val tracks: List<WorkstationTrack>, val index: Int = 0, val positionMs: Long = 0,
    val repeatMode: Int = 0, val shuffle: Boolean = false
) {
    fun playable(connection: ServerConnection?, availableIds: Set<String>? = null): WorkstationPlaybackSnapshot? {
        val currentId = tracks.getOrNull(index)?.id
        val valid = tracks.filter { (availableIds == null || it.id in availableIds) &&
            if (connection == null) LocalMusicSources.playable(it) else it.streamUrl(connection.baseUrl) != null }
        if (valid.isEmpty()) return null
        val nextIndex = valid.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: 0
        return copy(tracks = valid, index = nextIndex,
            positionMs = if (valid[nextIndex].id == currentId) positionMs.coerceAtLeast(0) else 0,
            repeatMode = repeatMode.takeIf { it in 0..2 } ?: 0)
    }
}

/** Persist queue on changes, and small progress fields separately every five seconds. */
class WorkstationPlaybackStore(context: Context) {
    private val prefs = context.getSharedPreferences("workstation_playback", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveQueue(connection: ServerConnection?, tracks: List<WorkstationTrack>) {
        prefs.edit().putString("server", connection?.baseUrl?.toString() ?: "mkusic:standalone")
            .putString("queue", gson.toJson(tracks)).apply()
    }

    fun savePosition(index: Int, position: Long, repeat: Int, shuffle: Boolean) {
        prefs.edit().putInt("index", index).putLong("position", position.coerceAtLeast(0))
            .putInt("repeat", repeat).putBoolean("shuffle", shuffle).apply()
    }

    fun load(connection: ServerConnection?): WorkstationPlaybackSnapshot? = try {
        if (prefs.getString("server", null) != (connection?.baseUrl?.toString() ?: "mkusic:standalone")) null
        else prefs.getString("queue", null)?.let { json ->
            val type = object : TypeToken<List<WorkstationTrack>>() {}.type
            val tracks: List<WorkstationTrack>? = gson.fromJson(json, type)
            tracks?.let { WorkstationPlaybackSnapshot(it, prefs.getInt("index", 0),
                prefs.getLong("position", 0), prefs.getInt("repeat", 0), prefs.getBoolean("shuffle", false))
                .playable(connection) }
        }
    } catch (_: RuntimeException) { null }

    fun clear() { prefs.edit().clear().apply() }
}
