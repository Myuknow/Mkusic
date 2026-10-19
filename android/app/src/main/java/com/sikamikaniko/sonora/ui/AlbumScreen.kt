package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.sikamikaniko.sonora.data.Subsonic

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(vm: SonoraViewModel, albumId: String, nav: NavController, onBack: () -> Unit) {
    LaunchedEffect(albumId) { vm.openAlbum(albumId) }
    val album by vm.currentAlbum.collectAsState()
    val starred by vm.starredIds.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(album?.name ?: "Album", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { album?.let { vm.openInsights(null, it.artist, it.name) } }) {
                        Icon(Icons.Filled.AutoAwesome, "About this album")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            val current = album
            if (current == null) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }
            val songs = current.song ?: emptyList()
            val albumStarred = current.id != null && starred.contains(current.id)

            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Box(Modifier.fillMaxWidth()) {
                        current.coverArt?.let { cover ->
                            SubcomposeAsyncImage(
                                model = Subsonic.coverArtUrl(cover, 512),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize().blur(38.dp).alpha(0.45f)
                            )
                            Box(
                                Modifier.matchParentSize().background(
                                    Brush.verticalGradient(
                                        listOf(
                                            MaterialTheme.colorScheme.background.copy(alpha = 0.25f),
                                            MaterialTheme.colorScheme.background
                                        )
                                    )
                                )
                            )
                        }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Artwork(current.coverArt, size = 220.dp, corner = 16.dp)
                        Spacer(Modifier.height(14.dp))
                        Text(current.name ?: "Unknown album", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(current.artist ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { vm.playSongs(songs, 0) }) {
                                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.size(6.dp)); Text("Play")
                            }
                            OutlinedButton(onClick = { vm.shufflePlay(songs) }) {
                                Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.size(6.dp)); Text("Shuffle")
                            }
                            current.id?.let { id ->
                                IconButton(onClick = { vm.toggleStar(id) }) {
                                    Icon(
                                        if (albumStarred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                        "Favourite",
                                        tint = if (albumStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    }
                }
                itemsIndexed(songs) { index, song ->
                    SongItem(vm, nav, song, songs, index, showIndex = true)
                }
                item { Spacer(Modifier.height(90.dp)) }
            }
        }
    }
}
