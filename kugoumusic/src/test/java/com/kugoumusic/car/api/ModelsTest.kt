package com.kugoumusic.car.api

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {
    @Test
    fun parsesKuGouSearchTrack() {
        val track = Track.parse(JSONObject("""{
          "FileHash":"ABC123","MixSongID":42,"SongName":"晴天","SingerName":"周杰伦",
          "AlbumName":"叶惠美","AlbumID":7,"Duration":269,"Image":"http://img/{size}/cover.jpg"
        }"""))

        assertEquals(42, track.id)
        assertEquals("ABC123", track.mid)
        assertEquals("晴天", track.name)
        assertEquals("周杰伦", track.artistNames)
        assertEquals(269_000, track.durationMs)
        assertEquals("https://img/500/cover.jpg", track.coverUrl.sized(500))
    }

    @Test
    fun qualityLevelsAreProviderNative() {
        val levels = com.kugoumusic.car.data.AudioQuality.entries.map { it.level }
        assertEquals(listOf("128", "320", "flac", "viper_clear"), levels)
    }

    @Test
    fun stableIdsRemainPositiveAndStable() {
        assertEquals(stableId("collection_3_123"), stableId("collection_3_123"))
        assertTrue(stableId("collection_3_123") > 0)
    }

    @Test
    fun parsesLikedPlaylistTracks() {
        val track = Track.parse(JSONObject("""{
          "hash":"ABC123","mixsongid":32177274,"name":"麻园诗人 - 泸沽湖.mp3",
          "timelen":246047,"cover":"https://img/cover.jpg"
        }"""))

        assertEquals(32177274, track.id)
        assertEquals("泸沽湖", track.name)
        assertEquals("麻园诗人", track.artistNames)
        assertEquals(246_047, track.durationMs)
    }

    @Test
    fun selectsKuGouLikedPlaylistInsteadOfEmptyDefaultCollection() {
        val items = listOf(
            JSONObject("""{"listid":1,"name":"默认收藏","count":0,"is_def":1}"""),
            JSONObject("""{"listid":2,"name":"我喜欢","count":119,"is_def":2}"""),
        )

        assertEquals(1, KuGouMusicApi.findLikedPlaylistIndex(items))
    }

    @Test
    fun separatesCollectedPlaylistsByOriginalCreator() {
        val own = JSONObject("""{"listid":3,"name":"通勤","list_create_userid":42}""")
        val collected = JSONObject("""{"listid":4,"name":"别人的歌单","list_create_userid":7}""")
        val likedFromOthers = JSONObject("""{"listid":2,"name":"我喜欢","list_create_userid":7}""")

        assertEquals(false, KuGouMusicApi.isCollectedPlaylist(own, uid = 42, liked = false))
        assertEquals(true, KuGouMusicApi.isCollectedPlaylist(collected, uid = 42, liked = false))
        assertEquals(false, KuGouMusicApi.isCollectedPlaylist(likedFromOthers, uid = 42, liked = true))
    }

    @Test
    fun searchedPlaylistUsesGlobalCollectionGidForDetailRequests() {
        val item = JSONObject(
            """{"specialid":6409645,"gid":"collection_3_2132029040_287_0","img":"http://img/cover.png"}""",
        )

        assertEquals("collection_3_2132029040_287_0", KuGouMusicApi.playlistRemoteKey(item))
        assertEquals("https://img/cover.png", item.stringAny("img").sized(300))
    }
}
