package com.sikamikaniko.sonora.data

import com.google.gson.Gson
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

interface WorkstationApi {
    @GET("api/playlist")
    suspend fun playlist(): List<WorkstationTrack>

    @GET("api/bilibili/resolve")
    suspend fun resolve(@Query("aid") aid: String, @Query("cid") cid: String? = null): ResolvedWorkstationAudio
}

data class ResolvedWorkstationAudio(val audioUrl: String)

/** Network interceptor runs again on redirects; credentials stay within one origin. */
class WorkstationAuth(private val connection: () -> ServerConnection?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val current = connection()
        val authorized = current != null && sameOrigin(current.baseUrl, request.url)
        // Also checked on redirects, before credentials or response data are exchanged.
        if (!request.url.isHttps && (!authorized || !ServerConnection.allowsLocalHttp(request.url))) {
            throw IOException("HTTP 仅用于当前连接的局域网服务器，公网地址需要 HTTPS")
        }
        val updated = request.newBuilder().removeHeader("Authorization").apply {
            if (authorized) header("Authorization", Credentials.basic("", current!!.password))
        }.build()
        return chain.proceed(updated)
    }

    companion object {
        fun sameOrigin(first: HttpUrl, second: HttpUrl): Boolean =
            first.scheme == second.scheme && first.host == second.host && first.port == second.port
    }
}

object WorkstationHttp {
    fun client(connection: () -> ServerConnection?): OkHttpClient = OkHttpClient.Builder()
        .addNetworkInterceptor(WorkstationAuth(connection))
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    fun api(connection: ServerConnection): WorkstationApi = Retrofit.Builder()
        .baseUrl(connection.baseUrl)
        .client(client { connection })
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .build().create(WorkstationApi::class.java)
}

object WorkstationErrors {
    fun message(error: Throwable): String = when (error) {
        is HttpException -> when (error.code()) {
            401, 403 -> "访问密码错误或已失效，请重新连接"
            502, 503, 504 -> "服务器暂时无法处理请求，请稍后重试"
            404 -> "未找到音乐接口，请检查服务器地址"
            else -> "服务器请求失败（HTTP ${error.code()}）"
        }
        is SSLException -> "HTTPS 证书验证失败，请检查服务器地址和证书配置"
        is SocketTimeoutException -> "连接超时，请检查网络后重试"
        is IllegalArgumentException -> error.message ?: "服务器地址或密码无效"
        is com.google.gson.JsonParseException -> "服务器返回的歌曲列表格式不正确"
        is IOException -> "无法连接服务器，请检查网络和地址"
        else -> "加载失败，请稍后重试"
    }
}
