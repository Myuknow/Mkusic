package com.sikamikaniko.sonora.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Grass
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sikamikaniko.sonora.data.Album

// ---------------------------------------------------------------------------
// Discover — "the shop". Everything here is computed from the catalogue on the
// device, so it keeps working with the AI switched off or unreachable.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(vm: SonoraViewModel, nav: NavController) {
    val shelf by vm.shelf.collectAsState()
    val loading by vm.shelfLoading.collectAsState()
    val decades by vm.decades.collectAsState()
    val genres by vm.genres.collectAsState()
    val unplayed by vm.neverPlayed.collectAsState()
    val exact by vm.playCountsExact.collectAsState()
    val forgotten by vm.forgottenFavourites.collectAsState()
    val pick by vm.tonightsPick.collectAsState()
    val pickReason by vm.tonightsPickReason.collectAsState()
    val blind by vm.blindPick.collectAsState()

    LaunchedEffect(Unit) {
        vm.loadShelf()
        if (genres.isEmpty()) vm.loadGenres()
    }

    blind?.let { album ->
        BlindPickDialog(
            album = album,
            onPlay = { vm.playAlbums(listOf(album), false); vm.clearBlindPick() },
            onReroll = { vm.rollBlindPick() },
            onDismiss = { vm.clearBlindPick() }
        )
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Discover") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            actions = {
                IconButton(onClick = { vm.loadShelf(force = true) }) { Icon(Icons.Filled.Refresh, "Rescan") }
            }
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (shelf.isEmpty()) {
                item {
                    if (loading) LoadingShelf() else CenterMessage("Nothing on the shelves yet.")
                }
            }

            pick?.let { album ->
                item { SectionHeader("Tonight's pick") }
                item {
                    TonightsPick(
                        album = album,
                        neverPlayed = unplayed.any { it.id == album.id },
                        exact = exact,
                        reason = pickReason,
                        onPlay = { vm.playAlbums(listOf(album), false) },
                        onOpen = { nav.navigateDistinct("album/${album.id}") },
                        onAbout = { vm.openInsights(null, album.artist, album.name) }
                    )
                }
            }

            if (decades.isNotEmpty()) {
                item { SectionHeader("By decade") }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        decades.forEach { (decade, albums) ->
                            CrateChip("${decade}s", albums.size) { nav.navigateDistinct("crate/decade:$decade") }
                        }
                    }
                }
            }

            if (genres.isNotEmpty()) {
                item { SectionHeader("By genre") }
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        genres.take(24).forEach { g ->
                            val name = g.value ?: return@forEach
                            // Encode: genres like "Hip Hop/Rap" or "R&B" would break the route.
                            CrateChip(name, g.albumCount ?: 0) { nav.navigateDistinct("genre/${Uri.encode(name)}") }
                        }
                    }
                }
            }

            // Only once the shelf is in: otherwise the crates advertise "0 albums" next to
            // a "Reading the shelves…" spinner, and tap into empty screens.
            if (shelf.isNotEmpty()) {
            item { SectionHeader("Crates") }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CrateCard(
                        icon = Icons.Filled.Grass,
                        title = if (exact) "Never played" else "Rarely played",
                        line = if (exact) "${unplayed.size} albums you own and have never pressed play on"
                        else "${unplayed.size} albums that aren't in your recent or most-played",
                        modifier = Modifier.weight(1f)
                    ) { nav.navigateDistinct("crate/unplayed") }
                    CrateCard(
                        icon = Icons.Filled.Star,
                        title = "Forgotten",
                        line = "${forgotten.size} you starred once and haven't played since",
                        modifier = Modifier.weight(1f)
                    ) { nav.navigateDistinct("crate/forgotten") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CrateCard(
                        icon = Icons.Filled.Casino,
                        title = "Blind pick",
                        line = "One record, no preview. Trust me.",
                        highlight = true,
                        modifier = Modifier.weight(1f)
                    ) { vm.rollBlindPick() }
                    CrateCard(
                        icon = Icons.Filled.LibraryMusic,
                        title = "Everything",
                        line = "${shelf.size} albums on the shelves",
                        modifier = Modifier.weight(1f)
                    ) { nav.navigateDistinct("crate/all") }
                }
            }
            }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

