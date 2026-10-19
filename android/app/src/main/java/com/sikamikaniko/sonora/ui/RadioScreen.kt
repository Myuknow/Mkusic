package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sikamikaniko.sonora.data.RadioBrowser

private val RADIO_GENRES = listOf(
    "Pop", "Rock", "Jazz", "Classical", "Electronic", "House", "Deep House",
    "Techno", "Trance", "Drum & Bass", "Hip-Hop", "R&B", "Funk", "Soul",
    "Lofi", "Chillout", "Ambient", "Dance", "Disco", "Metal", "Punk", "Indie",
    "Reggae", "Blues", "Country", "Folk", "Latin", "Salsa", "K-Pop", "Gospel",
    "Soundtrack", "Oldies", "News", "Talk"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(vm: SonoraViewModel, nav: NavController) {
    LaunchedEffect(Unit) { vm.loadTopStations() }

    val stations by vm.stations.collectAsState()
    val loading by vm.radioLoading.collectAsState()
    val genre by vm.radioGenre.collectAsState()
    val favs by vm.favStations.collectAsState()

    val brand = LocalBrandBrush.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("World Radio", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                // ---- Hero banner + Surprise me ----
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(brand)
                            .padding(20.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.Radio, null,
                                    tint = Color.White, modifier = Modifier.size(26.dp)
                                )
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    "Radio",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Thousands of live stations from around the world.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.22f))
                                    .clickable { vm.surpriseStation() }
                                    .padding(horizontal = 22.dp, vertical = 12.dp)
                            ) {
                                Text(
                                    "🎲  Surprise me",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // ---- Genre chips ----
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        RADIO_GENRES.forEach { g ->
                            val tag = g.lowercase()
                            val active = genre == tag
                            GenreChip(
                                label = g,
                                active = active,
                                brand = brand,
                                onClick = { vm.loadGenre(tag) }
                            )
                        }
                    }
                }

                // ---- Search field ----
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search stations") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Filled.Close, "Clear")
                                }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            if (query.isNotBlank()) vm.searchStations(query.trim())
                            keyboard?.hide()
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }

                // ---- Favourites ----
                if (favs.isNotEmpty()) {
                    item { SectionHeader("Favourites") }
                    items(favs, key = { "fav_" + it.stationuuid }) { st ->
                        StationRow(
                            station = st,
                            fav = true,
                            onPlay = { vm.playStation(st) },
                            onToggleFav = { vm.toggleFavStation(st) }
                        )
                    }
                    item { SectionHeader("Stations") }
                }

                // ---- Main station list / states ----
                if (stations.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (loading) {
                                CircularProgressIndicator()
                            } else {
                                CenterMessage("No stations — try another genre.")
                            }
                        }
                    }
                } else {
                    items(stations, key = { it.stationuuid }) { st ->
                        StationRow(
                            station = st,
                            fav = favs.any { f -> f.stationuuid == st.stationuuid },
                            onPlay = { vm.playStation(st) },
                            onToggleFav = { vm.toggleFavStation(st) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(
    label: String,
    active: Boolean,
    brand: androidx.compose.ui.graphics.Brush,
    onClick: () -> Unit
) {
    val base = Modifier
        .clip(RoundedCornerShape(50))
        .clickable(onClick = onClick)
    val styled = if (active) {
        base.background(brand)
    } else {
        base.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    }
    Box(styled.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun StationRow(
    station: RadioBrowser.Station,
    fav: Boolean,
    onPlay: () -> Unit,
    onToggleFav: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        UrlArt(station.favicon, Modifier.size(48.dp), corner = 10.dp)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name?.trim().takeUnless { it.isNullOrBlank() } ?: "Unknown station",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val sub = stationSubline(station)
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        IconButton(onClick = onToggleFav) {
            if (fav) {
                Icon(Icons.Filled.Favorite, "Remove favourite", tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Filled.FavoriteBorder, "Add favourite")
            }
        }
    }
}

private fun stationSubline(s: RadioBrowser.Station): String {
    val parts = ArrayList<String>(4)
    // Lead with popularity — stations are ordered most-voted first, so this explains the ranking.
    s.votes?.takeIf { it > 0 }?.let { parts.add("★ ${compact(it)}") }
    s.country?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    s.bitrate?.takeIf { it > 0 }?.let { parts.add("$it kbps") }
    s.codec?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
    return parts.joinToString("  ·  ")
}

/** 12345 -> "12.3k" so popularity reads cleanly in the tight subline. */
private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
