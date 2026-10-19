package com.sikamikaniko.sonora.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sikamikaniko.sonora.SonoraApp
import com.sikamikaniko.sonora.data.CredentialStore
import com.sikamikaniko.sonora.data.ServerConnection
import com.sikamikaniko.sonora.data.WorkstationErrors
import com.sikamikaniko.sonora.data.WorkstationRepository
import com.sikamikaniko.sonora.data.WorkstationTrack
import com.sikamikaniko.sonora.data.WorkstationPlaybackStore
import com.sikamikaniko.sonora.data.LocalMusicRepository
import com.sikamikaniko.sonora.data.LocalBilibiliClient
import com.sikamikaniko.sonora.data.BilibiliException
import com.sikamikaniko.sonora.data.playableIn
import com.sikamikaniko.sonora.playback.LocalMusicDownloads
import com.sikamikaniko.sonora.playback.PlaybackService
import com.sikamikaniko.sonora.playback.WorkstationMedia
import com.sikamikaniko.sonora.playback.WorkstationStreaming
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

data class WorkstationPlayerState(
    val ready: Boolean = false, val track: WorkstationTrack? = null,
    val queue: List<WorkstationTrack> = emptyList(), val index: Int = 0,
    val playing: Boolean = false, val buffering: Boolean = false,
    val positionMs: Long = 0, val durationMs: Long = 0,
    val seekable: Boolean = false, val canSkipNext: Boolean = false, val repeat: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false, val error: String? = null
)

data class WorkstationState(
    val server: String = ServerConnection.DEFAULT_URL,
    val connected: Boolean = false,
    val busy: Boolean = false,
    val tracks: List<WorkstationTrack> = emptyList(),
    val refreshedAt: Long = 0,
    val cached: Boolean = false,
    val standalone: Boolean = false,
    val downloads: Map<String, String> = emptyMap(),
    val downloadBytes: Long = 0,
    val message: String? = null,
    val error: String? = null
)

class WorkstationViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SonoraApp
    private val credentials = CredentialStore(application)
    private val repository = WorkstationRepository(application)
    private val localRepository = LocalMusicRepository(application)
    private val bilibili = LocalBilibiliClient()
    private var requestJob: Job? = null
    private val mutableState = MutableStateFlow(WorkstationState(standalone = app.standalone, connected = app.standalone))
    val state = mutableState.asStateFlow()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingAction: ((MediaController) -> Unit)? = null
    private var closed = false
    private val mutablePlayer = MutableStateFlow(WorkstationPlayerState())
    val playback = mutablePlayer.asStateFlow()
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { updatePlayer() }
    }

    init {
        if (app.standalone) {
            refreshLocal()
            connectController()
        } else app.connection?.let { connection ->
            val cached = repository.cached(connection)
            mutableState.value = WorkstationState(server = connection.baseUrl.toString(), connected = true,
                tracks = cached?.tracks.orEmpty(), refreshedAt = cached?.refreshedAt ?: 0, cached = cached != null)
            refresh()
            connectController()
        }
        viewModelScope.launch {
            while (isActive) {
                controller?.takeIf { it.isConnected }?.let { c -> mutablePlayer.value = mutablePlayer.value.copy(
                    positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0)) }
                if (app.standalone) {
                    try {
                        val (statuses, bytes) = withContext(Dispatchers.IO) {
                            LocalMusicDownloads.statuses(app) to LocalMusicDownloads.sizeBytes(app)
                        }
                        if (app.standalone) mutableState.value = mutableState.value.copy(downloads = statuses, downloadBytes = bytes)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Library and playback remain available if the download database is busy. */ }
                }
                delay(1000)
            }
        }
    }

    fun connect(address: String, password: String) {
        if (mutableState.value.busy) return
        val connection = try { ServerConnection.create(address, password) } catch (error: IllegalArgumentException) {
            mutableState.value = mutableState.value.copy(error = WorkstationErrors.message(error)); return
        }
        requestJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val library = repository.fetch(connection)
                withContext(Dispatchers.IO) { credentials.save(connection) }
                repository.save(library)
                app.connection = connection
                mutableState.value = WorkstationState(server = library.server, connected = true,
                    tracks = library.tracks, refreshedAt = library.refreshedAt)
                connectController()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, error = WorkstationErrors.message(error))
            }
        }
    }

    fun refresh() {
        if (app.standalone) { refreshLocal(); return }
        val connection = app.connection ?: return
        if (mutableState.value.busy) return
        requestJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null)
            try {
                val library = repository.fetch(connection)
                repository.save(library)
                mutableState.value = mutableState.value.copy(tracks = library.tracks,
                    refreshedAt = library.refreshedAt, busy = false, cached = false)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(busy = false,
                    cached = mutableState.value.refreshedAt > 0, error = WorkstationErrors.message(error))
            }
        }
    }

    private fun refreshLocal() {
        viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { localRepository.tracks() }
                if (app.standalone) mutableState.value = mutableState.value.copy(tracks = tracks, busy = false,
                    connected = true, standalone = true, server = "", error = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(busy = false, error = "手机音乐库读取失败，请保留数据后重试") }
        }
    }

    fun switchMode(standalone: Boolean) {
        if (app.standalone == standalone) return
        requestJob?.cancel()
        pendingAction = null
        controller?.stop()
        controller?.clearMediaItems()
        releaseController()
        app.standalone = standalone
        mutablePlayer.value = WorkstationPlayerState()
        mutableState.value = WorkstationState(standalone = standalone, connected = standalone,
            server = if (standalone) "" else app.connection?.baseUrl?.toString().orEmpty())
        if (standalone) { refreshLocal(); connectController() }
        else app.connection?.let { connection ->
            val cached = repository.cached(connection)
            mutableState.value = mutableState.value.copy(connected = true, tracks = cached?.tracks.orEmpty(),
                refreshedAt = cached?.refreshedAt ?: 0, cached = cached != null)
            refresh(); connectController()
        }
    }

    fun importMusic(raw: String) {
        if (!app.standalone || mutableState.value.busy) return
        requestJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(busy = true, error = null, message = "正在读取 B站音乐…")
            try {
                val count = withContext(Dispatchers.IO) { localRepository.add(bilibili.tracks(raw)) }
                if (app.standalone) {
                    refreshLocal()
                    mutableState.value = mutableState.value.copy(message = if (count == 0) "歌曲已在音乐库中" else "已添加 $count 首歌曲")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = mutableState.value.copy(busy = false, message = null,
                    error = if (error is IllegalArgumentException || error is BilibiliException) error.message else "无法读取 B站音源，请检查网络后重试")
            }
        }
    }

    fun editLocal(track: WorkstationTrack, title: String, group: String) {
        if (!app.standalone) return
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { localRepository.edit(track.id, title, group) }; refreshLocal() }
            catch (error: Exception) { mutableState.value = mutableState.value.copy(error = error.message ?: "修改失败") }
        }
    }

    fun deleteLocal(track: WorkstationTrack) {
        if (!app.standalone) return
        controller?.let { c ->
            for (i in c.mediaItemCount - 1 downTo 0) if (c.getMediaItemAt(i).mediaId == track.id) c.removeMediaItem(i)
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { localRepository.remove(track.id) }
                withContext(Dispatchers.IO) { LocalMusicDownloads.remove(app, track) }
                refreshLocal()
            } catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "删除失败，请重试") }
        }
    }

    fun cacheLocal(track: WorkstationTrack) {
        if (!app.standalone) return
        try { LocalMusicDownloads.enqueue(app, track); mutableState.value = mutableState.value.copy(message = "已加入完整缓存队列") }
        catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "缓存任务启动失败，请返回前台重试") }
    }
    fun clearLocalCache() {
        if (!app.standalone) return
        controller?.pause()
        controller?.stop()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LocalMusicDownloads.clear(app) }
                mutableState.value = mutableState.value.copy(message = "正在清理音频缓存，歌曲列表保留")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.value = mutableState.value.copy(error = "缓存清理失败，请稍后重试") }
        }
    }
    fun dismissMessage() { mutableState.value = mutableState.value.copy(error = null, message = null) }

    private fun canControl() = app.standalone || app.connection != null

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun disconnect() {
        requestJob?.cancel()
        pendingAction = null
        controller?.stop()
        controller?.clearMediaItems()
        releaseController()
        app.stopService(Intent(app, PlaybackService::class.java))
        app.connection = null
        credentials.clear()
        repository.clear()
        WorkstationPlaybackStore(app).clear()
        app.imageLoader.memoryCache?.clear()
        app.imageLoader.diskCache?.clear()
        mutableState.value = WorkstationState(server = mutableState.value.server)
        mutablePlayer.value = WorkstationPlayerState()
    }

    private fun connectController() {
        if (closed || controller?.isConnected == true || controllerFuture != null || !canControl()) return
        controller?.let { releaseController() }
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val future = MediaController.Builder(app, token).setListener(object : MediaController.Listener {
            override fun onDisconnected(disconnected: MediaController) {
                // A disconnected MediaController silently ignores all transport commands.
                // Keep the displayed queue until a fresh service/controller restores it.
                if (controller !== disconnected) return
                disconnected.removeListener(playerListener)
                controller = null
                mutablePlayer.value = mutablePlayer.value.copy(ready = false, playing = false,
                    buffering = false, error = "后台播放器已断开，点击播放或切歌重新连接")
            }
        }).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future || !canControl()) return@addListener
            try {
                val connected = future.get()
                check(connected.isConnected) { "Player disconnected during connection" }
                controllerFuture = null
                controller = connected.also { it.addListener(playerListener) }
                updatePlayer()
                val action = pendingAction
                pendingAction = null
                action?.invoke(connected)
            } catch (_: Exception) {
                controllerFuture = null
                pendingAction = null
                mutablePlayer.value = mutablePlayer.value.copy(ready = false, playing = false,
                    buffering = false, error = "播放器连接失败，请点击播放或切歌重试")
            }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun updatePlayer() {
        val c = controller?.takeIf { it.isConnected } ?: return
        mutablePlayer.value = WorkstationPlayerState(ready = true,
            track = WorkstationMedia.track(c.currentMediaItem),
            queue = (0 until c.mediaItemCount).mapNotNull { WorkstationMedia.track(c.getMediaItemAt(it)) },
            index = c.currentMediaItemIndex.coerceAtLeast(0), playing = c.playWhenReady && c.playerError == null,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0), durationMs = c.duration.coerceAtLeast(0),
            seekable = c.isCurrentMediaItemSeekable, canSkipNext = c.hasNextMediaItem(),
            repeat = c.repeatMode, shuffle = c.shuffleModeEnabled,
            error = c.playerError?.let { WorkstationStreaming.errorMessage(it, app.standalone) })
    }

    fun play(tracks: List<WorkstationTrack>, startId: String) {
        val connection = if (app.standalone) null else app.connection ?: return
        val queue = tracks.filter { it.playableIn(if (app.standalone) "" else connection!!.baseUrl.toString()) }
        val index = queue.indexOfFirst { it.id == startId }
        if (index < 0) {
            mutablePlayer.value = mutablePlayer.value.copy(error = "这首歌曲没有可播放的音源")
            return
        }
        withController { c ->
            c.setMediaItems(queue.map { WorkstationMedia.item(it, connection) }, index, 0)
            c.prepare()
            c.play()
        }
    }

    /** Rebind on returning to the app, without starting paused music automatically. */
    fun onForeground() {
        connectController()
        if (app.standalone) try { LocalMusicDownloads.resume(app) } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(error = "待缓存任务未能恢复，可在歌曲菜单中重试缓存")
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        if (closed || !canControl()) return
        val current = controller
        if (current?.isConnected == true) action(current) else {
            pendingAction = action
            connectController()
        }
    }

    fun togglePlay() {
        withController { c ->
            if (c.playWhenReady && c.playerError == null) c.pause() else {
                if (c.playbackState == Player.STATE_ENDED) c.seekTo(0)
                if (c.playbackState == Player.STATE_IDLE || c.playerError != null) c.prepare()
                c.play()
            }
        }
    }

    fun retryPlayback() {
        withController { it.prepare(); it.play() }
    }

    fun next() { withController { if (it.hasNextMediaItem()) { it.seekToNextMediaItem(); it.prepare(); it.play() } } }
    fun previous() { withController { c ->
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
        c.prepare(); c.play()
    } }
    fun seek(positionMs: Long) { withController { c -> if (c.isCurrentMediaItemSeekable) c.seekTo(positionMs.coerceIn(0, c.duration.coerceAtLeast(0))) } }
    fun selectQueued(index: Int) { withController { c -> if (index in 0 until c.mediaItemCount) { c.seekTo(index, 0); c.prepare(); c.play() } } }
    fun removeQueued(index: Int) { withController { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    fun toggleShuffle() { withController { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun cycleRepeat() { withController { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
        else -> Player.REPEAT_MODE_OFF
    } } }

    private fun releaseController() {
        val previous = controller
        val future = controllerFuture
        // Clear first: intentional release also invokes onDisconnected.
        controllerFuture = null
        controller = null
        previous?.removeListener(playerListener)
        previous?.release()
        future?.let(MediaController::releaseFuture)
    }

    override fun onCleared() {
        closed = true
        pendingAction = null
        releaseController()
        super.onCleared()
    }
}
