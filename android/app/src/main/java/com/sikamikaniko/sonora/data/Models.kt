package com.sikamikaniko.sonora.data

import com.google.gson.annotations.SerializedName

/**
 * Gson models for the subset of the Subsonic / OpenSubsonic API that Sonora uses.
 * Every response is wrapped in a top-level "subsonic-response" object.
 */

data class SubsonicEnvelope(
    @SerializedName("subsonic-response") val response: SubsonicResponse?
)

data class SubsonicResponse(
    val status: String?,
    val version: String?,
    val error: SubsonicError?,
    val albumList2: AlbumList2?,
    val album: AlbumWithSongs?,
    val searchResult3: SearchResult3?,
    val artists: ArtistsContainer?,
    val artist: ArtistWithAlbums?,
    val randomSongs: RandomSongs?,
    val playlists: Playlists?,
    val playlist: PlaylistWithSongs?,
    val starred2: Starred2?,
    val genres: Genres?,
    val songsByGenre: SongsByGenre? = null,
    val lyrics: Lyrics? = null,
    val lyricsList: LyricsList? = null
)

// OpenSubsonic getLyricsBySongId — structured (optionally time-synced) lyrics
// from the file's embedded tags or a sidecar .lrc, served by song id so it
// never depends on artist/title string matching.
data class LyricsList(val structuredLyrics: List<StructuredLyrics>? = null)
data class StructuredLyrics(
    val lang: String? = null,
    val synced: Boolean? = null,
    val offset: Long? = null,
    val line: List<LyricLine>? = null
)
data class LyricLine(val start: Long? = null, val value: String? = null)

data class SongsByGenre(val song: List<Song>? = null)

data class Genres(val genre: List<Genre>?)
data class Genre(val value: String?, val songCount: Int? = null, val albumCount: Int? = null)
data class Lyrics(val value: String? = null, val artist: String? = null, val title: String? = null)

data class SubsonicError(val code: Int?, val message: String?)

data class AlbumList2(val album: List<Album>?)

data class AlbumWithSongs(
    val id: String?,
    val name: String?,
    val artist: String?,
    val artistId: String? = null,
    val coverArt: String?,
    val songCount: Int?,
    val year: Int? = null,
    val starred: String? = null,
    val song: List<Song>?
)

data class SearchResult3(
    val artist: List<Artist>?,
    val album: List<Album>?,
    val song: List<Song>?
)

data class ArtistsContainer(val index: List<ArtistIndex>?)
data class ArtistIndex(val name: String?, val artist: List<Artist>?)
data class ArtistWithAlbums(
    val id: String?,
    val name: String?,
    val coverArt: String? = null,
    val albumCount: Int? = null,
    val starred: String? = null,
    val album: List<Album>?
)
data class RandomSongs(val song: List<Song>?)

data class Playlists(val playlist: List<Playlist>?)
data class Playlist(
    val id: String,
    val name: String? = null,
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int? = null,
    val duration: Int? = null,
    val coverArt: String? = null
)
data class PlaylistWithSongs(
    val id: String?,
    val name: String?,
    val songCount: Int? = null,
    val coverArt: String? = null,
    val entry: List<Song>?
)

data class Starred2(
    val song: List<Song>?,
    val album: List<Album>?,
    val artist: List<Artist>?
)

data class Album(
    val id: String,
    val name: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int? = null,
    val year: Int? = null,
    val starred: String? = null,
    /** Navidrome sends this; plain Subsonic servers may not. Null means "unknown", not zero. */
    val playCount: Int? = null,
    val genre: String? = null
)

data class Artist(
    val id: String,
    val name: String? = null,
    val albumCount: Int? = null,
    val coverArt: String? = null,
    val starred: String? = null
)

data class Song(
    val id: String,
    val title: String? = null,
    val album: String? = null,
    val albumId: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val duration: Int? = null,
    val track: Int? = null,
    val year: Int? = null,
    val genre: String? = null,
    val starred: String? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    // Local (device) playback — set for MediaStore songs, null for server songs.
    val localUri: String? = null,
    val artUri: String? = null
)
