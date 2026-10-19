package com.sikamikaniko.sonora.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil.compose.SubcomposeAsyncImage
import com.sikamikaniko.sonora.data.Album
import com.sikamikaniko.sonora.data.Song
import com.sikamikaniko.sonora.data.Subsonic

// ---------- shared motion helpers ----------

/** Gentle tactile press-scale bound to an [interactionSource]. */
@Composable
private fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.955f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "pressScale"
    )
    return scale
}

// ---------- artwork ----------

@Composable
private fun ArtPlaceholder(modifier: Modifier = Modifier, iconSize: Dp = 28.dp) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.colorScheme.surfaceContainerHighest
                )
            )
        ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun CoverArt(coverId: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp, requestPx: Int = 512) {
    UrlArt(Subsonic.coverArtUrl(coverId, requestPx), modifier, corner)
}

@Composable
fun UrlArt(url: String?, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrBlank()) {
            ArtPlaceholder(Modifier.fillMaxSize())
        } else {
            SubcomposeAsyncImage(
                model = url, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { ArtPlaceholder(Modifier.fillMaxSize()) },
                error = { ArtPlaceholder(Modifier.fillMaxSize()) }
            )
        }
    }
}

@Composable
fun Artwork(coverId: String?, size: Dp, corner: Dp = 10.dp, requestPx: Int = 512) {
    CoverArt(coverId, Modifier.size(size), corner, requestPx)
}

/** Premium framed cover: soft shadow, hairline ring, gentle bottom scrim, optional select badge. */
@Composable
private fun AlbumCover(
    coverId: String?,
    selectionActive: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    corner: Dp = 16.dp,
    isLocal: Boolean = false
) {
    Box(
        modifier = modifier
            .shadow(12.dp, RoundedCornerShape(corner), clip = false)
            .clip(RoundedCornerShape(corner))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(corner))
    ) {
        CoverArt(coverId, Modifier.fillMaxSize(), corner = corner)
        // gentle grounding scrim for depth + badge legibility
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.24f)
                    )
                )
            )
        )
        if (isLocal) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(8.dp).size(22.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Smartphone, "On device", tint = Color.White, modifier = Modifier.size(13.dp))
            }
        }
        if (selectionActive) SelectBadge(selected, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

// ---------- album cards ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumGridCard(
    album: Album,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp)
    ) {
        AlbumCover(
            coverId = album.coverArt,
            selectionActive = selectionActive,
            selected = selected,
            isLocal = album.id.startsWith("localalbum-"),
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).scale(scale)
        )
        Spacer(Modifier.size(10.dp))
        Text(
            album.name ?: "Unknown album",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            album.artist ?: "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumRailCard(
    album: Album,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interaction)
    Column(
        modifier = Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(4.dp)
    ) {
        AlbumCover(
            coverId = album.coverArt,
            selectionActive = selectionActive,
            selected = selected,
            modifier = Modifier.size(148.dp).scale(scale)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            album.name ?: "Unknown album",
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            album.artist ?: "",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun AlbumItem(vm: SonoraViewModel, nav: NavController, album: Album) {
    val selMode by vm.selMode.collectAsState()
    val selectedAlbums by vm.selectedAlbums.collectAsState()
    val active = selMode == SelMode.ALBUMS
    val selected = selectedAlbums.any { it.id == album.id }
    AlbumGridCard(
        album, selected, active,
        onClick = { if (active) vm.toggleAlbumSelection(album) else nav.navigateDistinct("album/${album.id}") },
        onLongClick = { vm.toggleAlbumSelection(album) }
    )
}

@Composable
fun AlbumRailItem(vm: SonoraViewModel, nav: NavController, album: Album) {
    val selMode by vm.selMode.collectAsState()
    val selectedAlbums by vm.selectedAlbums.collectAsState()
    val active = selMode == SelMode.ALBUMS
    val selected = selectedAlbums.any { it.id == album.id }
    AlbumRailCard(
        album, selected, active,
        onClick = { if (active) vm.toggleAlbumSelection(album) else nav.navigateDistinct("album/${album.id}") },
        onLongClick = { vm.toggleAlbumSelection(album) }
    )
}

@Composable
private fun SelectBadge(selected: Boolean, modifier: Modifier = Modifier) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "selBadge"
    )
    Box(
        modifier = modifier
            .size(28.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x55000000))
            .border(
                1.5.dp,
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.85f),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (selected) Icons.Filled.Check else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
            modifier = Modifier.size(if (selected) 18.dp else 20.dp)
        )
    }
}

