package com.sikamikaniko.sonora.data

import android.content.Context

/** Minimal persisted config: which server to talk to and the credentials. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sonora", Context.MODE_PRIVATE)

    var baseUrl: String?
        get() = sp.getString("baseUrl", null)
        set(v) = sp.edit().putString("baseUrl", v).apply()

    var username: String?
        get() = sp.getString("username", null)
        set(v) = sp.edit().putString("username", v).apply()

    var password: String?
        get() = sp.getString("password", null)
        set(v) = sp.edit().putString("password", v).apply()

    var dynamicColor: Boolean
        get() = sp.getBoolean("dynamicColor", false)
        set(v) = sp.edit().putBoolean("dynamicColor", v).apply()

    var artTheme: Boolean
        get() = sp.getBoolean("artTheme", true)
        set(v) = sp.edit().putBoolean("artTheme", v).apply()

    /** Selected Aurora theme, stored by AppTheme.name (default "MIDNIGHT"). */
    var themeName: String
        get() = sp.getString("themeName", "MIDNIGHT") ?: "MIDNIGHT"
        set(v) = sp.edit().putString("themeName", v).apply()

    // ---- AI (all user-configured; nothing hardcoded) ----
    var aiEnabled: Boolean
        get() = sp.getBoolean("aiEnabled", false)
        set(v) = sp.edit().putBoolean("aiEnabled", v).apply()

    /** Which API the AI server speaks — AiClient.Provider.name ("OLLAMA" or "VLLM"). */
    var aiProvider: String
        get() = sp.getString("aiProvider", "OLLAMA") ?: "OLLAMA"
        set(v) = sp.edit().putString("aiProvider", v).apply()

    /** Optional bearer token for a vLLM server started with --api-key. */
    var aiApiKey: String
        get() = sp.getString("aiApiKey", "") ?: ""
        set(v) = sp.edit().putString("aiApiKey", v).apply()

    /** AI server base URL, e.g. http://your-server:11434 (Ollama) or http://your-server:8000 (vLLM) */
    var aiBaseUrl: String
        get() = sp.getString("aiBaseUrl", "") ?: ""
        set(v) = sp.edit().putString("aiBaseUrl", v).apply()

    var aiModel: String
        get() = sp.getString("aiModel", "") ?: ""
        set(v) = sp.edit().putString("aiModel", v).apply()

    /** Target language for lyric translation. */
    var aiLang: String
        get() = sp.getString("aiLang", "English") ?: "English"
        set(v) = sp.edit().putString("aiLang", v).apply()

    /** Car karaoke: push the current synced lyric line to the Bluetooth display as the track title. */
    var carKaraoke: Boolean
        get() = sp.getBoolean("carKaraoke", false)
        set(v) = sp.edit().putBoolean("carKaraoke", v).apply()

    /** Saved AI mixes, serialised as a JSON array. */
    var aiMixesJson: String
        get() = sp.getString("aiMixes", "[]") ?: "[]"
        set(v) = sp.edit().putString("aiMixes", v).apply()

    /** Favourite radio stations, serialised as a JSON array. */
    var favStationsJson: String
        get() = sp.getString("favStations", "[]") ?: "[]"
        set(v) = sp.edit().putString("favStations", v).apply()

    /** Recently played radio stations (JSON array, most-recent first). */
    var recentStationsJson: String
        get() = sp.getString("recentStations", "[]") ?: "[]"
        set(v) = sp.edit().putString("recentStations", v).apply()

    /** Resume exactly where you left off on app open (default off). */
    var resumeEnabled: Boolean
        get() = sp.getBoolean("resumeEnabled", false)
        set(v) = sp.edit().putBoolean("resumeEnabled", v).apply()

    /** Start playing the last queue as soon as the app opens (default on). */
    var autoplayOnStart: Boolean
        get() = sp.getBoolean("autoplayOnStart", true)
        set(v) = sp.edit().putBoolean("autoplayOnStart", v).apply()

    /** Start playing as soon as a Bluetooth audio device connects, app open or not (default on). */
    var btAutoplay: Boolean
        get() = sp.getBoolean("btAutoplay", true)
        set(v) = sp.edit().putBoolean("btAutoplay", v).apply()

    /** Show synced karaoke lyrics in place of the cover on Now Playing automatically (default off). */
    var autoLyrics: Boolean
        get() = sp.getBoolean("autoLyrics", false)
        set(v) = sp.edit().putBoolean("autoLyrics", v).apply()

    /**
     * Download the current and next couple of tracks in full while the connection is
     * good, so playback survives a coverage gap (default on). Costs a little data.
     */
    var preloadEnabled: Boolean
        get() = sp.getBoolean("preloadEnabled", true)
        set(v) = sp.edit().putBoolean("preloadEnabled", v).apply()

    /** Snapshot of the last playback (queue + position) for resume. */
    var lastPlaybackJson: String?
        get() = sp.getString("lastPlayback", null)
        set(v) = sp.edit().putString("lastPlayback", v).apply()

    /** Recent search queries, most-recent first (newline-delimited). */
    var recentSearches: List<String>
        get() = sp.getString("recentSearches", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
        set(v) = sp.edit().putString("recentSearches", v.joinToString("\n")).apply()

    val isConfigured: Boolean
        get() = !baseUrl.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()

    fun save(baseUrl: String, username: String, password: String) {
        sp.edit()
            .putString("baseUrl", baseUrl)
            .putString("username", username)
            .putString("password", password)
            .apply()
    }

    fun clear() = sp.edit().clear().apply()
}
