package com.qqmusic.car.api

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object QQMusicApi {
    private val client = QQMusicClient
    private val tracks = ConcurrentHashMap<Long, Track>()

    suspend fun logout() = client.clearAuthCookies()
    suspend fun refreshLogin() = Unit

    suspend fun userAccount(): Profile? = client.credential?.let {
        Profile(it.musicId, it.nickname.ifBlank { "QQ 音乐用户" }, it.avatarUrl.ifBlank { null }, 0)
    }

    suspend fun userPlaylists(uid: Long): List<Playlist> {
        val auth = client.credential ?: return emptyList()
        val created = client.cgi(
            "music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", JSONObject().put("uin", uid.toString()),
        )
        val mine = findArrays(created, "v_playlist", "playlist", "list").flatMap { it.objects() }.map(Playlist::parse)
        val favorites = client.cgi(
            "music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
            JSONObject().put("uin", auth.encryptUin).put("offset", 0).put("size", 1000),
        )
        val saved = findArrays(favorites, "v_playlist", "playlist", "list").flatMap { it.objects() }.map(Playlist::parse)
        val liked = Playlist(0, "我喜欢的音乐", null, 0, 0, uid, auth.nickname, 5)
        return (listOf(liked) + mine + saved).distinctBy(Playlist::id)
    }

    suspend fun likedTrackIds(uid: Long): Set<Long> = likedTracks().mapTo(mutableSetOf(), Track::id)

    suspend fun likeTrack(id: Long, like: Boolean) {
        val track = tracks[id] ?: error("歌曲信息已失效，请重新打开列表")
        val method = if (like) "AddSonglist" else "DelSonglist"
        client.cgi(
            "music.musicasset.PlaylistDetailWrite", method,
            JSONObject()
                .put("dirId", 201)
                .put("tid", 0)
                .put("bFmtUtf8", true)
                .put("v_songInfo", JSONArray().put(JSONObject().put("songId", id).put("songType", track.songType))),
        )
    }

    suspend fun playRecords(uid: Long): List<Track> = QQRecentStore.load()

    class CloudDrive(val tracks: List<Track>, val usedBytes: Long, val maxBytes: Long)
    suspend fun cloudDrive(maxTracks: Int = 3000) = CloudDrive(dailySongs().take(maxTracks), 0, 0)

    suspend fun recommendResource(): List<Playlist> = personalizedPlaylists(18)

    suspend fun personalizedPlaylists(limit: Int = 30): List<Playlist> {
        val data = client.cgi(
            "music.playlist.PlaylistSquare", "GetRecommendFeed",
            JSONObject().put("From", 0).put("Size", limit),
        )
        return data.optJSONArray("List").objects().mapNotNull { item ->
            item.optJSONObject("Playlist")?.optJSONObject("basic")?.let(Playlist::parse)
        }
    }

    suspend fun dailySongs(): List<Track> {
        val data = client.cgi("newsong.NewSongServer", "get_new_song_info", JSONObject().put("type", 5))
        return register(data.optJSONArray("songlist").objects().map(Track::parse))
    }

    suspend fun personalFm(): List<Track> {
        val data = client.cgi(
            "music.radioProxy.MbTrackRadioSvr", "get_radio_track",
            JSONObject().put("id", 99).put("num", 20).put("from", 0).put("scene", 0).put("song_ids", JSONArray()),
        )
        return register(data.optJSONArray("tracks").objects().map(Track::parse))
    }

    suspend fun fmTrash(id: Long) = Unit
    suspend fun intelligenceList(songId: Long, playlistId: Long): List<Track> = personalFm()

    suspend fun toplists(): List<Playlist> {
        val data = client.cgi("music.musicToplist.Toplist", "GetAll")
        return data.optJSONArray("group").objects().flatMap { group ->
            group.optJSONArray("toplist").objects().map { top ->
                Playlist(
                    id = top.optLong("topId"),
                    name = top.optString("title"),
                    coverUrl = top.toplistCoverUrl(),
                    trackCount = top.optInt("totalNum"),
                    playCount = top.optLong("listenNum"),
                    creatorId = 0,
                    creatorName = "QQ 音乐",
                    specialType = -1,
                    subtitle = top.optStringOrNull("updateTips"),
                )
            }
        }
    }

    class PlaylistDetail(val playlist: Playlist, val description: String?, val tracks: List<Track>)

    suspend fun playlistDetail(id: Long, maxTracks: Int = 3000): PlaylistDetail {
        if (id == 0L) {
            val liked = likedTracks()
            return PlaylistDetail(
                Playlist(0, "我喜欢的音乐", liked.firstOrNull()?.coverUrl, liked.size, 0, client.credential?.musicId ?: 0, "", 5),
                null,
                liked,
            )
        }
        if (id in 1..999) return topDetail(id, maxTracks)
        val data = client.cgi(
            "music.srfDissInfo.DissInfo", "CgiGetDiss",
            JSONObject()
                .put("disstid", id).put("dirid", 0).put("tag", true)
                .put("song_begin", 0).put("song_num", maxTracks).put("userinfo", true)
                .put("orderlist", true).put("onlysonglist", false),
        )
        val info = data.optJSONObject("dirinfo") ?: JSONObject().put("id", id).put("title", "歌单")
        return PlaylistDetail(Playlist.parse(info), info.optStringOrNull("desc"), register(data.optJSONArray("songlist").objects().map(Track::parse)))
    }

    private suspend fun topDetail(id: Long, maxTracks: Int): PlaylistDetail {
        val data = client.cgi(
            "music.musicToplist.Toplist", "GetDetail",
            JSONObject().put("topId", id).put("offset", 0).put("num", maxTracks).put("withTags", true),
        )
        val info = data.optJSONObject("data") ?: JSONObject().put("topId", id).put("title", "排行榜")
        val playlist = Playlist(
            id, info.optString("title"), info.toplistCoverUrl(), info.optInt("totalNum"),
            info.optLong("listenNum"), 0, "QQ 音乐", -1, info.optStringOrNull("updateTips"),
        )
        return PlaylistDetail(playlist, info.optStringOrNull("intro"), register(data.optJSONArray("songInfoList").objects().map(Track::parse)))
    }

    suspend fun songDetails(ids: List<Long>): List<Track> = ids.mapNotNull(tracks::get)

    class SongUrl(val url: String?, val isTrial: Boolean)

    fun songUrlBlocking(id: Long, level: String): SongUrl {
        val track = tracks[id] ?: return SongUrl(null, false)
        val (prefix, extension) = qqFileFormat(level)
        val filename = "$prefix${track.mediaMid}$extension"
        val auth = client.credential
        val data = client.cgiBlocking(
            "music.vkey.GetVkey", "UrlGetVkey",
            JSONObject()
                .put("uin", auth?.musicId?.toString().orEmpty())
                .put("filename", JSONArray().put(filename))
                .put("guid", deviceGuid())
                .put("songmid", JSONArray().put(track.mid))
                .put("songtype", JSONArray().put(track.songType))
                .put("ctx", 0),
        )
        val info = data.optJSONArray("midurlinfo")?.optJSONObject(0)
        val purl = info?.optString("purl").orEmpty()
        val base = data.optJSONArray("sip")?.optString(0).orEmpty().ifBlank { "https://isure.stream.qqmusic.qq.com/" }
        return SongUrl(purl.takeIf(String::isNotBlank)?.let { base.replace("http://", "https://") + it }, info?.optString("opi30surl").orEmpty().isNotBlank())
    }

    suspend fun lyric(id: Long): List<LyricLine> {
        val track = tracks[id] ?: return emptyList()
        val mid = URLEncoder.encode(track.mid, "UTF-8")
        val root = client.getJson("https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid=$mid&format=json&nobase64=1")
        return LyricsParser.merge(root.optString("lyric"), root.optString("trans"))
    }

    suspend fun scrobble(action: String, trackId: Long, sourceId: Long, seconds: Long = 0) = Unit

    fun remember(track: Track) {
        tracks[track.id] = track
        QQRecentStore.record(track)
    }

    suspend fun searchSongs(keywords: String, limit: Int = 50): List<Track> {
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val data = client.getJson("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=1&n=$limit&w=$encoded&cr=1&g_tk=5381&t=0&aggr=1&lossless=1")
        return register(data.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list").objects().map(Track::parse))
    }

    suspend fun searchPlaylists(keywords: String, limit: Int = 40): List<Playlist> {
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val data = client.getJson("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=1&n=$limit&w=$encoded&cr=1&g_tk=5381&t=3&aggr=1")
        return data.optJSONObject("data")?.optJSONObject("songlist")?.optJSONArray("list").objects().map(Playlist::parse)
    }

    suspend fun searchDefaultKeyword(): String? = "搜索歌曲、歌手、专辑或歌单"

    private suspend fun likedTracks(): List<Track> {
        val auth = client.credential ?: return emptyList()
        val data = client.cgi(
            "music.srfDissInfo.DissInfo", "CgiGetDiss",
            JSONObject()
                .put("disstid", 0).put("dirid", 201).put("tag", true)
                .put("song_begin", 0).put("song_num", 3000).put("userinfo", true)
                .put("orderlist", true).put("enc_host_uin", auth.encryptUin),
        )
        return register(data.optJSONArray("songlist").objects().map(Track::parse))
    }

    private fun register(items: List<Track>): List<Track> = items.also { list -> list.forEach { tracks[it.id] = it } }

    private fun findArrays(root: JSONObject, vararg keys: String): List<JSONArray> {
        val found = mutableListOf<JSONArray>()
        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    val child = value.opt(key)
                    if (key in keys && child is JSONArray) found += child else walk(child)
                }
                is JSONArray -> (0 until value.length()).forEach { walk(value.opt(it)) }
            }
        }
        walk(root)
        return found
    }

    private fun deviceGuid(): String = Random.nextLong(100_000_000L, 999_999_999L).toString()

    private fun JSONObject.toplistCoverUrl(): String? =
        optStringOrNull("frontPicUrl")
            ?: optStringOrNull("mbFrontPicUrl")
            ?: optStringOrNull("musichallPicUrl")
            ?: optStringOrNull("headPicUrl")
}

internal fun qqFileFormat(level: String): Pair<String, String> = when (level) {
    "master" -> "AI00" to ".flac"
    "lossless" -> "F000" to ".flac"
    "exhigh" -> "M800" to ".mp3"
    else -> "M500" to ".mp3"
}
