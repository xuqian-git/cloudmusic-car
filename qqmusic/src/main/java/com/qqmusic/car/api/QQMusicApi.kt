package com.qqmusic.car.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

object QQMusicApi {
    private const val TAG = "QQSearch"
    private const val SEARCH_TYPE_SONG = 0
    private const val SEARCH_TYPE_PLAYLIST = 3

    private val client = QQMusicClient
    private val tracks = ConcurrentHashMap<Long, Track>()

    /**
     * 先通知服务器退出再清本地（同 QQMusicApi 的 LoginServer.Logout）。只清本地的话服务器仍记着这台设备已登录，
     * 装过/重装过的设备越攒越多，最后新登录报 20279「登录设备数已达上限」。网络失败也照样清本地。
     */
    suspend fun logout() {
        if (client.isLoggedIn) runCatching { client.cgiAndroid("music.login.LoginServer", "Logout") }
        client.clearAuthCookies()
    }
    suspend fun refreshLogin() = Unit

    suspend fun userAccount(): Profile? {
        val auth = client.credential ?: return null
        val remote = runCatching {
            client.cgiAndroid(
                "music.UserInfo.userInfoServer", "GetLoginUserInfo",
            ).let { parseProfile(it, auth.musicId) }
        }.getOrNull()
        if (remote != null) {
            client.updateProfile(remote.nickname, remote.avatarUrl.orEmpty())
            return remote
        }
        return Profile(auth.musicId, auth.nickname.ifBlank { "QQ 音乐用户" }, auth.avatarUrl.ifBlank { null }, 0)
    }

    suspend fun userPlaylists(uid: Long): List<Playlist> {
        val auth = client.credential ?: return emptyList()
        val created = client.cgiAndroid(
            "music.musicasset.PlaylistBaseRead", "GetPlaylistByUin", JSONObject().put("uin", uid.toString()),
        )
        val createdItems = findArrays(created, "v_playlist", "playlist", "list").flatMap { it.objects() }
        // 自建列表里的创建者是字符串 uin，按接口语义直接记成自己；dirid 201 是「我喜欢」，已由下面的 liked 代表
        val mine = createdItems.filter { it.optInt("dirid", it.optInt("dirId")) != 201 }
            .map { Playlist.parse(it).copy(creatorId = uid) }
        val favorites = runCatching {
            client.cgiAndroid(
                "music.musicasset.PlaylistFavRead", "CgiGetPlaylistFavInfo",
                JSONObject().put("uin", auth.encryptUin).put("offset", 0).put("size", 1000),
            )
        }
        val favItems = findArrays(favorites.getOrNull() ?: JSONObject(), "v_list", "v_playlist", "playlist", "list")
            .flatMap { it.objects() }
        val saved = favItems.map(Playlist::parse).filter { it.id > 0 && it.name.isNotBlank() }
        val liked = Playlist(0, "我喜欢的音乐", null, 0, 0, uid, auth.nickname, 5)
        return (listOf(liked) + mine + saved).distinctBy(Playlist::id)
    }

    suspend fun likedTrackIds(uid: Long): Set<Long> = likedTracks().mapTo(mutableSetOf(), Track::id)

