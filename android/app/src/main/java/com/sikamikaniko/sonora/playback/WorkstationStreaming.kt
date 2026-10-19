package com.sikamikaniko.sonora.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.sikamikaniko.sonora.SonoraApp
import com.sikamikaniko.sonora.data.WorkstationAuth
import com.sikamikaniko.sonora.data.WorkstationHttp
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

object WorkstationStreaming {
    fun cacheFactory(context: Context): CacheDataSource.Factory {
        val app = context.applicationContext as SonoraApp
        // A stream may run for minutes. Read timeout remains bounded, total call time does not.
        val client = WorkstationHttp.client { app.connection }.newBuilder()
            .callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        val http = OkHttpDataSource.Factory(client).setUserAgent("WorkstationMusic/0.2")
        val resolver = ResolvingDataSource.Resolver { spec ->
            val requested = spec.uri.toString().toHttpUrlOrNull()
            val current = app.connection
            val aid = requested?.queryParameter("aid")
            if (current != null && requested != null && !aid.isNullOrBlank() &&
                requested.encodedPath.endsWith("/api/bilibili/audio") &&
                WorkstationAuth.sameOrigin(current.baseUrl, requested)) {
                // Media3 opens sources on its loading thread, including after seek/skip.
                val resolved = runBlocking { WorkstationHttp.api(current).resolve(aid, requested.queryParameter("cid")) }
                val uri = current.baseUrl.resolve(resolved.audioUrl)
                    ?.takeIf { WorkstationAuth.sameOrigin(current.baseUrl, it) }
                    ?: throw IOException("服务器返回了无效的音频代理地址")
                spec.withUri(Uri.parse(uri.toString()))
            } else spec
        }
        return CacheDataSource.Factory().setCache(PlayerCache.get(context))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, ResolvingDataSource.Factory(http, resolver)))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    fun loadErrorPolicy(): LoadErrorHandlingPolicy = object : DefaultLoadErrorHandlingPolicy(3) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            var cause: Throwable? = info.exception
            while (cause != null) {
                if (cause is SSLException || cause is HttpDataSource.InvalidResponseCodeException &&
                    cause.responseCode in listOf(401, 403, 404) || cause is HttpException &&
                    cause.code() in listOf(401, 403, 404)) return C.TIME_UNSET
                cause = cause.cause
            }
            return super.getRetryDelayMsFor(info)
        }
    }

    fun errorMessage(error: Throwable): String {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpException) return com.sikamikaniko.sonora.data.WorkstationErrors.message(cause)
            if (cause is HttpDataSource.InvalidResponseCodeException) return when (cause.responseCode) {
                401, 403 -> "访问密码已失效，请重新连接服务器"
                404 -> "这首歌曲已失效或服务器文件不存在"
                502, 503, 504 -> "服务器暂时无法获取这首歌曲，可重试或切换下一首"
                else -> "音频请求失败（HTTP ${cause.responseCode}）"
            }
            if (cause is SSLException) return "音频连接的 HTTPS 证书验证失败"
            cause = cause.cause
        }
        return "播放失败，请检查网络后重试或切换下一首"
    }
}
