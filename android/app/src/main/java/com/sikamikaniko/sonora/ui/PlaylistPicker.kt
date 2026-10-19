package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun PlaylistPickerDialog(vm: SonoraViewModel) {
    val songs = vm.playlistPickerSongs.collectAsState().value ?: return
    val playlists by vm.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { vm.dismissPlaylistPicker() },
        title = { Text("Add ${songs.size} song${if (songs.size == 1) "" else "s"}") },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("New playlist name") },
                    singleLine = true,
                    trailingIcon = {
                        if (newName.isNotBlank()) {
                            IconButton(onClick = { vm.createPlaylistWithPickerSongs(newName) }) {
                                Icon(Icons.Filled.Add, "Create")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                if (playlists.isNotEmpty()) {
                    Spacer(Modifier.padding(4.dp))
                    HorizontalDivider()
                    Text("Existing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                    Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        playlists.forEach { pl ->
                            Text(
                                pl.name ?: "Playlist",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.addPickerSongsToPlaylist(pl.id) }
                                    .padding(vertical = 12.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.dismissPlaylistPicker() }) { Text("Close") }
        }
    )
}
