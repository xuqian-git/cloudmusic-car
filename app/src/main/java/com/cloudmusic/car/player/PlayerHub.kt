package com.cloudmusic.car.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
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
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
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
import com.cloudmusic.car.link.Codecs
import com.paopao.music.link.EngineLink
import com.paopao.music.nowplaying.KrcSource
import com.paopao.music.nowplaying.METADATA_KEY_KRC_LYRIC
import com.paopao.music.nowplaying.NowPlayingNeighbors
import com.paopao.music.nowplaying.PlayModes
import com.paopao.music.nowplaying.WordLyricsParser
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
 * 播放进度快照：[atElapsedMs]（elapsedRealtime）那一刻在 [positionMs]，[advancing] 时按 [speed] 往前走。
 * 界面进程拿不到播放器，进度条和逐字歌词按它本地外推，引擎只在状态变化时推一次。
 */
data class PlaybackPosition(
    val positionMs: Long,
    val durationMs: Long,
    val atElapsedMs: Long,
    val advancing: Boolean,
    val speed: Float,
) {
    fun now(): Long {
        if (!advancing) return positionMs
        val moved = ((SystemClock.elapsedRealtime() - atElapsedMs) * speed).toLong()
        val at = positionMs + moved.coerceAtLeast(0)
        return if (durationMs > 0) at.coerceAtMost(durationMs) else at
    }
}

