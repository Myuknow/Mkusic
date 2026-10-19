package com.sikamikaniko.sonora

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.sikamikaniko.sonora.data.CredentialStore
import com.sikamikaniko.sonora.data.ServerConnection
import com.sikamikaniko.sonora.data.WorkstationHttp

class SonoraApp : Application(), ImageLoaderFactory {
    @Volatile var connection: ServerConnection? = null
    val imageLoader: ImageLoader by lazy {
        ImageLoader.Builder(this)
            .okHttpClient { WorkstationHttp.client { connection } }
            .crossfade(true).build()
    }
    override fun onCreate() {
        super.onCreate()
        connection = CredentialStore(this).load()
    }
    override fun newImageLoader() = imageLoader
}
