package com.cloudmusic.car.ui.screens

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import com.cloudmusic.car.api.Track
import com.cloudmusic.car.api.sized
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.player.PlayMode
import com.cloudmusic.car.player.PlayerHub
import com.cloudmusic.car.ui.theme.K
import com.paopao.music.nowplaying.NowPlayingLyric
import com.paopao.music.nowplaying.NowPlayingQueueItem
import com.paopao.music.nowplaying.NowPlayingSource
import com.paopao.music.nowplaying.NowPlayingTrack
import com.paopao.music.nowplaying.mapState
import com.paopao.music.nowplaying.NowPlayingScreen as SharedNowPlayingScreen

/** 播放页本体在共用源码 shared/nowplaying；这里只把本功能包的播放器接进去。 */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val buffering by PlayerHub.isBuffering.collectAsState()
    Box(Modifier.fillMaxSize()) {
        SharedNowPlayingScreen(PlayerSource, K.colors.accent, onClose)
        AnimatedVisibility(
            visible = buffering,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.systemBars).padding(top = 18.dp),
        ) {
            Text(
                "正在缓冲…",
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.66f), RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
    }
}

private object PlayerSource : NowPlayingSource {
    override val current = PlayerHub.current.mapState { it?.toNowPlaying() }
    override val isPlaying = PlayerHub.isPlaying
    override val queue = PlayerHub.queue.mapState { list -> list.map { (index, t) -> NowPlayingQueueItem(index, t.toNowPlaying()) } }
    override val isFm = PlayerHub.mode.mapState { it == PlayMode.FM }
    override val sourceName = PlayerHub.sourceName
    override val playMode = PlayerHub.playMode
    override val neighbors = PlayerHub.neighbors
    override val lyrics = PlayerHub.lyrics.mapState { lines ->
        lines.map { NowPlayingLyric(it.timeMs, it.text, it.translation, it.words) }
    }
    override val likedIds = AccountStore.likedIds

    override fun positionMs(): Long = PlayerHub.player.currentPosition
    override fun durationMs(): Long =
        PlayerHub.player.duration.takeIf { it > 0 } ?: PlayerHub.current.value?.durationMs ?: 0L

    override fun togglePlay() = PlayerHub.togglePlay()
    override fun next() = PlayerHub.next()
    override fun previous() { PlayerHub.previous() }
    override fun seekTo(ms: Long) { PlayerHub.seekTo(ms) }
    override fun jumpTo(index: Int) = PlayerHub.jumpTo(index)
    override fun cyclePlayMode() = PlayerHub.cyclePlayMode()
    override fun fmTrash() = PlayerHub.fmTrash()
    override fun toggleLike(trackId: Long) {
        val track = PlayerHub.current.value?.takeIf { it.id == trackId }
            ?: PlayerHub.queue.value.firstOrNull { it.second.id == trackId }?.second
            ?: return
        AccountStore.toggleLike(track, PlayerHub::toast)
    }

    override fun artworkUrl(url: String?, px: Int): String? = url.sized(px)
    override fun imageLoader(context: Context): ImageLoader = MusicCache.imageLoader(context)
}

private fun Track.toNowPlaying() = NowPlayingTrack(
    id = id,
    title = name,
    artists = artistNames,
    album = album.name,
    coverUrl = album.picUrl,
    durationMs = durationMs,
)
