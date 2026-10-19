package com.sikamikaniko.sonora.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** The personal library is separate from server snapshots and survives cache clearing. */
class LocalMusicRepository(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "mkusic-library.json"))
    private val gson = Gson()
    companion object { private val lock = Any() }

    fun tracks(): List<WorkstationTrack> = synchronized(lock) {
        if (!file.baseFile.exists()) return@synchronized emptyList()
        file.openRead().bufferedReader().use { reader ->
            val type = object : TypeToken<List<WorkstationTrack>>() {}.type
            val tracks: List<WorkstationTrack>? = gson.fromJson(reader, type)
            tracks.orEmpty().filter { it.id.isNotBlank() && LocalMusicSources.playable(it) }.distinctBy { it.id }
        }
    }
    fun add(incoming: List<WorkstationTrack>): Int = synchronized(lock) {
        val existing = tracks()
        val ids = existing.map { it.id }.toSet()
        val additions = incoming.filter { it.id !in ids && LocalMusicSources.playable(it) }.distinctBy { it.id }
        save(existing + additions)
        additions.size
    }
    fun remove(id: String) = synchronized(lock) { save(tracks().filterNot { it.id == id }) }
    fun edit(id: String, title: String, group: String) = synchronized(lock) {
        require(title.isNotBlank()) { "歌名不能为空" }
        save(tracks().map { if (it.id == id) it.copy(title = title.trim(), group = group.trim()) else it })
    }
    private fun save(tracks: List<WorkstationTrack>) {
        val output = file.startWrite()
        try { output.write(gson.toJson(tracks).toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }
}
