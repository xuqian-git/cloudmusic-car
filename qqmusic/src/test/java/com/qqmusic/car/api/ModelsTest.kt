package com.qqmusic.car.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ModelsTest {
    private val chartCover = "http://y.gtimg.cn/music/photo_new/T003R300x300M000001l6zwH2HbWtj.jpg"

    @Test
    fun sized_usesSupportedQqCdnDimensions() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T003R150x150M000001l6zwH2HbWtj.jpg",
            chartCover.sized(96),
        )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T003R500x500M000001l6zwH2HbWtj.jpg",
            chartCover.sized(400),
        )
    }

    @Test
    fun sized_preservesNonQqArtworkUrls() {
        assertEquals("https://example.com/R300x300.jpg", "http://example.com/R300x300.jpg".sized(400))
    }

    @Test
    fun qqFileFormat_mapsEachVisibleQualityToItsRealCdnFormat() {
        assertEquals("M500" to ".mp3", qqFileFormat("standard"))
        assertEquals("M800" to ".mp3", qqFileFormat("exhigh"))
        assertEquals("F000" to ".flac", qqFileFormat("lossless"))
        assertEquals("AI00" to ".flac", qqFileFormat("master"))
    }

    @Test
    fun loginOnlyOffersQqAndWechat() {
        assertEquals(listOf("QQ", "微信"), QQLoginType.entries.map(QQLoginType::label))
    }

    @Test
    fun qqQrTokenUsesZeroSeedRequiredByPtlogin() {
        assertEquals(1041100915, QQLoginApi.qqQrToken("test-qrsig"))
    }

    @Test
    fun playlistSearchParsesAndroidCgiBody() {
        val response = JSONObject(
            """{"body":{"item_songlist":[{"id":42,"title":"通勤歌单","cover":"https://example.com/cover.jpg","songnum":18,"listennum":99,"nickname":"车友"}]}}""",
        )
        val playlists = QQMusicApi.parsePlaylistSearch(response)

        assertEquals(1, playlists.size)
        assertEquals(42L, playlists.single().id)
        assertEquals("通勤歌单", playlists.single().name)
        assertEquals("https://example.com/cover.jpg", playlists.single().coverUrl)
        assertFalse(playlists.single().isLikedSongs)
    }

    @Test
    fun profileParserFindsNicknameAndAvatarAcrossNestedLoginInfo() {
        val response = JSONObject(
            """{"info":{"nick":"泡泡用户","logo":"https://example.com/avatar.jpg"}}""",
        )
        val profile = QQMusicApi.parseProfile(response, 1234)

        assertEquals(1234L, profile?.userId)
        assertEquals("泡泡用户", profile?.nickname)
        assertEquals("https://example.com/avatar.jpg", profile?.avatarUrl)
    }

    @Test
    fun favoritePlaylistUsesItsOwnNameAndArtworkFields() {
        val playlist = Playlist.parse(
            JSONObject(
                """{"tid":99,"dirName":"收藏歌单","albumPicUrl":"https://example.com/favorite.jpg","songnum":12,"nickname":"歌单作者"}""",
            ),
        )

        assertEquals("收藏歌单", playlist.name)
        assertEquals("https://example.com/favorite.jpg", playlist.coverUrl)
    }

    @Test
    fun searchRetriesOnlyRecoverableFailures() {
        assertTrue(QQMusicApi.isTransientSearchFailure(IOException("connection reset")))
        assertTrue(QQMusicApi.isTransientSearchFailure(ApiException(503, "busy")))
        assertTrue(QQMusicApi.isTransientSearchFailure(ApiException(429, "limited")))
        assertFalse(QQMusicApi.isTransientSearchFailure(ApiException(401, "login expired")))
        assertFalse(QQMusicApi.isTransientSearchFailure(IllegalArgumentException("bad query")))
    }
}
