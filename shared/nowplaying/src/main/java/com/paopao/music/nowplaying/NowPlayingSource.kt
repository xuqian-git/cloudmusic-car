package com.paopao.music.nowplaying

import android.content.Context
import coil.ImageLoader
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 三个音乐功能包共用的播放页。源码目录被云音乐 / QQ 音乐 / 酷狗三个模块分别编进各自的 DEX，
 * 各模块只需实现 [NowPlayingSource]，把自己的播放器、歌词与红心状态接进来。
 */
data class NowPlayingTrack(
    val id: Long,
    val title: String,
    val artists: String,
    val album: String,
    val coverUrl: String?,
    val durationMs: Long,
)

/** 一个逐字片段，时间为歌曲内的绝对毫秒。 */
data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

data class NowPlayingLyric(
    val timeMs: Long,
    val text: String,
    val translation: String?,
    /** 为空表示该源没有逐字数据，整句一次点亮。 */
    val words: List<LyricWord> = emptyList(),
)

data class NowPlayingQueueItem(val index: Int, val track: NowPlayingTrack)

interface NowPlayingSource {
    val current: StateFlow<NowPlayingTrack?>
    val isPlaying: StateFlow<Boolean>
    /** 按实际播放顺序（含随机）排好的整条队列。 */
    val queue: StateFlow<List<NowPlayingQueueItem>>
    val isFm: StateFlow<Boolean>
    val sourceName: StateFlow<String?>
    val shuffle: StateFlow<Boolean>
    /** Media3 的 Player.REPEAT_MODE_*。 */
    val repeatMode: StateFlow<Int>
    val lyrics: StateFlow<List<NowPlayingLyric>>
    val likedIds: StateFlow<Set<Long>>

    fun positionMs(): Long
    fun durationMs(): Long

    fun togglePlay()
    fun next()
    /** 按钮语义：播放超过几秒时回到开头。 */
    fun previous()
    fun seekTo(ms: Long)
    fun jumpTo(index: Int)
    fun toggleShuffle()
    fun cycleRepeat()
    fun fmTrash()
    fun toggleLike(trackId: Long)

    /** 按源的 CDN 规则取指定边长的封面地址。 */
    fun artworkUrl(url: String?, px: Int): String?
    fun imageLoader(context: Context): ImageLoader
}

/** 不需要协程作用域的派生 StateFlow：值按需计算，收集时去重。 */
fun <T, R> StateFlow<T>.mapState(transform: (T) -> R): StateFlow<R> = MappedStateFlow(this, transform)

private class MappedStateFlow<T, R>(
    private val upstream: StateFlow<T>,
    private val transform: (T) -> R,
) : StateFlow<R> {
    private var cachedIn: Any? = NONE
    private var cachedOut: Any? = null

    @Suppress("UNCHECKED_CAST")
    override val value: R
        get() {
            val input = upstream.value
            synchronized(this) {
                if (cachedIn !== input) {
                    cachedOut = transform(input)
                    cachedIn = input
                }
                return cachedOut as R
            }
        }

    override val replayCache: List<R> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        upstream.map { value }.distinctUntilChanged().collect(collector)
        awaitCancellation()
    }

    private companion object {
        val NONE = Any()
    }
}
