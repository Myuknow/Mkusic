package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sikamikaniko.sonora.data.Playlist
import com.sikamikaniko.sonora.data.RadioBrowser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(vm: SonoraViewModel, nav: NavController) {
    // Always refresh on entry so a just-saved playlist or a just-starred song shows up.
    LaunchedEffect(Unit) { vm.loadPlaylists(); vm.loadStarred() }
    val playlists by vm.playlists.collectAsState()
    val favStations by vm.favStations.collectAsState()
    val starredSongs by vm.starredSongs.collectAsState()
    val starredAlbums by vm.starredAlbums.collectAsState()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Saved") },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground
            )
        )

        if (playlists.isEmpty() && favStations.isEmpty() && starredSongs.isEmpty() && starredAlbums.isEmpty()) {
            CenterMessage(
                "Nothing saved yet.\n\nTap the ♥ on any song, album or radio station, or freeze an AI mix into a playlist — they'll all live here."
            )
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize()) {
            // ---- Favourite songs & albums (the ♥ lives here, as every toast promises) ----
            if (starredAlbums.isNotEmpty()) {
                item { SectionHeader("Favourite albums") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(starredAlbums, key = { "sa_" + it.id }) { album -> AlbumRailItem(vm, nav, album) }
                    }
                }
            }
            if (starredSongs.isNotEmpty()) {
                item {
                    PlayAllHeader(
                        "Favourite songs",
                        onPlay = { vm.playSongs(starredSongs, 0) },
                        onShuffle = { vm.shufflePlay(starredSongs) }
                    )
                }
                itemsIndexed(starredSongs, key = { _, s -> "ss_" + s.id }) { index, song ->
                    SongItem(vm, nav, song, starredSongs, index, showIndex = false)
                }
            }

            // ---- Playlists ----
            item { SectionHeader("Playlists") }
            if (playlists.isEmpty()) {
                item { HintRow("No playlists yet — freeze an AI mix or your queue to save one.") }
            } else {
                items(playlists, key = { "pl_" + it.id }) { pl ->
                    PlaylistRow(pl) { nav.navigate("playlist/${pl.id}") }
                }
            }

            // ---- Favourite radios ----
            item { SectionHeader("Favourite radios") }
            if (favStations.isEmpty()) {
                item { HintRow("No favourite stations yet — tap the ♥ on a station in the Radio tab.") }
            } else {
                items(favStations, key = { "fav_" + it.stationuuid }) { st ->
                    FavRadioRow(
                        station = st,
                        onPlay = { vm.playStation(st) },
                        onUnfav = { vm.toggleFavStation(st) }
                    )
                }
            }
            item { Spacer(Modifier.height(90.dp)) }
        }
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp)
    )
}

@Composable
private fun PlaylistRow(pl: Playlist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (pl.coverArt != null) Artwork(pl.coverArt, size = 48.dp)
        else Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pl.name ?: "Playlist", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val n = pl.songCount ?: 0
            Text("$n song${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FavRadioRow(station: RadioBrowser.Station, onPlay: () -> Unit, onUnfav: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        if (!station.favicon.isNullOrBlank()) UrlArt(station.favicon, Modifier.size(48.dp), corner = 10.dp)
        else Icon(Icons.Filled.Radio, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(station.name?.trim().takeUnless { it.isNullOrBlank() } ?: "Station", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(
                station.country?.trim()?.takeIf { it.isNotEmpty() },
                station.codec?.trim()?.takeIf { it.isNotEmpty() }
            ).joinToString("  ·  ")
            if (sub.isNotBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary) }
        IconButton(onClick = onUnfav) { Icon(Icons.Filled.Favorite, "Remove favourite", tint = MaterialTheme.colorScheme.primary) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(vm: SonoraViewModel, playlistId: String, nav: NavController) {
    LaunchedEffect(playlistId) { vm.openPlaylist(playlistId) }
    val pl by vm.currentPlaylist.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete playlist?") },
            text = { Text("\"${pl?.name ?: "This playlist"}\" will be removed from your server.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(playlistId); nav.popBackStack() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(pl?.name ?: "Playlist", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Delete playlist") }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
            val current = pl
            if (current == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }
            val songs = current.entry ?: emptyList()
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(current.name ?: "Playlist", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("${songs.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { vm.playSongs(songs, 0) }) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Play")
                            }
                            OutlinedButton(onClick = { vm.shufflePlay(songs) }) {
                                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.size(6.dp)); Text("Shuffle")
                            }
                        }
                    }
                }
                itemsIndexed(songs) { index, song ->
                    SongItem(
                        vm, nav, song, songs, index, showIndex = false,
                        onRemove = { vm.removeFromPlaylist(playlistId, index) },
                        removeLabel = "Remove from playlist"
                    )
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}
