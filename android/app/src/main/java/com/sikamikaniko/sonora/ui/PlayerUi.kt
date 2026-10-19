package com.sikamikaniko.sonora.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.SubcomposeAsyncImage
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Mini player — a tactile bar that floats above the nav, with a live progress
// hairline drawn in the theme's brand gradient.
// ---------------------------------------------------------------------------

@Composable
fun MiniPlayer(vm: SonoraViewModel, onExpand: () -> Unit) {
    val title by vm.title.collectAsState()
    val artist by vm.artist.collectAsState()
    val artwork by vm.artworkUri.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val artBrush by vm.artBrush.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    val brand = artBrush ?: LocalBrandBrush.current

    val progress = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val animatedProgress by animateFloatAsState(progress, tween(280), label = "miniProgress")

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.98f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "miniScale"
    )

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 10.dp,
        modifier = Modifier.fillMaxWidth().scale(scale)
    ) {
        Column(Modifier.fillMaxWidth()) {
            // Live progress hairline in brand gradient.
            Box(
                Modifier.fillMaxWidth().height(2.5.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier.fillMaxHeight().fillMaxWidth(animatedProgress).background(brand)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onExpand)
                    // Swipe up on the bar to open the full player (like the big apps).
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragEnd = { if (acc < -40f) onExpand(); acc = 0f },
                            onVerticalDrag = { _, d -> acc += d }
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                UrlArt(artwork, Modifier.size(48.dp), corner = 12.dp)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title ?: "Not playing",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    // Silence with no explanation is the worst possible failure while
                    // driving — say out loud that we're fighting the connection.
                    Text(
                        if (reconnecting) "Reconnecting…" else (artist ?: ""),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (reconnecting) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.togglePlay() }) {
                    Crossfade(targetState = isPlaying, label = "miniPlayIcon") { playing ->
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/Pause",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                IconButton(onClick = { vm.next() }) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Now Playing — immersive art-derived backdrop, breathing cover, brand-gradient
// transport FAB, refined seek bar and clearly-tinted controls.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    vm: SonoraViewModel,
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenLyrics: () -> Unit,
    onOpenInsights: () -> Unit,
    onFindSimilar: () -> Unit,
    onGoToAlbum: (String) -> Unit,
    onGoToArtist: (String) -> Unit
) {
    val title by vm.title.collectAsState()
    val artist by vm.artist.collectAsState()
    val artwork by vm.artworkUri.collectAsState()
    val isPlaying by vm.isPlaying.collectAsState()
    val position by vm.position.collectAsState()
    val duration by vm.duration.collectAsState()
    val repeatMode by vm.repeatMode.collectAsState()
    val shuffle by vm.shuffle.collectAsState()
    val mediaId by vm.currentMediaId.collectAsState()
    val starredIds by vm.starredIds.collectAsState()
    val sleepLeft by vm.sleepMinutesLeft.collectAsState()
    val sleepEnd by vm.sleepEndOfTrack.collectAsState()
    val speed by vm.speed.collectAsState()
    val albumId by vm.currentAlbumId.collectAsState()
    val artistId by vm.currentArtistId.collectAsState()
    val isLive by vm.isLive.collectAsState()
    val autoLyrics by vm.autoLyrics.collectAsState()
    val currentStation by vm.currentStation.collectAsState()
    val favStations by vm.favStations.collectAsState()
    val stationFav = currentStation != null && favStations.any { it.stationuuid == currentStation?.stationuuid }

    // Inline karaoke: show synced lyrics in place of the cover, right here — no screen deeper.
    var showLyricsInline by remember { mutableStateOf(autoLyrics) }
    if (isLive && showLyricsInline) showLyricsInline = false
    // Auto-download lyrics whenever the lyric view is active or the track changes (cached = instant).
    LaunchedEffect(mediaId, showLyricsInline, isLive) {
        if (showLyricsInline && !isLive) vm.loadLyrics()
    }

    val artBrush by vm.artBrush.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    val brand = artBrush ?: LocalBrandBrush.current
    val haptics = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current

    val isStarred = mediaId != null && starredIds.contains(mediaId)
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val sliderMax = duration.toFloat().coerceAtLeast(1f)
    val sliderValue = (if (dragging) dragPos else position.toFloat()).coerceIn(0f, sliderMax)

    // Cover breathing: gently shrinks when paused, softly pulses when playing.
    val inf = rememberInfiniteTransition(label = "npPulse")
    val pulse by inf.animateFloat(
        initialValue = 1f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "coverPulse"
    )
    val settle by animateFloatAsState(
        if (isPlaying) 1f else 0.9f,
        spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow),
        label = "coverSettle"
    )
    val artScale = settle * (if (isPlaying) pulse else 1f)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize()) {
        Backdrop(artwork)
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Collapse") }
                Spacer(Modifier.weight(1f))
                Text("Now Playing", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                SpeedMenu(speed) { vm.setSpeed(it) }
                SleepMenu(
                    sleepLeft, sleepEnd,
                    onPick = { vm.startSleepTimer(it) },
                    onEndOfTrack = { vm.sleepAtEndOfTrack() },
                    onCancel = { vm.cancelSleepTimer() }
                )
                NpOverflow(
                    hasAlbum = albumId != null,
                    hasArtist = artistId != null,
                    onLyrics = onOpenLyrics,
                    onChords = {
                        val q = URLEncoder.encode("${artist ?: ""} ${title ?: ""} chords", "UTF-8")
                        uriHandler.openUri("https://www.google.com/search?q=$q")
                    },
                    onInsights = onOpenInsights,
                    onSimilar = onFindSimilar,
                    onSimilarRadio = { vm.startSimilarRadio() },
                    onShare = { ShareUtil.shareNowPlaying(context, title, artist) },
                    onGoAlbum = { albumId?.let(onGoToAlbum) },
                    onGoArtist = { artistId?.let(onGoToArtist) }
                )
            }

            Spacer(Modifier.height(10.dp))
            // Flexible media area — takes the space left after the fixed transport below,
            // so nothing ever truncates and the vertical drag is free for swipe-to-dismiss.
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Largest square that fits the flexible area (captured here; the constraint
                // scope isn't reachable from the nested boxes below).
                val coverSide = minOf(maxWidth, maxHeight)
                // One unified swipe layer over the whole media area (cover OR lyrics):
                //   left/right = next/prev
                //   swipe UP   = go a level deeper: cover → lyrics → album
                //   swipe DOWN = go back a level:   lyrics → cover, cover → dismiss
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(albumId, showLyricsInline, isLive) {
                            var dx = 0f; var dy = 0f
                            detectDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                                        if (dx < -80f) vm.next() else if (dx > 80f) vm.previous()
                                    } else if (dy > 120f) {
                                        if (showLyricsInline) showLyricsInline = false else onBack()
                                    } else if (dy < -110f) {
                                        if (isLive) Unit
                                        else if (!showLyricsInline) showLyricsInline = true
                                        else albumId?.let(onGoToAlbum)
                                    }
                                    dx = 0f; dy = 0f
                                },
                                onDrag = { change, amount -> change.consume(); dx += amount.x; dy += amount.y }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (showLyricsInline && !isLive) {
                        InlineLyrics(
                            vm = vm,
                            onClose = { showLyricsInline = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(
                            Modifier.size(coverSide)
                                // Tap the cover to flip to karaoke lyrics (no screen deeper).
                                .pointerInput(isLive) {
                                    detectTapGestures(onTap = { if (!isLive) showLyricsInline = true })
                                }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(28.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = 26.dp,
                                modifier = Modifier.fillMaxSize().scale(artScale)
                            ) {
                                UrlArt(artwork, Modifier.fillMaxSize(), corner = 28.dp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title ?: "Not playing",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.fillMaxWidth().basicMarquee()
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        artist ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // Never leave a dropout looking like the app simply stopped.
                    if (reconnecting) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Reconnecting… playback resumes on its own",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Live radio → favourite the STATION; a normal track → star the song.
                if (isLive) {
                    if (currentStation != null) {
                        val heartScale by animateFloatAsState(
                            if (stationFav) 1.18f else 1f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                            label = "stationHeart"
                        )
                        IconButton(onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.favCurrentStation()
                        }) {
                            Icon(
                                if (stationFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                "Favourite station",
                                tint = if (stationFav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.scale(heartScale)
                            )
                        }
                    }
                } else mediaId?.let { id ->
                    val heartScale by animateFloatAsState(
                        if (isStarred) 1.18f else 1f,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        label = "heart"
                    )
                    IconButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        vm.toggleStar(id)
                    }) {
                        Icon(
                            if (isStarred) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favourite",
                            tint = if (isStarred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.scale(heartScale)
                        )
                    }
                }
            }

            if (!isLive) {
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NpQuickChip(
                        if (showLyricsInline) Icons.Filled.Album else Icons.Filled.Lyrics,
                        if (showLyricsInline) "Cover" else "Lyrics",
                        active = showLyricsInline
                    ) { showLyricsInline = !showLyricsInline }
                    NpQuickChip(Icons.Filled.AutoAwesome, "About") { onOpenInsights() }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (isLive) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color.Red))
                    Spacer(Modifier.size(8.dp))
                    Text("LIVE RADIO", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Slider(
                    value = sliderValue,
                    onValueChange = { dragging = true; dragPos = it },
                    onValueChangeFinished = { vm.seekTo(dragPos.toLong()); dragging = false },
                    valueRange = 0f..sliderMax,
                    thumb = {
                        val thumbScale by animateFloatAsState(if (dragging) 1.3f else 1f, label = "thumb")
                        Box(
                            Modifier.size(18.dp).scale(thumbScale)
                                .clip(CircleShape).background(brand)
                                .border(2.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    },
                    track = {
                        val frac = (sliderValue / sliderMax).coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth().height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape).background(brand))
                        }
                    }
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(sliderValue.toLong()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatDuration(duration), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                IconButton(onClick = { vm.toggleShuffle() }) {
                    Icon(
                        Icons.Filled.Shuffle,
                        "Shuffle",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { vm.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipPrevious, "Previous", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(40.dp))
                }

                // Signature brand-gradient transport FAB.
                val fabInteraction = remember { MutableInteractionSource() }
                val fabPressed by fabInteraction.collectIsPressedAsState()
                val fabScale by animateFloatAsState(
                    if (fabPressed) 0.92f else 1f,
                    spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "fabScale"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(78.dp).scale(fabScale)
                        .clip(CircleShape).background(brand)
                        .clickable(interactionSource = fabInteraction, indication = LocalIndication.current) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.togglePlay()
                        }
                ) {
                    Crossfade(targetState = isPlaying, label = "fabIcon") { playing ->
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            "Play/Pause",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }

                IconButton(onClick = { vm.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.SkipNext, "Next", tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { vm.cycleRepeat() }) {
                    Icon(
                        if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        "Repeat",
                        tint = if (repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.clickable(onClick = onOpenQueue)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("Up next", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
                }
            }
            if (sleepLeft > 0) {
                Spacer(Modifier.height(10.dp))
                Text("Sleep in $sleepLeft min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
        }
      }
    }
}

// ---------------------------------------------------------------------------
// Backdrop — blurred artwork + drifting brand shimmer + legibility scrims.
// Blur only applies on API 31+, so the base scrim is always present.
// ---------------------------------------------------------------------------

@Composable
private fun Backdrop(artwork: String?) {
    val brand = LocalBrandBrush.current
    val inf = rememberInfiniteTransition(label = "backdrop")
    val shimmer by inf.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.34f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Reverse),
        label = "shimmer"
    )
    val hasArt = !artwork.isNullOrBlank()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (hasArt) {
            SubcomposeAsyncImage(
                model = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(48.dp)
            )
        }
        // Base scrim: keeps content legible and makes the blur read well below API 31.
        Box(
            Modifier.fillMaxSize().background(
                MaterialTheme.colorScheme.background.copy(alpha = if (hasArt) 0.72f else 1f)
            )
        )
        // Slow-drifting brand wash.
        Box(Modifier.fillMaxSize().alpha(shimmer).background(brand))
        // Bottom fade so the transport controls always sit on solid ground.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
                )
            )
        )
    }
}

/** Karaoke/lyrics rendered inline in the media area — synced (auto-scroll + tap-to-seek) when available. */
@Composable
private fun InlineLyrics(vm: SonoraViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val synced by vm.syncedLyrics.collectAsState()
    val plain by vm.lyrics.collectAsState()
    val loading by vm.lyricsLoading.collectAsState()

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = modifier
    ) {
        Box(Modifier.fillMaxSize().padding(6.dp), contentAlignment = Alignment.Center) {
            when {
                // Auto-follow only (no manual scroll) so the swipe-up gesture reaches the album shortcut.
                synced != null -> SyncedLyrics(vm, synced!!, userScrollEnabled = false)
                !plain.isNullOrBlank() -> Text(
                    plain ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)
                )
                loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(38.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Loading lyrics…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lyrics, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No lyrics for this track", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap to show the cover", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onClose))
                }
            }
        }
    }
}

@Composable
private fun NpQuickChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun SleepMenu(
    minutesLeft: Int,
    endActive: Boolean,
    onPick: (Int) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val active = minutesLeft > 0 || endActive
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Timer, "Sleep timer", tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("End of track") }, onClick = { onEndOfTrack(); expanded = false })
            listOf(15, 30, 45, 60).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutes") }, onClick = { onPick(m); expanded = false })
            }
            if (active) {
                DropdownMenuItem(text = { Text("Cancel timer") }, onClick = { onCancel(); expanded = false })
            }
        }
    }
}

