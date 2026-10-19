package com.sikamikaniko.sonora.data

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Extracts two accent colours (ARGB ints) from an artwork URL for album-art theming. */
object ArtPalette {

    suspend fun colors(context: Context, url: String?): Pair<Int, Int>? =
        withContext(Dispatchers.IO) {
            if (url.isNullOrBlank()) return@withContext null
            try {
                val loader = ImageLoader(context)
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(320)
                    .build()
                val drawable = loader.execute(request).drawable ?: return@withContext null
                val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
                val palette = Palette.from(bitmap).generate()
                val primary = palette.vibrantSwatch?.rgb
                    ?: palette.lightVibrantSwatch?.rgb
                    ?: palette.dominantSwatch?.rgb
                    ?: return@withContext null
                val secondary = palette.darkVibrantSwatch?.rgb
                    ?: palette.mutedSwatch?.rgb
                    ?: palette.darkMutedSwatch?.rgb
                    ?: primary
                Pair(primary, secondary)
            } catch (e: Exception) {
                null
            }
        }
}