    suspend fun likeTrack(id: Long, like: Boolean) {
        val track = tracks[id] ?: error("歌曲信息已失效，请重新打开列表")
        val method = if (like) "AddSonglist" else "DelSonglist"
        client.cgiAndroid(
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

    /**
     * 搜歌走 App 同款的 DoSearchForQQMusicMobile（与 QQMusicApi 一致）。
     * 老的网页接口 client_search_cp 在车机流量卡这类共享出口 IP 上会被间歇限流，只在新接口出错或没结果时兜底。
     */
    suspend fun searchSongs(keywords: String, limit: Int = 50): List<Track> {
        val mobile = runCatching {
            searchRequest { mobileSearch(keywords, SEARCH_TYPE_SONG, limit) }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "App 搜索接口失败，退回网页接口", it)
        }.getOrNull()?.let(::parseSongSearch).orEmpty()
        if (mobile.isNotEmpty()) return register(mobile)
        Log.i(TAG, "App 搜索接口没结果，退回网页接口")
        val encoded = URLEncoder.encode(keywords, "UTF-8")
        val data = searchRequest {
            client.getJson("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&p=1&n=$limit&w=$encoded&cr=1&g_tk=5381&t=0&aggr=1&lossless=1")
        }
        return register(data.optJSONObject("data")?.optJSONObject("song")?.optJSONArray("list").objects().map(Track::parse))
    }

    suspend fun searchPlaylists(keywords: String, limit: Int = 40): List<Playlist> =
        parsePlaylistSearch(searchRequest { mobileSearch(keywords, SEARCH_TYPE_PLAYLIST, limit) })

    private suspend fun mobileSearch(keywords: String, type: Int, limit: Int): JSONObject =
        client.cgiAndroid(
            "music.search.SearchCgiService", "DoSearchForQQMusicMobile",
            JSONObject()
                .put("searchid", searchId())
                .put("query", keywords)
                .put("search_type", type)
                .put("num_per_page", limit)
                .put("page_num", 1)
                .put("highlight", 0)
                .put("grp", 1)
                .put("selectors", JSONObject())
                .put("vec_selectors", JSONArray()),
        )

    internal fun parseSongSearch(root: JSONObject): List<Track> =
        root.optJSONObject("body")?.optJSONArray("item_song").objects()
            .map(Track::parse).filter { it.mid.isNotBlank() && it.name.isNotBlank() }

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

    internal fun parsePlaylistSearch(root: JSONObject): List<Playlist> =
        findArrays(root, "item_songlist", "songlist").flatMap { it.objects() }
            .map(Playlist::parse).filter { it.id > 0 && it.name.isNotBlank() }.distinctBy(Playlist::id)

    internal fun parseProfile(root: JSONObject, userId: Long): Profile? {
        fun firstString(value: Any?, keys: Set<String>): String? {
            when (value) {
                is JSONObject -> {
                    for (key in keys) {
                        value.optString(key).takeIf(String::isNotBlank)?.let { return it }
                    }
                    value.keys().forEach { key -> firstString(value.opt(key), keys)?.let { return it } }
                }
                is JSONArray -> (0 until value.length()).forEach { index ->
                    firstString(value.opt(index), keys)?.let { return it }
                }
            }
            return null
        }
        val nickname = firstString(root, setOf("nick", "nickname", "nickName")) ?: return null
        val avatar = firstString(root, setOf("avatar", "avatarUrl", "headPic", "pic", "logo"))
        val vip = root.optInt("vipType", root.optInt("vip_type", 0))
        return Profile(userId, nickname, avatar, vip)
    }

    private fun deviceGuid(): String = Random.nextLong(100_000_000L, 999_999_999L).toString()

    private fun searchId(): String {
        val multiplier = Random.nextInt(1, 21).toLong() * 18_014_398_509_481_984L
        val randomPart = Random.nextLong(0, 4_194_305L) * 4_294_967_296L
        return (multiplier + randomPart + System.currentTimeMillis() % 86_400_000L).toString()
    }

    private suspend fun <T> searchRequest(block: suspend () -> T): T {
        var lastFailure: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!isTransientSearchFailure(error)) throw error
                lastFailure = error
                if (attempt < 2) delay(if (attempt == 0) 250 else 700)
            }
        }
        throw ApiException(-2, "QQ 音乐搜索服务暂时不稳定，请点击重试").also {
            it.initCause(lastFailure)
        }
    }

    internal fun isTransientSearchFailure(error: Exception): Boolean = when (error) {
        is ApiException -> error.code == -1 || error.code == 408 || error.code == 429 || error.code in 500..599
        is IOException, is JSONException -> true
        else -> false
    }

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
