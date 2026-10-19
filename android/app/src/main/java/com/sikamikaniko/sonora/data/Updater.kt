package com.sikamikaniko.sonora.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Self-update: checks the GitHub Releases API for a newer tag, downloads the
 * attached APK, and hands it to the system package installer. Only works when
 * every release shares the same signing key (see the release CI + keystore).
 */
object Updater {

    private const val LATEST_URL =
        "https://api.github.com/repos/Myuknow/Mkusic/releases/latest"

    private val client = OkHttpClient()
    private val gson = Gson()

    data class Release(val tag_name: String?, val name: String?, val body: String?, val assets: List<Asset>?)
    data class Asset(val name: String?, val browser_download_url: String?)
    data class UpdateInfo(val version: String, val apkUrl: String, val notes: String?)

    suspend fun check(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(LATEST_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val rel = gson.fromJson(resp.body?.string(), Release::class.java) ?: return@withContext null
                val tag = rel.tag_name ?: return@withContext null
                val apk = rel.assets
                    ?.firstOrNull { it.name?.endsWith(".apk", ignoreCase = true) == true }
                    ?.browser_download_url ?: return@withContext null
                if (isNewer(tag, currentVersion)) {
                    UpdateInfo(tag.trimStart('v', 'V'), apk, rel.body)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(context: Context, url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val dir = context.externalCacheDir ?: context.cacheDir
                val file = File(dir, "sonora-update.apk")
                if (file.exists()) file.delete()
                resp.body?.byteStream()?.use { input ->
                    file.outputStream().use { output -> input.copyTo(output) }
                }
                file
            }
        } catch (e: Exception) {
            null
        }
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Compares dotted versions like "v0.3.0" vs "0.2.0". */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
