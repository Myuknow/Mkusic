package com.sikamikaniko.sonora

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sikamikaniko.sonora.data.CredentialStore
import com.sikamikaniko.sonora.data.ServerConnection
import com.sikamikaniko.sonora.data.WorkstationHttp
import com.sikamikaniko.sonora.data.LocalBilibiliClient

class SonoraApp : Application(), ImageLoaderFactory {
    @Volatile var connection: ServerConnection? = null
    var standalone: Boolean
        get() = getSharedPreferences("mkusic_mode", MODE_PRIVATE).getBoolean("standalone", true)
        set(value) { getSharedPreferences("mkusic_mode", MODE_PRIVATE).edit().putBoolean("standalone", value).apply() }
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient { WorkstationHttp.client { connection }.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                if (standalone && (request.url.host.endsWith(".hdslb.com") || request.url.host.endsWith(".bilibili.com"))) {
                    chain.proceed(request.newBuilder().apply { LocalBilibiliClient.headers.forEach { (k, v) -> header(k, v) } }.build())
                } else chain.proceed(request)
            }.build() }
            .crossfade(true).build()
    }
    override fun onCreate() {
        super.onCreate()
        connection = CredentialStore(this).load()
    }
    override fun newImageLoader() = imageLoader
}