@Composable
private fun SpeedMenu(speed: Float, onPick: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"
    Box {
        TextButton(onClick = { expanded = true }) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { s ->
                val l = if (s % 1f == 0f) "${s.toInt()}×" else "$s×"
                DropdownMenuItem(text = { Text(l) }, onClick = { onPick(s); expanded = false })
            }
        }
    }
}

@Composable
private fun NpOverflow(
    hasAlbum: Boolean,
    hasArtist: Boolean,
    onLyrics: () -> Unit,
    onChords: () -> Unit,
    onInsights: () -> Unit,
    onSimilar: () -> Unit,
    onSimilarRadio: () -> Unit,
    onShare: () -> Unit,
    onGoAlbum: () -> Unit,
    onGoArtist: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("About this music") }, leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) }, onClick = { expanded = false; onInsights() })
            DropdownMenuItem(text = { Text("Find similar songs") }, leadingIcon = { Icon(Icons.Filled.Explore, null) }, onClick = { expanded = false; onSimilar() })
            DropdownMenuItem(text = { Text("Similar radio") }, leadingIcon = { Icon(Icons.Filled.Radio, null) }, onClick = { expanded = false; onSimilarRadio() })
            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { expanded = false; onShare() })
            DropdownMenuItem(text = { Text("Lyrics") }, leadingIcon = { Icon(Icons.Filled.Lyrics, null) }, onClick = { expanded = false; onLyrics() })
            DropdownMenuItem(text = { Text("Guitar chords") }, leadingIcon = { Icon(Icons.Filled.MusicNote, null) }, onClick = { expanded = false; onChords() })
            if (hasAlbum) DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(Icons.Filled.Album, null) }, onClick = { expanded = false; onGoAlbum() })
            if (hasArtist) DropdownMenuItem(text = { Text("Go to artist") }, leadingIcon = { Icon(Icons.Filled.Person, null) }, onClick = { expanded = false; onGoArtist() })
        }
    }
}
