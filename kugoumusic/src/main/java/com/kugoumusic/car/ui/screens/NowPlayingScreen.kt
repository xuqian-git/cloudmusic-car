package com.kugoumusic.car.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import coil.ImageLoader
import com.kugoumusic.car.api.Track
import com.kugoumusic.car.api.sized
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.player.PlayMode
import com.kugoumusic.car.player.PlayerHub
import com.kugoumusic.car.ui.theme.K
import com.paopao.music.nowplaying.NowPlayingLyric
import com.paopao.music.nowplaying.NowPlayingQueueItem
import com.paopao.music.nowplaying.NowPlayingSource
import com.paopao.music.nowplaying.NowPlayingTrack
import com.paopao.music.nowplaying.mapState
import com.paopao.music.nowplaying.NowPlayingScreen as SharedNowPlayingScreen

/** 播放页本体在共用源码 shared/nowplaying；这里只把本功能包的播放器接进去。 */
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    SharedNowPlayingScreen(PlayerSource, K.colors.accent, onClose)
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
