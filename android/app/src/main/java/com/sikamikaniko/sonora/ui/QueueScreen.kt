package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueScreen(vm: SonoraViewModel, onBack: () -> Unit) {
    val queue by vm.queue.collectAsState()
    val rowPx = with(LocalDensity.current) { 64.dp.toPx() }
    var saving by remember { mutableStateOf(false) }

    if (saving) {
        var name by remember { mutableStateOf("My mix") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Save queue as playlist") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Playlist name") })
            },
            confirmButton = { TextButton(onClick = { vm.saveQueueAsPlaylist(name); saving = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancel") } }
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Up next") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        IconButton(onClick = { saving = true }) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, "Save as playlist")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
            if (queue.isEmpty()) { CenterMessage("Queue is empty. Play something to build one."); return@Column }
            LazyColumn(Modifier.fillMaxSize()) {
                items(queue, key = { it.index }) { item ->
                    var offsetY by remember { mutableFloatStateOf(0f) }
                    val dragging = offsetY != 0f
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset(0, offsetY.roundToInt()) }
                            .shadow(if (dragging) 8.dp else 0.dp, RoundedCornerShape(if (dragging) 12.dp else 0.dp))
                            .background(
                                when {
                                    dragging -> MaterialTheme.colorScheme.surfaceVariant
                                    item.isCurrent -> MaterialTheme.colorScheme.surfaceVariant
                                    else -> MaterialTheme.colorScheme.background
                                }
                            )
                            .clickable { vm.playFromQueue(item.index) }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        UrlArt(item.artworkUri, Modifier.size(44.dp), corner = 8.dp)
                        Spacer(Modifier.size(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (item.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                color = if (item.isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground
                            )
                            Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = { vm.removeFromQueue(item.index) }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Filled.Close, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Drag handle: long-press and drag to reorder.
                        Icon(
                            Icons.Filled.DragHandle,
                            "Reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                                .pointerInput(item.index) {
                                    detectDragGesturesAfterLongPress(
                                        onDrag = { change, delta ->
                                            change.consume()
                                            offsetY += delta.y
                                        },
                                        onDragEnd = {
                                            val steps = (offsetY / rowPx).roundToInt()
                                            offsetY = 0f
                                            if (steps != 0) vm.moveQueueBy(item.index, steps)
                                        },
                                        onDragCancel = { offsetY = 0f }
                                    )
                                }
                        )
                    }
                }
            }
        }
    }
}
