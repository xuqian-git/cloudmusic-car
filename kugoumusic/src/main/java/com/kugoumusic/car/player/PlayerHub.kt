package com.kugoumusic.car.player

import android.content.Context
import android.net.Uri
import android.os.Bundle
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.kugoumusic.car.api.LyricLine
import com.kugoumusic.car.api.LyricsParser
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.KuGouMusicClient
import com.kugoumusic.car.api.Track
import com.kugoumusic.car.api.sized
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.data.AudioQuality
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

/**
 * 全局播放器。队列直接交给 ExoPlayer（通知栏、方向盘按键的上一首/下一首因此天然可用），
 * 每首歌以 `kugoumusic://song/<id>` 占位，真正加载时才通过 [ResolvingDataSource] 换成酷狗音乐的播放地址。
 */
@OptIn(UnstableApi::class)
object PlayerHub {
    private const val SCHEME = "kugoumusic"
    private const val URL_TTL_MS = 15 * 60 * 1000L
    private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var player: ExoPlayer
        private set

    private val tracks = ConcurrentHashMap<Long, Track>()
    private data class ResolvedUrl(val url: String, val resolvedAt: Long, val cacheKey: String)

    private val urlCache = ConcurrentHashMap<String, ResolvedUrl>()

    private val _current = MutableStateFlow<Track?>(null)
    val current: StateFlow<Track?> = _current.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** 按实际播放顺序（含随机）排列的队列，以及当前歌曲在其中的位置。 */
    private val _queue = MutableStateFlow<List<Pair<Int, Track>>>(emptyList())
    val queue: StateFlow<List<Pair<Int, Track>>> = _queue.asStateFlow()

    private val _mode = MutableStateFlow(PlayMode.NORMAL)
    val mode: StateFlow<PlayMode> = _mode.asStateFlow()

