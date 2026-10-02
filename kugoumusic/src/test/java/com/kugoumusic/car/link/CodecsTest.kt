package com.kugoumusic.car.link

import com.kugoumusic.car.api.Album
import com.kugoumusic.car.api.Artist
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.LyricLine
import com.kugoumusic.car.api.Playlist
import com.kugoumusic.car.api.Privilege
import com.kugoumusic.car.api.Profile
import com.kugoumusic.car.api.Track
import com.kugoumusic.car.data.MusicCacheStats
import com.kugoumusic.car.player.PlaybackNotice
import com.kugoumusic.car.player.PlaybackPosition
import com.paopao.music.nowplaying.LyricWord
import com.paopao.music.nowplaying.NowPlayingNeighbors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 跨进程编码必须无损：取地址要 hash / album_audio_id / songType，界面要封面、可播放性。 */
class CodecsTest {
    private val full = Track(
        id = 42, mid = "ABC123", mediaMid = "987654", songType = 1, name = "晴天",
        artists = listOf(Artist(1, "周杰伦"), Artist(2, "")),
        album = Album(7, "叶惠美", "https://img/{size}/c.jpg"), durationMs = 269_000,
        privilege = Privilege(42, fee = 8, pl = 320000, st = 0, cs = true), hasPlayableFile = false,
    )
    private val bare = Track(
        id = 9, mid = "", mediaMid = "", songType = 0, name = "x",
        artists = emptyList(), album = Album(0, "", null), durationMs = 0,
    )

    @Test
    fun tracksRoundTrip() {
        assertEquals(listOf(full, bare), Codecs.tracks(Codecs.tracks(listOf(full, bare))))
        assertEquals(full, Codecs.trackOrNull(Codecs.trackOrNull(full)))
        assertNull(Codecs.trackOrNull(Codecs.trackOrNull(null)))
        val queue = listOf(3 to full, 0 to bare)
        assertEquals(queue, Codecs.queue(Codecs.queue(queue)))
    }

    @Test
    fun playlistsAndDetailsRoundTrip() {
        val a = Playlist(5, "我喜欢", "https://p", 12, 34, 56, "我", 5, "副标题")
        val b = Playlist(-1, "", null, 0, 0, 0, "", -1, null)
        assertEquals(listOf(a, b), Codecs.playlists(Codecs.playlists(listOf(a, b))))
        val detail = Codecs.playlistDetail(Codecs.playlistDetail(KuGouMusicApi.PlaylistDetail(a, "简介", listOf(full))))
        assertEquals(a, detail.playlist)
        assertEquals("简介", detail.description)
        assertEquals(listOf(full), detail.tracks)
        val cloud = Codecs.cloudDrive(Codecs.cloudDrive(KuGouMusicApi.CloudDrive(listOf(bare), 100, 200)))
        assertEquals(listOf(bare), cloud.tracks)
        assertEquals(100, cloud.usedBytes)
        assertEquals(200, cloud.maxBytes)
    }

    @Test
    fun smallModelsRoundTrip() {
        val profile = Profile(1, "昵称", null, 3)
        assertEquals(profile, Codecs.profile(Codecs.profile(profile)))
        assertNull(Codecs.profile(Codecs.profile(null)))
        val lines = listOf(LyricLine(1000, "a", "译", listOf(LyricWord("a", 1000, 1200))), LyricLine(2000, "", null))
        assertEquals(lines, Codecs.lyrics(Codecs.lyrics(lines)))
        assertEquals(setOf(1L, 2L), Codecs.ids(Codecs.ids(setOf(1L, 2L))))
        val n = NowPlayingNeighbors(null, 4)
        assertEquals(n, Codecs.neighbors(Codecs.neighbors(n)))
        val notice = PlaybackNotice("network_wait", "等网")
        assertEquals(notice, Codecs.notice(Codecs.notice(notice)))
        val p = PlaybackPosition(1, 2, 3, true, 1.5f)
        assertEquals(p, Codecs.position(Codecs.position(p)))
        val s = MusicCacheStats(1, 2, 3)
        assertEquals(s, Codecs.stats(Codecs.stats(s)))
    }
}
