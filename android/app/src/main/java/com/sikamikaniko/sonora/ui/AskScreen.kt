package com.sikamikaniko.sonora.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Send
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

private val EXAMPLES = listOf(
    "Chill music for coding",
    "High-energy workout mix",
    "Focus / instrumental",
    "90s party throwbacks",
    "Relaxing evening",
    "Surprise me — discover something"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AskScreen(vm: SonoraViewModel, nav: NavController) {
    val enabled by vm.aiEnabled.collectAsState()
    val baseUrl by vm.aiBaseUrl.collectAsState()
    val model by vm.aiModel.collectAsState()
    val busy by vm.aiBusy.collectAsState()
    val status by vm.aiStatus.collectAsState()
    val radio by vm.radio.collectAsState()
    val lastPrompt by vm.lastDjPrompt.collectAsState()
    val mixSongs by vm.mixSongs.collectAsState()
    val ready = enabled && baseUrl.isNotBlank() && model.isNotBlank()
    val brand = LocalBrandBrush.current
    var prompt by remember { mutableStateOf("") }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val text = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) { prompt = text; vm.aiDj(text) }
        }
    }
    fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say a vibe, artist, or activity…")
        }
        try { voice.launch(intent) } catch (_: Exception) { }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Hero header
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(brand)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Spacer(Modifier.size(4.dp))
                Icon(Icons.Filled.AutoAwesome, null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text("Ask Sonora", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Describe a vibe and I'll build a queue from your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        if (!ready) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
                Spacer(Modifier.height(14.dp))
                Text("Connect your AI first", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Add your Ollama or vLLM server and pick a model in Settings → AI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(brand).clickable { nav.navigateDistinct("settings") }.padding(horizontal = 22.dp, vertical = 12.dp)
                ) { Text("Open AI settings", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
            return
        }

        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                placeholder = { Text("e.g. mellow jazz for a rainy night") },
                singleLine = false,
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { startVoice() }) { Icon(Icons.Filled.Mic, "Voice", tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = { vm.aiDj(prompt) }, enabled = !busy && prompt.isNotBlank()) {
                            Icon(Icons.Filled.Send, "Go", tint = if (prompt.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(50)))
                Spacer(Modifier.height(6.dp))
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
            }
            if (lastPrompt != null && !busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(brand)
                            .clickable { vm.saveCurrentAsMix() }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Save as mix", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.saveQueueAsPlaylist(lastPrompt ?: "My mix") }
                            .padding(horizontal = 16.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Freeze as playlist", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Preview of the tracks the AI just gathered — tap any to jump in.
            if (mixSongs.isNotEmpty()) {
                Text(
                    "In this mix · ${mixSongs.size} tracks",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                mixSongs.forEachIndexed { i, s ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.playSongs(mixSongs, i) }
                            .padding(vertical = 6.dp, horizontal = 4.dp)
                    ) {
                        CoverArt(s.coverArt, Modifier.size(40.dp), corner = 8.dp)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.title ?: "Unknown", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(s.artist ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Icon(Icons.Filled.PlayArrow, "Play", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            Spacer(Modifier.height(10.dp))
            Text("Try", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EXAMPLES.forEach { ex ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = !busy) { prompt = ex; vm.aiDj(ex) }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) { Text(ex, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
            ) {
                Icon(Icons.Filled.Radio, null, tint = if (radio) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Smart Radio", fontWeight = FontWeight.SemiBold)
                    Text("Keep the queue topped up with similar tracks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = radio, onCheckedChange = { vm.setRadio(it) })
            }
        }
    }
}
