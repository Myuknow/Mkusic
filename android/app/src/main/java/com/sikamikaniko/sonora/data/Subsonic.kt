package com.sikamikaniko.sonora.data

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Singleton that holds the active server connection and knows how to build
 * authenticated Subsonic URLs (token auth: t = md5(password + salt)).
 */
object Subsonic {

    const val CLIENT = "Sonora"
    const val API_VERSION = "1.16.1"

    @Volatile private var server: String? = null
    @Volatile private var user: String? = null
    @Volatile private var pass: String? = null
    @Volatile var api: SubsonicApi? = null
        private set

    val isReady: Boolean get() = api != null && server != null

    /** server e.g. "https://music.example.com" (no trailing slash needed). */
    fun configure(baseUrl: String, username: String, password: String) {
        server = baseUrl.trim().trimEnd('/')
        user = username.trim()
        pass = password
        api = buildApi(server!!)
    }

    fun loadFrom(prefs: Prefs) {
        val b = prefs.baseUrl
        val u = prefs.username
        val p = prefs.password
        if (!b.isNullOrBlank() && !u.isNullOrBlank() && !p.isNullOrBlank()) {
            configure(b, u, p)
        }
    }

    fun reset() {
        server = null; user = null; pass = null; api = null
    }

    private fun authQuery(): String {
        val salt = randomSalt()
        val token = md5((pass ?: "") + salt)
        return "u=${enc(user)}&t=$token&s=$salt&v=$API_VERSION&c=$CLIENT&f=json"
    }

    fun streamUrl(songId: String): String =
        "$server/rest/stream.view?id=${enc(songId)}&${authQuery()}"

    fun coverArtUrl(coverId: String?, size: Int = 512): String? {
        if (coverId.isNullOrBlank()) return null
        // Local device art is a direct URI — pass it through so Coil loads it as-is.
        if (coverId.startsWith("content://") || coverId.startsWith("http://") || coverId.startsWith("https://")) return coverId
        if (server == null) return null
        return "$server/rest/getCoverArt.view?id=${enc(coverId)}&size=$size&${authQuery()}"
    }

    private fun buildApi(server: String): SubsonicApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val auth = Interceptor { chain ->
            val u = user
            val p = pass
            if (u == null || p == null) {
                chain.proceed(chain.request())
            } else {
                val salt = randomSalt()
                val token = md5(p + salt)
                val url = chain.request().url.newBuilder()
                    .addQueryParameter("u", u)
                    .addQueryParameter("t", token)
                    .addQueryParameter("s", salt)
                    .addQueryParameter("v", API_VERSION)
                    .addQueryParameter("c", CLIENT)
                    .addQueryParameter("f", "json")
                    .build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(auth)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("$server/rest/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SubsonicApi::class.java)
    }

    private fun randomSalt(): String {
        val bytes = Random.nextBytes(6)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun enc(s: String?): String =
        java.net.URLEncoder.encode(s ?: "", "UTF-8")
}
