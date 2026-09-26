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

    @Test
    fun cgiErrorsReadableWithCode() {
        assertEquals("QQ 音乐判定请求异常（风控），请稍后再试 (2001)", QQMusicClient.errorMessage(2001, JSONObject()))
        assertEquals("参数错误 (500)", QQMusicClient.errorMessage(500, JSONObject("""{"message":"参数错误"}""")))
        assertEquals("接口错误 (123)", QQMusicClient.errorMessage(123, JSONObject()))
    }


    @Test
    fun mqttPublishSurvivesSplitFramesAndKeepsUserProperties() {
        val props = Mqtt.properties { userProperty("type", "scanned") }
        val body = Mqtt.string("management.qrcode_login/abc") + props + """{"a":1}""".toByteArray()
        val publish = Mqtt.packet(0x30, body)
        assertEquals(null, Mqtt.readPacket(publish.copyOf(publish.size - 3)))
        val packet = Mqtt.readPacket(publish + byteArrayOf(0xD0.toByte(), 0))!!
        assertEquals(3, packet.type)
        assertEquals(publish.size, packet.consumed)
        val reader = Mqtt.Reader(packet.body)
        assertEquals("management.qrcode_login/abc", reader.string())
        assertEquals("scanned", reader.properties().userProperties["type"])
        assertEquals("""{"a":1}""", String(reader.rest()))
    }

    @Test
    fun mqttConnackRedirectReadsServerReference() {
        val props = Mqtt.properties { string(0x1C, "10.0.0.1:443") }
        val reader = Mqtt.Reader(byteArrayOf(0, 0x9D.toByte()) + props)
        reader.byte()
        assertEquals(0x9D, reader.byte())
        assertEquals("10.0.0.1:443", reader.properties().strings[0x1C])
        assertEquals("/ws/handshake/10.0.0.1:443", QQMobileLogin.redirectPath("/ws/handshake", "10.0.0.1:443"))
        assertEquals("/ws/handshake/10.0.0.2:443", QQMobileLogin.redirectPath("/ws/handshake/10.0.0.1:443", "10.0.0.2:443"))
    }

    @Test
    fun mqttRemainingLengthUsesVarInt() {
        assertEquals(listOf(0xC1, 0x02), Mqtt.varInt(321).map { it.toInt() and 0xFF })
        val big = Mqtt.packet(0x30, ByteArray(321))
        assertEquals(321, Mqtt.readPacket(big)!!.body.size)
    }
}
