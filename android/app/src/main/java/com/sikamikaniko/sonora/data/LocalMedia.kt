package com.sikamikaniko.sonora.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scans the device (internal storage + every mounted volume incl. SD card) for audio via MediaStore. */
object LocalMedia {

    private val ALBUM_ART = Uri.parse("content://media/external/audio/albumart")

    suspend fun scan(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val songs = ArrayList<Song>()
        val hasGenre = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.YEAR)
            if (hasGenre) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val order = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        val volumes: List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).map { MediaStore.Audio.Media.getContentUri(it) }
        } else {
            listOf(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        }

        for (collection in volumes) {
            try {
                context.contentResolver.query(collection, projection, selection, null, order)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                    val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    val yearCol = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                    val genreCol = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val albumId = c.getLong(albumIdCol)
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        val artUri = ContentUris.withAppendedId(ALBUM_ART, albumId)
                        songs.add(
                            Song(
                                id = "local:$id",
                                title = c.getString(titleCol) ?: "Unknown",
                                artist = c.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                                album = c.getString(albumCol),
                                albumId = albumId.toString(),
                                coverArt = artUri.toString(),
                                duration = (c.getLong(durCol) / 1000L).toInt(),
                                year = if (yearCol >= 0) c.getInt(yearCol).takeIf { it > 0 } else null,
                                genre = if (genreCol >= 0) c.getString(genreCol)?.takeIf { it.isNotBlank() } else null,
                                localUri = contentUri.toString(),
                                artUri = artUri.toString()
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // ignore an unreadable volume, keep scanning others
            }
        }
        songs
    }
}
