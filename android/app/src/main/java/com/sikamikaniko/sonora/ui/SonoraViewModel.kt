package com.sikamikaniko.sonora.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sikamikaniko.sonora.BuildConfig
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sikamikaniko.sonora.data.AiClient
import com.sikamikaniko.sonora.data.AiMix
import com.sikamikaniko.sonora.data.Album
import com.sikamikaniko.sonora.data.AlbumWithSongs
import com.sikamikaniko.sonora.data.ArtPalette
import com.sikamikaniko.sonora.data.Artist
import com.sikamikaniko.sonora.data.ArtistWithAlbums
import com.sikamikaniko.sonora.data.Deezer
import com.sikamikaniko.sonora.data.Genre
import com.sikamikaniko.sonora.data.LocalMedia
import com.sikamikaniko.sonora.data.NetworkMonitor
import com.sikamikaniko.sonora.data.OnlineLyrics
import com.sikamikaniko.sonora.data.Playlist
import com.sikamikaniko.sonora.data.PlaylistWithSongs
import com.sikamikaniko.sonora.data.PlaybackSnapshot
import com.sikamikaniko.sonora.data.loadPlaybackSnapshot
import com.sikamikaniko.sonora.data.toMediaItems
import com.sikamikaniko.sonora.data.Prefs
import com.sikamikaniko.sonora.data.RadioBrowser
import com.sikamikaniko.sonora.data.SavedTrack
import com.sikamikaniko.sonora.data.SearchResult3
import com.sikamikaniko.sonora.data.Wikipedia
import com.sikamikaniko.sonora.data.Song
import com.sikamikaniko.sonora.data.Subsonic
import com.sikamikaniko.sonora.data.Updater
import com.sikamikaniko.sonora.playback.PlaybackService
import com.sikamikaniko.sonora.playback.PlayerCache
import com.sikamikaniko.sonora.playback.PlaybackHealth
import com.sikamikaniko.sonora.playback.StreamGuard
import com.sikamikaniko.sonora.playback.Streaming
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class QueueItem(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUri: String?,
    val isCurrent: Boolean
)

enum class SelMode { NONE, SONGS, ALBUMS }

class SonoraViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    // Declared FIRST: several StateFlows below (favourites, recent stations) deserialize
    // their persisted value at construction, so this must exist before them — otherwise
    // it's null during init and every restart silently loads empty (lost favourites).
    private val aiGson = Gson()

    val serverUrl: String? get() = prefs.baseUrl
    val username: String? get() = prefs.username

    // ---- Auth / session ----
    private val _loggedIn = MutableStateFlow(prefs.isConfigured)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    fun consumeError() { _error.value = null }

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    // ---- Connectivity ----
    private val _online = MutableStateFlow(true)
    /** False while the device has no validated internet — the UI shows an offline banner. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    // ---- Library ----
    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<List<Artist>>(emptyList())
    val artists: StateFlow<List<Artist>> = _artists.asStateFlow()

    private val _genres = MutableStateFlow<List<Genre>>(emptyList())
    val genres: StateFlow<List<Genre>> = _genres.asStateFlow()

    private val _genreAlbums = MutableStateFlow<List<Album>>(emptyList())
    val genreAlbums: StateFlow<List<Album>> = _genreAlbums.asStateFlow()

    private val _albumSort = MutableStateFlow("alphabeticalByName")
    val albumSort: StateFlow<String> = _albumSort.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    // ---- Appearance / settings ----
    private val _dynamicColor = MutableStateFlow(prefs.dynamicColor)
    val dynamicColor: StateFlow<Boolean> = _dynamicColor.asStateFlow()

    private val _appTheme = MutableStateFlow(
        runCatching { AppTheme.valueOf(prefs.themeName) }.getOrDefault(AppTheme.MIDNIGHT)
    )
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()
    fun setTheme(t: AppTheme) { prefs.themeName = t.name; _appTheme.value = t }

    // Album-art auto theming
    private val _artTheme = MutableStateFlow(prefs.artTheme)
    val artTheme: StateFlow<Boolean> = _artTheme.asStateFlow()
    private val _artBrush = MutableStateFlow<Brush?>(null)
    val artBrush: StateFlow<Brush?> = _artBrush.asStateFlow()
    private val _albumTitle = MutableStateFlow<String?>(null)
    private var lastArtUrl: String? = null

    fun setArtTheme(v: Boolean) {
        prefs.artTheme = v
        _artTheme.value = v
        if (!v) _artBrush.value = null
        else extractArtColors(_artworkUri.value)
    }

    private fun extractArtColors(url: String?) {
        if (!_artTheme.value) { _artBrush.value = null; return }
        viewModelScope.launch {
            val pair = ArtPalette.colors(getApplication<Application>(), url)
            _artBrush.value = pair?.let {
                Brush.linearGradient(listOf(Color(it.first), Color(it.second)))
            }
        }
    }

    // ---- World radio ----
    private val _stations = MutableStateFlow<List<RadioBrowser.Station>>(emptyList())
    val stations: StateFlow<List<RadioBrowser.Station>> = _stations.asStateFlow()
    private val _radioLoading = MutableStateFlow(false)
    val radioLoading: StateFlow<Boolean> = _radioLoading.asStateFlow()
    private val _radioGenre = MutableStateFlow<String?>(null)
    val radioGenre: StateFlow<String?> = _radioGenre.asStateFlow()
    private val _isLive = MutableStateFlow(false)
    val isLive: StateFlow<Boolean> = _isLive.asStateFlow()
    private val _favStations = MutableStateFlow(loadFavStations())
    val favStations: StateFlow<List<RadioBrowser.Station>> = _favStations.asStateFlow()

    private fun loadFavStations(): List<RadioBrowser.Station> = try {
        aiGson.fromJson(prefs.favStationsJson, Array<RadioBrowser.Station>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }
    private fun persistFavStations() { prefs.favStationsJson = aiGson.toJson(_favStations.value) }

    // Recently played stations, for the Home "Recent radio" rail.
    private val _recentStations = MutableStateFlow(loadRecentStations())
    val recentStations: StateFlow<List<RadioBrowser.Station>> = _recentStations.asStateFlow()
    private fun loadRecentStations(): List<RadioBrowser.Station> = try {
        aiGson.fromJson(prefs.recentStationsJson, Array<RadioBrowser.Station>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }
    private fun addRecentStation(s: RadioBrowser.Station) {
        val list = (listOf(s) + _recentStations.value.filter { it.stationuuid != s.stationuuid }).take(12)
        _recentStations.value = list
        prefs.recentStationsJson = aiGson.toJson(list)
    }

    fun loadTopStations() = viewModelScope.launch {
        if (_radioLoading.value) return@launch
        _radioLoading.value = true; _radioGenre.value = null
        _stations.value = RadioBrowser.top(100)
        _radioLoading.value = false
    }
    fun loadGenre(tag: String) = viewModelScope.launch {
        if (_radioLoading.value) return@launch
        _radioLoading.value = true; _radioGenre.value = tag
        _stations.value = RadioBrowser.byTag(tag)
        if (_stations.value.isEmpty()) _error.value = "No stations found for that genre right now."
        _radioLoading.value = false
    }
    fun searchStations(q: String) = viewModelScope.launch {
        if (q.isBlank() || _radioLoading.value) return@launch
        _radioLoading.value = true; _radioGenre.value = null
        _stations.value = RadioBrowser.search(q)
        _radioLoading.value = false
    }
    fun surpriseStation() = viewModelScope.launch {
        if (_radioLoading.value) return@launch
        _radioLoading.value = true
        val s = RadioBrowser.random(_radioGenre.value)
        _radioLoading.value = false
        if (s != null) playStation(s) else _error.value = "Couldn't reach radio — check your connection."
    }
    fun playStation(s: RadioBrowser.Station) {
        val c = controller ?: return
        val url = s.url_resolved ?: return
        val meta = MediaMetadata.Builder()
            .setTitle(s.name ?: "Live radio")
            .setArtist(s.country ?: "Live radio")
            .apply { s.favicon?.takeIf { it.isNotBlank() }?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val item = MediaItem.Builder()
            .setMediaId("radio:${s.stationuuid}")
            .setUri(url)
            .setMediaMetadata(meta)
            .build()
        c.setMediaItems(listOf(item)); c.prepare(); c.play()
        _currentStation.value = s
        addRecentStation(s)
    }

    // The station currently on air, so the player can favourite IT (not a bogus song star).
    private val _currentStation = MutableStateFlow<RadioBrowser.Station?>(null)
    val currentStation: StateFlow<RadioBrowser.Station?> = _currentStation.asStateFlow()
    fun favCurrentStation() { _currentStation.value?.let { toggleFavStation(it) } }

    fun isFavStation(uuid: String) = _favStations.value.any { it.stationuuid == uuid }
    fun toggleFavStation(s: RadioBrowser.Station) {
        val wasFav = isFavStation(s.stationuuid)
        _favStations.value = if (wasFav) _favStations.value.filter { it.stationuuid != s.stationuuid }
        else _favStations.value + s
        persistFavStations()
        _toast.value = if (wasFav) "Removed from favourites" else "Saved to favourites — find it in the Saved tab"
    }

    // ---- Insights target (educator works anywhere, not just now-playing) ----
    data class InsightTarget(val title: String?, val artist: String?, val album: String?)
    private val _insightTarget = MutableStateFlow<InsightTarget?>(null)
    val insightTarget: StateFlow<InsightTarget?> = _insightTarget.asStateFlow()
    fun openInsights(title: String?, artist: String?, album: String?) {
        _insightTarget.value = InsightTarget(title, artist, album)
        _aiText.value = ""
        _insightWiki.value = null
        _insightHero.value = null
        aiInsights(if (!title.isNullOrBlank()) "song" else if (!album.isNullOrBlank()) "album" else "artist")
    }
    fun openInsightsCurrent() = openInsights(_title.value, _artist.value, _albumTitle.value)
    fun closeInsights() {
        _insightTarget.value = null; stopAiStream(); _aiText.value = ""
        _insightWiki.value = null; _insightHero.value = null
    }

    // ---- Local device library ----
    private val _localSongs = MutableStateFlow<List<Song>>(emptyList())
    val localSongs: StateFlow<List<Song>> = _localSongs.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    fun scanDevice() = viewModelScope.launch {
        _scanning.value = true
        _localSongs.value = try { LocalMedia.scan(getApplication<Application>()) } catch (_: Exception) { emptyList() }
        _scanning.value = false
        reblend() // blend the freshly-scanned device albums into Home/Library/mix
    }

    // ---- Recent searches ----
    private val _recentSearches = MutableStateFlow(prefs.recentSearches)
    val recentSearches: StateFlow<List<String>> = _recentSearches.asStateFlow()
    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        val list = (listOf(t) + _recentSearches.value.filter { !it.equals(t, true) }).take(12)
        prefs.recentSearches = list
        _recentSearches.value = list
    }
    fun clearRecentSearches() {
        prefs.recentSearches = emptyList()
        _recentSearches.value = emptyList()
    }

    // ---- AI ----

    // Guard rails for small/hallucination-prone models, and anti-repetition.
    private val antiHallucination = "Only say things you are genuinely confident about. If you don't know the " +
        "writer, year, label or a specific detail, say so briefly — NEVER invent names, dates, chart positions or events. " +
        "Do NOT open with filler like \"Okay\", \"Sure\", \"Let's dive in\" or \"Let's explore\" — start directly with the substance, " +
        "and vary your phrasing each time."
    private val styleHints = listOf(
        "Lead with the single most surprising or specific detail.",
        "Open with a concrete image or fact, mid-thought.",
        "Be matter-of-fact and precise; no throat-clearing.",
        "Start from an unexpected connection to something else.",
        "Write like a friend texting you a cool fact — no preamble.",
        "Anchor it in a time and place from the first words."
    )
    private fun styleHint() = styleHints.random()
    private val _aiEnabled = MutableStateFlow(prefs.aiEnabled)
    val aiEnabled: StateFlow<Boolean> = _aiEnabled.asStateFlow()
    private val _aiProvider = MutableStateFlow(AiClient.Provider.of(prefs.aiProvider).also { AiClient.provider = it })
    val aiProvider: StateFlow<AiClient.Provider> = _aiProvider.asStateFlow()
    private val _aiApiKey = MutableStateFlow(prefs.aiApiKey.also { AiClient.apiKey = it })
    val aiApiKey: StateFlow<String> = _aiApiKey.asStateFlow()
    private val _aiBaseUrl = MutableStateFlow(prefs.aiBaseUrl)
    val aiBaseUrl: StateFlow<String> = _aiBaseUrl.asStateFlow()
    private val _aiModel = MutableStateFlow(prefs.aiModel)
    val aiModel: StateFlow<String> = _aiModel.asStateFlow()
    private val _aiLang = MutableStateFlow(prefs.aiLang)
    val aiLang: StateFlow<String> = _aiLang.asStateFlow()
    private val _aiModels = MutableStateFlow<List<String>>(emptyList())
    val aiModels: StateFlow<List<String>> = _aiModels.asStateFlow()
    private val _aiModelsLoading = MutableStateFlow(false)
    val aiModelsLoading: StateFlow<Boolean> = _aiModelsLoading.asStateFlow()

    /** Shown in the AI panels when the model/server can't be reached, so a failure is never silent. */
    private val aiUnreachable: String
        get() = "⚠️ Couldn't reach your AI" + (AiClient.lastError?.let { " ($it)" } ?: "") +
            ". Use Settings → AI → Test connection to see what's wrong."

    private val _aiTest = MutableStateFlow<AiClient.TestResult?>(null)
    val aiTest: StateFlow<AiClient.TestResult?> = _aiTest.asStateFlow()
    private val _aiTesting = MutableStateFlow(false)
    val aiTesting: StateFlow<Boolean> = _aiTesting.asStateFlow()

    // All AI answer surfaces (About / Ask / Lyrics tools) share _aiText, so only ONE stream may
    // write at a time. Track it so starting a new one CANCELS the old (no cross-screen bleed),
    // and closing a screen stops it. The `finally` only resets the flag if it still owns the job.
    private var aiStreamJob: Job? = null
    /** Cancel any in-flight AI answer stream and clear the streaming flag. */
    fun stopAiStream() { aiStreamJob?.cancel(); aiStreamJob = null; _aiStreaming.value = false }
    private fun launchAiStream(block: suspend () -> Unit) {
        aiStreamJob?.cancel()
        _aiText.value = ""
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            _aiStreaming.value = true
            try { block() }
            finally { if (aiStreamJob === coroutineContext[Job]) _aiStreaming.value = false }
        }
        aiStreamJob = job
        job.start()
    }
    private val _aiBusy = MutableStateFlow(false)
    val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()
    private val _aiStatus = MutableStateFlow<String?>(null)
    val aiStatus: StateFlow<String?> = _aiStatus.asStateFlow()
    private val _aiText = MutableStateFlow("")
    val aiText: StateFlow<String> = _aiText.asStateFlow()
    private val _aiStreaming = MutableStateFlow(false)
    val aiStreaming: StateFlow<Boolean> = _aiStreaming.asStateFlow()

    // Saved AI mixes (criteria-as-query) + last DJ prompt so it can be saved from a moment
    private val _mixes = MutableStateFlow(loadMixes())
    val mixes: StateFlow<List<AiMix>> = _mixes.asStateFlow()
    private val _lastDjPrompt = MutableStateFlow<String?>(null)
    val lastDjPrompt: StateFlow<String?> = _lastDjPrompt.asStateFlow()

    private fun loadMixes(): List<AiMix> = try {
        aiGson.fromJson(prefs.aiMixesJson, Array<AiMix>::class.java)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }
    private fun persistMixes() { prefs.aiMixesJson = aiGson.toJson(_mixes.value) }

    fun saveMix(criteria: String) {
        val c = criteria.trim()
        if (c.isBlank()) return
        val mix = AiMix(UUID.randomUUID().toString(), mixName(c), mixEmoji(c), c)
        _mixes.value = _mixes.value + mix
        persistMixes()
        _toast.value = "Saved “${mix.name}”"
    }
    fun saveCurrentAsMix() { _lastDjPrompt.value?.let { saveMix(it) } }
    fun deleteMix(id: String) { _mixes.value = _mixes.value.filter { it.id != id }; persistMixes() }
    fun renameMix(id: String, name: String) {
        _mixes.value = _mixes.value.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it }
        persistMixes()
    }
    fun playMix(mix: AiMix) = aiDj(mix.criteria)

    private fun mixName(c: String): String =
        c.trim().replaceFirstChar { it.uppercase() }.take(40)

    private fun mixEmoji(c: String): String {
        val t = c.lowercase()
        return when {
            listOf("cod", "program", "dev").any { t.contains(it) } -> "💻"
            listOf("gym", "workout", "run", "rage", "hype", "energy").any { t.contains(it) } -> "🔥"
            listOf("study", "focus", "concentrat").any { t.contains(it) } -> "📚"
            listOf("chill", "relax", "calm", "mellow").any { t.contains(it) } -> "🌙"
            listOf("jazz").any { t.contains(it) } -> "🎷"
            listOf("party", "dance", "club").any { t.contains(it) } -> "🎉"
            listOf("rain", "cozy", "autumn", "coffee").any { t.contains(it) } -> "🌧️"
            listOf("love", "romance", "sad").any { t.contains(it) } -> "💜"
            listOf("sleep", "night", "ambient").any { t.contains(it) } -> "😴"
            listOf("classic", "orchestra", "piano").any { t.contains(it) } -> "🎻"
            else -> "✨"
        }
    }

    val aiReady: Boolean
        get() = _aiEnabled.value && _aiBaseUrl.value.isNotBlank() && _aiModel.value.isNotBlank()

    fun setAiEnabled(v: Boolean) { prefs.aiEnabled = v; _aiEnabled.value = v }
    fun setAiProvider(v: AiClient.Provider) {
        if (v == _aiProvider.value) return
        prefs.aiProvider = v.name; _aiProvider.value = v; AiClient.provider = v
        // Model names don't carry across servers (e.g. "qwen3:8b" vs "qwen3.8-27b") — a stale
        // one made every vLLM call 404 while Settings still showed it as selected.
        _aiModels.value = emptyList()
        prefs.aiModel = ""; _aiModel.value = ""
        _aiTest.value = null
    }
    fun setAiApiKey(v: String) { prefs.aiApiKey = v; _aiApiKey.value = v; AiClient.apiKey = v; _aiTest.value = null }
    fun setAiBaseUrl(v: String) { prefs.aiBaseUrl = v; _aiBaseUrl.value = v; _aiTest.value = null }
    fun setAiModel(v: String) {
        prefs.aiModel = v; _aiModel.value = v; _aiTest.value = null
        // Choosing a model implies wanting AI on — avoids the "enabled?" trap.
        if (v.isNotBlank() && !_aiEnabled.value) { prefs.aiEnabled = true; _aiEnabled.value = true }
    }
    fun setAiLang(v: String) { prefs.aiLang = v; _aiLang.value = v }
    fun loadAiModels() = viewModelScope.launch {
        if (_aiModelsLoading.value) return@launch
        _aiModelsLoading.value = true
        try {
            _aiModels.value = AiClient.listModels(_aiBaseUrl.value)
            adoptOnlyModel(_aiModels.value)
        } finally {
            _aiModelsLoading.value = false
        }
    }

    /** A server serving exactly one model (typical for vLLM) needs no picking — and it fixes a stale name. */
    private fun adoptOnlyModel(models: List<String>): Boolean {
        val only = models.singleOrNull() ?: return false
        if (_aiModel.value == only) return false
        setAiModel(only)
        return true
    }

    /** Settings → AI → Test connection. */
    fun testAi() = viewModelScope.launch {
        if (_aiTesting.value) return@launch
        _aiTesting.value = true
        _aiTest.value = null
        try {
            var r = AiClient.testConnection(_aiBaseUrl.value, _aiModel.value)
            if (r.models.isNotEmpty()) _aiModels.value = r.models
            if (!r.modelFound && adoptOnlyModel(r.models)) r = AiClient.testConnection(_aiBaseUrl.value, _aiModel.value)
            _aiTest.value = r
        } finally {
            _aiTesting.value = false
        }
    }

    init {
        // Heal configs saved by v1.14.0, where switching to vLLM kept the old Ollama model name.
        if (_aiProvider.value == AiClient.Provider.VLLM && _aiBaseUrl.value.isNotBlank()) {
            viewModelScope.launch { adoptOnlyModel(AiClient.listModels(_aiBaseUrl.value)) }
        }
    }
    fun clearAiText() { stopAiStream(); _aiText.value = "" }

    private suspend fun pickAlbums(prompt: String, count: Int): List<Album> {
        var lib = _albums.value
        if (lib.isEmpty()) {
            lib = try {
                Subsonic.api?.getAlbumList2("alphabeticalByName", 500)?.response?.albumList2?.album ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        if (lib.isEmpty()) return emptyList()
        // Prefilter by prompt-token hits, then cap hard: small models pick reliably from
        // ~20-40 numbered entries and collapse on 200 (mid-list items just vanish).
        val tokens = prompt.lowercase().split(Regex("\\W+")).filter { it.length >= 3 }
        val scored = lib.map { a ->
            val hay = "${a.artist ?: ""} ${a.name ?: ""} ${a.genre ?: ""}".lowercase()
            a to tokens.count { hay.contains(it) }
        }
        val hits = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        val capped = (hits.take(30) + lib.shuffled()).distinctBy { it.id }.take(40)
        val numbered = capped.mapIndexed { i, a ->
            "${i + 1}. ${a.artist ?: "?"} — ${a.name ?: "?"}"
        }.joinToString("\n")
        // Instruction placed AFTER the list so context truncation can't drop it.
        val user = "You are a music DJ. Below is a numbered music library.\n\n$numbered\n\n" +
            "Task: pick about $count entries that best fit this request: \"$prompt\".\n" +
            "Reply ONLY as JSON: {\"picks\":[numbers]} using ONLY numbers from the list above."
        val json = AiClient.chat(
            _aiBaseUrl.value, _aiModel.value,
            listOf(AiClient.Msg("user", user)),
            json = true
        ) ?: return emptyList()
        return parsePicks(json).mapNotNull { capped.getOrNull(it - 1) }.distinct()
    }

    /** Leniently pulls a list of 1-based indices from whatever JSON the model returned. */
    private fun parsePicks(json: String): List<Int> {
        return try {
            val root = JsonParser.parseString(json)
            val arr = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject -> root.asJsonObject.entrySet().firstOrNull { it.value.isJsonArray }?.value?.asJsonArray
                else -> null
            } ?: return emptyList()
            arr.mapNotNull { el ->
                try {
                    if (el.isJsonPrimitive) {
                        val p = el.asJsonPrimitive
                        if (p.isNumber) p.asInt else p.asString.trim().toIntOrNull()
                    } else null
                } catch (_: Exception) { null }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---------------------------------------------------------------------
    // AI DJ selection pipeline (retrieval-augmented — scales to any library).
    //
    // Instead of stuffing the whole catalog into the prompt (slow, and impossible
    // once the library is large), the model only turns the request into a tiny
    // structured query grounded in the REAL genre vocabulary. A deterministic
    // retriever then hits the server's own genre/artist/year indexes. The model
    // never processes the catalog, so this is fast and reliable even for small
    // models with tiny context windows.
    // ---------------------------------------------------------------------
    private data class DjQuery(
        val genres: List<String> = emptyList(),
        val artists: List<String> = emptyList(),
        val decades: List<Int> = emptyList(),
        val keywords: List<String> = emptyList()
    ) {
        val isEmpty get() = genres.isEmpty() && artists.isEmpty() && keywords.isEmpty()
    }

    /** Album picks plus the query that produced them (aiDj orders tracks by its keywords). */
    private data class DjSelection(val albums: List<Album>, val query: DjQuery)

    /** Top-level DJ album selection: intent-extraction → index retrieval → fallbacks. */
    private suspend fun djSelect(prompt: String, count: Int): DjSelection {
        val q = extractDjQuery(prompt)
        var albums = if (!q.isEmpty) retrieveAlbumsForQuery(q, count) else emptyList()
        // Structured retrieval found nothing (odd request / no genre match) → try the
        // classic numbered-pick on a sample, then finally a random handful.
        if (albums.isEmpty()) albums = pickAlbums(prompt, count)
        if (albums.isEmpty()) albums = fallbackRandomAlbums(count)
        return DjSelection(albums, q)
    }

    /** Stage A — the model maps the request to a compact JSON filter (tiny context). */
    private suspend fun extractDjQuery(prompt: String): DjQuery {
        var vocab = _genres.value.mapNotNull { it.value?.trim() }.filter { it.isNotBlank() }
        if (vocab.isEmpty()) {
            vocab = try {
                Subsonic.api?.getGenres()?.response?.genres?.genre?.mapNotNull { it.value?.trim() }?.filter { it.isNotBlank() } ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        val vocabLine = if (vocab.isNotEmpty())
            "Available genres (pick ONLY from these where relevant): ${vocab.joinToString(", ")}\n\n" else ""
        val sys = "You translate a music request into a compact JSON filter for a library search. " +
            "Reply ONLY with JSON: {\"genres\":[],\"artists\":[],\"decades\":[1990],\"keywords\":[]}. " +
            "Choose genres ONLY from the provided list. decades are 4-digit (e.g. 1980). " +
            "keywords are extra words to match on song/album titles (include song titles the request names). No prose."
        val json = AiClient.chat(
            _aiBaseUrl.value, _aiModel.value,
            listOf(AiClient.Msg("system", sys), AiClient.Msg("user", vocabLine + "Request: \"$prompt\"")),
            json = true
        )
        return parseDjQuery(json, vocab)
    }

    private fun parseDjQuery(json: String?, vocab: List<String>): DjQuery {
        if (json.isNullOrBlank()) return DjQuery()
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            fun strList(key: String): List<String> =
                o.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { runCatching { it.asString }.getOrNull()?.trim() }
                    ?.filter { it.isNotBlank() } ?: emptyList()
            // Map the model's genre words onto real library genres so index lookups are exact.
            val genres = strList("genres").mapNotNull { g ->
                vocab.firstOrNull { it.equals(g, true) }
                    ?: vocab.firstOrNull { it.contains(g, true) || g.contains(it, true) }
            }.distinct()
            val decades = (o.get("decades")?.takeIf { it.isJsonArray }?.asJsonArray)
                ?.mapNotNull { el ->
                    runCatching { el.asInt }.getOrNull()
                        ?: runCatching { el.asString }.getOrNull()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }
                }?.distinct() ?: emptyList()
            DjQuery(genres, strList("artists"), decades, strList("keywords"))
        } catch (_: Exception) { DjQuery() }
    }

    /**
     * Stage B — deterministic retrieval against the server's genre/artist indexes + local
     * library, RANKED by how many of the query's facets each album matches (an album that
     * hits genre+artist+decade must beat a stray keyword-only match).
     */
    private suspend fun retrieveAlbumsForQuery(q: DjQuery, target: Int): List<Album> {
        val pool = LinkedHashMap<String, Album>()
        val score = HashMap<String, Int>()
        fun addAll(al: List<Album>?, points: Int) {
            al?.forEach {
                pool.putIfAbsent(it.id, it)
                score.merge(it.id, points, Int::plus)
            }
        }

        for (g in q.genres.take(6)) {
            // 100-deep + shuffle: a 40-entry alphabetical slice biased every genre mix
            // toward artists starting with "A".
            try {
                addAll(
                    Subsonic.api?.getAlbumList2("byGenre", 100, 0, g)?.response?.albumList2?.album?.shuffled(),
                    2
                )
            } catch (_: Exception) {}
        }
        for (a in q.artists.take(6)) {
            try {
                val r = Subsonic.api?.search3(a, songCount = 0, albumCount = 20, artistCount = 5)?.response?.searchResult3
                addAll(r?.album?.filter { it.artist?.contains(a, true) == true }, 3)
                r?.artist?.firstOrNull { it.name?.contains(a, true) == true }?.id?.let { aid ->
                    addAll(Subsonic.api?.getArtist(aid)?.response?.artist?.album, 3)
                }
            } catch (_: Exception) {}
        }
        // Song-level retrieval: a request naming a song or a lyric vibe finds the songs
        // themselves, then their albums — album-name matching alone can't see them.
        for (kw in q.keywords.take(4)) {
            try {
                val r = Subsonic.api?.search3(kw, songCount = 25, albumCount = 10)?.response?.searchResult3
                addAll(r?.album, 2)
                val albumIds = r?.song?.mapNotNull { it.albumId }?.toSet() ?: emptySet()
                if (albumIds.isNotEmpty()) addAll(_albums.value.filter { it.id in albumIds }, 2)
            } catch (_: Exception) {}
        }
        // Keyword / genre-word match across the blended local list (also covers device music).
        val kws = (q.keywords + q.genres).map { it.lowercase() }.filter { it.length >= 3 }
        if (kws.isNotEmpty()) {
            addAll(_albums.value.filter { al ->
                val hay = "${al.name ?: ""} ${al.artist ?: ""}".lowercase()
                kws.any { hay.contains(it) }
            }, 2)
        }
        // Device albums whose songs carry a matching genre tag.
        if (q.genres.isNotEmpty()) {
            val gset = q.genres.map { it.lowercase() }.toSet()
            val localIds = _localSongs.value.filter { it.genre?.lowercase() in gset }.mapNotNull { it.albumId }.toSet()
            addAll(_albums.value.filter { it.id.removePrefix("localalbum-") in localIds && it.id.startsWith("localalbum-") }, 2)
        }

        fun inDecade(y: Int?) = y != null && q.decades.any { y >= it && y < it + 10 }
        if (q.decades.isNotEmpty()) {
            pool.keys.forEach { id -> if (inDecade(pool[id]?.year)) score.merge(id, 1, Int::plus) }
        }
        // Shuffle THEN stable-sort: score ranking holds, but equal scores vary per call —
        // a deterministic order made every radio top-up draw the same handful of albums.
        return pool.values
            .shuffled()
            .sortedByDescending { score[it.id] ?: 0 }
            .take(target)
    }

    private suspend fun fallbackRandomAlbums(count: Int): List<Album> {
        val lib = _albums.value.ifEmpty {
            try { Subsonic.api?.getAlbumList2("random", 100)?.response?.albumList2?.album ?: emptyList() }
            catch (_: Exception) { emptyList() }
        }
        return lib.shuffled().take(count)
    }

    private suspend fun gatherSongs(albums: List<Album>): List<Song> =
        albums.flatMap { al ->
            if (al.id.startsWith("localalbum-")) {
                val albumId = al.id.removePrefix("localalbum-")
                _localSongs.value.filter { it.albumId == albumId }
            } else Subsonic.api?.getAlbum(al.id)?.response?.album?.song ?: emptyList()
        }


    /** Tracks the AI DJ just built, so the Ask screen can preview them instead of only auto-playing. */
    private val _mixSongs = MutableStateFlow<List<Song>>(emptyList())
    val mixSongs: StateFlow<List<Song>> = _mixSongs.asStateFlow()

    /** AI DJ: turn a natural-language request into a playing queue. */
    fun aiDj(prompt: String) = viewModelScope.launch {
        if (!aiReady) { _aiStatus.value = "Set up AI in Settings first"; return@launch }
        if (prompt.isBlank()) return@launch
        // Re-entry guard: ignore repeat taps while a mix is already generating, so
        // a slow network can't stack three mixes that all start playing in turn.
        if (_aiBusy.value) return@launch
        _aiBusy.value = true
        _aiStatus.value = "Thinking…"
        _mixSongs.value = emptyList()
        try {
            val (albums, djQuery) = djSelect(prompt, 12)
            if (albums.isEmpty()) { _aiStatus.value = "Couldn't reach the AI, or no matches — check the AI settings & your connection."; return@launch }
            _aiStatus.value = "Gathering tracks…"
            // Whole-album dumps in random order drown a correct pick in off-vibe deep
            // cuts: tracks that literally match the request lead, and the queue is capped.
            val gathered = gatherSongs(albums)
            val kw = djQuery.keywords.map { it.lowercase() }.filter { it.length >= 3 }
            val songs = if (kw.isNotEmpty()) {
                val (hit, rest) = gathered.partition { s ->
                    val hay = "${s.title ?: ""} ${s.artist ?: ""}".lowercase()
                    kw.any { hay.contains(it) }
                }
                (hit.shuffled() + rest.shuffled()).take(60)
            } else gathered.shuffled().take(60)
            if (songs.isEmpty()) { _aiStatus.value = "No playable tracks found"; return@launch }
            _mixSongs.value = songs
            _aiStatus.value = if (djQuery.isEmpty && AiClient.lastError != null)
                // The AI failed and djSelect fell back — say so instead of passing it off as a pick.
                "⚠️ AI unreachable (${AiClient.lastError}) — playing ${songs.size} tracks from a fallback pick"
            else "Playing ${songs.size} tracks · ${albums.size} albums"
            _lastDjPrompt.value = prompt
            playSongs(songs, 0)
        } catch (e: Exception) {
            _aiStatus.value = "AI error — check your endpoint/model"
        } finally {
            _aiBusy.value = false
        }
    }

    // ---- Smart Radio ----
    private val _radio = MutableStateFlow(false)
    val radio: StateFlow<Boolean> = _radio.asStateFlow()
    private var radioSeed: String? = null
    private var radioBusy = false
    // Similar-radio seed (song-level, mood/genre/era-matched) — set = similar mode active.
    private var similarSeedTitle: String? = null
    private var similarSeedArtist: String? = null

    fun setRadio(v: Boolean) {
        _radio.value = v
        if (!v) { similarSeedTitle = null; similarSeedArtist = null }
        else maybeExtendRadio()
    }
    fun toggleRadio() = setRadio(!_radio.value)
    fun startRadioFromCurrent() {
        similarSeedTitle = null; similarSeedArtist = null
        radioSeed = "${_artist.value ?: ""} — ${_title.value ?: ""}"
        setRadio(true)
    }

    /**
     * Similar Radio: an endless queue matched to the vibe of the song playing right now.
     * Song-level (genre/era/mood via the similar-songs pipeline), not the album-level DJ —
     * and it keeps working without AI (deterministic prefilter picks) so it never dies mid-drive.
     */
    fun startSimilarRadio() {
        val t = _title.value?.trim()?.takeIf { it.isNotBlank() }
        if (t == null) { _toast.value = "Play a song first — similar radio follows what's on."; return }
        similarSeedTitle = t
        similarSeedArtist = _artist.value?.trim() ?: ""
        radioSeed = "${similarSeedArtist} — $t"
        _radio.value = true
        _toast.value = "Similar radio on — keeping this vibe going"
        maybeExtendRadio(force = true)
    }
    fun similarRadioActive() = _radio.value && similarSeedTitle != null

    private fun maybeExtendRadio(force: Boolean = false) {
        val c = controller ?: return
        val similarMode = similarSeedTitle != null
        if (!_radio.value || radioBusy) return
        if (!similarMode && !aiReady) return // similar mode has a no-AI fallback
        val remaining = c.mediaItemCount - (c.currentMediaItemIndex + 1)
        if (!force && remaining > 3) return
        radioBusy = true
        viewModelScope.launch {
            try {
                if (similarMode) extendSimilarRadio()
                else {
                    val seed = radioSeed ?: "${_artist.value ?: ""} ${_albumTitle.value ?: ""}"
                    val albums = djSelect("Music similar in style and mood to: $seed. Keep it varied.", 5).albums
                    // Dedup against the queue or a long radio session replays the same albums.
                    val queued = queuedIds()
                    val songs = gatherSongs(albums).filter { it.id !in queued }.shuffled().take(20)
                    if (songs.isNotEmpty()) addToQueue(songs)
                }
            } catch (_: Exception) {
            } finally {
                radioBusy = false
            }
        }
    }

    /** Top up the similar-radio queue: pool from the seed's genre/artist/era, AI-picked (or scored). */
    private suspend fun extendSimilarRadio() {
        val t = similarSeedTitle ?: return
        val ar = similarSeedArtist ?: ""
        val seedHit = resolveSeed(t, ar)
        val genre = seedHit?.genre?.takeIf { it.isNotBlank() }
        val year = seedHit?.year
        val queued = queuedIds()
        val fresh = buildSimilarPool(ar, genre, seedHit?.id).filter { it.id !in queued }
        if (fresh.isEmpty()) return
        val ctx = buildString {
            append("\"$t\" by ${ar.ifBlank { "unknown artist" }}")
            if (genre != null || year != null)
                append(" (${listOfNotNull(genre, year?.let { "${it}s" }).joinToString(", ")})")
            append(" — match its mood and energy")
        }
        val picks = if (aiReady) rankPool(ctx, ar, genre, year, fresh).mapNotNull { it.librarySong } else emptyList()
        val songs = picks.ifEmpty { prefilterPool(ar, genre, year, fresh).take(12) }
        if (songs.isNotEmpty()) addToQueue(songs)
    }

    private fun queuedIds(): Set<String> {
        val c = controller ?: return emptySet()
        return (0 until c.mediaItemCount).mapTo(HashSet()) { c.getMediaItemAt(it).mediaId }
    }

    // Per-song/topic cache of AI insights so re-opening the same analysis is instant.
    private val insightCache = HashMap<String, String>()
    // The Wikipedia article that grounded the current insight — photo + facts for the UI.
    private val wikiCache = HashMap<String, Wikipedia.Page>()
    private val wikiFullCache = HashMap<String, String>()
    private val _insightWiki = MutableStateFlow<Wikipedia.Page?>(null)
    val insightWiki: StateFlow<Wikipedia.Page?> = _insightWiki.asStateFlow()
    // Proper artist photo (Deezer press shot; Wikipedia thumbnails are license-filtered).
    private val heroCache = HashMap<String, String>()
    private val _insightHero = MutableStateFlow<String?>(null)
    val insightHero: StateFlow<String?> = _insightHero.asStateFlow()

    private suspend fun heroPhoto(artist: String): String? {
        if (artist.isBlank()) return null
        val key = artist.lowercase()
        // Cache hits only — a timeout must not blank this artist's photo all session.
        return heroCache[key] ?: Deezer.artist(artist)?.pictureUrl
            .also { if (it != null) heroCache[key] = it }
    }
    private val _lastInsightTopic = MutableStateFlow("song")
    val lastInsightTopic: StateFlow<String> = _lastInsightTopic.asStateFlow()
    /** Re-run the last insight, bypassing the cache. */
    fun refreshInsight() = aiInsights(_lastInsightTopic.value, force = true)

    /** AI insights about the current music. topic = song | album | artist | era | songwriter. */
    fun aiInsights(topic: String = "song", force: Boolean = false) {
        _lastInsightTopic.value = topic
        val tgt = _insightTarget.value
        val t = tgt?.title ?: _title.value ?: ""
        val ar = tgt?.artist ?: _artist.value ?: ""
        val al = tgt?.album ?: _albumTitle.value ?: ""
        // Artist/era/songwriter blurbs don't depend on the specific song title, so keep it out
        // of the key — otherwise two songs by the same artist never share a cached artist blurb.
        val cacheKey = when (topic) {
            "artist" -> "artist|$ar"
            "album" -> "album|$al|$ar"
            "era" -> "era|$ar|$al"
            else -> "$topic|$t|$ar|$al"
        }.lowercase()
        // Plain artist name ranks the band page first; "X band musician" famously returns
        // Ron McGovney for Metallica and a 1960s UK band for Nirvana.
        val wikiQuery = when (topic) {
            "album" -> "$al album $ar"
            "artist" -> ar
            "songwriter" -> "$t song $ar"
            "era" -> "$ar $al"
            else -> "$t song $ar"
        }
        // Never cache a miss: a 429/timeout would otherwise blank wiki until app restart.
        suspend fun wikiPage(): Wikipedia.Page? =
            wikiCache[cacheKey] ?: Wikipedia.lookupPage(wikiQuery)
                .also { if (it != null) wikiCache[cacheKey] = it }
        if (!aiReady) {
            // No AI configured — the photo + Wikipedia facts still make an About page.
            stopAiStream(); _aiText.value = "Set up AI in Settings first."
            viewModelScope.launch {
                _insightWiki.value = wikiPage()
                _insightHero.value = heroPhoto(ar)
            }
            return
        }
        if (!force) {
            insightCache[cacheKey]?.let {
                stopAiStream(); _aiText.value = it
                _insightWiki.value = wikiCache[cacheKey]
                viewModelScope.launch { _insightHero.value = heroPhoto(ar) }
                return
            }
        }
        launchAiStream {
        // Retrieval-augmented grounding: pull real facts from Wikipedia first.
        val page = wikiPage()
        _insightWiki.value = page
        _insightHero.value = heroPhoto(ar)
        // The artist blurb deserves the whole article (members, history), not just the intro.
        val fullRef = if (topic == "artist") {
            wikiFullCache[cacheKey] ?: Wikipedia.lookupFull(wikiQuery)?.extract
                ?.also { wikiFullCache[cacheKey] = it }
        } else null
        val reference = fullRef ?: page?.extract
        val tasteLine = taste.value.summary()?.let {
            "This regular's heavy rotation: $it. When it genuinely fits, connect what you're describing " +
                "to what they already play — one natural aside, never forced, never flattery. "
        } ?: ""
        val sys = "You are the owner of a legendary record shop — encyclopedic, warm, a little opinionated, " +
            "the friend who always has one more story. Write 2-3 SHORT paragraphs separated by blank lines, " +
            "plain conversational English. Lead with the most interesting thing, not the most obvious. " +
            "Weave in the kind of details a real music nerd trades over the counter: who's in the band, " +
            "how a song got made, samples and influences, feuds, chart or cultural moments, what came after. " +
            tasteLine +
            (if (reference != null) "Every fact must come from the REFERENCE below — do NOT contradict or go beyond it. " +
                "If something juicy isn't in the reference, say it's not documented rather than guessing. "
            else "You have NO reference for this — so be brief and general, and clearly say you're not certain of specifics; do not invent facts. ") +
            antiHallucination + " " + styleHint()
        val ask = when (topic) {
            "album" -> "Tell me about the album \"$al\" by $ar — the story around its making, its place in their run, and a fun fact worth telling a customer."
            "artist" -> "Tell me about $ar — who's in the band (members and what they play, if documented), how they formed, what makes their sound theirs, and the fun facts a fan would want."
            "era" -> "Place the song \"$t\" by $ar in its era, scene and genre — what was happening around it, and who else mattered right then."
            "songwriter" -> "Who wrote, composed or produced \"$t\" by $ar, and any notable story behind how it came together?"
            else -> "Tell me about the song \"$t\" by $ar — its story, meaning, how it was made, and anything surprising about it."
        }
        val user = ask + (reference?.let { "\n\nREFERENCE (from Wikipedia):\n$it" } ?: "")
        val ok = AiClient.chatStream(_aiBaseUrl.value, _aiModel.value, listOf(AiClient.Msg("system", sys), AiClient.Msg("user", user))) { tok ->
            _aiText.value += tok
        }
        // Only the still-live stream may write results — a cancelled one must not clobber _aiText.
        if (coroutineContext[Job]?.isActive == true) {
            when {
                ok && _aiText.value.isNotBlank() -> insightCache[cacheKey] = _aiText.value
                _aiText.value.isBlank() -> _aiText.value = aiUnreachable
            }
        }
        }
    }

    // ---- AI similar-songs discovery (AI-based, not hardcoded) ----
    data class SimilarItem(val title: String, val artist: String, val librarySong: Song?)
    private val _similar = MutableStateFlow<List<SimilarItem>>(emptyList())
    val similar: StateFlow<List<SimilarItem>> = _similar.asStateFlow()
    private val _similarLoading = MutableStateFlow(false)
    val similarLoading: StateFlow<Boolean> = _similarLoading.asStateFlow()
    private val _similarSeed = MutableStateFlow<String?>(null)
    val similarSeed: StateFlow<String?> = _similarSeed.asStateFlow()

    private data class SimSong(val title: String? = null, val artist: String? = null)
    private data class SimResult(val songs: List<SimSong>? = null)

    /**
     * Find songs similar to the given (or current) song.
     *
     * Small local models are bad at *recalling* similar tracks from just a title, so instead we
     * GROUND the seed (its real genre/year from the server), build a candidate pool of songs the
     * user actually owns (same artist + same genre), and ask the model to *pick* the closest
     * matches by index -- a far easier, more reliable task that guarantees playable results.
     * A grounded discovery pass then suggests cross-artist picks for the YouTube path.
     */
    fun loadSimilar(title: String? = null, artist: String? = null) = viewModelScope.launch {
        val t = (title ?: _title.value ?: "").trim()
        val ar = (artist ?: _artist.value ?: "").trim()
        if (!aiReady || t.isBlank()) { _similar.value = emptyList(); _similarSeed.value = null; return@launch }
        _similarSeed.value = if (t.isNotBlank()) "$t · $ar" else null
        if (_similarLoading.value) return@launch
        _similarLoading.value = true
        _similar.value = emptyList()
        try {
            // 1) Ground the seed: resolve its real genre / year from the server.
            val seedHit = resolveSeed(t, ar)
            val genre = seedHit?.genre?.takeIf { it.isNotBlank() }
            val year = seedHit?.year
            val seedId = seedHit?.id
            val ctx = buildString {
                append("\"$t\" by ${ar.ifBlank { "unknown artist" }}")
                when {
                    genre != null && year != null -> append(" (genre: $genre, ${year}s)")
                    genre != null -> append(" (genre: $genre)")
                    year != null -> append(" (${year}s)")
                }
            }

            // 2) Build a pool of REAL songs the user owns: same artist + same genre.
            val pool = buildSimilarPool(ar, genre, seedId)
            val libraryPicks = if (pool.size >= 4) rankPool(ctx, ar, genre, year, pool) else emptyList()

            // 3) Grounded discovery for the YouTube path (different artists, same lane).
            val discovery = discoverSimilar(ctx, ar, genre, year)
                .map { (ti, arti) -> SimilarItem(ti, arti, findInLibrary(ti, arti)) }

            // Merge: owned-and-matching first, then fresh discoveries not already shown.
            val seen = HashSet<String>()
            fun key(it: SimilarItem) = "${it.title.lowercase().trim()}|${it.artist.lowercase().trim()}"
            val merged = (libraryPicks + discovery).filter { seen.add(key(it)) }
            _similar.value = merged
            // Don't leave the user on a blank "nothing here" screen if the AI was unreachable.
            if (merged.isEmpty()) _error.value = "Couldn't get similar songs — check your AI settings & connection."
        } catch (_: Exception) {
            _error.value = "Couldn't get similar songs — check your AI settings & connection."
        } finally {
            _similarLoading.value = false
        }
    }

    /**
     * Look up the seed track on the server to recover its genre/year/id for grounding.
     * Query by TITLE ONLY: Navidrome's search3 matches songs on the title field, so
     * appending the artist frequently returns zero hits and nulls the whole grounding.
     */
    private suspend fun resolveSeed(title: String, artist: String): Song? = try {
        val res = Subsonic.api?.search3(title, songCount = 20)?.response?.searchResult3?.song ?: emptyList()
        res.firstOrNull {
            it.title?.equals(title, true) == true &&
                (artist.isBlank() || looseMatch(it.artist, artist, 4))
        } ?: res.firstOrNull {
            looseMatch(it.title, title, 5) && (artist.isBlank() || looseMatch(it.artist, artist, 4))
        }
    } catch (_: Exception) { null }

    /** Real owned songs to choose from: same genre + same artist (+ local device tracks of that genre). */
    private suspend fun buildSimilarPool(artist: String, genre: String?, seedId: String?): List<Song> {
        val out = LinkedHashMap<String, Song>()
        fun add(songs: List<Song>?) {
            songs?.forEach { s -> if (s.id != seedId) out.putIfAbsent(s.id, s) }
        }
        try {
            if (genre != null) add(Subsonic.api?.getSongsByGenre(genre, count = 80)?.response?.songsByGenre?.song)
            if (artist.isNotBlank()) add(
                Subsonic.api?.search3(artist, songCount = 40)?.response?.searchResult3?.song
                    ?.filter { it.artist?.contains(artist, true) == true }
            )
            // Blend in local device tracks of the same genre so owned music isn't siloed.
            if (genre != null) add(_localSongs.value.filter { it.genre?.equals(genre, true) == true })
        } catch (_: Exception) {}
        return out.values.toList()
    }

    /**
     * Deterministic prefilter (genre/era/artist-variety scored, max 2 per artist, ≤20
     * candidates) — small models pick reliably from 20 and collapse on 120 — then the
     * model picks the closest by index (recall -> selection).
     */
    private fun prefilterPool(artist: String, genre: String?, year: Int?, pool: List<Song>): List<Song> {
        fun score(s: Song): Int =
            (if (genre != null && s.genre?.equals(genre, true) == true) 3 else 0) +
                (if (year != null && s.year != null && kotlin.math.abs(s.year - year) <= 10) 2 else 0) +
                (if (!looseMatch(s.artist, artist, 4)) 1 else 0) // cross-artist variety bonus
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<Song>(20)
        for (s in pool.shuffled().sortedByDescending(::score)) { // stable sort keeps shuffle as tiebreak
            val a = s.artist?.lowercase() ?: "?"
            if ((perArtist[a] ?: 0) >= 2) continue
            perArtist[a] = (perArtist[a] ?: 0) + 1
            out.add(s)
            if (out.size >= 20) break
        }
        return out
    }

    private suspend fun rankPool(ctx: String, artist: String, genre: String?, year: Int?, pool: List<Song>): List<SimilarItem> {
        val capped = prefilterPool(artist, genre, year, pool)
        val numbered = capped.mapIndexed { i, s ->
            "${i + 1}. ${s.artist ?: "?"} - ${s.title ?: "?"}${s.genre?.let { " [$it]" } ?: ""}"
        }.joinToString("\n")
        // Instruction placed AFTER the list so context truncation can't drop it.
        val user = "Seed song: $ctx.\n\nNumbered library of songs the listener owns:\n\n$numbered\n\n" +
            "Task: pick the 10 songs above that are the CLOSEST match to the seed in genre, style, mood, energy and era. " +
            "Prefer the same or adjacent genre; vary the artists; do NOT pick the seed itself. " +
            "Reply ONLY as JSON: {\"picks\":[numbers]} using ONLY numbers from the list."
        val json = AiClient.chat(_aiBaseUrl.value, _aiModel.value, listOf(AiClient.Msg("user", user)), json = true)
            ?: return emptyList()
        return parsePicks(json).mapNotNull { capped.getOrNull(it - 1) }.distinctBy { it.id }
            .map { SimilarItem(it.title ?: "?", it.artist ?: "?", it) }
    }

    /** Grounded discovery: cross-artist suggestions in the same lane (for the YouTube path). */
    private suspend fun discoverSimilar(ctx: String, artist: String, genre: String?, year: Int?): List<Pair<String, String>> = try {
        val lane = genre ?: "the same style"
        val sys = "You are a music discovery expert with deep, accurate knowledge. Suggest 8 REAL, well-known songs that " +
            "genuinely sound similar to the seed - same $lane, comparable mood, energy and era. Use DIFFERENT artists than " +
            "the seed. Only suggest songs you are confident actually exist; never invent titles. " +
            "Reply ONLY as JSON: {\"songs\":[{\"title\":\"..\",\"artist\":\"..\"}]}."
        val user = "Seed song: $ctx." + (year?.let { " Stay close to the ${it}s." } ?: "")
        val json = AiClient.chat(_aiBaseUrl.value, _aiModel.value, listOf(AiClient.Msg("system", sys), AiClient.Msg("user", user)), json = true)
        parseSimilar(json).filter { (_, a) -> a.isNotBlank() && !a.equals(artist, true) }
    } catch (_: Exception) { emptyList() }

    private fun parseSimilar(json: String?): List<Pair<String, String>> = try {
        if (json.isNullOrBlank()) emptyList()
        else aiGson.fromJson(json, SimResult::class.java)?.songs
            ?.mapNotNull { s -> if (!s.title.isNullOrBlank() && !s.artist.isNullOrBlank()) s.title to s.artist else null }
            ?: emptyList()
    } catch (_: Exception) { emptyList() }

    private suspend fun findInLibrary(title: String, artist: String): Song? = try {
        // Title-only query for the same reason as resolveSeed — artist tokens zero it out.
        val res = Subsonic.api?.search3(title, songCount = 12)?.response?.searchResult3?.song ?: emptyList()
        res.firstOrNull { looseMatch(it.title, title, 5) && looseMatch(it.artist, artist, 4) }
    } catch (_: Exception) { null }

    /**
     * Fuzzy-but-safe match. Exact (case/space-insensitive) always counts; a substring only
     * counts when the shorter string is long enough that it cannot accidentally match
     * (avoids "One" in "Someone", or a blank/null title matching everything).
     */
    private fun looseMatch(a: String?, b: String?, minSub: Int): Boolean {
        val x = a?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        val y = b?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
        if (x == y) return true
        val (short, long) = if (x.length <= y.length) x to y else y to x
        return short.length >= minSub && long.contains(short)
    }

    fun playSimilar(item: SimilarItem) { item.librarySong?.let { playSongs(listOf(it), 0) } }
    fun queueSimilar(item: SimilarItem) { item.librarySong?.let { addToQueue(listOf(it)) } }
    fun youtubeQuery(item: SimilarItem) = "${item.title} ${item.artist}"

    /** Free-form question about the currently playing music (streaming). */
    fun aiAsk(question: String) {
        if (!aiReady) { stopAiStream(); _aiText.value = "Set up AI in Settings first."; return }
        if (question.isBlank()) return
        launchAiStream {
            val tgt = _insightTarget.value
            val cTitle = tgt?.title ?: _title.value ?: ""
            val cArtist = tgt?.artist ?: _artist.value ?: ""
            val cAlbum = tgt?.album ?: _albumTitle.value
            val ctx = "Context: \"$cTitle\" by $cArtist" + (cAlbum?.let { " (album \"$it\")" } ?: "") + "."
            val reference = Wikipedia.lookup("$cArtist ${cAlbum ?: cTitle}")
            val sys = "You are a knowledgeable, friendly music companion. Answer briefly (2-4 sentences), plain text. " +
                (if (reference != null) "Prefer the REFERENCE facts below; if the answer isn't in them and you're unsure, say so rather than guessing. " else "") +
                antiHallucination + " " + styleHint()
            val userMsg = "$ctx Question: $question" + (reference?.let { "\n\nREFERENCE (from Wikipedia):\n$it" } ?: "")
            val ok = AiClient.chatStream(_aiBaseUrl.value, _aiModel.value, listOf(AiClient.Msg("system", sys), AiClient.Msg("user", userMsg))) { tok ->
                _aiText.value += tok
            }
            if (coroutineContext[Job]?.isActive == true && !ok && _aiText.value.isBlank()) _aiText.value = aiUnreachable
        }
    }

    // Which lyric tool produced _aiText — the translate view styles line pairs differently.
    private val _aiLyricsMode = MutableStateFlow<String?>(null)
    val aiLyricsMode: StateFlow<String?> = _aiLyricsMode.asStateFlow()

    /** AI on lyrics: mode = "translate" or "explain" (streaming). */
    fun aiLyrics(mode: String) {
        if (!aiReady) { stopAiStream(); _aiText.value = "Set up AI in Settings first."; return }
        val lyr = _lyrics.value
        if (lyr.isNullOrBlank()) { stopAiStream(); _aiText.value = "No lyrics to work with yet."; return }
        _aiLyricsMode.value = mode
        launchAiStream {
            val prompt = if (mode == "translate")
                "Translate these song lyrics into ${_aiLang.value}, bilingually: repeat each original line " +
                    "unchanged, then on the very next line put its ${_aiLang.value} translation, prefixed " +
                    "with \"» \" (right guillemet and a space). Every translated line MUST start with \"» \"; " +
                    "original lines must not. Keep stanza breaks (blank lines) where the original has them. " +
                    "If a line is already in ${_aiLang.value}, still show both. Output only the lyrics, " +
                    "no commentary.\n\n$lyr"
            else buildString {
                append("Explain these song lyrics like a knowledgeable friend — plain, conversational English, ")
                append("short sentences, no academic words. Use EXACTLY this markdown structure:\n")
                append("## What it's about\nOne short paragraph: the story or subject of the song.\n")
                append("## What it means\nTwo short paragraphs: the deeper meaning, emotions, what the writer is really saying, and why it hits.\n")
                append("## Slang & references\nBullet list: every slang word, idiom, name or reference in the lyrics and what it means HERE. If there are none, write \"Nothing tricky in this one.\"\n\n")
                append("Be specific to these lyrics — quote a word or line when you decode it. No intro, no outro.\n\n")
                append("Song: \"${_title.value ?: ""}\" by ${_artist.value ?: "unknown"}")
                _albumTitle.value?.let { append(" (album \"$it\")") }
                val ref = Wikipedia.lookup("${_title.value ?: ""} song ${_artist.value ?: ""}")
                if (ref != null) append("\n\nBACKGROUND (from Wikipedia — use it, don't contradict it):\n$ref")
                append("\n\nLYRICS:\n$lyr")
            }
            val ok = AiClient.chatStream(_aiBaseUrl.value, _aiModel.value, listOf(AiClient.Msg("user", prompt))) { tok ->
                _aiText.value += tok
            }
            if (coroutineContext[Job]?.isActive == true && !ok && _aiText.value.isBlank()) _aiText.value = aiUnreachable
        }
    }

    private val _cacheBytes = MutableStateFlow(0L)
    val cacheBytes: StateFlow<Long> = _cacheBytes.asStateFlow()

    // ---- Add-to-playlist picker ----
    private val _playlistPickerSongs = MutableStateFlow<List<Song>?>(null)
    val playlistPickerSongs: StateFlow<List<Song>?> = _playlistPickerSongs.asStateFlow()

    // ---- Home rows ----
    private val _newest = MutableStateFlow<List<Album>>(emptyList())
    val newest: StateFlow<List<Album>> = _newest.asStateFlow()
    private val _recent = MutableStateFlow<List<Album>>(emptyList())
    val recent: StateFlow<List<Album>> = _recent.asStateFlow()
    private val _frequent = MutableStateFlow<List<Album>>(emptyList())
    val frequent: StateFlow<List<Album>> = _frequent.asStateFlow()
    private val _randomAlbums = MutableStateFlow<List<Album>>(emptyList())
    val randomAlbums: StateFlow<List<Album>> = _randomAlbums.asStateFlow()

    // ---- Detail ----
    private val _currentAlbum = MutableStateFlow<AlbumWithSongs?>(null)
    val currentAlbum: StateFlow<AlbumWithSongs?> = _currentAlbum.asStateFlow()

    private val _currentArtist = MutableStateFlow<ArtistWithAlbums?>(null)
    val currentArtist: StateFlow<ArtistWithAlbums?> = _currentArtist.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _currentPlaylist = MutableStateFlow<PlaylistWithSongs?>(null)
    val currentPlaylist: StateFlow<PlaylistWithSongs?> = _currentPlaylist.asStateFlow()

    // ---- Search ----
    private val _searchResult = MutableStateFlow<SearchResult3?>(null)
    val searchResult: StateFlow<SearchResult3?> = _searchResult.asStateFlow()

    // One-shot search request (e.g. voice search from Home) picked up by the Library screen.
    private val _searchRequest = MutableStateFlow<String?>(null)
    val searchRequest: StateFlow<String?> = _searchRequest.asStateFlow()
    fun requestLibrarySearch(q: String) { if (q.isNotBlank()) _searchRequest.value = q.trim() }
    fun consumeSearchRequest() { _searchRequest.value = null }

    // ---- Favourites ----
    private val _starredIds = MutableStateFlow<Set<String>>(emptySet())
    val starredIds: StateFlow<Set<String>> = _starredIds.asStateFlow()
    private val _starredSongs = MutableStateFlow<List<Song>>(emptyList())
    val starredSongs: StateFlow<List<Song>> = _starredSongs.asStateFlow()
    private val _starredAlbums = MutableStateFlow<List<Album>>(emptyList())
    val starredAlbums: StateFlow<List<Album>> = _starredAlbums.asStateFlow()

    // ---- Multi-select ----
    private val _selMode = MutableStateFlow(SelMode.NONE)
    val selMode: StateFlow<SelMode> = _selMode.asStateFlow()
    private val _selSongs = MutableStateFlow<List<Song>>(emptyList())
    val selectedSongs: StateFlow<List<Song>> = _selSongs.asStateFlow()
    private val _selAlbums = MutableStateFlow<List<Album>>(emptyList())
    val selectedAlbums: StateFlow<List<Album>> = _selAlbums.asStateFlow()

    // ---- Player ----
    private var controller: MediaController? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _title = MutableStateFlow<String?>(null)
    val title: StateFlow<String?> = _title.asStateFlow()
    private val _artist = MutableStateFlow<String?>(null)
    val artist: StateFlow<String?> = _artist.asStateFlow()
    private val _artworkUri = MutableStateFlow<String?>(null)
    val artworkUri: StateFlow<String?> = _artworkUri.asStateFlow()
    private val _currentMediaId = MutableStateFlow<String?>(null)
    val currentMediaId: StateFlow<String?> = _currentMediaId.asStateFlow()
    private val _hasCurrent = MutableStateFlow(false)
    val hasCurrent: StateFlow<Boolean> = _hasCurrent.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()
    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()
    private val _queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val queue: StateFlow<List<QueueItem>> = _queue.asStateFlow()
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()
    private val _currentAlbumId = MutableStateFlow<String?>(null)
    val currentAlbumId: StateFlow<String?> = _currentAlbumId.asStateFlow()
    private val _currentArtistId = MutableStateFlow<String?>(null)
    val currentArtistId: StateFlow<String?> = _currentArtistId.asStateFlow()

    // ---- Lyrics ----
    private val _lyrics = MutableStateFlow<String?>(null)
    val lyrics: StateFlow<String?> = _lyrics.asStateFlow()
    private val _syncedLyrics = MutableStateFlow<List<Pair<Long, String>>?>(null)
    val syncedLyrics: StateFlow<List<Pair<Long, String>>?> = _syncedLyrics.asStateFlow()
    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    // ---- Sleep timer ----
    private var sleepJob: Job? = null
    private var pauseAfterTrack = false
    private val _sleepMinutesLeft = MutableStateFlow(0)
    val sleepMinutesLeft: StateFlow<Int> = _sleepMinutesLeft.asStateFlow()
    private val _sleepEndOfTrack = MutableStateFlow(false)
    val sleepEndOfTrack: StateFlow<Boolean> = _sleepEndOfTrack.asStateFlow()

    // ---- Self-update ----
    private val _update = MutableStateFlow<Updater.UpdateInfo?>(null)
    val update: StateFlow<Updater.UpdateInfo?> = _update.asStateFlow()
    private val _updateBusy = MutableStateFlow(false)
    val updateBusy: StateFlow<Boolean> = _updateBusy.asStateFlow()

    /**
     * Connection resilience itself lives in [StreamGuard] inside the playback service,
     * because playback outlives this ViewModel. Here we only mirror what it reports.
     */
    val reconnecting: StateFlow<Boolean> = PlaybackHealth.reconnecting

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { _isPlaying.value = isPlaying; savePlaybackSnapshot() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { updateNowPlaying() }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (pauseAfterTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                controller?.pause()
                pauseAfterTrack = false
                _sleepEndOfTrack.value = false
            }
            updateNowPlaying()
            rebuildQueue()
            prefetchLyrics()
            karaokeLoadLyrics()
            mediaItem?.mediaId?.let { scrobble(it) }
            if (_radio.value) maybeExtendRadio()
        }
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            rebuildQueue()
        }
        override fun onPlaybackStateChanged(playbackState: Int) { updateNowPlaying() }
        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
            _speed.value = playbackParameters.speed
        }
        override fun onRepeatModeChanged(repeatMode: Int) { _repeatMode.value = repeatMode }
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffle.value = shuffleModeEnabled
        }
    }

    init {
        Subsonic.loadFrom(prefs)
        connectController()
        if (_loggedIn.value) refreshAll()
        maybeScanDevice()
        checkForUpdate()
        // Surface anything the service-side guard gave up on through the normal error UI.
        viewModelScope.launch {
            PlaybackHealth.problem.collect { message ->
                if (message != null) { _error.value = message; PlaybackHealth.consumeProblem() }
            }
        }
        viewModelScope.launch {
            NetworkMonitor.online(getApplication<Application>()).collect { up ->
                val wasOffline = !_online.value
                _online.value = up
                // Coming back online — quietly recover content that failed while down.
                // (Recovering *playback* is StreamGuard's job, in the service.)
                if (up && wasOffline && _loggedIn.value) refreshAll()
            }
        }
    }

    private fun maybeScanDevice() {
        val ctx = getApplication<Application>()
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) scanDevice()
    }

    fun checkForUpdate() {
        viewModelScope.launch { _update.value = Updater.check(BuildConfig.VERSION_NAME) }
    }
    fun dismissUpdate() { _update.value = null }
    fun downloadAndInstallUpdate() {
        val info = _update.value ?: return
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            _updateBusy.value = true
            val file = Updater.download(ctx, info.apkUrl)
            _updateBusy.value = false
            if (file != null) Updater.install(ctx, file) else _error.value = "Update download failed"
        }
    }

    fun consumeToast() { _toast.value = null }

    // ---- Resume last session ----
    private val _resumeEnabled = MutableStateFlow(prefs.resumeEnabled)
    val resumeEnabled: StateFlow<Boolean> = _resumeEnabled.asStateFlow()
    fun setResumeEnabled(v: Boolean) { prefs.resumeEnabled = v; _resumeEnabled.value = v }

    private val _autoplayOnStart = MutableStateFlow(prefs.autoplayOnStart)
    val autoplayOnStart: StateFlow<Boolean> = _autoplayOnStart.asStateFlow()
    fun setAutoplayOnStart(v: Boolean) { prefs.autoplayOnStart = v; _autoplayOnStart.value = v }

    private val _btAutoplay = MutableStateFlow(prefs.btAutoplay)
    val btAutoplay: StateFlow<Boolean> = _btAutoplay.asStateFlow()
    fun setBtAutoplay(v: Boolean) { prefs.btAutoplay = v; _btAutoplay.value = v }

    private val _autoLyrics = MutableStateFlow(prefs.autoLyrics)
    val autoLyrics: StateFlow<Boolean> = _autoLyrics.asStateFlow()
    fun setAutoLyrics(v: Boolean) { prefs.autoLyrics = v; _autoLyrics.value = v }

    private val _preload = MutableStateFlow(prefs.preloadEnabled)
    val preloadEnabled: StateFlow<Boolean> = _preload.asStateFlow()
    fun setPreloadEnabled(v: Boolean) {
        prefs.preloadEnabled = v; _preload.value = v
        StreamGuard.active?.refreshPreload()
    }

    private val _carKaraoke = MutableStateFlow(prefs.carKaraoke)
    val carKaraoke: StateFlow<Boolean> = _carKaraoke.asStateFlow()
    fun setCarKaraoke(v: Boolean) {
        prefs.carKaraoke = v; _carKaraoke.value = v
        if (v) karaokeLoadLyrics() else restoreRealTitles()
    }

    private fun savePlaybackSnapshot() {
        val c = controller ?: return
        val n = c.mediaItemCount
        if (n == 0) { prefs.lastPlaybackJson = null; return }
        val tracks = (0 until n).map { i ->
            val mi = c.getMediaItemAt(i)
            SavedTrack(
                mediaId = mi.mediaId,
                uri = mi.localConfiguration?.uri?.toString() ?: "",
                // Karaoke rewrites the live title with lyric lines — persist the real one,
                // or the restored queue (and the next lyricsKey) is poisoned with a lyric.
                title = mi.mediaMetadata.extras?.getString("origTitle")
                    ?: mi.mediaMetadata.title?.toString(),
                artist = mi.mediaMetadata.artist?.toString(),
                artUri = mi.mediaMetadata.artworkUri?.toString()
            )
        }.filter { it.uri.isNotBlank() }
        if (tracks.isEmpty()) return
        val snap = PlaybackSnapshot(
            tracks = tracks,
            index = c.currentMediaItemIndex.coerceIn(0, tracks.lastIndex),
            positionMs = c.currentPosition.coerceAtLeast(0),
            wasPlaying = c.isPlaying
        )
        prefs.lastPlaybackJson = aiGson.toJson(snap)
    }

    private fun restorePlaybackSnapshot() {
        // Read straight from prefs, not the StateFlows: this runs from the controller
        // callback, which can land before those properties are constructed.
        val autoplay = prefs.autoplayOnStart
        if (!prefs.resumeEnabled && !autoplay) return
        val c = controller ?: return
        if (c.mediaItemCount > 0) {
            // The service outlived the UI and still holds the queue — just un-pause it.
            if (autoplay && !c.isPlaying) c.play()
            return
        }
        val snap = prefs.loadPlaybackSnapshot(aiGson) ?: return
        val items = snap.toMediaItems()
        if (items.isEmpty()) return
        c.setMediaItems(items, snap.index.coerceIn(0, items.lastIndex), snap.positionMs)
        c.prepare()
        if (autoplay || snap.wasPlaying) c.play()
    }

    private fun connectController() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            controller = future.get()
            controller?.addListener(playerListener)
            restorePlaybackSnapshot()
            updateNowPlaying()
            rebuildQueue()
            StreamGuard.active?.refreshPreload()
            startPositionPoller()
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun startPositionPoller() {
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                controller?.let { c ->
                    _position.value = c.currentPosition.coerceAtLeast(0)
                    val d = c.duration
                    _duration.value = if (d > 0) d else 0
                }
                if (_carKaraoke.value) pushKaraokeLine()
                if (++tick % 10 == 0) savePlaybackSnapshot() // persist ~every 5s
                delay(500)
            }
        }
    }

    // ---- Car karaoke: the head unit's only text channel is AVRCP track metadata, so in
    // karaoke mode the current synced lyric line is pushed as the *title*. The real title
    // is stashed in the item's extras ("origTitle") so the in-app UI never shows lyric
    // lines as the song name.
    private var lastKaraokeLine: String? = null
    private var lastKaraokeMediaId: String? = null

    /**
     * Karaoke mode fetches its own lyrics per track — it must not depend on the lyric
     * screen having been opened, and must never time Song A's lines against Song B.
     * [syncedLyricsFor] marks which mediaId the published _syncedLyrics belong to.
     */
    private fun karaokeLoadLyrics() {
        if (!_carKaraoke.value) return
        val mid = controller?.currentMediaItem?.mediaId ?: return
        if (mid.startsWith("radio:")) return
        viewModelScope.launch {
            val key = lyricsKey() ?: return@launch
            val cached = lyricsCache[key] ?: fetchLyrics().also { cacheIfFound(key, it) }
            // Only publish if this is still the playing track (fetch may take seconds).
            if (controller?.currentMediaItem?.mediaId == mid) {
                _lyrics.value = cached.plain
                _syncedLyrics.value = cached.synced
                syncedLyricsFor = mid
            }
        }
    }

    private fun pushKaraokeLine() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        if (item.mediaId.startsWith("radio:")) return
        if (item.mediaId != lastKaraokeMediaId) { lastKaraokeMediaId = item.mediaId; lastKaraokeLine = null }
        if (syncedLyricsFor != item.mediaId) return // stale lyrics from a previous track
        val synced = _syncedLyrics.value ?: return
        if (synced.isEmpty() || !c.isPlaying) return
        val pos = _position.value
        val line = synced.lastOrNull { it.first <= pos }?.second?.takeIf { it.isNotBlank() }
            ?: item.mediaMetadata.extras?.getString("origTitle")
            ?: return
        if (line == lastKaraokeLine) return
        lastKaraokeLine = line
        val md = item.mediaMetadata
        val extras = android.os.Bundle(md.extras ?: android.os.Bundle()).apply {
            if (getString("origTitle") == null) putString("origTitle", md.title?.toString())
        }
        val updated = item.buildUpon()
            .setMediaMetadata(md.buildUpon().setTitle(line).setExtras(extras).build())
            .build()
        // Same URI + same mediaId: media3 swaps metadata without interrupting playback.
        runCatching { c.replaceMediaItem(c.currentMediaItemIndex, updated) }
    }

    private var syncedLyricsFor: String? = null

    /** Turn titles back into real song names — every item karaoke touched, not just the current. */
    private fun restoreRealTitles() {
        lastKaraokeLine = null
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            val md = item.mediaMetadata
            val orig = md.extras?.getString("origTitle") ?: continue
            if (orig == md.title?.toString()) continue
            val updated = item.buildUpon()
                .setMediaMetadata(md.buildUpon().setTitle(orig).build())
                .build()
            runCatching { c.replaceMediaItem(i, updated) }
        }
    }

    private fun updateNowPlaying() {
        val c = controller ?: return
        val md = c.mediaMetadata
        // Car karaoke rewrites the AVRCP title with lyric lines; the app UI shows the real one.
        _title.value = md.extras?.getString("origTitle") ?: md.title?.toString()
        _artist.value = md.artist?.toString()
        _albumTitle.value = md.albumTitle?.toString()
        val art = md.artworkUri?.toString()
        _artworkUri.value = art
        if (art != lastArtUrl) {
            lastArtUrl = art
            extractArtColors(art)
        }
        val mid = c.currentMediaItem?.mediaId
        _currentMediaId.value = mid
        _isLive.value = mid?.startsWith("radio:") == true
        _hasCurrent.value = c.currentMediaItem != null
        _isPlaying.value = c.isPlaying
        _repeatMode.value = c.repeatMode
        _shuffle.value = c.shuffleModeEnabled
        _speed.value = c.playbackParameters.speed
        val extras = md.extras
        _currentAlbumId.value = extras?.getString("albumId")
        _currentArtistId.value = extras?.getString("artistId")
        val d = c.duration
        _duration.value = if (d > 0) d else 0
    }

    private fun rebuildQueue() {
        val c = controller ?: return
        val count = c.mediaItemCount
        val list = ArrayList<QueueItem>(count)
        for (i in 0 until count) {
            val mi = c.getMediaItemAt(i)
            list.add(
                QueueItem(
                    index = i,
                    mediaId = mi.mediaId,
                    title = mi.mediaMetadata.extras?.getString("origTitle")
                        ?: mi.mediaMetadata.title?.toString() ?: "Unknown",
                    artist = mi.mediaMetadata.artist?.toString() ?: "",
                    artworkUri = mi.mediaMetadata.artworkUri?.toString(),
                    isCurrent = i == c.currentMediaItemIndex
                )
            )
        }
        _queue.value = list
    }

    // ---- Auth ----
    fun login(url: String, user: String, pass: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                Subsonic.configure(url, user, pass)
                val resp = Subsonic.api?.ping()?.response
                if (resp?.status == "ok") {
                    prefs.save(url.trim().trimEnd('/'), user.trim(), pass)
                    _loggedIn.value = true
                    refreshAll()
                    onResult(true, null)
                } else onResult(false, resp?.error?.message ?: "Login failed")
            } catch (e: Exception) {
                onResult(false, e.message ?: "Cannot reach server")
            }
        }
    }

    fun logout() {
        controller?.stop()
        controller?.clearMediaItems()
        prefs.clear()
        Subsonic.reset()
        _albums.value = emptyList(); _artists.value = emptyList()
        _newest.value = emptyList(); _recent.value = emptyList()
        _frequent.value = emptyList(); _randomAlbums.value = emptyList()
        _playlists.value = emptyList(); _currentAlbum.value = null
        _currentArtist.value = null; _currentPlaylist.value = null
        _searchResult.value = null; _starredIds.value = emptySet()
        _starredSongs.value = emptyList(); _starredAlbums.value = emptyList()
        // Without this, signing into a different server shows the old account's catalogue:
        // loadShelf() short-circuits on a non-empty shelf.
        _shelf.value = emptyList(); _playedIds.value = emptySet(); _recentIds.value = emptySet()
        _blindPick.value = null
        clearSelection()
        _hasCurrent.value = false
        _loggedIn.value = false
    }

    fun refreshAll() {
        loadLibrary(); loadHome(); loadArtists(); loadGenres(); loadPlaylists(); loadStarred()
        refreshCacheSize()
    }

    // ---- Loaders ----
    // Raw server lists kept separately so device music can be blended in and re-blended after a scan.
    private var srvAlbums: List<Album> = emptyList()
    private var srvNewest: List<Album> = emptyList()
    private var srvRandom: List<Album> = emptyList()

    /** Device songs grouped into albums so they flow through the same UI as server albums. */
    private fun localAlbums(): List<Album> =
        _localSongs.value
            .filter { !it.album.isNullOrBlank() }
            .groupBy { it.albumId ?: it.album }
            .mapNotNull { (_, s) ->
                val f = s.first()
                Album(
                    id = "localalbum-${f.albumId}",
                    name = f.album,
                    artist = f.artist,
                    coverArt = f.coverArt,
                    songCount = s.size
                )
            }

    /** Recompute the blended album lists (server + device). */
    private fun reblend() {
        val la = localAlbums()
        _albums.value = srvAlbums + la
        _newest.value = la + srvNewest
        _randomAlbums.value = (srvRandom + la).shuffled()
    }

    fun loadLibrary() = viewModelScope.launch {
        _loading.value = true; _error.value = null
        try {
            val resp = Subsonic.api?.getAlbumList2(_albumSort.value, 500)?.response
            if (resp?.error != null) _error.value = resp.error.message
            else srvAlbums = resp?.albumList2?.album ?: emptyList()
            reblend()
        } catch (e: Exception) { _error.value = e.message ?: "Network error" }
        finally { _loading.value = false }
    }

    fun setAlbumSort(type: String) {
        if (_albumSort.value == type) return
        _albumSort.value = type
        loadLibrary()
    }

    fun loadGenres() = viewModelScope.launch {
        try { _genres.value = Subsonic.api?.getGenres()?.response?.genres?.genre ?: emptyList() }
        catch (_: Exception) { }
    }

    fun loadGenreAlbums(name: String) = viewModelScope.launch {
        try {
            _genreAlbums.value = Subsonic.api?.getAlbumList2("byGenre", 500, 0, name)?.response?.albumList2?.album ?: emptyList()
        } catch (_: Exception) { _genreAlbums.value = emptyList() }
    }

    // ---- Discover: the shop floor ----
    //
    // Discovery in a personal library is archaeology, not recommendation: the point is
    // surfacing what he already owns and never plays. All of it is computed from the
    // catalogue on the device, so the crates keep working when the AI is unreachable.

    companion object {
        private const val SHELF_PAGE = 500
        private const val SHELF_MAX = 10_000
        /** How many albums to resolve when a whole shelf is played. */
        private const val PLAYED_PROBE = 500
        /** Fetch album tracklists this many at a time — serial over 60 albums is ~30s. */
        private const val GATHER_PARALLELISM = 6
        /** A crate can hold thousands of albums; don't try to queue all of them. */
        const val CRATE_PLAY_MAX = 60
    }

    /** The whole catalogue, paged in — the Library's list is capped at 500 and sorted. */
    private val _shelf = MutableStateFlow<List<Album>>(emptyList())
    val shelf: StateFlow<List<Album>> = _shelf.asStateFlow()
    private val _shelfLoading = MutableStateFlow(false)
    val shelfLoading: StateFlow<Boolean> = _shelfLoading.asStateFlow()

    /** Separate from [_shelfLoading]: building a queue must not cancel the shelf spinner. */
    private val _queueBuilding = MutableStateFlow(false)
    val queueBuilding: StateFlow<Boolean> = _queueBuilding.asStateFlow()

    /**
     * Ids the server says have been played, probed deep (500 each) rather than relying on
     * the Home rails' 20 — otherwise "rarely played" means "not in your last 20", which
     * on a big library is almost everything and the crate stops meaning anything.
     */
    private val _playedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _recentIds = MutableStateFlow<Set<String>>(emptySet())

    /**
     * "Fresh" for the Forgotten crate = the last few dozen albums, NOT the 500-deep probe:
     * against 500, everything ever played counts as fresh and the crate is permanently empty.
     */
    private val _freshIds = MutableStateFlow<Set<String>>(emptySet())

    fun loadShelf(force: Boolean = false) = viewModelScope.launch {
        if (_shelfLoading.value) return@launch
        if (!force && _shelf.value.isNotEmpty()) return@launch
        _shelfLoading.value = true
        try {
            val out = ArrayList<Album>()
            var offset = 0
            while (offset < SHELF_MAX) {
                val page = Subsonic.api
                    ?.getAlbumList2("alphabeticalByName", SHELF_PAGE, offset)
                    ?.response?.albumList2?.album ?: emptyList()
                out.addAll(page)
                if (page.size < SHELF_PAGE) break
                offset += SHELF_PAGE
            }
            // A rescan between pages can hand us the same album twice; a duplicate key
            // is a hard crash in the crate grid.
            _shelf.value = (out + localAlbums()).distinctBy { it.id }

            val recent = Subsonic.api?.getAlbumList2("recent", PLAYED_PROBE)
                ?.response?.albumList2?.album ?: emptyList()
            val frequent = Subsonic.api?.getAlbumList2("frequent", PLAYED_PROBE)
                ?.response?.albumList2?.album ?: emptyList()
            _recentIds.value = recent.map { it.id }.toSet()
            _freshIds.value = recent.take(40).map { it.id }.toSet()
            _playedIds.value = _recentIds.value + frequent.map { it.id }.toSet()
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not read your library"
        } finally { _shelfLoading.value = false }
    }

    /**
     * Albums he owns but has never pressed play on. Navidrome reports playCount, which
     * makes this exact; on a server that doesn't, we fall back to the played probe —
     * approximate, so the UI calls it "rarely played".
     *
     * Navidrome omits playCount entirely (null) for albums never played — the very albums
     * this crate is for — so the exact branch must read null as zero. Device albums also
     * carry no playCount, and null-as-zero would flag every one of them; they're excluded
     * from the exact branch instead.
     */
    val neverPlayed: StateFlow<List<Album>> =
        combine(_shelf, _playedIds) { shelf, played ->
            if (shelf.isEmpty()) return@combine emptyList()
            if (shelf.any { it.playCount != null })
                shelf.filter { !it.id.startsWith("localalbum-") && (it.playCount ?: 0) == 0 }
            else shelf.filter { it.id !in played }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True when the server gave us real play counts, so the UI can say "never" honestly. */
    val playCountsExact: StateFlow<Boolean> =
        _shelf.map { s -> s.any { it.playCount != null } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * Starred once, not touched since — the ones worth an apology. People star songs far
     * more often than whole albums, so the albums those starred songs live on count too;
     * only relying on starred *albums* left this crate permanently empty.
     */
    val forgottenFavourites: StateFlow<List<Album>> =
        combine(_starredAlbums, _starredSongs, _freshIds) { starred, songs, fresh ->
            val fromSongs = songs.asSequence()
                .filter { it.localUri == null && !it.albumId.isNullOrBlank() }
                .groupBy { it.albumId!! }
                .map { (albumId, group) ->
                    val f = group.first()
                    Album(
                        id = albumId, name = f.album, artist = f.artist, artistId = f.artistId,
                        coverArt = f.coverArt, year = f.year, genre = f.genre
                    )
                }
            (starred + fromSongs).distinctBy { it.id }.filter { it.id !in fresh }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Decade -> albums, newest decade first. Albums with no year are left out. */
    val decades: StateFlow<List<Pair<Int, List<Album>>>> =
        _shelf.map { shelf ->
            shelf.filter { (it.year ?: 0) > 1000 }
                .groupBy { (it.year!! / 10) * 10 }
                .toList()
                .sortedByDescending { it.first }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * What this listener actually plays, as weights: artists, genres and decades scored
     * from most-played + recently-played + starred. Drives the shopkeeper's taste.
     */
    data class Taste(
        val artists: Map<String, Int> = emptyMap(),
        val genres: Map<String, Int> = emptyMap(),
        val decades: Map<Int, Int> = emptyMap()
    ) {
        val isEmpty get() = artists.isEmpty() && genres.isEmpty()
        /** Short human summary for AI prompts: "Faith No More, Pearl Jam; alternative, grunge; the 90s". */
        fun summary(): String? {
            if (isEmpty) return null
            val a = artists.entries.sortedByDescending { it.value }.take(6).joinToString(", ") { it.key }
            val g = genres.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { it.key }
            val d = decades.entries.sortedByDescending { it.value }.take(2).joinToString(" and ") { "the ${it.key}s" }
            return listOf(a, g, d).filter { it.isNotBlank() }.joinToString("; ")
        }
    }

    val taste: StateFlow<Taste> =
        combine(_frequent, _recent, _starredSongs, _starredAlbums) { freq, rec, ss, sa ->
            val artists = HashMap<String, Int>(); val genres = HashMap<String, Int>(); val decades = HashMap<Int, Int>()
            fun add(artist: String?, genre: String?, year: Int?, w: Int) {
                artist?.takeIf { it.isNotBlank() }?.let { artists.merge(it, w, Int::plus) }
                genre?.takeIf { it.isNotBlank() }?.let { genres.merge(it, w, Int::plus) }
                year?.takeIf { it > 1000 }?.let { decades.merge((it / 10) * 10, w, Int::plus) }
            }
            freq.forEach { add(it.artist, it.genre, it.year, 3) }   // most-played weighs most
            rec.forEach { add(it.artist, it.genre, it.year, 2) }
            sa.forEach { add(it.artist, it.genre, it.year, 2) }
            ss.forEach { add(it.artist, it.genre, it.year, 1) }
            Taste(artists, genres, decades)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, Taste())

    /**
     * One album, the same one all day. A pick that changes every time Home is opened is
     * not a recommendation, it's a shuffle — so it's seeded on the date. With listening
     * history the pick leans toward the listener's taste (adjacent artists/genres/decades,
     * favouring records they HAVEN'T played); one day in three it deliberately explores
     * outside the pattern so the shop never becomes an echo chamber.
     */
    val tonightsPick: StateFlow<Album?> =
        combine(_shelf, neverPlayed, taste) { shelf, unplayed, t ->
            val pool = unplayed.ifEmpty { shelf }
            if (pool.isEmpty()) return@combine null
            val day = LocalDate.now().toEpochDay()
            if (t.isEmpty) return@combine pool[day.mod(pool.size)]
            val explore = day.mod(3L) == 2L
            fun score(a: Album): Int =
                (t.artists[a.artist] ?: 0) * 3 +
                    (t.genres[a.genre] ?: 0) * 2 +
                    (a.year?.takeIf { it > 1000 }?.let { t.decades[(it / 10) * 10] } ?: 0)
            val ranked = pool.sortedByDescending(::score)
            val slice = if (explore) ranked.filter { score(it) == 0 }.ifEmpty { ranked }
            else ranked.take(40)
            slice[day.mod(slice.size)]
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Why tonight's pick is on the counter — shown under the card, derived, no AI needed. */
    val tonightsPickReason: StateFlow<String?> =
        combine(tonightsPick, taste) { pick, t ->
            if (pick == null || t.isEmpty) return@combine null
            val aw = t.artists[pick.artist] ?: 0
            val gw = t.genres[pick.genre] ?: 0
            val dec = pick.year?.takeIf { it > 1000 }?.let { (it / 10) * 10 }
            val dw = dec?.let { t.decades[it] } ?: 0
            when {
                aw > 0 -> "Because ${pick.artist} keeps landing on your turntable"
                gw > 0 -> "Because you've been deep in ${pick.genre?.lowercase()} lately"
                dw > 0 -> "Because the ${dec}s keep pulling you back"
                else -> "Something different — trust the counter"
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _blindPick = MutableStateFlow<Album?>(null)
    val blindPick: StateFlow<Album?> = _blindPick.asStateFlow()
    fun rollBlindPick() {
        val pool = neverPlayed.value.ifEmpty { _shelf.value }
        _blindPick.value = pool.randomOrNull()
    }
    fun clearBlindPick() { _blindPick.value = null }

    /**
     * Everything on one shelf, queued. Fetched a few albums at a time — serially this is
     * one round-trip per album and takes half a minute over a remote link — and one dead
     * album drops itself instead of throwing away the other 59.
     */
    private suspend fun gatherSongsResilient(albums: List<Album>): List<Song> = coroutineScope {
        albums.chunked(GATHER_PARALLELISM).flatMap { chunk ->
            chunk.map { al ->
                async {
                    try {
                        if (al.id.startsWith("localalbum-")) {
                            val albumId = al.id.removePrefix("localalbum-")
                            _localSongs.value.filter { it.albumId == albumId }
                        } else Subsonic.api?.getAlbum(al.id)?.response?.album?.song ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }
    }

    fun playAlbums(albums: List<Album>, shuffle: Boolean) = viewModelScope.launch {
        if (albums.isEmpty()) return@launch
        if (_queueBuilding.value) return@launch
        _queueBuilding.value = true
        try {
            // Take AFTER shuffling: the shelf is alphabetical, so taking first would make
            // "shuffle" always pick over the same 60 albums at the front of the alphabet.
            val picked = if (shuffle) albums.shuffled().take(CRATE_PLAY_MAX)
            else albums.take(CRATE_PLAY_MAX)
            val songs = gatherSongsResilient(picked)
            if (songs.isEmpty()) { _toast.value = "Nothing playable on this shelf"; return@launch }
            if (shuffle) shufflePlay(songs) else playSongs(songs, 0)
            _toast.value = "Playing ${songs.size} tracks"
        } catch (e: Exception) {
            _error.value = e.message ?: "Could not build a queue from this shelf"
        } finally { _queueBuilding.value = false }
    }

    fun shuffleLibrary() = viewModelScope.launch {
        val server = Subsonic.api?.getRandomSongs(150)?.response?.randomSongs?.song ?: emptyList()
        // Include device songs in the shuffle pool so they're part of the whole fleet.
        val pool = (server + _localSongs.value).shuffled()
        shufflePlay(pool)
    }

    // ---- Appearance / cache ----
    fun setDynamicColor(v: Boolean) { prefs.dynamicColor = v; _dynamicColor.value = v }
    fun refreshCacheSize() { _cacheBytes.value = PlayerCache.sizeBytes() }
    fun clearCache() { PlayerCache.clear(); refreshCacheSize(); _toast.value = "Cache cleared" }

    // ---- Add to playlist ----
    fun openPlaylistPicker(songs: List<Song>) { if (songs.isNotEmpty()) _playlistPickerSongs.value = songs }
    fun dismissPlaylistPicker() { _playlistPickerSongs.value = null }
    fun openPlaylistPickerFromSelection() = viewModelScope.launch {
        val songs = resolveSelection()
        clearSelection()
        if (songs.isNotEmpty()) _playlistPickerSongs.value = songs
    }
    fun addPickerSongsToPlaylist(playlistId: String) {
        val songs = _playlistPickerSongs.value ?: return
        _playlistPickerSongs.value = null
        viewModelScope.launch {
            try {
                Subsonic.api?.updatePlaylist(playlistId, songs.map { it.id })
                loadPlaylists()
                _toast.value = "Added ${songs.size} to playlist"
            } catch (_: Exception) { _error.value = "Could not add to playlist" }
        }
    }
    fun createPlaylistWithPickerSongs(name: String) {
        val songs = _playlistPickerSongs.value ?: return
        _playlistPickerSongs.value = null
        viewModelScope.launch {
            try {
                Subsonic.api?.createPlaylist(name.trim(), songs.map { it.id })
                loadPlaylists()
                _toast.value = "Created \"$name\""
            } catch (_: Exception) { _error.value = "Could not create playlist" }
        }
    }

    fun loadHome() = viewModelScope.launch {
        try {
            srvNewest = Subsonic.api?.getAlbumList2("newest", 20)?.response?.albumList2?.album ?: emptyList()
            _recent.value = Subsonic.api?.getAlbumList2("recent", 20)?.response?.albumList2?.album ?: emptyList()
            _frequent.value = Subsonic.api?.getAlbumList2("frequent", 20)?.response?.albumList2?.album ?: emptyList()
            srvRandom = Subsonic.api?.getAlbumList2("random", 20)?.response?.albumList2?.album ?: emptyList()
            reblend()
        } catch (_: Exception) { }
    }

    fun loadArtists() = viewModelScope.launch {
        try {
            val indexes = Subsonic.api?.getArtists()?.response?.artists?.index ?: emptyList()
            _artists.value = indexes.flatMap { it.artist ?: emptyList() }
        } catch (_: Exception) { }
    }

    fun loadPlaylists() = viewModelScope.launch {
        try {
            _playlists.value = Subsonic.api?.getPlaylists()?.response?.playlists?.playlist ?: emptyList()
        } catch (_: Exception) { }
    }

    fun loadStarred() = viewModelScope.launch {
        try {
            val s = Subsonic.api?.getStarred2()?.response?.starred2
            val songs = s?.song ?: emptyList()
            _starredSongs.value = songs
            _starredAlbums.value = s?.album ?: emptyList()
            val ids = HashSet<String>()
            songs.forEach { ids.add(it.id) }
            s?.album?.forEach { ids.add(it.id) }
            s?.artist?.forEach { ids.add(it.id) }
            _starredIds.value = ids
        } catch (_: Exception) { }
    }

    fun openAlbum(id: String) = viewModelScope.launch {
        _error.value = null
        if (id.startsWith("localalbum-")) {
            val albumId = id.removePrefix("localalbum-")
            val songs = _localSongs.value.filter { it.albumId == albumId }
            val f = songs.firstOrNull()
            _currentAlbum.value = AlbumWithSongs(
                id = id, name = f?.album, artist = f?.artist,
                coverArt = f?.coverArt, songCount = songs.size, song = songs
            )
            return@launch
        }
        try { _currentAlbum.value = Subsonic.api?.getAlbum(id)?.response?.album }
        catch (e: Exception) { _error.value = e.message ?: "Could not load album" }
    }

    fun openArtist(id: String) = viewModelScope.launch {
        _error.value = null
        try { _currentArtist.value = Subsonic.api?.getArtist(id)?.response?.artist }
        catch (e: Exception) { _error.value = e.message ?: "Could not load artist" }
    }

    fun openPlaylist(id: String) = viewModelScope.launch {
        _error.value = null
        try { _currentPlaylist.value = Subsonic.api?.getPlaylist(id)?.response?.playlist }
        catch (e: Exception) { _error.value = e.message ?: "Could not load playlist" }
    }

    fun search(query: String) {
        if (query.isBlank()) { _searchResult.value = null; return }
        viewModelScope.launch {
            try {
                val server = Subsonic.api?.search3(query)?.response?.searchResult3
                // Device songs are part of the whole library — merge matching ones in.
                val q = query.trim()
                val local = _localSongs.value.filter {
                    (it.title?.contains(q, true) == true) ||
                        (it.artist?.contains(q, true) == true) ||
                        (it.album?.contains(q, true) == true)
                }.take(40)
                _searchResult.value = when {
                    server == null && local.isEmpty() -> null
                    server == null -> SearchResult3(null, null, local)
                    else -> server.copy(song = (server.song ?: emptyList()) + local)
                }
            } catch (e: Exception) { _error.value = e.message ?: "Search failed" }
        }
    }

    // ---- Favourites ----
    fun toggleStar(id: String) {
        val currentlyStarred = _starredIds.value.contains(id)
        _starredIds.value = _starredIds.value.toMutableSet().apply {
            if (currentlyStarred) remove(id) else add(id)
        }
        viewModelScope.launch {
            try {
                if (currentlyStarred) Subsonic.api?.unstar(id) else Subsonic.api?.star(id)
                loadStarred()
            } catch (_: Exception) { }
        }
    }

    // ---- Selection ----
    fun toggleSongSelection(song: Song) {
        if (_selMode.value == SelMode.ALBUMS) clearSelection()
        val cur = _selSongs.value
        val next = if (cur.any { it.id == song.id }) cur.filter { it.id != song.id } else cur + song
        _selSongs.value = next
        _selMode.value = if (next.isEmpty()) SelMode.NONE else SelMode.SONGS
    }

    fun toggleAlbumSelection(album: Album) {
        if (_selMode.value == SelMode.SONGS) clearSelection()
        val cur = _selAlbums.value
        val next = if (cur.any { it.id == album.id }) cur.filter { it.id != album.id } else cur + album
        _selAlbums.value = next
        _selMode.value = if (next.isEmpty()) SelMode.NONE else SelMode.ALBUMS
    }

    fun isSongSelected(id: String) = _selSongs.value.any { it.id == id }
    fun isAlbumSelected(id: String) = _selAlbums.value.any { it.id == id }

    fun clearSelection() {
        _selMode.value = SelMode.NONE
        _selSongs.value = emptyList()
        _selAlbums.value = emptyList()
    }

    private suspend fun resolveSelection(): List<Song> = when (_selMode.value) {
        SelMode.SONGS -> _selSongs.value
        SelMode.ALBUMS -> _selAlbums.value.flatMap { al ->
            Subsonic.api?.getAlbum(al.id)?.response?.album?.song ?: emptyList()
        }
        SelMode.NONE -> emptyList()
    }

    fun playSelection(shuffle: Boolean) = viewModelScope.launch {
        val songs = resolveSelection()
        clearSelection()
        if (shuffle) shufflePlay(songs) else playSongs(songs, 0)
    }

    fun queueSelection() = viewModelScope.launch {
        val songs = resolveSelection()
        val n = songs.size
        clearSelection()
        addToQueue(songs)
        _toast.value = "Added $n to queue"
    }

    fun playNextSelection() = viewModelScope.launch {
        val songs = resolveSelection()
        clearSelection()
        playNext(songs)
    }

    // ---- Bulk "play all" ----
    private suspend fun songsOfArtist(artistId: String): List<Song> {
        val albums = Subsonic.api?.getArtist(artistId)?.response?.artist?.album ?: emptyList()
        return albums.flatMap { Subsonic.api?.getAlbum(it.id)?.response?.album?.song ?: emptyList() }
    }

    fun playArtist(artistId: String, shuffle: Boolean) = viewModelScope.launch {
        val songs = songsOfArtist(artistId)
        if (shuffle) shufflePlay(songs) else playSongs(songs, 0)
    }

    fun playSearchSongs(shuffle: Boolean) {
        val songs = _searchResult.value?.song ?: return
        if (shuffle) shufflePlay(songs) else playSongs(songs, 0)
    }

    private val _searchPlayBusy = MutableStateFlow(false)
    val searchPlayBusy: StateFlow<Boolean> = _searchPlayBusy.asStateFlow()

    /**
     * Play *everything* the search turned up, not just the song-title matches.
     * Searching an artist ("depeche mode") mostly returns artists and albums — their
     * tracks were previously unreachable in bulk, so you had to open one and pick a song.
     * Collects: matching songs + every track of the matching albums + every album of the
     * matching artists, de-duplicated, in that order.
     */
    fun playAllSearchResults(shuffle: Boolean) {
        val res = _searchResult.value ?: return
        if (_searchPlayBusy.value) return
        viewModelScope.launch {
            _searchPlayBusy.value = true
            try {
                val albums = LinkedHashMap<String, Album>()
                (res.album ?: emptyList()).forEach { albums[it.id] = it }
                for (ar in res.artist ?: emptyList()) {
                    val arAlbums = try {
                        Subsonic.api?.getArtist(ar.id)?.response?.artist?.album
                    } catch (_: Exception) { null } ?: emptyList()
                    arAlbums.forEach { al -> albums.putIfAbsent(al.id, al) }
                }
                val out = LinkedHashMap<String, Song>()
                (res.song ?: emptyList()).forEach { out[it.id] = it }
                gatherSongs(albums.values.toList()).forEach { s -> out.putIfAbsent(s.id, s) }
                val songs = out.values.toList()
                if (songs.isEmpty()) { _toast.value = "Nothing playable in these results"; return@launch }
                if (shuffle) shufflePlay(songs) else playSongs(songs, 0)
                _toast.value = "Playing ${songs.size} tracks"
            } catch (e: Exception) {
                _error.value = e.message ?: "Could not build a queue from these results"
            } finally { _searchPlayBusy.value = false }
        }
    }

    // ---- Playback ----
    fun playSongs(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        _currentStation.value = null
        val items = songs.map { toMediaItem(it) }
        c.shuffleModeEnabled = false
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare(); c.play()
    }

    fun shufflePlay(songs: List<Song>) {
        if (songs.isEmpty()) return
        val c = controller ?: return
        _currentStation.value = null
        val items = songs.map { toMediaItem(it) }
        c.setMediaItems(items, 0, 0L)
        c.shuffleModeEnabled = true
        c.prepare(); c.play()
    }

    fun addToQueue(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val items = songs.map { toMediaItem(it) }
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() }
        else c.addMediaItems(items)
        rebuildQueue()
        StreamGuard.active?.refreshPreload()
    }

    fun playNext(songs: List<Song>) {
        val c = controller ?: return
        if (songs.isEmpty()) return
        val items = songs.map { toMediaItem(it) }
        if (c.mediaItemCount == 0) { c.setMediaItems(items); c.prepare(); c.play() }
        else c.addMediaItems((c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount), items)
        rebuildQueue()
        StreamGuard.active?.refreshPreload()
    }

    fun removeFromQueue(index: Int) {
        val c = controller ?: return
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
        rebuildQueue()
        StreamGuard.active?.refreshPreload()
    }

    /**
     * A player that gave up on a dead connection sits in STATE_IDLE, where `play()` is a
     * no-op — so every transport control has to be willing to re-prepare first. Without
     * this, "tap play to retry" simply did nothing.
     */
    private fun revive(c: MediaController) {
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
    }

    fun togglePlay() {
        controller?.let {
            if (it.isPlaying) it.pause() else { StreamGuard.active?.onUserAction(); revive(it); it.play() }
        }
    }
    fun next() { controller?.let { StreamGuard.active?.onUserAction(); revive(it); it.seekToNext() } }
    fun previous() { controller?.let { StreamGuard.active?.onUserAction(); revive(it); it.seekToPrevious() } }
    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }
    fun playFromQueue(index: Int) {
        controller?.let { StreamGuard.active?.onUserAction(); revive(it); it.seekTo(index, 0L); it.play() }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        _repeatMode.value = c.repeatMode
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
        _shuffle.value = c.shuffleModeEnabled
    }

    // ---- Sleep timer ----
    fun startSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        _sleepMinutesLeft.value = minutes
        sleepJob = viewModelScope.launch {
            var left = minutes
            while (left > 0 && isActive) {
                delay(60_000); left--; _sleepMinutesLeft.value = left
            }
            if (isActive) { controller?.pause(); _sleepMinutesLeft.value = 0 }
        }
    }

    fun sleepAtEndOfTrack() {
        sleepJob?.cancel(); sleepJob = null; _sleepMinutesLeft.value = 0
        pauseAfterTrack = true
        _sleepEndOfTrack.value = true
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel(); sleepJob = null; _sleepMinutesLeft.value = 0
        pauseAfterTrack = false
        _sleepEndOfTrack.value = false
    }

    // ---- Playback speed ----
    fun setSpeed(value: Float) {
        controller?.setPlaybackSpeed(value)
        _speed.value = value
    }

    // ---- Queue reorder ----
    fun moveQueueUp(index: Int) {
        val c = controller ?: return
        if (index > 0) { c.moveMediaItem(index, index - 1); rebuildQueue() }
    }
    fun moveQueueDown(index: Int) {
        val c = controller ?: return
        if (index < c.mediaItemCount - 1) { c.moveMediaItem(index, index + 1); rebuildQueue() }
    }
    fun moveQueueBy(from: Int, steps: Int) {
        val c = controller ?: return
        if (steps == 0) return
        val to = (from + steps).coerceIn(0, c.mediaItemCount - 1)
        if (to != from) { c.moveMediaItem(from, to); rebuildQueue() }
    }

    // ---- Lyrics (cached per track + prefetched on track change so opening them is instant) ----
    private data class CachedLyrics(val plain: String?, val synced: List<Pair<Long, String>>?)
    private val lyricsCache = HashMap<String, CachedLyrics>()

    private fun lyricsKey(): String? {
        val t = _title.value ?: return null
        return "${_artist.value ?: ""}|$t".lowercase()
    }

    /**
     * Fetch lyrics for the current track — returns null fields if none. Order:
     * OpenSubsonic by-id (embedded/.lrc, immune to tag-string mismatches), then the
     * legacy artist+title endpoint, then lrclib.
     */
    private suspend fun fetchLyrics(): CachedLyrics {
        val songId = _currentMediaId.value
            ?.takeIf { !it.startsWith("local:") && !it.startsWith("radio:") }
        if (songId != null) {
            try {
                val structured = Subsonic.api?.getLyricsBySongId(songId)
                    ?.response?.lyricsList?.structuredLyrics
                val best = structured?.firstOrNull { it.synced == true && !it.line.isNullOrEmpty() }
                    ?: structured?.firstOrNull { !it.line.isNullOrEmpty() }
                if (best != null) {
                    val lines = best.line!!.mapNotNull { l -> l.value?.let { (l.start ?: 0L) to it } }
                    val plain = lines.joinToString("\n") { it.second }.trim()
                    if (plain.isNotBlank()) {
                        val synced = lines.takeIf { best.synced == true && it.size > 1 }
                        return CachedLyrics(plain, synced)
                    }
                }
            } catch (_: Exception) { }
        }
        var text = try {
            Subsonic.api?.getLyrics(_artist.value, _title.value)?.response?.lyrics?.value
        } catch (_: Exception) { null }
        var synced: List<Pair<Long, String>>? = null
        if (text.isNullOrBlank()) {
            val lrc = OnlineLyrics.best(_artist.value, _title.value, _albumTitle.value, (_duration.value / 1000).toInt())
            text = OnlineLyrics.plainFrom(lrc)
            synced = OnlineLyrics.parseSynced(lrc?.syncedLyrics).takeIf { it.isNotEmpty() }
        }
        return CachedLyrics(text, synced)
    }

    /** Only real lyrics are worth remembering — a miss may be a network blip, retry later. */
    private fun cacheIfFound(key: String?, result: CachedLyrics) {
        if (key != null && (!result.plain.isNullOrBlank() || result.synced != null)) {
            lyricsCache[key] = result
        }
    }

    /** Warm the cache in the background the moment a track starts, so the screen is instant. */
    private fun prefetchLyrics() {
        val key = lyricsKey() ?: return
        if (lyricsCache.containsKey(key)) return
        viewModelScope.launch {
            if (lyricsCache.containsKey(key)) return@launch
            cacheIfFound(key, fetchLyrics())
        }
    }

    fun loadLyrics() = viewModelScope.launch {
        val key = lyricsKey()
        // Cache hit — show immediately, no spinner, no wait.
        val cached = key?.let { lyricsCache[it] }
        if (cached != null) {
            _lyrics.value = cached.plain
            _syncedLyrics.value = cached.synced
            syncedLyricsFor = _currentMediaId.value
            _lyricsLoading.value = false
            return@launch
        }
        _lyricsLoading.value = true
        _syncedLyrics.value = null
        val result = fetchLyrics()
        cacheIfFound(key, result)
        _lyrics.value = result.plain
        _syncedLyrics.value = result.synced
        syncedLyricsFor = _currentMediaId.value
        _lyricsLoading.value = false
    }

    /** Personalised AI recommendation seeded from the user's favourites & most-played. */
    fun madeForYou() {
        val taste = (_starredSongs.value.mapNotNull { it.artist } +
            _starredAlbums.value.mapNotNull { it.artist } +
            _frequent.value.mapNotNull { it.artist })
            .distinct().take(12)
        val prompt = if (taste.isEmpty()) "Build a great, varied mix for discovery."
        else "I like artists like: ${taste.joinToString(", ")}. Build a great mix for me — mostly in that taste, with a couple of fresh discoveries."
        aiDj(prompt)
    }

    // ---- Playlist management ----
    fun deletePlaylist(id: String) = viewModelScope.launch {
        try {
            Subsonic.api?.deletePlaylist(id)
            _currentPlaylist.value = null
            loadPlaylists()
            _toast.value = "Playlist deleted"
        } catch (_: Exception) { _error.value = "Could not delete playlist" }
    }

    fun removeFromPlaylist(playlistId: String, index: Int) = viewModelScope.launch {
        try {
            Subsonic.api?.updatePlaylist(playlistId, null, listOf(index))
            _currentPlaylist.value = Subsonic.api?.getPlaylist(playlistId)?.response?.playlist
        } catch (_: Exception) { _error.value = "Could not remove song" }
    }

    /**
     * Freeze whatever is currently playing (e.g. an ever-changing AI mix) into a
     * permanent, unchanging playlist on the server. Local/radio items are skipped
     * since the server can't hold them.
     */
    fun saveQueueAsPlaylist(name: String) = viewModelScope.launch {
        val c = controller ?: return@launch
        val ids = (0 until c.mediaItemCount)
            .map { c.getMediaItemAt(it).mediaId }
            .filter { it.isNotBlank() && !it.startsWith("local:") && !it.startsWith("radio:") }
        if (ids.isEmpty()) { _error.value = "Nothing to save — play a mix first."; return@launch }
        try {
            Subsonic.api?.createPlaylist(name.trim().ifBlank { "My mix" }, ids)
            loadPlaylists()
            _toast.value = "Saved \"${name.trim().ifBlank { "My mix" }}\" (${ids.size} tracks) — find it in the Saved tab"
        } catch (_: Exception) { _error.value = "Could not save playlist" }
    }

    private fun scrobble(id: String) {
        if (id.startsWith("local:")) return
        viewModelScope.launch { try { Subsonic.api?.scrobble(id) } catch (_: Exception) { } }
    }

    fun clearAlbum() { _currentAlbum.value = null }
    fun clearArtist() { _currentArtist.value = null }
    fun clearPlaylist() { _currentPlaylist.value = null }

    private fun toMediaItem(song: Song): MediaItem {
        val isLocal = song.localUri != null
        val extras = Bundle().apply {
            if (!isLocal) {
                song.albumId?.let { putString("albumId", it) }
                song.artistId?.let { putString("artistId", it) }
            }
        }
        val artUrl = song.artUri ?: Subsonic.coverArtUrl(song.coverArt, 512)
        val meta = MediaMetadata.Builder()
            .setTitle(song.title ?: "Unknown")
            .setArtist(song.artist ?: "Unknown artist")
            .setAlbumTitle(song.album)
            .setExtras(extras)
            .apply { artUrl?.let { setArtworkUri(Uri.parse(it)) } }
            .build()
        val uri = song.localUri ?: Subsonic.streamUrl(song.id)
        return MediaItem.Builder()
            .setMediaId(song.id)
            .setUri(uri)
            // Subsonic re-salts every stream URL, so without a stable key the media
            // cache never hits and "cached for a flaky connection" is a lie.
            .apply { if (!isLocal) setCustomCacheKey(Streaming.cacheKey(uri)) }
            .setMediaMetadata(meta)
            .build()
    }

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }
}
