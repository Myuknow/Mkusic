package com.sikamikaniko.sonora.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WorkstationRepository(context: Context) {
    private val cache = context.getSharedPreferences("workstation_library", Context.MODE_PRIVATE)
    private val gson = Gson()

    suspend fun fetch(connection: ServerConnection): WorkstationLibrary = withContext(Dispatchers.IO) {
        val tracks = WorkstationHttp.api(connection).playlist().filter { it.id.isNotBlank() }.distinctBy { it.id }
        WorkstationLibrary(connection.baseUrl.toString(), System.currentTimeMillis(), tracks)
    }

    fun cached(connection: ServerConnection): WorkstationLibrary? {
        return try {
            val json = cache.getString("snapshot", null) ?: return null
            val snapshot = gson.fromJson(json, CachedLibrary::class.java) ?: return null
            val tracks = snapshot.tracks ?: return null
            if (snapshot.server != connection.baseUrl.toString()) null
            else WorkstationLibrary(connection.baseUrl.toString(), snapshot.refreshedAt, tracks)
        } catch (_: RuntimeException) { null }
    }

    fun save(library: WorkstationLibrary) { cache.edit().putString("snapshot", gson.toJson(library)).apply() }
    fun clear() { cache.edit().clear().apply() }

    private data class CachedLibrary(val server: String?, val refreshedAt: Long, val tracks: List<WorkstationTrack>?)
}
