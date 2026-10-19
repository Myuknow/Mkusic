package com.sikamikaniko.sonora.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.sikamikaniko.sonora.BuildConfig
import com.sikamikaniko.sonora.data.WorkstationTrack

@Composable
fun LocalMusicImportDialog(dismiss: () -> Unit, add: (String) -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("添加 B站音乐") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("粘贴 BV/av/au 号、视频分享链接或公开收藏夹链接。音频由手机直接获取。")
            OutlinedTextField(input, { input = it }, modifier = Modifier.fillMaxWidth(), label = { Text("编号或链接") }, minLines = 2, maxLines = 5)
        }
    }, confirmButton = { TextButton(onClick = { add(input.trim()) }, enabled = input.isNotBlank()) { Text("添加") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
fun LocalTrackMenu(track: WorkstationTrack, cache: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Rounded.MoreVert, "管理 ${track.displayTitle}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("完整缓存 / 重试缓存") }, onClick = { expanded = false; cache() })
            DropdownMenuItem(text = { Text("修改歌名和分组") }, onClick = { expanded = false; edit() })
            DropdownMenuItem(text = { Text("删除歌曲与缓存") }, onClick = { expanded = false; delete() })
        }
    }
}

@Composable
fun LocalMusicEditDialog(track: WorkstationTrack, dismiss: () -> Unit, save: (String, String) -> Unit) {
    var title by rememberSaveable(track.id) { mutableStateOf(track.displayTitle) }
    var group by rememberSaveable(track.id) { mutableStateOf(track.group.orEmpty()) }
    AlertDialog(onDismissRequest = dismiss, title = { Text("修改歌曲") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("歌名") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(group, { group = it }, label = { Text("分组") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(onClick = { save(title, group) }, enabled = title.isNotBlank()) { Text("保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
fun LocalMusicSettings(state: WorkstationState, serverMode: () -> Unit, clearCache: () -> Unit) {
    var clearing by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Text("手机独立模式", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text("无需部署服务器。歌曲列表、分组和完整音频缓存都保存在这台手机上。")
        }
        item {
            Text("音频缓存", style = MaterialTheme.typography.titleMedium)
            Text("已完整缓存 ${state.downloads.count { it.value == "已完整缓存" }} 首 · %.1f MB".format(state.downloadBytes / 1048576.0))
            Text("播放时自动缓存已读取的音频，最多 1 GB。需要离线播放时，在歌曲菜单中选择「完整缓存」，等待显示「已完整缓存」。完整缓存会保留到手动清理。",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { clearing = true }) { Text("清理手机音频缓存") }
        }
        item { OutlinedButton(onClick = serverMode) { Text("连接自己的音乐服务器") } }
        item {
            Text("Mkusic ${BuildConfig.VERSION_NAME} · 基于 Sonora · MIT License", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uriHandler.openUri("https://github.com/SikamikanikoBG/Sonora") }) { Text("Sonora 原作者") }
        }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, title = { Text("清理音频缓存") },
        text = { Text("将停止当前播放并清理手机上的音频缓存，歌曲列表和分组会保留。") },
        confirmButton = { TextButton(onClick = { clearing = false; clearCache() }) { Text("清理") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
