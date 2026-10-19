package com.sikamikaniko.sonora.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Client for the free, keyless Radio Browser API — live internet radio from around the world. */
object RadioBrowser {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private const val BASE = "https://de1.api.radio-browser.info"
    private const val UA = "Sonora/1.5 (https://github.com/SikamikanikoBG/Sonora)"

    data class Station(
        val stationuuid: String,
        val name: String? = null,
        val url_resolved: String? = null,
        val favicon: String? = null,
        val tags: String? = null,
        val country: String? = null,
        val countrycode: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        // Popularity signals from Radio Browser: community up-votes and total tune-ins.
        val votes: Int? = null,
        val clickcount: Int? = null
    )

    suspend fun top(limit: Int = 100): List<Station> =
        get("/json/stations/topvote/$limit")

    /**
     * Stations for a genre, most-popular first. Tries the exact tag, then falls back
     * to a fuzzy tag search so multi-word genres (e.g. "deep house") still return results.
     */
    suspend fun byTag(tag: String, limit: Int = 100): List<Station> {
        val exact = get("/json/stations/bytagexact/${enc(tag)}?order=votes&reverse=true&hidebroken=true&limit=$limit")
        if (exact.size >= 5) return exact
        val fuzzy = get("/json/stations/bytag/${enc(tag)}?order=votes&reverse=true&hidebroken=true&limit=$limit")
        return (exact + fuzzy).distinctBy { it.stationuuid }
    }

    suspend fun byCountry(code: String, limit: Int = 80): List<Station> =
        get("/json/stations/bycountrycodeexact/${enc(code)}?order=votes&reverse=true&hidebroken=true&limit=$limit")

    suspend fun search(name: String, limit: Int = 80): List<Station> =
        get("/json/stations/byname/${enc(name)}?order=votes&reverse=true&hidebroken=true&limit=$limit")

    /** A random, playable station (optionally within a genre tag). */
    suspend fun random(tag: String?): Station? {
        val pool = if (!tag.isNullOrBlank()) byTag(tag, 120) else top(120)
        return pool.filter { !it.url_resolved.isNullOrBlank() }.shuffled().firstOrNull()
    }

    private suspend fun get(path: String): List<Station> = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(BASE + path).header("User-Agent", UA).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext emptyList()
                val arr = gson.fromJson(r.body?.string(), Array<Station>::class.java) ?: return@withContext emptyList()
                arr.filter { !it.url_resolved.isNullOrBlank() }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
