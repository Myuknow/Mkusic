package com.sikamikaniko.sonora.data

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Keyless Deezer lookup — the most reliable free source of proper artist photos
 * (Wikipedia's lead images are license-filtered and often missing; Deezer serves
 * a 1000×1000 press shot for practically every act).
 */
object Deezer {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    private const val UA = "Sonora/1.12 (https://github.com/SikamikanikoBG/Sonora)"

    data class Artist(val name: String?, val pictureUrl: String?, val fans: Int?)

    /** Best-matching artist (first search hit), or null. */
    suspend fun artist(name: String): Artist? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        try {
            val body = request("https://api.deezer.com/search/artist?q=${enc(name)}")
                ?: return@withContext null
            val first = JsonParser.parseString(body).asJsonObject
                .getAsJsonArray("data")?.firstOrNull()?.asJsonObject ?: return@withContext null
            Artist(
                name = first.get("name")?.asString,
                pictureUrl = first.get("picture_xl")?.asString?.takeIf { it.isNotBlank() }
                    ?: first.get("picture_big")?.asString?.takeIf { it.isNotBlank() },
                fans = first.get("nb_fan")?.asInt
            )
        } catch (_: Exception) { null }
    }

    private fun request(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
