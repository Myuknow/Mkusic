package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.sikamikaniko.sonora.data.artworkIn
import com.sikamikaniko.sonora.data.WorkstationPlaybackMode
import okhttp3.HttpUrl.Companion.toHttpUrl

@Composable
fun MiniWorkstationPlayer(state: WorkstationPlayerState, server: String, toggle: () -> Unit, next: () -> Unit, open: () -> Unit) {
    val track = state.track ?: return
    Surface(tonalElevation = 3.dp) {
        Column {
            if (state.durationMs > 0) LinearProgressIndicator(progress = {
                (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            }, modifier = Modifier.fillMaxWidth().height(2.dp))
            Row(Modifier.fillMaxWidth().clickable(onClick = open).semantics { contentDescription = "打开播放器" }
                .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                PlayerArtwork(state, server, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(track.displayTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    Text(if (state.buffering) "正在缓冲…" else track.artist ?: track.groupName,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = toggle) { Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.playing) "暂停" else "播放") }
                IconButton(onClick = next, enabled = canNext(state)) { Icon(Icons.Rounded.SkipNext, "下一首") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkstationPlayerDialog(state: WorkstationPlayerState, server: String, vm: WorkstationViewModel, onDismiss: () -> Unit, onQueue: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = { TopAppBar(title = { Text("正在播放") }, navigationIcon = {
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.KeyboardArrowDown, "收起播放器") }
            }, actions = { IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列") } }) }) { padding ->
                BoxWithConstraints(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                    if (maxWidth > 600.dp) {
                        val artSize = minOf(maxHeight, 300.dp)
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(0.42f), contentAlignment = Alignment.Center) { PlayerArtwork(state, server, Modifier.size(artSize)) }
                            Column(Modifier.weight(0.58f).verticalScroll(rememberScrollState()).padding(start = 24.dp)) { PlayerControls(state, vm, onQueue) }
                        }
                    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        PlayerArtwork(state, server, Modifier.fillMaxWidth(0.78f).aspectRatio(1f))
                        Spacer(Modifier.height(24.dp))
                        PlayerControls(state, vm, onQueue)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerArtwork(state: WorkstationPlayerState, server: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, modifier = Modifier.fillMaxSize(0.45f), tint = MaterialTheme.colorScheme.primary)
        AsyncImage(model = state.track?.artworkIn(server), contentDescription = "歌曲封面",
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun PlayerControls(state: WorkstationPlayerState, vm: WorkstationViewModel, onQueue: () -> Unit) {
    var dragged by remember(state.track?.id) { mutableStateOf<Float?>(null) }
    var showModes by remember { mutableStateOf(false) }
    val mode = WorkstationPlaybackMode.from(state.repeat, state.shuffle)
    val duration = state.durationMs.coerceAtLeast(1).toFloat()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(state.track?.displayTitle.orEmpty(), style = MaterialTheme.typography.titleLarge,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(state.track?.artist ?: state.track?.groupName.orEmpty(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Slider(value = (dragged ?: state.positionMs.toFloat()).coerceIn(0f, duration),
            onValueChange = { dragged = it }, onValueChangeFinished = { dragged?.let { vm.seek(it.toLong()) }; dragged = null },
            valueRange = 0f..duration, enabled = state.seekable && state.durationMs > 0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(playbackTime(dragged?.toLong() ?: state.positionMs), style = MaterialTheme.typography.labelMedium)
            Text(if (state.durationMs > 0) playbackTime(state.durationMs) else "--:--", style = MaterialTheme.typography.labelMedium)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::previous) { Icon(Icons.Rounded.SkipPrevious, "上一首", Modifier.size(32.dp)) }
            FilledIconButton(onClick = vm::togglePlay, modifier = Modifier.size(64.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.buffering) CircularProgressIndicator(Modifier.size(48.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Icon(if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.playing) "暂停" else "播放", Modifier.size(36.dp))
                }
            }
            IconButton(onClick = vm::next, enabled = canNext(state)) { Icon(Icons.Rounded.SkipNext, "下一首", Modifier.size(32.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { showModes = true }, modifier = Modifier.semantics {
                    contentDescription = "播放模式：${mode.label}，点击切换"
                }) {
                    Icon(playbackModeIcon(mode), null)
                    Spacer(Modifier.width(6.dp)); Text(mode.label)
                    Icon(Icons.Rounded.ArrowDropDown, null)
                }
                DropdownMenu(expanded = showModes, onDismissRequest = { showModes = false }) {
                    WorkstationPlaybackMode.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) },
                            leadingIcon = { Icon(playbackModeIcon(option), null) },
                            trailingIcon = { if (mode == option) Icon(Icons.Rounded.Check, "当前模式") },
                            onClick = { showModes = false; vm.setPlaybackMode(option) })
                    }
                }
            }
            IconButton(onClick = onQueue) { Icon(Icons.AutoMirrored.Rounded.QueueMusic, "播放队列（${state.queue.size} 首）") }
        }
        if (state.error != null) TextButton(onClick = vm::retryPlayback) { Text("重试播放") }
    }
}

private fun playbackModeIcon(mode: WorkstationPlaybackMode) = when (mode) {
    WorkstationPlaybackMode.SEQUENTIAL -> Icons.Rounded.PlaylistPlay
    WorkstationPlaybackMode.LIST_LOOP -> Icons.Rounded.Repeat
    WorkstationPlaybackMode.SHUFFLE -> Icons.Rounded.Shuffle
    WorkstationPlaybackMode.REPEAT_ONE -> Icons.Rounded.RepeatOne
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkstationQueueSheet(state: WorkstationPlayerState, vm: WorkstationViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列 · ${state.queue.size} 首", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            if (state.shuffle) TextButton(onClick = vm::reshuffleQueue, enabled = state.queue.size > 1) {
                Icon(Icons.Rounded.Shuffle, null)
                Text("重新打乱")
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "关闭队列") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            itemsIndexed(state.queue, key = { index, track -> "$index:${track.id}" }) { index, track ->
                Row(Modifier.fillMaxWidth().clickable { vm.selectQueued(index) }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (index == state.index) "▶" else "${index + 1}", Modifier.width(40.dp), color = MaterialTheme.colorScheme.primary)
                    Text(track.displayTitle, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.removeQueued(index) }) { Icon(Icons.Rounded.Close, "移除 ${track.displayTitle}") }
                }
            }
        }
    }
}

private fun canNext(state: WorkstationPlayerState) = state.canSkipNext

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
