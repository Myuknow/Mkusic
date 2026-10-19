package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

private val TOPICS = listOf(
    "Song" to "song",
    "Album" to "album",
    "Artist" to "artist",
    "Era" to "era",
    "Songwriter" to "songwriter"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(vm: SonoraViewModel, onBack: () -> Unit) {
    val text by vm.aiText.collectAsState()
    val streaming by vm.aiStreaming.collectAsState()
    val target by vm.insightTarget.collectAsState()
    val wiki by vm.insightWiki.collectAsState()
    val hero by vm.insightHero.collectAsState()
    val uriHandler = LocalUriHandler.current
    val heading = target?.title ?: target?.artist ?: target?.album ?: "this music"
    var ask by remember { mutableStateOf("") }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("About $heading", maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                },
                actions = {
                    if (text.isNotBlank() && !streaming) {
                        IconButton(onClick = { vm.refreshInsight() }) {
                            Icon(Icons.Filled.Refresh, "Regenerate")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                TOPICS.forEach { (label, topic) ->
                    Chip(label) { vm.aiInsights(topic) }
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (text.isBlank() && streaming) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Thinking…", color = MaterialTheme.colorScheme.primary) }
                } else {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        (hero ?: wiki?.imageUrl)?.let { img ->
                            UrlArt(img, Modifier.fillMaxWidth().height(230.dp), corner = 16.dp)
                            Spacer(Modifier.height(16.dp))
                        }
                        MarkdownText(text.ifBlank { "Pick a topic above, or ask anything below." }, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(16.dp))
                        Text("✨ AI-generated — may be imperfect.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        wiki?.let { w ->
                            Spacer(Modifier.height(24.dp))
                            Text("From Wikipedia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text(w.extract, style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Wikipedia: ${w.title ?: "article"} ↗",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable {
                                    w.pageUrl?.let { runCatching { uriHandler.openUri(it) } }
                                }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = ask,
                onValueChange = { ask = it },
                placeholder = { Text("Ask anything about this…") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (ask.isNotBlank()) { vm.aiAsk(ask); ask = "" } }),
                trailingIcon = {
                    IconButton(onClick = { if (ask.isNotBlank()) { vm.aiAsk(ask); ask = "" } }, enabled = ask.isNotBlank()) {
                        Icon(Icons.Filled.Send, "Ask", tint = if (ask.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
        }
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
