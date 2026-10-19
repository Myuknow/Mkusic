package com.sikamikaniko.sonora.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.sikamikaniko.sonora.BuildConfig
import com.sikamikaniko.sonora.data.AiClient

/** Targets for bilingual lyric translation — the everyday picks first, the rest A→Z. */
private val TRANSLATE_LANGUAGES = listOf(
    "Bulgarian", "English",
    "Arabic", "Chinese", "Croatian", "Czech", "Danish", "Dutch", "Finnish", "French",
    "German", "Greek", "Hebrew", "Hindi", "Hungarian", "Indonesian", "Italian",
    "Japanese", "Korean", "Norwegian", "Polish", "Portuguese", "Romanian", "Russian",
    "Serbian", "Spanish", "Swedish", "Thai", "Turkish", "Ukrainian", "Vietnamese"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SonoraViewModel, nav: NavController) {
    LaunchedEffect(Unit) { vm.refreshCacheSize() }
    val cacheBytes by vm.cacheBytes.collectAsState()
    val currentTheme by vm.appTheme.collectAsState()
    val uri = LocalUriHandler.current
    val brand = LocalBrandBrush.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = MaterialTheme.colorScheme.onBackground,
                navigationIconContentColor = MaterialTheme.colorScheme.onBackground
            )
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 4.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // ---- Account ----
            SectionCard(title = "Account", icon = Icons.Filled.AccountCircle) {
                InfoRow("Server", vm.serverUrl ?: "—")
                RowDivider()
                InfoRow("User", vm.username ?: "—")
            }

            // ---- Appearance / theme gallery ----
            SectionCard(title = "Appearance", icon = Icons.Filled.Palette) {
                Text(
                    "Pick a theme — it flows across the whole app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
                ThemeGallery(
                    current = currentTheme,
                    onPick = { vm.setTheme(it) }
                )
                Spacer(Modifier.height(14.dp))
                val artTheme by vm.artTheme.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Colour from album art")
                        Text(
                            "Now Playing tints itself to the current cover",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(checked = artTheme, onCheckedChange = { vm.setArtTheme(it) })
                }
            }

            // ---- AI ----
            SectionCard(title = "AI", icon = Icons.Filled.AutoAwesome) {
                val aiEnabled by vm.aiEnabled.collectAsState()
                val aiProvider by vm.aiProvider.collectAsState()
                val aiApiKey by vm.aiApiKey.collectAsState()
                val aiBaseUrl by vm.aiBaseUrl.collectAsState()
                val aiModel by vm.aiModel.collectAsState()
                val aiLang by vm.aiLang.collectAsState()
                val models by vm.aiModels.collectAsState()
                val modelsLoading by vm.aiModelsLoading.collectAsState()
                var modelMenu by remember { mutableStateOf(false) }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable AI features")
                        Text("AI DJ, Radio, insights & lyric tools", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = aiEnabled, onCheckedChange = { vm.setAiEnabled(it) })
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AiClient.Provider.values().forEach { p ->
                        androidx.compose.material3.FilterChip(
                            selected = aiProvider == p,
                            onClick = { vm.setAiProvider(p) },
                            label = { Text(p.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = aiBaseUrl,
                    onValueChange = { vm.setAiBaseUrl(it) },
                    label = { Text("${aiProvider.label} server URL") },
                    placeholder = { Text(aiProvider.urlHint) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (aiProvider == AiClient.Provider.VLLM) {
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = aiApiKey,
                        onValueChange = { vm.setAiApiKey(it) },
                        label = { Text("API key (optional)") },
                        placeholder = { Text("Only if vLLM runs with --api-key") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Spacer(Modifier.height(10.dp))
                Box {
                    OutlinedButton(
                        onClick = { vm.loadAiModels(); modelMenu = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (modelsLoading) {
                            androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            when {
                                modelsLoading -> "Loading models…"
                                aiModel.isBlank() -> "Pick a model"
                                else -> aiModel
                            }
                        )
                    }
                    androidx.compose.material3.DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        when {
                            modelsLoading -> androidx.compose.material3.DropdownMenuItem(text = { Text("Loading models…") }, onClick = { })
                            models.isEmpty() -> androidx.compose.material3.DropdownMenuItem(text = { Text("No models found — tap Test connection to see why") }, onClick = { modelMenu = false })
                        }
                        models.forEach { m ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(m) }, onClick = { vm.setAiModel(m); modelMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val aiTest by vm.aiTest.collectAsState()
                val aiTesting by vm.aiTesting.collectAsState()
                androidx.compose.material3.FilledTonalButton(
                    onClick = { vm.testAi() },
                    enabled = !aiTesting && aiBaseUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (aiTesting) {
                        androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(if (aiTesting) "Testing…" else "Test connection")
                }
                aiTest?.let { r ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        r.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (r.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                var langMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { langMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Translate lyrics to: $aiLang")
                    }
                    androidx.compose.material3.DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                        TRANSLATE_LANGUAGES.forEach { lang ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(lang) },
                                onClick = { vm.setAiLang(lang); langMenu = false }
                            )
                        }
                    }
                }
                Text(
                    "Lyrics translate bilingually — every original line with its translation under it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Runs on your own Ollama or vLLM server — private, nothing sent to the cloud.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ---- Playback ----
            SectionCard(title = "Playback", icon = Icons.Filled.PlayArrow) {
                val autoplay by vm.autoplayOnStart.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Autoplay on start")
                        Text("Start playing your last queue the moment Sonora opens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = autoplay, onCheckedChange = { vm.setAutoplayOnStart(it) })
                }
                RowDivider()
                val carKaraoke by vm.carKaraoke.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Car karaoke (experimental)")
                        Text(
                            "Show the current lyric line on your car's Bluetooth display (sent as the track title — the notification shows lyrics too while on)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(checked = carKaraoke, onCheckedChange = { vm.setCarKaraoke(it) })
                }
                RowDivider()
                val btAutoplay by vm.btAutoplay.collectAsState()
                // Android 12+ withholds the "device connected" broadcast unless
                // BLUETOOTH_CONNECT is granted, so switching this on without asking for
                // the permission would leave a switch that reads ON and does nothing.
                val ctx = LocalContext.current
                var btGranted by remember {
                    mutableStateOf(
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                            PackageManager.PERMISSION_GRANTED
                    )
                }
                val askBluetooth = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> btGranted = granted }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val needsGrant = btAutoplay && !btGranted
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable(enabled = needsGrant) {
                                askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                    ) {
                        Text("Play on Bluetooth connect")
                        Text(
                            if (needsGrant)
                                "Tap here to grant the Bluetooth permission — Android won't tell Sonora about your car without it"
                            else "Car stereo or headphones connect → Sonora starts, app open or not",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (needsGrant) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(
                        checked = btAutoplay,
                        onCheckedChange = {
                            vm.setBtAutoplay(it)
                            if (it && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !btGranted) {
                                askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                            }
                        }
                    )
                }
                RowDivider()
                val resume by vm.resumeEnabled.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Resume on open")
                        Text("Restore the last queue and position without starting it — autoplay covers this too", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = resume, onCheckedChange = { vm.setResumeEnabled(it) })
                }
                RowDivider()
                val preload by vm.preloadEnabled.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Preload upcoming tracks")
                        Text(
                            "Download the current and next few songs in full while the signal is good, so playback survives tunnels and dead spots. Uses a little more data.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    androidx.compose.material3.Switch(checked = preload, onCheckedChange = { vm.setPreloadEnabled(it) })
                }
                RowDivider()
                val autoLyrics by vm.autoLyrics.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto lyrics on Now Playing")
                        Text("Show synced karaoke lyrics in place of the cover automatically", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = autoLyrics, onCheckedChange = { vm.setAutoLyrics(it) })
                }
            }

            // ---- Storage ----
            SectionCard(title = "Storage", icon = Icons.Filled.Storage) {
                InfoRow("Cached audio", formatBytes(cacheBytes))
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.clearCache() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.CleaningServices, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Clear cache")
                }
            }

            // ---- Updates ----
            SectionCard(title = "Updates", icon = Icons.Filled.SystemUpdateAlt) {
                InfoRow("Version", BuildConfig.VERSION_NAME)
                Spacer(Modifier.height(12.dp))
                // brand-gradient CTA
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(50))
                        .background(brand)
                        .clickable { vm.checkForUpdate() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.SystemUpdateAlt,
                            null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "Check for updates",
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                RowDivider()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { uri.openUri("https://github.com/SikamikanikoBG/Sonora") }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        "github.com/SikamikanikoBG/Sonora",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        Icons.Filled.OpenInNew,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // ---- Log out ----
            Button(
                onClick = { vm.logout() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Log out", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Theme gallery
// ---------------------------------------------------------------------------

@Composable
private fun ThemeGallery(current: AppTheme, onPick: (AppTheme) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val rows = AppTheme.entries.toList().chunked(3)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { theme ->
                    Box(Modifier.weight(1f)) {
                        ThemeChip(
                            theme = theme,
                            selected = theme == current,
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onPick(theme)
                            }
                        )
                    }
                }
                // pad the final short row so chips keep their width
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemeChip(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    val swatch = themeSwatch(theme)
    val gradient = twoStopBrush(swatch)
    val ringColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        animationSpec = tween(220),
        label = "ring"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (selected) 2.5.dp else 1.dp,
        animationSpec = tween(220),
        label = "ringW"
    )
    val labelWeight = if (selected) FontWeight.Bold else FontWeight.Medium
    val checkAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(200),
        label = "check"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(gradient)
                .border(ringWidth, ringColor, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (checkAlpha > 0.01f) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.32f * checkAlpha)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Selected",
                        tint = Color.White.copy(alpha = checkAlpha),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            theme.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = labelWeight,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/** Build a linear gradient from the swatch, guaranteeing at least two stops. */
private fun twoStopBrush(swatch: List<Color>): Brush {
    val stops = when {
        swatch.isEmpty() -> listOf(Color(0xFF6C4CF1), Color(0xFF8A6CF2))
        swatch.size == 1 -> listOf(swatch[0], swatch[0])
        else -> swatch
    }
    return Brush.linearGradient(stops)
}

// ---------------------------------------------------------------------------
// Section scaffolding
// ---------------------------------------------------------------------------

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
    )
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.1f GB".format(mb / 1024) else "%.0f MB".format(mb)
}
