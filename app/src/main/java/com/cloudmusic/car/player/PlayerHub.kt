package com.cloudmusic.car.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.cloudmusic.car.api.LyricLine
import com.cloudmusic.car.api.LyricsParser
import com.cloudmusic.car.api.NeteaseApi
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.api.Track
import com.cloudmusic.car.api.sized
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.AudioQuality
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.data.Settings
import com.paopao.music.nowplaying.NowPlayingNeighbors
import com.paopao.music.nowplaying.PlayModes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class PlayMode { NORMAL, FM, HEARTBEAT }

data class PlaybackNotice(val kind: String, val text: String)

/**
 * 全局播放器。队列直接交给 ExoPlayer（通知栏、方向盘按键的上一首/下一首因此天然可用），
 * 每首歌以 `cloudmusic://song/<id>` 占位，真正加载时才通过 [ResolvingDataSource] 换成网易云的播放地址。
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private class UnplayableTrackException(id: Long) : IOException("no url for $id")

    private const val SCHEME = "cloudmusic"
    private const val URL_TTL_MS = 15 * 60 * 1000L
    private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var player: ExoPlayer
        private set

    private val tracks = ConcurrentHashMap<Long, Track>()
    private data class ResolvedUrl(val url: String, val resolvedAt: Long, val cacheKey: String)

    private val urlCache = ConcurrentHashMap<String, ResolvedUrl>()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 按歌单原顺序排列的队列（随机模式下也不打乱），以及当前歌曲在其中的位置。 */
    private val _queue = MutableStateFlow<List<Pair<Int, Track>>>(emptyList())
    val queue: StateFlow<List<Pair<Int, Track>>> = _queue.asStateFlow()

    private val _mode = MutableStateFlow(PlayMode.NORMAL)
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private val _playMode = MutableStateFlow(PlayModes.SEQUENTIAL)
    val playMode: StateFlow<Int> = _playMode.asStateFlow()

    private val _neighbors = MutableStateFlow(NowPlayingNeighbors(null, null))
    val neighbors: StateFlow<NowPlayingNeighbors> = _neighbors.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _status = MutableStateFlow<PlaybackNotice?>(null)
    val status: StateFlow<PlaybackNotice?> = _status.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var sourceId = 0L
    private var lyricsJob: Job? = null
    private var fmLoading = false
    private var consecutiveFailures = 0
    private var connectivity: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var waitingMediaId: String? = null
    private var waitingPosition = 0L
    private var waitingForRecovery = false
    private var progressMediaId: String? = null
    private var progressPosition = 0L
    private var continuousPlaybackMs = 0L
    private var lastTrack: Track? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        MusicCache.init(context)
        val http = OkHttpDataSource.Factory(NeteaseClient.http)
        val upstream = DefaultDataSource.Factory(context, http)
        val cached = CacheDataSource.Factory()
            .setCache(MusicCache.audio)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val resolving = ResolvingDataSource.Factory(cached) { spec -> resolve(spec) }
        player = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 「上一首」永远切到上一首，不按默认规则放过 3 秒就改成从头重放；桌面底栏、方向盘走的也是它。
            .setMaxSeekToPreviousPositionMs(Long.MAX_VALUE)
            .build()
        player.addListener(listener)
        PlaybackSnapshotStore.init(context)
        _playMode.value = PlaybackSnapshotStore.restorePlayMode()
        applyPlayMode()
        PlaybackSnapshotStore.restore()?.let(::restorePlayback)
        scope.launch {
            while (true) {
                delay(5_000)
                updatePlaybackProgress()
                persistPosition()
            }
        }
    }

    @Synchronized
    fun release() {
        if (!initialized) return
        cancelNetworkWait()
        _status.value = null
        connectivity = null
        runCatching(::persistPosition)
        lyricsJob?.cancel()
        lyricsJob = null
        scope.cancel()
        runCatching { player.removeListener(listener) }
        runCatching { player.release() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fmLoading = false
        _isPlaying.value = false
        initialized = false
    }

    private fun restorePlayback(snapshot: PlaybackSnapshot) {
        sourceId = snapshot.sourceId
        _mode.value = snapshot.mode
        _sourceName.value = snapshot.sourceName
        applyPlayMode()
        player.setMediaItems(snapshot.tracks.map(::mediaItem), snapshot.currentIndex, snapshot.positionMs)
        player.prepare()
        player.pause()
    }

    fun toast(message: String) {
        _messages.tryEmit(message)
    }

    // ---------- 地址解析（运行在加载线程） ----------

    private fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val id = spec.uri.lastPathSegment?.toLongOrNull() ?: throw IOException("bad uri")
        val requestedQuality = Settings.quality.value
        val requestKey = "$id:${requestedQuality.level}"
        urlCache[requestKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.resolvedAt < URL_TTL_MS) {
                return spec.buildUpon()
                    .setUri(Uri.parse(cached.url))
                    .setKey(cached.cacheKey)
                    .build()
            }
        }
        var quality = requestedQuality
        var result = NeteaseApi.songUrlBlocking(id, quality.level)
        if (result.url == null && quality != AudioQuality.STANDARD) {
            quality = AudioQuality.STANDARD
            result = NeteaseApi.songUrlBlocking(id, AudioQuality.STANDARD.level)
        }
        val url = result.url ?: throw UnplayableTrackException(id)
        if (result.isTrial) toast("《${tracks[id]?.name.orEmpty()}》为 VIP 歌曲，当前为试听片段")
        val cacheKey = "song-$id-${quality.level}"
        // 存储快满先删最久没听的；真写不进去时缓存层会自动改走网络，不会跳歌
        MusicCache.ensureDiskRoom(cacheKey)
        urlCache[requestKey] = ResolvedUrl(url, System.currentTimeMillis(), cacheKey)
        return spec.buildUpon()
            .setUri(Uri.parse(url))
            .setKey(cacheKey)
            .build()
    }

    private fun mediaItem(track: Track): MediaItem {
        tracks[track.id] = track
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri("$SCHEME://song/${track.id}")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtist(track.artistNames)
                    .setAlbumTitle(track.album.name)
                    .setArtworkUri(track.album.picUrl.sized(512)?.let(Uri::parse))
                    .build(),
            )
            .build()
    }

    // ---------- 播放入口 ----------

    /**
     * 播放一组歌曲。不可播放（VIP/无版权等）的歌曲会被跳过。
     * @param start 从哪首开始；为 null 且 [shuffle] 时随机开始。
     */
    fun play(
        list: List<Track>,
        start: Track? = null,
        shuffle: Boolean = false,
        source: String? = null,
        sourceId: Long = 0,
        mode: PlayMode = PlayMode.NORMAL,
    ) {
        if (start != null) {
            AccountStore.unplayableReason(start)?.let {
                toast("《${start.name}》无法播放：$it")
                return
            }
        }
        val playable = list.filter { AccountStore.unplayableReason(it) == null }
        if (playable.isEmpty()) {
            toast("没有可播放的歌曲")
            return
        }
        val startIndex = when {
            start != null -> playable.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
            shuffle -> playable.indices.random()
            else -> 0
        }
        this.sourceId = sourceId
        cancelNetworkWait()
        _status.value = null
        _mode.value = mode
        _sourceName.value = source
        consecutiveFailures = 0
        if (shuffle) setPlayMode(PlayModes.SHUFFLE) else applyPlayMode()
        player.setMediaItems(playable.map(::mediaItem), startIndex, 0)
        persistQueue(playable, startIndex)
        player.prepare()
        player.play()
    }

    fun startFm() {
        scope.launch {
            runCatching { NeteaseApi.personalFm() }
                .onSuccess { play(it, source = "私人 FM", mode = PlayMode.FM) }
                .onFailure { toast(it.message ?: "私人 FM 加载失败") }
        }
    }

    fun startHeartbeat() {
        val liked = AccountStore.likedPlaylist
        val seed = AccountStore.likedIds.value.randomOrNull()
        if (liked == null || seed == null) {
            toast("先去红心几首歌，才能开启心动模式")
            return
        }
        scope.launch {
            runCatching { NeteaseApi.intelligenceList(seed, liked.id) }
                .onSuccess { play(it, source = "心动模式", sourceId = liked.id, mode = PlayMode.HEARTBEAT) }
                .onFailure { toast(it.message ?: "心动模式加载失败") }
        }
    }

    // ---------- 控制 ----------

    fun togglePlay() {
        if (waitingMediaId != null) cancelNetworkWait()
        if (player.isPlaying) {
            player.pause()
        } else {
            consecutiveFailures = 0
            _status.value = null
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun next() {
        cancelNetworkWait()
        _status.value = null
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else if (_mode.value == PlayMode.FM) extendFm(true)
    }

    fun previous() {
        cancelNetworkWait()
        _status.value = null
        player.seekToPrevious()
    }

    fun seekTo(ms: Long) {
        cancelNetworkWait()
        player.seekTo(ms)
    }

    fun jumpTo(index: Int) {
        cancelNetworkWait()
        _status.value = null
        consecutiveFailures = 0
        player.seekTo(index, 0)
        player.play()
    }

    /** 顺序播放 → 单曲循环 → 随机播放 → 顺序播放，持久化后重启仍保持。 */
    fun cyclePlayMode() {
        cancelNetworkWait()
        setPlayMode(
            when (_playMode.value) {
                PlayModes.SEQUENTIAL -> PlayModes.REPEAT_ONE
                PlayModes.REPEAT_ONE -> PlayModes.SHUFFLE
                else -> PlayModes.SEQUENTIAL
            },
        )
    }

    private fun setPlayMode(mode: Int) {
        _playMode.value = mode
        PlaybackSnapshotStore.savePlayMode(mode)
        applyPlayMode()
    }

    /**
     * 播放模式直接落到 ExoPlayer 上，自动切歌、播放失败跳过、通知栏、方向盘、宿主底栏因此都按同一套顺序走。
     * 私人 FM 是边放边续的电台，始终顺序、不循环，播放模式只对普通队列生效。
     */
    private fun applyPlayMode() {
        val radio = _mode.value == PlayMode.FM
        player.shuffleModeEnabled = !radio && _playMode.value == PlayModes.SHUFFLE
        player.repeatMode = when {
            radio -> Player.REPEAT_MODE_OFF
            _playMode.value == PlayModes.REPEAT_ONE -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_ALL
        }
    }

    /** 私人 FM：不喜欢当前歌曲并跳到下一首。 */
    fun fmTrash() {
        val track = _current.value ?: return
        scope.launch { runCatching { NeteaseApi.fmTrash(track.id) } }
        next()
    }

    private fun extendFm(skipAfter: Boolean) {
        if (fmLoading) return
        fmLoading = true
        scope.launch {
            val more = runCatching { NeteaseApi.personalFm() }.getOrDefault(emptyList())
                .filter { AccountStore.unplayableReason(it) == null && it.id !in currentIds() }
            if (more.isNotEmpty() && _mode.value == PlayMode.FM) {
                player.addMediaItems(more.map(::mediaItem))
                persistQueue()
                if (skipAfter) player.seekToNextMediaItem()
            }
            fmLoading = false
        }
    }

    private fun currentIds(): Set<Long> =
        (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).mediaId.toLongOrNull() }.toSet()

    private fun persistQueue(
        queueTracks: List<Track> = (0 until player.mediaItemCount).mapNotNull {
            player.getMediaItemAt(it).mediaId.toLongOrNull()?.let(tracks::get)
        },
        currentIndex: Int = player.currentMediaItemIndex,
    ) {
        PlaybackSnapshotStore.saveQueue(
            queueTracks,
            currentIndex,
            _sourceName.value,
            sourceId,
            _mode.value,
        )
        persistPosition()
    }

    private fun persistPosition() {
        PlaybackSnapshotStore.savePosition(player.currentMediaItem?.mediaId, player.currentPosition)
    }

    // ---------- 状态同步 ----------

    private fun rebuildQueue() {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) {
            _queue.value = emptyList()
            return
        }
        _queue.value = (0 until timeline.windowCount).mapNotNull { index ->
            tracks[player.getMediaItemAt(index).mediaId.toLongOrNull()]?.let { index to it }
        }
    }

    /** 上一首 / 下一首直接取自播放器，已含随机顺序和循环规则；只有一首歌时没有邻居。 */
    private fun updateNeighbors() {
        val current = player.currentMediaItemIndex
        fun neighbor(index: Int) = index.takeIf { it != C.INDEX_UNSET && it != current }
        _neighbors.value = NowPlayingNeighbors(
            previousIndex = neighbor(player.previousMediaItemIndex),
            nextIndex = neighbor(player.nextMediaItemIndex),
        )
    }

    private fun onTrackChanged(finishedPrevious: Boolean = false) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val track = id?.let { tracks[it] }
        val prev = lastTrack
        if (finishedPrevious && prev != null) {
            val secs = (prev.durationMs / 1000).coerceAtLeast(1)
            scope.launch { NeteaseApi.scrobble("play", prev.id, sourceId, secs) }
        }
        lastTrack = track
        _current.value = track
        if (track != null) persistQueue()
        lyricsJob?.cancel()
        _lyrics.value = emptyList()
        updateSessionLyrics(id, emptyList())
        if (track != null) {
            scope.launch { NeteaseApi.scrobble("startplay", track.id, sourceId) }
            lyricsJob = scope.launch {
                val cached = withContext(Dispatchers.IO) { MusicCache.readLyrics(track.id) }
                val lines = cached ?: runCatching { NeteaseApi.lyric(track.id) }
                    .onSuccess { fetched ->
                        withContext(Dispatchers.IO) { MusicCache.writeLyrics(track.id, fetched) }
                    }
                    .getOrDefault(emptyList())
                if (player.currentMediaItem?.mediaId == track.id.toString()) {
                    _lyrics.value = lines
                    updateSessionLyrics(track.id, lines)
                }
            }
        }
        if (_mode.value == PlayMode.FM && player.mediaItemCount - player.currentMediaItemIndex <= 2) {
            extendFm(false)
        }
    }

    private fun updateSessionLyrics(trackId: Long?, lines: List<LyricLine>) {
        if (trackId == null || player.currentMediaItem?.mediaId != trackId.toString()) return
        val index = player.currentMediaItemIndex
        if (index == C.INDEX_UNSET) return

        val item = player.currentMediaItem ?: return
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            remove(METADATA_KEY_LYRIC)
            LyricsParser.toLrc(lines).takeIf(String::isNotEmpty)?.let {
                putString(METADATA_KEY_LYRIC, it)
            }
        }
        val metadata = item.mediaMetadata.buildUpon().setExtras(extras).build()
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
    }

    private fun hasValidatedNetwork(): Boolean {
        val manager = connectivity ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun cancelNetworkWait() {
        waitingMediaId = null
        if (_status.value?.kind == "network_wait") _status.value = null
        networkCallback?.let { callback -> connectivity?.unregisterNetworkCallback(callback) }
        networkCallback = null
    }

    private fun waitForNetwork() {
        if (waitingMediaId != null) return
        waitingMediaId = player.currentMediaItem?.mediaId ?: return
        _status.value = PlaybackNotice("network_wait", "网络断开，恢复后自动继续")
        waitingPosition = player.currentPosition.coerceAtLeast(0L)
        waitingForRecovery = !hasValidatedNetwork()
        player.pause()
        val manager = connectivity ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val currentCallback = this
                scope.launch {
                    if (networkCallback !== currentCallback || waitingMediaId == null) return@launch
                    if (!validated) waitingForRecovery = true
                    else if (waitingForRecovery && hasValidatedNetwork()) {
                        val mediaId = waitingMediaId
                        val position = waitingPosition
                        cancelNetworkWait()
                        if (player.currentMediaItem?.mediaId == mediaId) {
                            player.seekTo(position)
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                val currentCallback = this
                scope.launch {
                    if (networkCallback === currentCallback) waitingForRecovery = true
                }
            }
        }
        networkCallback = callback
        manager.registerDefaultNetworkCallback(callback)
        if (waitingForRecovery && hasValidatedNetwork()) callback.onCapabilitiesChanged(
            manager.activeNetwork ?: return,
            manager.getNetworkCapabilities(manager.activeNetwork) ?: return,
        )
    }

    private fun resetPlaybackProgress() {
        progressMediaId = player.currentMediaItem?.mediaId
        progressPosition = player.currentPosition
        continuousPlaybackMs = 0L
    }

    private fun updatePlaybackProgress(includeStopped: Boolean = false) {
        if (!includeStopped && !player.isPlaying) return
        val mediaId = player.currentMediaItem?.mediaId
        val position = player.currentPosition
        if (mediaId != progressMediaId) {
            resetPlaybackProgress()
            return
        }
        val advanced = position - progressPosition
        continuousPlaybackMs = if (advanced in 1L..6_000L) continuousPlaybackMs + advanced else 0L
        progressPosition = position
        if (continuousPlaybackMs >= 10_000) {
            consecutiveFailures = 0
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && _isPlaying.value) updatePlaybackProgress(includeStopped = true)
            resetPlaybackProgress()
            _isPlaying.value = isPlaying
            persistPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            cancelNetworkWait()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) _status.value = null
            resetPlaybackProgress()
            onTrackChanged(finishedPrevious = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            updateNeighbors()
        }


        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (playWhenReady && waitingMediaId != null) cancelNetworkWait()
            if (playWhenReady && _status.value?.kind == "failures_paused") _status.value = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            rebuildQueue()
            if (_current.value?.id?.toString() != player.currentMediaItem?.mediaId) onTrackChanged()
            updateNeighbors()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = updateNeighbors()

        override fun onRepeatModeChanged(repeatMode: Int) = updateNeighbors()

        override fun onPlayerError(error: PlaybackException) {
            val track = _current.value
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null) urlCache.keys.removeAll { it.startsWith("$id:") }
            // 只有真没网才停下等：网络是通的却报超时/连接失败，等不到"恢复"回调会永远卡住，按这首放不了处理。
            if (!hasValidatedNetwork()) {
                waitForNetwork()
                return
            }
            consecutiveFailures++
            if (consecutiveFailures >= 5) {
                player.pause()
                _status.value = PlaybackNotice("failures_paused", "连续几首都放不了，已暂停")
                return
            }
            toast("《${track?.name.orEmpty()}》无法播放，已跳过")
            when {
                player.hasNextMediaItem() -> {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
                _mode.value == PlayMode.FM -> {
                    player.prepare()
                    extendFm(true)
                }
            }
        }
    }
}
