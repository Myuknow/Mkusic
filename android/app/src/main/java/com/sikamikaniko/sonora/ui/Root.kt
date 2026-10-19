package com.sikamikaniko.sonora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private sealed class Tab(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Tab("home", "Home", Icons.Filled.Home)
    data object Library : Tab("library", "Library", Icons.Filled.LibraryMusic)
    data object Playlists : Tab("playlists", "Saved", Icons.Filled.Bookmark)
    data object Radio : Tab("radio", "Radio", Icons.Filled.Radio)
    data object Settings : Tab("settings", "Settings", Icons.Filled.Settings)
}

@Composable
fun SonoraRoot(vm: SonoraViewModel = viewModel()) {
    UpdateDialog(vm)
    PlaylistPickerDialog(vm)
    val loggedIn by vm.loggedIn.collectAsState()
    if (!loggedIn) {
        LoginScreen(vm)
        return
    }

    val nav = rememberNavController()
    val hasCurrent by vm.hasCurrent.collectAsState()
    val selMode by vm.selMode.collectAsState()
    val selectedSongs by vm.selectedSongs.collectAsState()
    val selectedAlbums by vm.selectedAlbums.collectAsState()
    val toast by vm.toast.collectAsState()
    val error by vm.error.collectAsState()
    val online by vm.online.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showPlayer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showLyrics by remember { mutableStateOf(false) }
    val insightTarget by vm.insightTarget.collectAsState()
    val tabs = listOf(Tab.Home, Tab.Library, Tab.Playlists, Tab.Radio, Tab.Settings)
    val selCount = if (selMode == SelMode.SONGS) selectedSongs.size else selectedAlbums.size

    LaunchedEffect(toast) {
        toast?.let { snackbar.showSnackbar(it); vm.consumeToast() }
    }
    LaunchedEffect(error) {
        error?.let { snackbar.showSnackbar(it); vm.consumeError() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column {
                if (selMode != SelMode.NONE) SelectionBar(selCount, selMode, vm)
                else if (hasCurrent) MiniPlayer(vm, onExpand = { showPlayer = true })
                NavigationBar {
                    val backStack by nav.currentBackStackEntryAsState()
                    val route = backStack?.destination?.route
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                val before = nav.currentBackStackEntry?.id
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                                // Flat graph: tapping the tab that owns the current deep stack
                                // saves then instantly restores the identical stack (entry ids
                                // survive the round-trip) — the button reads as dead. Detect
                                // the no-op and surface the tab's root instead.
                                if (route != tab.route && nav.currentBackStackEntry?.id == before) {
                                    nav.popBackStack(tab.route, inclusive = false)
                                }
                            },
                            icon = { Icon(tab.icon, tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (!online) OfflineBanner()
            NavHost(nav, startDestination = Tab.Home.route, modifier = Modifier.weight(1f)) {
            composable(Tab.Home.route) { HomeScreen(vm, nav) }
            composable(Tab.Library.route) { LibraryScreen(vm, nav) }
            composable(Tab.Playlists.route) { PlaylistsScreen(vm, nav) }
            composable("album/{id}") { entry ->
                AlbumScreen(vm, entry.arguments?.getString("id") ?: "", nav, onBack = { nav.popBackStack() })
            }
            composable("artist/{id}") { entry ->
                ArtistScreen(vm, entry.arguments?.getString("id") ?: "", nav)
            }
            composable("playlist/{id}") { entry ->
                PlaylistScreen(vm, entry.arguments?.getString("id") ?: "", nav)
            }
            composable("genre/{name}") { entry ->
                GenreScreen(vm, entry.arguments?.getString("name") ?: "", nav)
            }
            composable("discover") { DiscoverScreen(vm, nav) }
            composable("crate/{key}") { entry ->
                CrateScreen(vm, entry.arguments?.getString("key") ?: "all", nav)
            }
            composable("settings") { SettingsScreen(vm, nav) }
            composable("ai") { AskScreen(vm, nav) }
            composable(Tab.Radio.route) { RadioScreen(vm, nav) }
            composable("similar") { SimilarSongsScreen(vm, nav) }
            composable("yt/{q}") { entry ->
                YouTubeScreen(entry.arguments?.getString("q") ?: "", nav)
            }
            }
        }
    }

    if (showPlayer && hasCurrent) {
        NowPlayingScreen(
            vm,
            onBack = { showPlayer = false },
            onOpenQueue = { showQueue = true },
            onOpenLyrics = { showLyrics = true },
            onOpenInsights = { vm.openInsightsCurrent() },
            onFindSimilar = { showPlayer = false; nav.navigateDistinct("similar") },
            onGoToAlbum = { id -> showPlayer = false; nav.navigateDistinct("album/$id") },
            onGoToArtist = { id -> showPlayer = false; nav.navigateDistinct("artist/$id") }
        )
    }
    if (showQueue) {
        QueueScreen(vm, onBack = { showQueue = false })
    }
    if (showLyrics) {
        LyricsScreen(vm, onBack = { showLyrics = false; vm.stopAiStream() })
    }
    if (insightTarget != null) {
        InsightsScreen(vm, onBack = { vm.closeInsights() })
    }
    BackHandler(enabled = showPlayer || showQueue || showLyrics || insightTarget != null || selMode != SelMode.NONE) {
        when {
            insightTarget != null -> vm.closeInsights()
            showLyrics -> { showLyrics = false; vm.stopAiStream() }
            showQueue -> showQueue = false
            showPlayer -> showPlayer = false
            selMode != SelMode.NONE -> vm.clearSelection()
        }
    }
}

@Composable
private fun OfflineBanner() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.CloudOff, null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "You're offline — some things won't load",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun UpdateDialog(vm: SonoraViewModel) {
    val update by vm.update.collectAsState()
    val busy by vm.updateBusy.collectAsState()
    val info = update ?: return
    AlertDialog(
        onDismissRequest = { if (!busy) vm.dismissUpdate() },
        title = { Text("Update available") },
        text = { Text("Sonora ${info.version} is available. Update now?") },
        confirmButton = {
            TextButton(onClick = { vm.downloadAndInstallUpdate() }, enabled = !busy) {
                Text(if (busy) "Downloading…" else "Update")
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUpdate() }, enabled = !busy) { Text("Later") }
        }
    )
}
