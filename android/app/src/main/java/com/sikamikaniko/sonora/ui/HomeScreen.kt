package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.compose.ui.text.style.TextOverflow
import com.sikamikaniko.sonora.data.AiMix
import com.sikamikaniko.sonora.data.Album
import com.sikamikaniko.sonora.data.RadioBrowser
import java.time.LocalTime

@Composable
fun HomeScreen(vm: SonoraViewModel, nav: NavController) {
    val newest by vm.newest.collectAsState()
    val recent by vm.recent.collectAsState()
    val frequent by vm.frequent.collectAsState()
    val mixes by vm.mixes.collectAsState()
    val recentStations by vm.recentStations.collectAsState()
    val favStations by vm.favStations.collectAsState()
    val aiEnabled by vm.aiEnabled.collectAsState()
    val aiBaseUrl by vm.aiBaseUrl.collectAsState()
    val aiModel by vm.aiModel.collectAsState()
    val aiReady = aiEnabled && aiBaseUrl.isNotBlank() && aiModel.isNotBlank()
    val brand = LocalBrandBrush.current
    val context = LocalContext.current
    var renaming by remember { mutableStateOf<AiMix?>(null) }

    // Voice quick-search: speak a song/artist, jump straight to Library results.
    val startVoice = rememberVoiceInput("Say a song, artist or album…") { spoken ->
        vm.requestLibrarySearch(spoken)
        nav.navigateDistinct("library")
    }

    renaming?.let { mix ->
        var name by remember(mix.id) { mutableStateOf(mix.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename mix") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameMix(mix.id, name); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } }
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            HomeHeader(
                brand = brand,
                onAsk = { nav.navigateDistinct("ai") },
                onShuffle = { vm.shuffleLibrary() }
            )
        }
        item {
            HomeQuickSearch(
                onOpenSearch = { nav.navigateDistinct("library") },
                onVoice = startVoice
            )
        }
        // --- Your music first: pick up where you left off, then your mixes, then the rest ---
        item { SimilarRadioCard(vm, brand) }
        item { Rail("Recently played", recent, vm, nav) }
        if (mixes.isNotEmpty() || aiReady) {
            item {
                SectionHeader("Your AI mixes")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (aiReady) item { MadeForYouCard(brand) { vm.madeForYou() } }
                    items(mixes, key = { it.id }) { mix ->
                        MixCard(
                            mix, brand,
                            onPlay = { vm.playMix(mix) },
                            onRename = { renaming = mix },
                            onDelete = { vm.deleteMix(mix.id) },
                            onShare = { ShareUtil.shareMix(context, mix.name, mix.criteria) },
                            onFreeze = { vm.saveQueueAsPlaylist(mix.name) }
                        )
                    }
                    item { NewMixCard { nav.navigateDistinct("ai") } }
                }
            }
        }
        item { Rail("Recently added", newest, vm, nav) }
        item { Rail("Most played", frequent, vm, nav) }
        // The shop, not a shelf: a rail of random covers gave no reason to care about
        // any of them. This leads with one pick and a reason, then the crates.
        item { DiscoverStorefront(vm, nav) }

        // --- Radio grouped together, lower down ---
        if (recentStations.isNotEmpty()) {
            item {
                SectionHeader("Recent radio")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(recentStations, key = { it.stationuuid }) { st ->
                        RadioMiniCard(st) { vm.playStation(st) }
                    }
                }
            }
        }
        if (favStations.isNotEmpty()) {
            item {
                SectionHeader("Favourite stations")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(favStations, key = { "favhome_" + it.stationuuid }) { st ->
                        RadioMiniCard(st) { vm.playStation(st) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun HomeHeader(
    brand: androidx.compose.ui.graphics.Brush,
    onAsk: () -> Unit,
    onShuffle: () -> Unit
) {
    val hour = remember { LocalTime.now().hour }
    val greeting = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    val onBrand = Color.White
    // Compact hero: greeting + wordmark on the left, a single Ask affordance, and an
    // inline Shuffle chip — no oversized banner, no mystery buttons.
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
            .background(brand)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(greeting, style = MaterialTheme.typography.labelMedium, color = onBrand.copy(alpha = 0.85f))
                Text(
                    "Sonora",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = onBrand
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.20f))
                    .clickable(onClick = onShuffle)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Filled.Shuffle, null, tint = onBrand, modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(6.dp))
                Text("Shuffle", color = onBrand, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            IconButton(onClick = onAsk) { Icon(Icons.Filled.AutoAwesome, "Ask Sonora", tint = onBrand) }
        }
    }
}

/** Prominent quick search — tap to search, or tap the mic to speak (great while driving). */
@Composable
private fun HomeQuickSearch(onOpenSearch: () -> Unit, onVoice: () -> Unit) {
    val brand = LocalBrandBrush.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(start = 18.dp, end = 6.dp)
    ) {
        Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            "Search songs, artists, albums…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onOpenSearch).padding(vertical = 14.dp)
        )
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(brand).clickable(onClick = onVoice),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Mic, "Voice search", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

private fun mixIcon(criteria: String): ImageVector {
    val t = criteria.lowercase()
    return when {
        listOf("cod", "program", "dev").any { t.contains(it) } -> Icons.Filled.Code
        listOf("gym", "workout", "run", "rage", "hype", "energy").any { t.contains(it) } -> Icons.Filled.FitnessCenter
        listOf("study", "focus", "concentrat").any { t.contains(it) } -> Icons.Filled.MenuBook
        listOf("chill", "relax", "calm", "mellow").any { t.contains(it) } -> Icons.Filled.DarkMode
        listOf("party", "dance", "club").any { t.contains(it) } -> Icons.Filled.Celebration
        listOf("love", "romance").any { t.contains(it) } -> Icons.Filled.Favorite
        listOf("sleep", "night", "ambient").any { t.contains(it) } -> Icons.Filled.Bedtime
        listOf("jazz", "classic", "orchestra", "piano").any { t.contains(it) } -> Icons.Filled.MusicNote
        else -> Icons.Filled.GraphicEq
    }
}

/** Compact, flat mix card — small gradient icon tile + name, in a horizontal row. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MixCard(
    mix: AiMix,
    brand: androidx.compose.ui.graphics.Brush,
    onPlay: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onFreeze: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .width(184.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .padding(10.dp)
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(brand),
                contentAlignment = Alignment.Center
            ) {
                Icon(mixIcon(mix.criteria), null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                mix.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Play") }, onClick = { menu = false; onPlay() })
            DropdownMenuItem(
                text = { Text("Save as playlist") },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { menu = false; onFreeze() }
            )
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun MadeForYouCard(brand: androidx.compose.ui.graphics.Brush, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(196.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brand)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Made for you", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("AI picks from your taste", style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun NewMixCard(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(10.dp))
        Text("New mix", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RadioMiniCard(station: RadioBrowser.Station, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable(onClick = onClick)) {
        UrlArt(station.favicon, Modifier.size(120.dp), corner = 14.dp)
        Spacer(Modifier.height(6.dp))
        Text(station.name ?: "Station", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Rail(title: String, albums: List<Album>, vm: SonoraViewModel, nav: NavController) {
    if (albums.isEmpty()) return
    SectionHeader(title)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumRailItem(vm, nav, album)
        }
    }
}

/** One-tap Similar Radio: endless queue matched to the song that's on right now. */
@Composable
private fun SimilarRadioCard(vm: SonoraViewModel, brand: androidx.compose.ui.graphics.Brush) {
    val hasCurrent by vm.hasCurrent.collectAsState()
    if (!hasCurrent) return
    val title by vm.title.collectAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(brand)
            .clickable { vm.startSimilarRadio() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(Icons.Filled.Radio, null, tint = Color.White)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Similar radio", color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(
                "Endless queue matched to “${title ?: "what's playing"}”",
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