// ---------- song row ----------

data class SongRowActions(
    val onPlayNext: () -> Unit,
    val onAddToQueue: () -> Unit,
    val onAddToPlaylist: () -> Unit,
    val onGoToArtist: (() -> Unit)?,
    val onGoToAlbum: (() -> Unit)?,
    val onToggleStar: () -> Unit,
    val onAbout: (() -> Unit)? = null,
    val onInfo: (() -> Unit)? = null,
    val onRemove: (() -> Unit)? = null,
    val removeLabel: String = "Remove"
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongRow(
    song: Song,
    index: Int? = null,
    starred: Boolean = false,
    selected: Boolean = false,
    selectionActive: Boolean = false,
    isLocal: Boolean = false,
    actions: SongRowActions? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val interaction = remember { MutableInteractionSource() }
    val rowBg by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
        animationSpec = tween(220),
        label = "rowBg"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(rowBg)
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        if (selectionActive) {
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1f else 0.86f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
                label = "selIcon"
            )
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).scale(iconScale)
            )
            Spacer(Modifier.size(12.dp))
        } else if (index != null) {
            Text(
                "$index",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(28.dp)
            )
            Spacer(Modifier.size(8.dp))
        } else {
            Artwork(song.coverArt, size = 46.dp, corner = 12.dp)
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title ?: "Unknown",
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.size(1.dp))
            Text(
                song.artist ?: "",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (!selectionActive) {
            if (isLocal) {
                Icon(Icons.Filled.Smartphone, "On device", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f), modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(6.dp))
            }
            if (starred) {
                Icon(Icons.Filled.Favorite, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(4.dp))
            }
            if (actions != null) SongMenu(starred, actions)
            else Text(
                formatSeconds(song.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
}

@Composable
private fun SongMenu(starred: Boolean, actions: SongRowActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Play next") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = { open = false; actions.onPlayNext() }
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, null) },
                onClick = { open = false; actions.onAddToQueue() }
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = { open = false; actions.onAddToPlaylist() }
            )
            DropdownMenuItem(
                text = { Text(if (starred) "Remove favourite" else "Favourite") },
                leadingIcon = { Icon(if (starred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                onClick = { open = false; actions.onToggleStar() }
            )
            actions.onAbout?.let { about ->
                DropdownMenuItem(
                    text = { Text("About this song") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                    onClick = { open = false; about() }
                )
            }
            actions.onInfo?.let { info ->
                DropdownMenuItem(
                    text = { Text("Info") },
                    leadingIcon = { Icon(Icons.Filled.Info, null) },
                    onClick = { open = false; info() }
                )
            }
            actions.onGoToArtist?.let { go ->
                DropdownMenuItem(
                    text = { Text("Go to artist") },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    onClick = { open = false; go() }
                )
            }
            actions.onGoToAlbum?.let { go ->
                DropdownMenuItem(
                    text = { Text("Go to album") },
                    leadingIcon = { Icon(Icons.Filled.Album, null) },
                    onClick = { open = false; go() }
                )
            }
            actions.onRemove?.let { rm ->
                DropdownMenuItem(
                    text = { Text(actions.removeLabel) },
                    leadingIcon = { Icon(Icons.Filled.Delete, null) },
                    onClick = { open = false; rm() }
                )
            }
        }
    }
}

@Composable
fun SongItem(
    vm: SonoraViewModel,
    nav: NavController,
    song: Song,
    songs: List<Song>,
    index: Int,
    showIndex: Boolean,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "Remove"
) {
    val selMode by vm.selMode.collectAsState()
    val selectedSongs by vm.selectedSongs.collectAsState()
    val starredIds by vm.starredIds.collectAsState()
    val active = selMode == SelMode.SONGS
    val selected = selectedSongs.any { it.id == song.id }
    val isLocal = song.localUri != null
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo) SongInfoDialog(song, isLocal) { showInfo = false }

    SongRow(
        song = song,
        index = if (showIndex) index + 1 else null,
        starred = starredIds.contains(song.id),
        selected = selected,
        selectionActive = active,
        isLocal = isLocal,
        actions = SongRowActions(
            onPlayNext = { vm.playNext(listOf(song)) },
            onAddToQueue = { vm.addToQueue(listOf(song)) },
            onAddToPlaylist = { vm.openPlaylistPicker(listOf(song)) },
            onGoToArtist = song.artistId?.let { aid -> { nav.navigateDistinct("artist/$aid") } },
            onGoToAlbum = song.albumId?.let { alid -> { nav.navigateDistinct("album/$alid") } },
            onToggleStar = { vm.toggleStar(song.id) },
            onAbout = { vm.openInsights(song.title, song.artist, song.album) },
            onInfo = { showInfo = true },
            onRemove = onRemove,
            removeLabel = removeLabel
        ),
        onClick = { if (active) vm.toggleSongSelection(song) else vm.playSongs(songs, index) },
        onLongClick = { vm.toggleSongSelection(song) }
    )
}

