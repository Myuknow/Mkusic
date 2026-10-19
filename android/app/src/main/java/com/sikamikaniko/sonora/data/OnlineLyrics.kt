package com.sikamikaniko.sonora.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Fetches lyrics from lrclib.net (free, no auth) — both plain and time-synced.
 * Tries several strategies (most precise first) so odd tags / missing album /
 * slightly-off durations still resolve.
 */
object OnlineLyrics {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private const val UA = "Sonora/1.6 (https://github.com/SikamikanikoBG/Sonora)"

    data class Lrc(
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean? = null
    )

    /** Best matching record (plain and/or synced). */
    suspend fun best(artist: String?, title: String?, album: String?, durationSec: Int): Lrc? =
        withContext(Dispatchers.IO) {
            if (artist.isNullOrBlank() || title.isNullOrBlank()) return@withContext null
            val a = artist.replace(Regex("(?i)\\s+feat\\.?.*$"), "").trim()
            // Rips often decorate titles ("Song (2008 Remaster)", "01 - Song"); lrclib
            // indexes the bare name, so try the tag as-is first, then cleaned.
            val titles = listOf(title, cleanTitle(title)).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            for (t in titles) {
                getLrc(t, a, null, 0)?.let { if (usable(it)) return@withContext it }
                if (!album.isNullOrBlank() || durationSec > 0) {
                    getLrc(t, a, album, durationSec)?.let { if (usable(it)) return@withContext it }
                }
                searchLrc("track_name=${enc(t)}&artist_name=${enc(a)}", durationSec)?.let { return@withContext it }
            }
            searchLrc("q=${enc("${titles.last()} $a")}", durationSec)?.let { return@withContext it }
            null
        }

    /** Strip leading track numbers and trailing remaster/version decorations. */
    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("^\\s*\\d{1,3}\\s*[-.]\\s*"), "")
        .replace(Regex("(?i)\\s*[(\\[][^)\\]]*(remaster|remix|live|demo|edit|version|mono|stereo|deluxe)[^)\\]]*[)\\]]\\s*$"), "")
        .replace(Regex("(?i)\\s*-\\s*(\\d{4}\\s+)?remaster(ed)?.*$"), "")
        .trim()

    /** Plain lyrics only (convenience). */
    suspend fun fetch(artist: String?, title: String?, album: String?, durationSec: Int): String? =
        best(artist, title, album, durationSec)?.let { plainFrom(it) }

    /** Parses "[mm:ss.xx] text" synced lyrics into (millis -> line) pairs, sorted. */
    fun parseSynced(synced: String?): List<Pair<Long, String>> {
        if (synced.isNullOrBlank()) return emptyList()
        val rx = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<Pair<Long, String>>()
        for (line in synced.lineSequence()) {
            val matches = rx.findAll(line).toList()
            if (matches.isEmpty()) continue
            val text = line.replace(rx, "").trim()
            for (m in matches) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val ms = mm * 60000 + ss * 1000 + (if (frac.isNotEmpty()) frac.padEnd(3, '0').take(3).toLong() else 0)
                out.add(ms to text)
            }
        }
        return out.sortedBy { it.first }
    }

    private fun usable(lrc: Lrc): Boolean =
        lrc.instrumental != true && (!lrc.plainLyrics.isNullOrBlank() || !lrc.syncedLyrics.isNullOrBlank())

    private fun getLrc(title: String, artist: String, album: String?, durationSec: Int): Lrc? {
        val url = buildString {
            append("https://lrclib.net/api/get?track_name=").append(enc(title))
            append("&artist_name=").append(enc(artist))
            if (!album.isNullOrBlank()) append("&album_name=").append(enc(album))
            if (durationSec > 0) append("&duration=").append(durationSec)
        }
        val body = request(url) ?: return null
        return try { gson.fromJson(body, Lrc::class.java) } catch (_: Exception) { null }
    }

    private data class SearchHit(
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
        val instrumental: Boolean? = null,
        val duration: Double? = null
    )

    /** Search, preferring the hit whose duration is closest to ours (album cuts over live takes). */
    private fun searchLrc(queryParams: String, durationSec: Int): Lrc? {
        val body = request("https://lrclib.net/api/search?$queryParams") ?: return null
        return try {
            val hits = JsonParser.parseString(body).asJsonArray
                .map { gson.fromJson(it, SearchHit::class.java) }
                .filter { usable(Lrc(it.plainLyrics, it.syncedLyrics, it.instrumental)) }
            val pick = if (durationSec > 0)
                hits.minByOrNull { kotlin.math.abs((it.duration ?: 1e9) - durationSec) }
            else hits.firstOrNull()
            pick?.let { Lrc(it.plainLyrics, it.syncedLyrics, it.instrumental) }
        } catch (_: Exception) { null }
    }

    fun plainFrom(lrc: Lrc?): String? {
        if (lrc == null || lrc.instrumental == true) return null
        lrc.plainLyrics?.takeIf { it.isNotBlank() }?.let { return it }
        lrc.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return stripTimestamps(it) }
        return null
    }

    private fun stripTimestamps(synced: String): String =
        synced.lineSequence()
            .map { it.replace(Regex("\\[\\d{1,2}:\\d{2}(?:[.:]\\d{1,3})?]"), "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    private fun request(url: String): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    } catch (_: Exception) { null }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
