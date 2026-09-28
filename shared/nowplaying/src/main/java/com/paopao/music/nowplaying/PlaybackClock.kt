package com.paopao.music.nowplaying

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * 播放进度时钟。
 *
 * 平时每 250ms 读一次播放器（进度条 1px 级别的跳动肉眼看不出）；只有逐字歌词正在屏幕上
 * 填色时才切到插值，且每 [KARAOKE_FRAME_MS] 才推进一次：插件画在车机桌面的窗口里，
 * 逐帧推进会让整个桌面每秒重画几十次（8155 上 CPU 到 130%）。[position] 只应在绘制/布局
 * lambda 或 derivedStateOf 里读，避免每次跳动都触发重组。
 */
@Stable
class PlaybackClock internal constructor() {
    internal val positionState = mutableLongStateOf(0L)
    internal val durationState = mutableLongStateOf(0L)
    val position: LongState get() = positionState
    val duration: LongState get() = durationState

    /** 可见的逐字行数；>0 时逐帧刷新。 */
    internal var frameClients = 0
}

@Composable
fun rememberPlaybackClock(source: NowPlayingSource): PlaybackClock {
    val clock = remember(source) { PlaybackClock() }
    LaunchedEffect(clock) {
        var anchorPos = 0L
        var anchorAt = 0L
        var polledAt = 0L
        var needAnchor = true
        while (true) {
            val playing = source.isPlaying.value
            if (playing && clock.frameClients > 0) {
                withFrameMillis { now ->
                    if (needAnchor || now - polledAt >= 250) {
                        val measured = source.positionMs()
                        val predicted = anchorPos + (now - anchorAt)
                        val drift = measured - predicted
                        // 播放器报的位置本身有几十毫秒抖动，直接对齐会让填色每 250ms 跳一下；
                        // 小偏差分几次慢慢吃掉，大偏差（拖动、切歌、卡顿）才直接对齐
                        anchorPos = if (needAnchor || abs(drift) > 300) measured else predicted + drift / 4
                        anchorAt = now
                        polledAt = now
                        needAnchor = false
                        clock.durationState.longValue = source.durationMs()
                    }
                    val dur = clock.durationState.longValue
                    val interpolated = anchorPos + (now - anchorAt)
                    clock.positionState.longValue = if (dur > 0) interpolated.coerceAtMost(dur) else interpolated
                }
                delay(KARAOKE_FRAME_MS)
            } else {
                needAnchor = true
                clock.positionState.longValue = source.positionMs()
                clock.durationState.longValue = source.durationMs()
                delay(POSITION_POLL_MS)
            }
        }
    }
    return clock
}

/** 逐字填色的推进间隔：约每秒 16 次，填色仍连续向前，只是步子稍大。 */
private const val KARAOKE_FRAME_MS = 55L

/**
 * 非逐字时读播放位置的间隔：每秒 2 次。插件画在车机桌面窗口里，进度条每动一下整个桌面都要重画；
 * 进度条每秒只走几个像素，换句判定另有 250ms 提前量，2 次足够。
 */
private const val POSITION_POLL_MS = 500L
