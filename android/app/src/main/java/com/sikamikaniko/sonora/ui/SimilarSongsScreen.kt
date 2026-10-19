package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimilarSongsScreen(vm: SonoraViewModel, nav: NavController) {
    LaunchedEffect(Unit) { vm.loadSimilar() }

    val items by vm.similar.collectAsState()
    val loading by vm.similarLoading.collectAsState()
    val seed by vm.similarSeed.collectAsState()

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text("Similar songs") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )

            seed?.let { s ->
                Text(
                    "Based on $s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
                )
                TextButton(
                    onClick = { vm.startSimilarRadio(); nav.popBackStack() },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("▶  Start similar radio — keep this vibe going") }
            }

            when {
                loading && items.isEmpty() -> LoadingState()
                items.isEmpty() -> CenterMessage(
                    "Play a song, then find similar music here. (Requires AI set up in Settings.)"
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    itemsIndexed(items, key = { i, it -> "$i:${it.title}:${it.artist}" }) { _, item ->
                        SimilarRow(item = item, vm = vm, nav = nav)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(44.dp)
            )
            Spacer(Modifier.size(18.dp))
            Text(
                "Finding music you'll love…",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SimilarRow(item: SonoraViewModel.SimilarItem, vm: SonoraViewModel, nav: NavController) {
    val inLibrary = item.librarySong != null
    val interaction = remember { MutableInteractionSource() }
    val brand = LocalBrandBrush.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (inLibrary) Modifier.clickable(
                    interactionSource = interaction,
                    indication = LocalIndication.current
                ) { vm.playSimilar(item) } else Modifier
            )
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // Leading art / play affordance
        if (inLibrary) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).background(brand),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PlayArrow, "Play",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            UrlArt(null, Modifier.size(46.dp), corner = 12.dp)
        }
        Spacer(Modifier.size(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                item.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (inLibrary) {
                Spacer(Modifier.size(6.dp))
                InLibraryPill()
            }
        }
        Spacer(Modifier.size(10.dp))

        if (inLibrary) {
            IconButton(onClick = { vm.playSimilar(item) }) {
                Icon(
                    Icons.Filled.PlayArrow, "Play",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        } else {
            YouTubeAffordance(
                onClick = {
                    nav.navigate("yt/" + android.net.Uri.encode(vm.youtubeQuery(item)))
                }
            )
        }
    }
}

@Composable
private fun InLibraryPill() {
    val green = Color(0xFF1DB954)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(green.copy(alpha = 0.16f))
            .border(1.dp, green.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(green))
        Spacer(Modifier.size(6.dp))
        Text(
            "In your library",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = green
        )
    }
}

@Composable
private fun YouTubeAffordance(onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Icon(
            Icons.Filled.OpenInNew, null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.size(6.dp))
        Text(
            "Preview on YouTube",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}
