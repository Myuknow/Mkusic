package com.sikamikaniko.sonora.ui

import android.content.Context
import android.content.Intent

/** Simple text sharing via the Android share sheet — mixes and now-playing. */
object ShareUtil {

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareMix(context: Context, name: String, criteria: String) =
        shareText(context, "🎧 Sonora mix — “$name”\nTry this vibe: “$criteria”\n\nMade with Sonora, a private self-hosted music player.")

    fun shareNowPlaying(context: Context, title: String?, artist: String?) =
        shareText(context, "🎵 Now playing: ${title ?: "music"} — ${artist ?: ""}\nvia Sonora 🎧")
}
