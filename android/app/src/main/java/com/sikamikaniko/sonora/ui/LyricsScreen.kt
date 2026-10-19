package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsScreen(vm: SonoraViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.clearAiText(); vm.loadLyrics() }
    val lyrics by vm.lyrics.collectAsState()
    val synced by vm.syncedLyrics.collectAsState()
    val loading by vm.lyricsLoading.collectAsState()
    val title by vm.title.collectAsState()
    val aiText by vm.aiText.collectAsState()
    val aiStreaming by vm.aiStreaming.collectAsState()
    val enabled by vm.aiEnabled.collectAsState()
    val baseUrl by vm.aiBaseUrl.collectAsState()
    val model by vm.aiModel.collectAsState()
    val aiReady = enabled && baseUrl.isNotBlank() && model.isNotBlank()
    val showAi = aiStreaming || aiText.isNotBlank()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(title ?: "Lyrics", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            if (aiReady && !lyrics.isNullOrBlank()) {
                val lang by vm.aiLang.collectAsState()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Chip("Translate → $lang") { vm.aiLyrics("translate") }
                    Chip("Explain") { vm.aiLyrics("explain") }
                    if (showAi) Chip("Show lyrics") { vm.clearAiText() }
                }
            }

            when {
                showAi -> {
                    val aiMode by vm.aiLyricsMode.collectAsState()
                    if (aiText.isBlank()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Thinking…", color = MaterialTheme.colorScheme.primary) }
                    } else if (aiMode == "translate") {
                        BilingualLyrics(aiText)
                    } else {
                        MarkdownText(aiText, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp))
                    }
                }
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                synced != null -> SyncedLyrics(vm, synced!!)
                lyrics.isNullOrBlank() -> CenterMessage("No lyrics found for this track.")
                else -> Text(
                    lyrics ?: "",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)
                )
            }
        }
    }
}

@Composable
fun SyncedLyrics(vm: SonoraViewModel, lines: List<Pair<Long, String>>, userScrollEnabled: Boolean = true) {
    val position by vm.position.collectAsState()
    val current = remember(position, lines) {
        lines.indexOfLast { it.first <= position }.coerceIn(0, lines.lastIndex)
    }
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        if (lines.isNotEmpty()) state.animateScrollToItem(current.coerceIn(0, lines.lastIndex), scrollOffset = -320)
    }
    LazyColumn(state = state, userScrollEnabled = userScrollEnabled, modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        itemsIndexed(lines) { i, line ->
            val active = i == current
            Text(
                line.second.ifBlank { "♪" },
                style = if (active) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.first) }.padding(vertical = 7.dp)
            )
        }
    }
}

/**
 * Bilingual translation view: originals in the normal text colour, translated lines
 * (the model prefixes them with "»") in the theme's accent colour and italic, with
 * each original+translation pair grouped tighter than the gap to the next pair.
 */
@Composable
private fun BilingualLyrics(text: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(18.dp))
                line.startsWith("»") || line.startsWith("> »") -> Text(
                    line.removePrefix("> ").removePrefix("»").trim(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                else -> Text(
                    line,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) { Text(label, style = MaterialTheme.typography.bodyMedium) }
}
