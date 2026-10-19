package com.sikamikaniko.sonora.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.sikamikaniko.sonora.data.WorkstationTrack
import com.sikamikaniko.sonora.data.playableIn
import com.sikamikaniko.sonora.data.artworkIn
import com.sikamikaniko.sonora.data.pendingLocalDownloads
import com.sikamikaniko.sonora.BuildConfig
import com.sikamikaniko.sonora.R
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkstationRoot(vm: WorkstationViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val player by vm.playback.collectAsStateWithLifecycle()
    var settings by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var offlineOnly by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkstationTrack?>(null) }
    var deleting by remember { mutableStateOf<WorkstationTrack?>(null) }
    LaunchedEffect(state.standalone) {
        settings = false; search = ""; selectedGroup = null; showPlayer = false; showQueue = false
        offlineOnly = false; showAdd = false; editing = null; deleting = null
    }
    LaunchedEffect(state.connected) {
        if (!state.connected) { settings = false; search = ""; selectedGroup = null; showPlayer = false; showQueue = false }
    }
    BackHandler(state.connected && settings) { settings = false }
    Scaffold(
        topBar = {
            if (state.connected) TopAppBar(
                title = { Text(if (settings) "设置" else if (state.standalone) "手机音乐库" else "服务器音乐库") },
                actions = {
                    if (state.standalone) IconButton(onClick = { showAdd = true }, enabled = !state.busy) {
                        Icon(Icons.Rounded.Add, "添加 B站音乐")
                    }
                    IconButton(onClick = vm::refresh, enabled = !state.busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新音乐库")
                    }
                }
            )
        },
        bottomBar = {
            if (state.connected) Column {
                player.track?.let { MiniWorkstationPlayer(player, state.server, vm::togglePlay, vm::next) { showPlayer = true } }
                NavigationBar {
                NavigationBarItem(selected = !settings, onClick = { settings = false },
                    icon = { Icon(Icons.Rounded.LibraryMusic, "音乐库") }, label = { Text("音乐库") })
                NavigationBarItem(selected = settings, onClick = { settings = true },
                    icon = { Icon(Icons.Rounded.Settings, "设置") }, label = { Text("设置") })
            }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.connected) {
                ConnectionForm(state, vm::connect) { vm.switchMode(true) }
            } else {
                player.error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            TextButton(onClick = vm::retryPlayback) { Text("重试播放") }
                        }
                    }
                }
                state.error?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                            Row {
                                TextButton(onClick = vm::refresh, enabled = !state.busy) { Text("重试") }
                                if (!state.standalone) TextButton(onClick = vm::disconnect) { Text("重新连接") }
                                TextButton(onClick = vm::dismissMessage) { Text("关闭") }
                            }
                        }
                    }
                }
                state.message?.let { message ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = vm::dismissMessage) { Text("关闭") }
                    }
                }
                if (settings && state.standalone) LocalMusicSettings(state, { vm.switchMode(false) }, vm::clearLocalCache)
                else if (settings) Column {
                    TextButton(onClick = { vm.switchMode(true) }, modifier = Modifier.padding(horizontal = 16.dp)) { Text("切换到手机独立模式") }
                    ConnectionSettings(state, vm::disconnect)
                }
                else {
                    val groups = remember(state.tracks) { state.tracks.map { it.groupName }.distinct().sorted() }
                    LaunchedEffect(groups) { if (selectedGroup !in groups) selectedGroup = null }
                    val filtered = remember(state.tracks, search, selectedGroup, offlineOnly, state.downloads) {
                        state.tracks.filter { it.matches(search) && (selectedGroup == null || it.groupName == selectedGroup) &&
                            (!offlineOnly || state.downloads[it.id] == "已完整缓存") }
                    }
                    OutlinedTextField(value = search, onValueChange = { search = it }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("搜索歌曲、歌手或 BV 号") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) {
                            Icon(Icons.Rounded.Close, "清空搜索")
                        } })
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = selectedGroup == null, onClick = { selectedGroup = null }, label = { Text("全部") }) }
                        if (state.standalone) item { FilterChip(selected = offlineOnly, onClick = { offlineOnly = !offlineOnly }, label = { Text("已完整缓存") }) }
                        items(groups) { group -> FilterChip(selected = selectedGroup == group,
                            onClick = { selectedGroup = group }, label = { Text(group) }) }
                    }
                    Text("${filtered.size} 首歌曲${if (state.cached) " · 显示上次同步的音乐库" else ""}",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                        TextButton(onClick = {
                            filtered.firstOrNull { it.playableIn(state.server) }?.let { vm.play(filtered, it.id) }
                        }, enabled = filtered.any { it.playableIn(state.server) }) {
                            Icon(Icons.Rounded.PlayArrow, null); Text("播放当前列表")
                        }
                        if (state.standalone) TextButton(onClick = { vm.cacheLocalList(filtered) },
                            enabled = !state.busy && !state.addingDownloads && pendingLocalDownloads(filtered, state.downloads).isNotEmpty()) {
                            Icon(Icons.Rounded.Download, null)
                            Text(if (state.addingDownloads) "加入队列中…" else "一键缓存")
                        }
                    }
                    if (filtered.isEmpty() && !state.busy) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (state.tracks.isEmpty() && state.standalone) "点右上角 +，添加 BV 号或公开收藏夹链接"
                            else if (state.tracks.isEmpty()) "音乐库暂无歌曲" else "没有找到匹配的歌曲")
                    } else LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(filtered, key = { it.id }) { track -> TrackRow(track, state.server,
                            active = track.id == player.track?.id, onPlay = { vm.play(filtered, track.id) },
                            cacheStatus = state.downloads[track.id], actions = if (state.standalone) ({
                                LocalTrackMenu(track, { vm.cacheLocal(track) }, { editing = track }, { deleting = track })
                            }) else null) }
                    }
                }
            }
        }
    }
    if (showPlayer && state.connected && player.track != null) {
        WorkstationPlayerDialog(player, state.server, vm, onDismiss = { showPlayer = false }, onQueue = { showQueue = true })
    }
    if (showQueue && state.connected) WorkstationQueueSheet(player, vm, onDismiss = { showQueue = false })
    if (showAdd && state.standalone) LocalMusicImportDialog({ showAdd = false }) { raw -> showAdd = false; vm.importMusic(raw) }
    editing?.let { track -> LocalMusicEditDialog(track, { editing = null }) { title, group -> vm.editLocal(track, title, group); editing = null } }
    deleting?.let { track -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除歌曲") },
        text = { Text("删除「${track.displayTitle}」及它在手机上的音频缓存？") },
        confirmButton = { TextButton(onClick = { vm.deleteLocal(track); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun ConnectionForm(state: WorkstationState, connect: (String, String) -> Unit, localMode: () -> Unit) {
    var address by rememberSaveable { mutableStateOf(state.server) }
    // Password deliberately stays out of saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Spacer(Modifier.height(32.dp)) }
        item {
            Image(painterResource(R.drawable.mkusic_icon), contentDescription = "Mkusic 图标", modifier = Modifier.size(80.dp))
            Spacer(Modifier.height(16.dp))
            Text("Mkusic", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("连接自己的音乐服务器，或返回手机独立模式。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { OutlinedTextField(value = address, onValueChange = { address = it }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(), label = { Text("服务器地址") }, placeholder = { Text("https://music.example.com/") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
        item { OutlinedTextField(value = password, onValueChange = { password = it }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(), label = { Text("访问密码") }, singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { showPassword = !showPassword }) {
                Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (showPassword) "隐藏密码" else "显示密码")
            } }) }
        state.error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { connect(address, password) }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(if (state.busy) "正在连接…" else "连接音乐库") } }
        item { TextButton(onClick = localMode, modifier = Modifier.fillMaxWidth()) { Text("不连接服务器，使用手机独立模式") } }
        item { Text("公网地址使用 HTTPS；局域网 IP 支持 HTTP。HTTP 连接未加密，请仅在可信 Wi-Fi 下使用。连接成功后，访问密码会加密保存在这台手机上。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun TrackRow(track: WorkstationTrack, server: String, active: Boolean, onPlay: () -> Unit,
    cacheStatus: String? = null, actions: (@Composable () -> Unit)? = null) {
    val playable = track.playableIn(server)
    Row(Modifier.fillMaxWidth().clickable(enabled = playable, onClick = onPlay)
        .background(if (active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            AsyncImage(model = track.artworkIn(server), contentDescription = "${track.displayTitle} 封面",
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(track.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.groupName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(cacheStatus ?: if (playable) track.sourceName else "缺少音源", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        if (active) Icon(Icons.Rounded.Equalizer, "当前歌曲", tint = MaterialTheme.colorScheme.primary)
        else if (playable) Icon(Icons.Rounded.PlayArrow, "播放 ${track.displayTitle}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        actions?.invoke()
    }
}

@Composable
private fun ConnectionSettings(state: WorkstationState, disconnect: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("当前服务器", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp)); Text(state.server)
        }
        item {
            Text("最近同步", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(if (state.refreshedAt > 0) DateFormat.getDateTimeInstance().format(Date(state.refreshedAt)) else "尚未同步")
            Text("共 ${state.tracks.size} 首歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { Text(if (state.server.startsWith("http://"))
            "当前使用局域网 HTTP，连接未加密，请仅在可信 Wi-Fi 下使用。密码仅发送给当前服务器。"
            else "当前使用 HTTPS，密码仅发送给当前服务器。",
            style = MaterialTheme.typography.bodyMedium) }
        item { OutlinedButton(onClick = disconnect) {
            Icon(Icons.AutoMirrored.Rounded.ExitToApp, null); Spacer(Modifier.width(8.dp)); Text("断开连接并清除保存的密码")
        } }
        item {
            Text("Mkusic ${BuildConfig.VERSION_NAME}\n基于 Sonora · MIT License",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProjectLinks()
        }
    }
}