    private val _sourceName = MutableStateFlow<String?>(null)
    val sourceName: StateFlow<String?> = _sourceName.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_ALL)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _lyrics = MutableStateFlow<List<LyricLine>>(emptyList())
    val lyrics: StateFlow<List<LyricLine>> = _lyrics.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private var sourceId = 0L
    private var lyricsJob: Job? = null
    private var fmLoading = false
    private var consecutiveFailures = 0
    private var lastTrack: Track? = null
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        MusicCache.init(context)
        val http = OkHttpDataSource.Factory(KuGouMusicClient.http)
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
            .build()
        player.repeatMode = Player.REPEAT_MODE_ALL
        player.addListener(listener)
        PlaybackSnapshotStore.init(context)
        PlaybackSnapshotStore.restore()?.let(::restorePlayback)
        scope.launch {
            while (true) {
                delay(5_000)
                persistPosition()
            }
        }
    }

    private fun restorePlayback(snapshot: PlaybackSnapshot) {
        sourceId = snapshot.sourceId
        _mode.value = snapshot.mode
        _sourceName.value = snapshot.sourceName
        player.setMediaItems(snapshot.tracks.map(::mediaItem), snapshot.currentIndex, snapshot.positionMs)
        player.shuffleModeEnabled = snapshot.shuffle
        player.repeatMode = snapshot.repeatMode
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
        val candidates = when (requestedQuality) {
            AudioQuality.MASTER -> listOf(AudioQuality.MASTER, AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD)
            AudioQuality.LOSSLESS -> listOf(AudioQuality.LOSSLESS, AudioQuality.EXHIGH, AudioQuality.STANDARD)
            AudioQuality.EXHIGH -> listOf(AudioQuality.EXHIGH, AudioQuality.STANDARD)
            AudioQuality.STANDARD -> listOf(AudioQuality.STANDARD)
        }
        var quality = candidates.first()
        var result = KuGouMusicApi.songUrlBlocking(id, quality.level)
        for (fallback in candidates.drop(1)) {
            if (result.url != null) break
            quality = fallback
            result = KuGouMusicApi.songUrlBlocking(id, quality.level)
        }
        val url = result.url ?: throw IOException("no url for $id")
        if (result.isTrial) toast("《${tracks[id]?.name.orEmpty()}》为 VIP 歌曲，当前为试听片段")
        val cacheKey = "song-$id-${quality.level}"
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
        _mode.value = mode
        _sourceName.value = source
        consecutiveFailures = 0
        player.shuffleModeEnabled = shuffle
        player.repeatMode = if (mode == PlayMode.FM) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
        player.setMediaItems(playable.map(::mediaItem), startIndex, 0)
        persistQueue(playable, startIndex)
        player.prepare()
        player.play()
    }

    fun startFm() {
        scope.launch {
            runCatching { KuGouMusicApi.personalFm() }
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
            runCatching { KuGouMusicApi.intelligenceList(seed, liked.id) }
                .onSuccess { play(it, source = "心动模式", sourceId = liked.id, mode = PlayMode.HEARTBEAT) }
                .onFailure { toast(it.message ?: "心动模式加载失败") }
        }
    }

    // ---------- 控制 ----------

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else if (_mode.value == PlayMode.FM) extendFm(true)
    }

    fun previous() = player.seekToPrevious()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun jumpTo(index: Int) {
        player.seekTo(index, 0)
        player.play()
    }

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** 私人 FM：不喜欢当前歌曲并跳到下一首。 */
    fun fmTrash() {
        val track = _current.value ?: return
        scope.launch { runCatching { KuGouMusicApi.fmTrash(track.id) } }
        next()
    }

    private fun extendFm(skipAfter: Boolean) {
        if (fmLoading) return
        fmLoading = true
        scope.launch {
            val more = runCatching { KuGouMusicApi.personalFm() }.getOrDefault(emptyList())
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
            player.shuffleModeEnabled,
            player.repeatMode,
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
        val shuffle = player.shuffleModeEnabled
        val ordered = mutableListOf<Pair<Int, Track>>()
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && ordered.size < timeline.windowCount) {
            val id = player.getMediaItemAt(index).mediaId.toLongOrNull()
            tracks[id]?.let { ordered += index to it }
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        _queue.value = ordered
    }

    private fun onTrackChanged(finishedPrevious: Boolean = false) {
        val id = player.currentMediaItem?.mediaId?.toLongOrNull()
        val track = id?.let { tracks[it] }
        val prev = lastTrack
        if (finishedPrevious && prev != null) {
            val secs = (prev.durationMs / 1000).coerceAtLeast(1)
            scope.launch { KuGouMusicApi.scrobble("play", prev.id, sourceId, secs) }
        }
        lastTrack = track
        _current.value = track
        if (track != null) persistQueue()
        lyricsJob?.cancel()
        _lyrics.value = emptyList()
        updateSessionLyrics(id, emptyList())
        if (track != null) {
            KuGouMusicApi.remember(track)
            scope.launch { KuGouMusicApi.scrobble("startplay", track.id, sourceId) }
            lyricsJob = scope.launch {
                val cached = withContext(Dispatchers.IO) { MusicCache.readLyrics(track.id) }
                val lines = cached ?: runCatching { KuGouMusicApi.lyric(track.id) }
                    .onSuccess { fetched ->
                        Log.i("KuGouMusic", "Lyrics fetched: track=${track.id}, lines=${fetched.size}")
                        withContext(Dispatchers.IO) { MusicCache.writeLyrics(track.id, fetched) }
                    }
                    .onFailure { Log.e("KuGouMusic", "Lyrics failed: track=${track.id}", it) }
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

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            persistPosition()
            if (isPlaying) consecutiveFailures = 0
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            onTrackChanged(finishedPrevious = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            rebuildQueue()
            if (_current.value?.id?.toString() != player.currentMediaItem?.mediaId) onTrackChanged()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _shuffle.value = shuffleModeEnabled
            rebuildQueue()
            persistQueue()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _repeatMode.value = repeatMode
            persistQueue()
        }

        override fun onPlayerError(error: PlaybackException) {
            val track = _current.value
            val id = player.currentMediaItem?.mediaId?.toLongOrNull()
            if (id != null) urlCache.keys.removeAll { it.startsWith("$id:") }
            consecutiveFailures++
            toast("《${track?.name.orEmpty()}》无法播放，已跳过")
            if (consecutiveFailures >= 5) return
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