/** The crate contents: one shelf, opened. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrateScreen(vm: SonoraViewModel, key: String, nav: NavController) {
    val shelf by vm.shelf.collectAsState()
    val loading by vm.shelfLoading.collectAsState()
    val building by vm.queueBuilding.collectAsState()
    val decades by vm.decades.collectAsState()
    val unplayed by vm.neverPlayed.collectAsState()
    val exact by vm.playCountsExact.collectAsState()
    val forgotten by vm.forgottenFavourites.collectAsState()

    LaunchedEffect(Unit) { vm.loadShelf() }

    val decade = key.removePrefix("decade:").toIntOrNull()
    val albums: List<Album> = when {
        key.startsWith("decade:") -> decades.firstOrNull { it.first == decade }?.second ?: emptyList()
        key == "unplayed" -> unplayed
        key == "forgotten" -> forgotten
        else -> shelf
    }
    val title = when {
        key.startsWith("decade:") -> "The ${decade}s"
        key == "unplayed" -> if (exact) "Never played" else "Rarely played"
        key == "forgotten" -> "Forgotten favourites"
        else -> "Everything"
    }
    val tracks = remember(albums) { albums.sumOf { it.songCount ?: 0 } }
    // Hoisted and set-based: inline `albums.count { unplayed.any { … } }` is an n×m scan
    // on the main thread, re-run on every recomposition — visible jank while scrolling.
    val unplayedHere = remember(albums, unplayed) {
        if (key == "unplayed") albums.size
        else {
            val ids = unplayed.mapTo(HashSet(unplayed.size)) { it.id }
            albums.count { it.id in ids }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            }
        )
        if (albums.isEmpty()) {
            if (loading) LoadingShelf() else CenterMessage("This crate is empty.")
            return@Column
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(140.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(
                        buildString {
                            append("${albums.size} albums")
                            if (tracks > 0) append(" · $tracks tracks")
                            if (unplayedHere > 0 && key != "unplayed") {
                                append(" · $unplayedHere ")
                                append(if (exact) "never played" else "rarely played")
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PlayAllHeader(
                        if (albums.size > SonoraViewModel.CRATE_PLAY_MAX)
                            "Play ${SonoraViewModel.CRATE_PLAY_MAX} of ${albums.size}"
                        else "Play the crate",
                        onPlay = { vm.playAlbums(albums, false) },
                        onShuffle = { vm.playAlbums(albums, true) }
                    )
                    if (building) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Building the queue…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            gridItems(albums, key = { it.id }) { album -> AlbumItem(vm, nav, album) }
        }
    }
}

@Composable
private fun TonightsPick(
    album: Album,
    neverPlayed: Boolean,
    exact: Boolean,
    reason: String? = null,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    onAbout: () -> Unit
) {
    val brand = LocalBrandBrush.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(14.dp)
    ) {
        Row {
            Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onOpen)) {
                CoverArt(album.coverArt, Modifier.fillMaxSize(), corner = 14.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    album.name ?: "Unknown album",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Text(
                    listOfNotNull(album.artist, album.year?.takeIf { it > 1000 }?.toString())
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (neverPlayed) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (exact) "On your shelves. Never played." else "You haven't played this in a long time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                reason?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(brand)
                    .clickable(onClick = onPlay)
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onAbout) { Text("Tell me more") }
        }
    }
}

@Composable
private fun BlindPickDialog(album: Album, onPlay: () -> Unit, onReroll: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The shopkeeper hands you…") },
        text = {
            Column {
                Text(
                    album.name ?: "Unknown album",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                )
                Text(
                    listOfNotNull(album.artist, album.year?.takeIf { it > 1000 }?.toString())
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().aspectRatio(1.6f), contentAlignment = Alignment.Center) {
                    CoverArt(album.coverArt, Modifier.fillMaxSize(), corner = 16.dp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onPlay) { Text("Put it on") } },
        dismissButton = { TextButton(onClick = onReroll) { Text("Something else") } }
    )
}

@Composable
private fun CrateChip(label: String, count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CrateCard(
    icon: ImageVector,
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    val brand = LocalBrandBrush.current
    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .then(if (highlight) Modifier.background(brand) else Modifier.background(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Icon(
            icon, null,
            tint = if (highlight) Color.White else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = if (highlight) Color.White else MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (highlight) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LoadingShelf() {
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Reading the shelves…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Home's storefront: the pick, then the crates, then a way in. */
@Composable
fun DiscoverStorefront(vm: SonoraViewModel, nav: NavController) {
    val pick by vm.tonightsPick.collectAsState()
    val pickReason by vm.tonightsPickReason.collectAsState()
    val unplayed by vm.neverPlayed.collectAsState()
    val exact by vm.playCountsExact.collectAsState()
    val forgotten by vm.forgottenFavourites.collectAsState()
    val shelf by vm.shelf.collectAsState()

    LaunchedEffect(Unit) { vm.loadShelf() }
    if (shelf.isEmpty()) return

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp)
        ) {
            Text(
                "Discover",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { nav.navigateDistinct("discover") }) { Text("Open shop") }
        }
        pick?.let { album ->
            TonightsPick(
                album = album,
                neverPlayed = unplayed.any { it.id == album.id },
                exact = exact,
                reason = pickReason,
                onPlay = { vm.playAlbums(listOf(album), false) },
                onOpen = { nav.navigateDistinct("album/${album.id}") },
                onAbout = { vm.openInsights(null, album.artist, album.name) }
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CrateCard(
                icon = Icons.Filled.Grass,
                title = if (exact) "Never played" else "Rarely played",
                line = "${unplayed.size} albums",
                modifier = Modifier.weight(1f)
            ) { nav.navigateDistinct("crate/unplayed") }
            // Its own destination, not another door to the shop — "Open shop" is right above.
            CrateCard(
                icon = Icons.Filled.Star,
                title = "Forgotten",
                line = "${forgotten.size} starred",
                modifier = Modifier.weight(1f)
            ) { nav.navigateDistinct("crate/forgotten") }
            CrateCard(
                icon = Icons.Filled.Casino,
                title = "Blind pick",
                line = "Trust me",
                highlight = true,
                modifier = Modifier.weight(1f)
            ) { nav.navigateDistinct("discover"); vm.rollBlindPick() }
        }
    }
}