@Composable
private fun SongInfoDialog(song: Song, isLocal: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(if (isLocal) Icons.Filled.Smartphone else Icons.Filled.Cloud, null) },
        title = { Text(song.title ?: "Song") },
        text = {
            Column {
                InfoLine("Artist", song.artist)
                InfoLine("Album", song.album)
                InfoLine("Location", if (isLocal) "On this device" else "Streaming from your server")
                if (isLocal) InfoLine("File", song.localUri?.substringAfterLast('/'))
                song.suffix?.let { InfoLine("Format", it.uppercase()) }
                song.duration?.takeIf { it > 0 }?.let { InfoLine("Duration", formatSeconds(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- selection action bar ----------

@Composable
fun SelectionBar(count: Int, mode: SelMode, vm: SonoraViewModel) {
    val haptic = LocalHapticFeedback.current
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 3.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Cancel") }
                Text(
                    "$count selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.playSelection(false)
                }) { Icon(Icons.Filled.PlayArrow, "Play") }
                IconButton(onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    vm.playSelection(true)
                }) { Icon(Icons.Filled.Shuffle, "Shuffle") }
                IconButton(onClick = { vm.playNextSelection() }) { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, "Play next") }
                IconButton(onClick = { vm.queueSelection() }) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Add to queue") }
                IconButton(onClick = { vm.openPlaylistPickerFromSelection() }) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Add to playlist") }
            }
        }
    }
}

// ---------- misc ----------

@Composable
fun SectionHeader(text: String) {
    val brand = LocalBrandBrush.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp)
    ) {
        Box(
            Modifier.size(width = 4.dp, height = 18.dp)
                .clip(RoundedCornerShape(50))
                .background(brand)
        )
        Spacer(Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun PlayAllHeader(title: String, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val brand = LocalBrandBrush.current
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(brand)
                .clickable {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onPlay()
                }
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.PlayArrow, null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(6.dp))
            Text(
                "Play all",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.size(4.dp))
        IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, "Shuffle all") }
    }
}

@Composable
fun CenterMessage(text: String) {
    val infinite = rememberInfiniteTransition(label = "empty")
    val pulse by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(104.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    modifier = Modifier.size(44.dp)
                )
            }
            Spacer(Modifier.size(20.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

fun formatSeconds(seconds: Int?): String = formatDuration((seconds ?: 0) * 1000L)