/**
 * 全局播放器。队列直接交给 ExoPlayer（通知栏、方向盘按键的上一首/下一首因此天然可用），
 * 每首歌以 `cloudmusic://song/<id>` 占位，真正加载时才通过 [ResolvingDataSource] 换成网易云的播放地址。
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private class UnplayableTrackException(id: Long) : IOException("no url for $id")

    private const val SCHEME = "cloudmusic"
    private const val URL_TTL_MS = 15 * 60 * 1000L
    private const val PREFETCH_MIN_BUFFER_MS = 30_000L
    private const val PREFETCH_RETRY_MS = 2 * 60 * 1000L
    private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var player: ExoPlayer
        private set

    private val tracks = ConcurrentHashMap<Long, Track>()
    private data class ResolvedUrl(val url: String, val resolvedAt: Long, val cacheKey: String)

    private val urlCache = ConcurrentHashMap<String, ResolvedUrl>()
    private lateinit var cacheDataSourceFactory: DataSource.Factory
    private var prefetchJob: Job? = null
    private var prefetchIds: List<Long> = emptyList()
    @Volatile private var prefetchGeneration = 0L
    private val prefetched = ConcurrentHashMap.newKeySet<String>()
    private val prefetchFailures = ConcurrentHashMap<String, Long>()
    private var prefetchSupported = false

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

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

    private val _position = MutableStateFlow(PlaybackPosition(0, 0, 0, false, 1f))

    /** 当前播放位置；界面进程里按引擎推来的快照外推。 */
    fun positionMs(): Long = if (EngineLink.isUi) _position.value.now() else player.currentPosition

    fun durationMs(): Long {
        val known = if (EngineLink.isUi) _position.value.durationMs else player.duration
        return known.takeIf { it > 0 } ?: _current.value?.durationMs ?: 0L
    }

    private fun publishPosition() {
        if (!initialized) return
        _position.value = PlaybackPosition(
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            atElapsedMs = SystemClock.elapsedRealtime(),
            advancing = player.isPlaying,
            speed = player.playbackParameters.speed,
        )
    }

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
        cacheDataSourceFactory = cached
        // Older hosts kept the factory names but obfuscated the return types in R8.
        prefetchSupported = runCatching {
            val dataSource = Class.forName("androidx.media3.datasource.DataSource")
            val cachedSource = Class.forName("androidx.media3.datasource.cache.CacheDataSource")
            DataSource.Factory::class.java.getMethod("createDataSource").returnType == dataSource &&
                CacheDataSource.Factory::class.java.methods.any {
                    it.name == "createDataSource" && it.returnType == cachedSource
                }
        }.getOrDefault(false)
        val resolving = ResolvingDataSource.Factory(cached) { spec -> resolve(spec) }
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        player = ExoPlayer.Builder(context, renderers)
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
                updatePrefetch()
            }
        }
    }

    @Synchronized
    fun release() {
        if (!initialized) return
        cancelNetworkWait()
        stopPrefetch()
        _status.value = null
        _isBuffering.value = false
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
        publishPosition()
    }

    fun toast(message: String) {
        _messages.tryEmit(message)
        if (EngineLink.isEngine) EngineLink.EngineSide.send(EVENT_TOAST, Bundle().apply { putString("text", message) })
    }

    fun onQualityChanged() {
        if (!initialized || player.currentMediaItem == null) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition.coerceAtLeast(0L)
        val resume = player.playWhenReady
        stopPrefetch()
        urlCache.clear()
        player.stop()
        player.seekTo(index, position)
        player.prepare()
        player.playWhenReady = resume
        Log.i("CloudPlayer", "quality_reload id=${player.currentMediaItem?.mediaId} requested=${Settings.quality.value.level} pos=$position")
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
        // 过期的 MUSIC_U 取地址不报 301，只会悄悄降成试听/空地址，所以取地址前补一次到期续签
        NeteaseClient.refreshIfDueBlocking()
        var quality = requestedQuality
        var result = NeteaseApi.songUrlBlocking(id, quality.level)
        if (result.url == null && quality != AudioQuality.STANDARD) {
            quality = AudioQuality.STANDARD
            result = NeteaseApi.songUrlBlocking(id, AudioQuality.STANDARD.level)
        }
        val url = result.url ?: throw UnplayableTrackException(id)
        Log.i("CloudPlayer", "quality_resolved id=$id requested=${requestedQuality.level} selected=${quality.level} server=${result.level} type=${result.type} bitrate=${result.bitrate} trial=${result.isTrial}")
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

    /** Only read ahead when playback already has enough audio to stay ahead of the download. */
    private fun updatePrefetch() {
        if (!prefetchSupported) return
        val bufferedAhead = player.bufferedPosition - player.currentPosition
        val ready = player.playWhenReady && player.playbackState == Player.STATE_READY &&
            (bufferedAhead >= PREFETCH_MIN_BUFFER_MS ||
                (player.duration > 0 && player.bufferedPosition >= player.duration))
        if (!ready) {
            stopPrefetch()
            return
        }
        val ids = upcomingTrackIds()
        if (ids.isEmpty()) {
            stopPrefetch()
            return
        }
        // Quality and play order are part of the work identity. A new selection cancels the old download.
        val workKey = ids.map { "$it:${Settings.quality.value.level}" }
        if (prefetchJob?.isActive == true && prefetchIds == ids && prefetchQuality == Settings.quality.value.level) return
        stopPrefetch()
        prefetchIds = ids
        prefetchQuality = Settings.quality.value.level
        val generation = prefetchGeneration
        prefetchJob = scope.launch(Dispatchers.IO) {
            for ((index, id) in ids.withIndex()) {
                if (generation != prefetchGeneration) break
                val key = workKey[index]
                if (key in prefetched) continue
                val failedAt = prefetchFailures[key]
                if (failedAt != null && System.currentTimeMillis() - failedAt < PREFETCH_RETRY_MS) continue
                Log.i("CloudPlayer", "prefetch start id=$id quality=${Settings.quality.value.level}")
                val success = runCatching { cacheWholeTrack(id, generation) }
                    .onFailure { Log.w("CloudPlayer", "prefetch failed id=$id", it) }
                    .getOrDefault(false)
                if (generation == prefetchGeneration) {
                    if (success) {
                        Log.i("CloudPlayer", "prefetch complete id=$id")
                        prefetchFailures.remove(key)
                        prefetched.add(key)
                    } else {
                        prefetchFailures[key] = System.currentTimeMillis()
                    }
                }
            }
        }
    }

    private var prefetchQuality: String? = null

    private fun stopPrefetch() {
        prefetchGeneration++
        prefetchJob?.cancel()
        prefetchJob = null
        prefetchIds = emptyList()
        prefetchQuality = null
    }

    private fun upcomingTrackIds(): List<Long> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode
        var index = player.currentMediaItemIndex
        val currentId = player.currentMediaItem?.mediaId
        val found = LinkedHashSet<Long>()
        repeat(timeline.windowCount.coerceAtMost(4)) {
            index = timeline.getNextWindowIndex(index, repeatMode, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return found.toList()
            val id = player.getMediaItemAt(index).mediaId.toLongOrNull() ?: return@repeat
            if (id.toString() != currentId) found.add(id)
            if (found.size == 3) return found.toList()
        }
        return found.toList()
    }

    private fun cacheWholeTrack(id: Long, generation: Long): Boolean {
        if (tracks[id] == null) return false
        val spec = resolve(DataSpec(Uri.parse("$SCHEME://song/$id")))
        if (spec.key == null) return false
        val source = cacheDataSourceFactory.createDataSource()
        return try {
            source.open(spec)
            val bytes = ByteArray(64 * 1024)
            while (generation == prefetchGeneration) {
                if (source.read(bytes, 0, bytes.size) == C.RESULT_END_OF_INPUT) return true
            }
            false
        } finally {
            source.close()
        }
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
        if (EngineLink.isUi) {
            remote(CMD_PLAY) {
                EngineLink.putText(this, Codecs.tracks(list))
                putLong("start", start?.id ?: -1L)
                putBoolean("shuffle", shuffle)
                putString("source", source)
                putLong("sourceId", sourceId)
                putString("mode", mode.name)
            }
            return
        }
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
        if (EngineLink.isUi) { remote(CMD_START_FM); return }
        scope.launch {
            runCatching { NeteaseApi.personalFm() }
                .onSuccess { play(it, source = "私人 FM", mode = PlayMode.FM) }
                .onFailure { toast(it.message ?: "私人 FM 加载失败") }
        }
    }

    fun startHeartbeat() {
        if (EngineLink.isUi) { remote(CMD_HEARTBEAT); return }
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
        if (EngineLink.isUi) { remote(CMD_TOGGLE); return }
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
        if (EngineLink.isUi) { remote(CMD_NEXT); return }
        cancelNetworkWait()
        _status.value = null
        val index = manualNextIndex()
        if (index != C.INDEX_UNSET) player.seekTo(index, 0) else if (_mode.value == PlayMode.FM) extendFm(true)
    }

    fun previous() {
        if (EngineLink.isUi) { remote(CMD_PREVIOUS); return }
        cancelNetworkWait()
        _status.value = null
        val index = manualPreviousIndex()
        if (index != C.INDEX_UNSET) player.seekTo(index, 0) else player.seekTo(0)
    }

    fun seekTo(ms: Long) {
        if (EngineLink.isUi) {
            // 先把本地快照挪过去，进度条不会在引擎回话之前弹回原处。
            _position.value = _position.value.copy(positionMs = ms, atElapsedMs = SystemClock.elapsedRealtime())
            remote(CMD_SEEK) { putLong("ms", ms) }
            return
        }
        cancelNetworkWait()
        player.seekTo(ms)
    }

    fun jumpTo(index: Int) {
        if (EngineLink.isUi) { remote(CMD_JUMP) { putInt("index", index) }; return }
        cancelNetworkWait()
        _status.value = null
        consecutiveFailures = 0
        player.seekTo(index, 0)
        player.play()
    }

    /** 顺序播放 → 单曲循环 → 随机播放 → 顺序播放，持久化后重启仍保持。 */
    fun cyclePlayMode() {
        if (EngineLink.isUi) { remote(CMD_CYCLE_MODE); return }
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
        if (EngineLink.isUi) { remote(CMD_FM_TRASH); return }
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

    /**
     * 手动切歌（按键、滑动、播放失败跳过）的目标。单曲循环只管自动续播，手动切歌仍按列表循环走，
     * 否则 ExoPlayer 在单曲循环下按「不循环」导航，列表最后一首点下一首没反应。
     * 通知栏 / 方向盘 / 桌面底栏经 MediaSession 直接调播放器，不经过这里（ForwardingPlayer 不在桌面共享库清单里）。
     */
    private fun manualNextIndex(): Int = manualNeighbor(forward = true)
    private fun manualPreviousIndex(): Int = manualNeighbor(forward = false)
    private fun manualNeighbor(forward: Boolean): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return C.INDEX_UNSET
        val repeat = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_ALL else player.repeatMode
        val current = player.currentMediaItemIndex
        return if (forward) timeline.getNextWindowIndex(current, repeat, player.shuffleModeEnabled)
        else timeline.getPreviousWindowIndex(current, repeat, player.shuffleModeEnabled)
    }

    /** 上一首 / 下一首按播放器当前的随机顺序和循环规则算，与按键实际切到的一致；只有一首歌时没有邻居。 */
    private fun updateNeighbors() {
        val current = player.currentMediaItemIndex
        fun neighbor(index: Int) = index.takeIf { it != C.INDEX_UNSET && it != current }
        _neighbors.value = NowPlayingNeighbors(
            previousIndex = neighbor(manualPreviousIndex()),
            nextIndex = neighbor(manualNextIndex()),
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
            remove(METADATA_KEY_KRC_LYRIC)
            WordLyricsParser.toKrc(lines.map { KrcSource(it.timeMs, it.text, it.words) })
                .takeIf(String::isNotEmpty)?.let { putString(METADATA_KEY_KRC_LYRIC, it) }
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

    // ---------- 拆进程：界面 ↔ 引擎 ----------

    private const val EVENT_TOAST = "player.toast"
    private const val CMD_PLAY = "player.play"
    private const val CMD_START_FM = "player.startFm"
    private const val CMD_HEARTBEAT = "player.startHeartbeat"
    private const val CMD_TOGGLE = "player.togglePlay"
    private const val CMD_NEXT = "player.next"
    private const val CMD_PREVIOUS = "player.previous"
    private const val CMD_SEEK = "player.seekTo"
    private const val CMD_JUMP = "player.jumpTo"
    private const val CMD_CYCLE_MODE = "player.cyclePlayMode"
    private const val CMD_FM_TRASH = "player.fmTrash"

    private inline fun remote(method: String, args: Bundle.() -> Unit = {}) {
        EngineLink.UiSide.fire(method, Bundle().apply(args))
    }

    /** 界面进程：播放状态全部由引擎推过来。 */
    fun mirrorFromEngine() {
        val ui = EngineLink.UiSide
        ui.mirror("player.current", _current, Codecs::trackOrNull)
        ui.mirror("player.isPlaying", _isPlaying) { it.toBoolean() }
        ui.mirror("player.isBuffering", _isBuffering) { it.toBoolean() }
        ui.mirror("player.queue", _queue, Codecs::queue)
        ui.mirror("player.mode", _mode, Codecs::mode)
        ui.mirror("player.sourceName", _sourceName) { it.takeIf { s -> s.isNotEmpty() } }
        ui.mirror("player.playMode", _playMode) { it.toInt() }
        ui.mirror("player.neighbors", _neighbors, Codecs::neighbors)
        ui.mirror("player.lyrics", _lyrics, Codecs::lyrics)
        ui.mirror("player.status", _status, Codecs::notice)
        ui.mirror("player.position", _position, Codecs::position)
        ui.on(EVENT_TOAST) { data -> data.getString("text")?.let { _messages.tryEmit(it) } }
    }

    fun serveToUi() {
        val engine = EngineLink.EngineSide
        engine.mirror("player.current", current, Codecs::trackOrNull)
        engine.mirror("player.isPlaying", isPlaying) { it.toString() }
        engine.mirror("player.isBuffering", isBuffering) { it.toString() }
        engine.mirror("player.queue", queue, Codecs::queue)
        engine.mirror("player.mode", mode, Codecs::mode)
        engine.mirror("player.sourceName", sourceName) { it.orEmpty() }
        engine.mirror("player.playMode", playMode) { it.toString() }
        engine.mirror("player.neighbors", neighbors, Codecs::neighbors)
        engine.mirror("player.lyrics", lyrics, Codecs::lyrics)
        engine.mirror("player.status", status, Codecs::notice)
        engine.mirror("player.position", _position, Codecs::position)
        fun main(name: String, block: (Bundle) -> Unit) = engine.command(name) { args -> engine.onMain { block(args) }; null }
        main(CMD_PLAY) { args ->
            val list = Codecs.tracks(EngineLink.readText(args) ?: "[]")
            val startId = args.getLong("start", -1L)
            play(
                list,
                start = list.firstOrNull { it.id == startId },
                shuffle = args.getBoolean("shuffle"),
                source = args.getString("source"),
                sourceId = args.getLong("sourceId"),
                mode = PlayMode.valueOf(args.getString("mode") ?: PlayMode.NORMAL.name),
            )
        }
        main(CMD_START_FM) { startFm() }
        main(CMD_HEARTBEAT) { startHeartbeat() }
        main(CMD_TOGGLE) { togglePlay() }
        main(CMD_NEXT) { next() }
        main(CMD_PREVIOUS) { previous() }
        main(CMD_SEEK) { seekTo(it.getLong("ms")) }
        main(CMD_JUMP) { jumpTo(it.getInt("index")) }
        main(CMD_CYCLE_MODE) { cyclePlayMode() }
        main(CMD_FM_TRASH) { fmTrash() }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying && _isPlaying.value) updatePlaybackProgress(includeStopped = true)
            resetPlaybackProgress()
            _isPlaying.value = isPlaying
            publishPosition()
            persistPosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            stopPrefetch()
            cancelNetworkWait()
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) _status.value = null
            resetPlaybackProgress()
            onTrackChanged(finishedPrevious = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            updateNeighbors()
            publishPosition()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = publishPosition()

        override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) =
            publishPosition()


        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _isBuffering.value = playWhenReady && player.playbackState == Player.STATE_BUFFERING
            updatePrefetch()
            if (playWhenReady && waitingMediaId != null) cancelNetworkWait()
            if (playWhenReady && _status.value?.kind == "failures_paused") _status.value = null
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            stopPrefetch()
            rebuildQueue()
            if (_current.value?.id?.toString() != player.currentMediaItem?.mediaId) onTrackChanged()
            updateNeighbors()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            stopPrefetch()
            updateNeighbors()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            stopPrefetch()
            updateNeighbors()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _isBuffering.value = player.playWhenReady && playbackState == Player.STATE_BUFFERING
            publishPosition()
            updatePrefetch()
        }

        override fun onPlayerError(error: PlaybackException) {
            stopPrefetch()
            _isBuffering.value = false
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
                manualNextIndex() != C.INDEX_UNSET -> {
                    player.seekTo(manualNextIndex(), 0)
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
