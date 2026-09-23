package com.kugoumusic.car.api

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.InflaterInputStream

object KuGouMusicApi {
    private data class RemotePlaylist(
        val key: String,
        val kind: Kind,
        val rankCid: Long = 0,
        val name: String = "歌单",
        val coverUrl: String? = null,
    )
    private enum class Kind { PUBLIC, USER, RANK }

    private val tracks = ConcurrentHashMap<Long, Track>()
    private val playlists = ConcurrentHashMap<Long, RemotePlaylist>()

    suspend fun logout() = KuGouMusicClient.clearAuthCookies()
    suspend fun refreshLogin() = Unit

    suspend fun userAccount(): Profile? {
        val auth = KuGouMusicClient.credential ?: return null
        if (auth.nickname.isNotBlank()) return Profile(auth.userId, auth.nickname, auth.avatarUrl.ifBlank { null }, auth.vipType)
        return runCatching {
            val root = KuGouMusicClient.request(
                "/v1/get_user_info", "POST",
                baseUrl = "https://relation.user.kugou.com",
                body = JSONObject().put("userid", auth.userId).put("token", auth.token).toString(),
            )
            val data = root.optJSONObject("data") ?: root
            Profile(auth.userId, data.stringAny("nickname", "username", "nick_name").ifBlank { "酷狗用户" }, data.stringAny("pic", "avatar", "user_img").ifBlank { null }, auth.vipType)
        }.getOrElse { Profile(auth.userId, "酷狗用户", null, auth.vipType) }
    }

    suspend fun userPlaylists(uid: Long): List<Playlist> {
        val auth = KuGouMusicClient.credential ?: return emptyList()
        val body = JSONObject().put("userid", uid).put("token", auth.token).put("total_ver", 979)
            .put("type", 2).put("page", 1).put("pagesize", 30).toString()
        val root = KuGouMusicClient.request(
            "/v7/get_all_list", "POST", mapOf("plat" to 1, "userid" to uid, "token" to auth.token), body,
            headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
        )
        val data = root.optJSONObject("data") ?: root
        val source = data.arrayAny("info", "list", "lists")?.objects().orEmpty()
        val likedIndex = findLikedPlaylistIndex(source)
        return source.mapIndexed { index, item ->
            val remote = item.stringAny("listid", "id", "global_collection_id")
            val id = item.longAny("listid", "id").takeIf { it > 0 } ?: stableId(remote)
            val name = item.stringAny("name", "listname", "specialname").ifBlank { "歌单" }
            val coverUrl = item.stringAny("pic", "img", "imgurl", "cover", "cover_url").ifBlank { null }
            playlists[id] = RemotePlaylist(remote.ifBlank { id.toString() }, Kind.USER, name = name, coverUrl = coverUrl)
            Playlist(
                id, name, coverUrl,
                item.longAny("count", "song_count", "total").toInt(), item.longAny("play_count"), uid,
                auth.nickname, if (index == likedIndex) 5 else 0,
            )
        }
    }

    suspend fun likedTrackIds(uid: Long): Set<Long> = userPlaylists(uid).firstOrNull(Playlist::isLikedSongs)
        ?.let { runCatching { playlistDetail(it.id).tracks.mapTo(mutableSetOf(), Track::id) }.getOrDefault(emptySet()) }
        ?: emptySet()

    suspend fun likeTrack(id: Long, like: Boolean) {
        val auth = KuGouMusicClient.credential ?: error("请先登录")
        val liked = userPlaylists(auth.userId).firstOrNull(Playlist::isLikedSongs) ?: error("没有找到我喜欢的音乐")
        val remote = playlists[liked.id]?.key ?: liked.id.toString()
        val track = tracks[id] ?: error("歌曲信息已失效")
        if (like) {
            val resource = JSONObject().put("number", 1).put("name", "${track.artistNames} - ${track.name}")
                .put("hash", track.mid).put("size", 0).put("sort", 0).put("timelen", track.durationMs)
                .put("bitrate", 0).put("album_id", track.album.id).put("mixsongid", track.mediaMid.toLongOrNull() ?: 0)
            val body = JSONObject().put("userid", auth.userId).put("token", auth.token).put("listid", remote)
                .put("list_ver", 0).put("type", 0).put("slow_upload", 1).put("scene", "false;null")
                .put("data", JSONArray().put(resource)).toString()
            KuGouMusicClient.request("/cloudlist.service/v6/add_song", "POST", body = body)
        } else {
            throw ApiException(-1, "酷狗删除红心需要歌单文件编号，请在酷狗 App 中操作")
        }
    }

    suspend fun playRecords(uid: Long): List<Track> = KuGouRecentStore.load()

    class CloudDrive(val tracks: List<Track>, val usedBytes: Long, val maxBytes: Long)

    suspend fun cloudDrive(maxTracks: Int = 3000): CloudDrive {
        val all = mutableListOf<Track>()
        var page = 1
        var used = 0L
        var max = 0L
        while (all.size < maxTracks) {
            val root = cloudPage(page, minOf(100, maxTracks - all.size))
            val data = root.optJSONObject("data") ?: root
            val list = data.arrayAny("info", "list", "files", "songs")?.objects().orEmpty()
            if (list.isEmpty()) break
            all += register(list.map { Track.parse(it, cloud = true) })
            used = data.longAny("used_size", "use_space", "used")
            max = data.longAny("total_size", "max_space", "total")
            if (list.size < 100) break
            page++
        }
        return CloudDrive(all.distinctBy(Track::id), used, max)
    }

    suspend fun recommendResource(): List<Playlist> = personalizedPlaylists(18)

    suspend fun personalizedPlaylists(limit: Int = 30): List<Playlist> {
        val now = System.currentTimeMillis() / 1000
        val special = JSONObject().put("withtag", 1).put("withsong", 1).put("sort", 1).put("ugc", 1)
            .put("is_selected", 0).put("withrecommend", 1).put("area_code", 1).put("categoryid", 0)
        val body = JSONObject().put("appid", KuGouMusicClient.APP_ID).put("mid", KuGouMusicClient.mid)
            .put("clientver", KuGouMusicClient.CLIENT_VERSION).put("platform", "android").put("clienttime", now)
            .put("userid", KuGouMusicClient.credential?.userId ?: 0).put("module_id", 1).put("page", 1)
            .put("pagesize", limit).put("key", KuGouMusicClient.signParamsKey(now.toString()))
            .put("special_recommend", special).put("req_multi", 1).put("retrun_min", 5).put("return_special_falg", 1).toString()
        val root = KuGouMusicClient.request("/v2/special_recommend", "POST", body = body, headers = mapOf("x-router" to "specialrec.service.kugou.com"))
        val data = root.optJSONObject("data") ?: root
        return parsePlaylists(data.arrayAny("special_list", "info", "list").objects(), Kind.PUBLIC)
    }

    suspend fun dailySongs(): List<Track> {
        val body = JSONObject().put("platform", "android").put("userid", KuGouMusicClient.credential?.userId ?: 0).toString()
        val root = KuGouMusicClient.request("/everyday_song_recommend", "POST", body = body, headers = mapOf("x-router" to "everydayrec.service.kugou.com"))
        val data = root.optJSONObject("data") ?: root
        return register(data.arrayAny("song_list", "songs", "info", "list").objects().map(Track::parse))
    }

    suspend fun personalFm(): List<Track> = dailySongs()
    suspend fun fmTrash(id: Long) = Unit

    /**
     * 酷狗没有网易云同名的心动模式；这里使用官方“猜你喜欢 / 私人 FM”，
     * 并把红心歌曲作为本次推荐的种子，避免退化成每日推荐的重复入口。
     */
    suspend fun intelligenceList(songId: Long, playlistId: Long): List<Track> {
        val seed = tracks[songId] ?: error("红心歌曲信息已失效")
        val now = System.currentTimeMillis()
        val body = JSONObject()
            .put("appid", KuGouMusicClient.APP_ID)
            .put("clienttime", now)
            .put("mid", KuGouMusicClient.mid)
            .put("action", "play")
            .put("recommend_source_locked", 0)
            .put("song_pool_id", 0)
            .put("callerid", 0)
            .put("m_type", 1)
            .put("platform", "android")
            .put("area_code", 1)
            .put("remain_songcnt", 0)
            .put("clientver", KuGouMusicClient.CLIENT_VERSION)
            .put("is_overplay", 0)
            .put("mode", "normal")
            .put("fakem", "ca981cfc583a4c37f28d2d49000013c16a0a")
            .put("key", KuGouMusicClient.signParamsKey(now.toString()))
            .put("hash", seed.mid)
            .put("songid", seed.mediaMid.toLongOrNull() ?: seed.id)
        KuGouMusicClient.credential?.let { auth ->
            body.put("userid", auth.userId)
                .put("kguid", auth.userId)
                .put("token", auth.token)
            if (auth.vipType > 0) body.put("vip_type", auth.vipType)
        }
        val root = KuGouMusicClient.request(
            "/v2/personal_recommend",
            "POST",
            body = body.toString(),
            headers = mapOf("x-router" to "persnfm.service.kugou.com"),
        )
        val data = root.optJSONObject("data") ?: root
        return register(data.arrayAny("song_list", "songs", "info", "list").objects().map(Track::parse))
    }

    suspend fun toplists(): List<Playlist> {
        val root = KuGouMusicClient.request("/ocean/v6/rank/list", params = mapOf("plat" to 2, "withsong" to 1, "parentid" to 0))
        val data = root.optJSONObject("data") ?: root
        return data.arrayAny("info", "list").objects().map { item ->
            val id = item.longAny("rankid", "id")
            playlists[id] = RemotePlaylist(id.toString(), Kind.RANK, item.longAny("rank_cid"))
            Playlist(id, item.stringAny("rankname", "name"), item.stringAny("imgurl", "bannerurl", "img_cover").ifBlank { null },
                item.longAny("total", "count").toInt(), item.longAny("play_times"), 0, "酷狗音乐", -1,
                item.stringAny("update_frequency", "intro").ifBlank { null })
        }
    }

    class PlaylistDetail(val playlist: Playlist, val description: String?, val tracks: List<Track>)

    suspend fun playlistDetail(id: Long, maxTracks: Int = 3000): PlaylistDetail {
        val remote = playlists[id] ?: RemotePlaylist(id.toString(), Kind.USER)
        return if (remote.kind == Kind.RANK) rankDetail(id, remote, maxTracks) else {
            if (remote.kind == Kind.USER) {
                val list = loadUserPlaylistTracks(remote, maxTracks)
                return PlaylistDetail(
                    Playlist(id, remote.name, remote.coverUrl ?: list.firstOrNull()?.coverUrl, list.size, 0, 0, "", 0),
                    null,
                    list,
                )
            }
            val params = mapOf("area_code" to 1, "begin_idx" to 0, "plat" to 1, "type" to 1, "mode" to 1,
                "personal_switch" to 1, "extend_fields" to "abtags,hot_cmt,popularization", "pagesize" to maxTracks,
                "global_collection_id" to remote.key)
            val root = KuGouMusicClient.request("/pubsongs/v2/get_other_list_file_nofilt", params = params)
            val data = root.optJSONObject("data") ?: root
            val list = register(data.arrayAny("songs", "songlist", "info", "list").objects().map(Track::parse))
            PlaylistDetail(
                Playlist(id, remote.name, remote.coverUrl ?: list.firstOrNull()?.coverUrl, list.size, 0, 0, "", if (id == -1L) 5 else 0),
                null,
                list,
            )
        }
    }

    private suspend fun loadUserPlaylistTracks(remote: RemotePlaylist, maxTracks: Int): List<Track> {
        val auth = KuGouMusicClient.credential ?: error("请先登录")
        val result = mutableListOf<Track>()
        var page = 1
        while (result.size < maxTracks) {
            val pageSize = minOf(300, maxTracks - result.size)
            val body = JSONObject().put("listid", remote.key).put("userid", auth.userId).put("token", auth.token)
                .put("type", 0).put("page", page).put("pagesize", pageSize).put("area_code", 1)
                .put("allplatform", 1).put("show_cover", 1).toString()
            val root = KuGouMusicClient.request(
                "/v4/get_list_all_file_v3",
                "POST",
                body = body,
                headers = mapOf("x-router" to "cloudlist.service.kugou.com"),
            )
            val data = root.optJSONObject("data") ?: root
            val chunk = data.arrayAny("songs", "songlist", "info", "list").objects()
            if (chunk.isEmpty()) break
            result += chunk.map(Track::parse)
            if (chunk.size < pageSize) break
            page++
        }
        return register(result.distinctBy(Track::id))
    }

    private suspend fun rankDetail(id: Long, remote: RemotePlaylist, maxTracks: Int): PlaylistDetail {
        val body = JSONObject().put("show_portrait_mv", 1).put("show_type_total", 1).put("filter_original_remarks", 1)
            .put("area_code", 1).put("pagesize", maxTracks).put("rank_cid", remote.rankCid).put("type", 1)
            .put("page", 1).put("rank_id", id).toString()
        val root = KuGouMusicClient.request("/openapi/kmr/v2/rank/audio", "POST", body = body, headers = mapOf("kg-tid" to "369"))
        val data = root.optJSONObject("data") ?: root
        val list = register(data.arrayAny("songlist", "songs", "info", "list").objects().map(Track::parse))
        return PlaylistDetail(Playlist(id, "排行榜", list.firstOrNull()?.coverUrl, list.size, 0, 0, "酷狗音乐", -1), null, list)
    }

    suspend fun songDetails(ids: List<Long>): List<Track> = ids.mapNotNull(tracks::get)

    class SongUrl(val url: String?, val isTrial: Boolean)

    fun songUrlBlocking(id: Long, level: String): SongUrl {
        val track = tracks[id] ?: return SongUrl(null, false)
        if (track.songType == 1) return cloudSongUrl(track)
        KuGouMusicClient.ensureDeviceRegisteredBlocking()
        val params = linkedMapOf<String, Any?>(
            "album_id" to track.album.id, "area_code" to 1, "hash" to track.mid.lowercase(),
            "ssa_flag" to "is_fromtrack", "version" to 11430, "page_id" to 151369488,
            "quality" to level, "album_audio_id" to (track.mediaMid.toLongOrNull() ?: 0), "behavior" to "play",
            "pid" to 2, "cmd" to 26, "pidversion" to 3001, "IsFreePart" to 1,
            "ppage_id" to "463467626,350369493,788954147", "cdnBackup" to 1, "module" to "", "clientver" to 11430,
        )
        params["key"] = KuGouMusicClient.signKey(track.mid)
        val root = KuGouMusicClient.requestBlocking("/v5/url", params = params, headers = mapOf("x-router" to "trackercdn.kugou.com"))
        val data = root.optJSONObject("data") ?: root
        val urls = data.optJSONArray("url") ?: root.optJSONArray("url")
        val url = urls?.optString(0)?.takeIf(String::isNotBlank) ?: data.stringAny("play_url", "url").takeIf(String::isNotBlank)
        return SongUrl(url?.replace("http://", "https://"), data.optInt("is_free_part") == 1 || data.optInt("status") == 2)
    }

    private fun cloudSongUrl(track: Track): SongUrl {
        val params = mapOf(
            "hash" to track.mid.lowercase(), "ssa_flag" to "is_fromtrack", "version" to 20102, "ssl" to 1,
            "album_audio_id" to (track.mediaMid.toLongOrNull() ?: 0), "pid" to 20026, "audio_id" to 0,
            "kv_id" to 2, "key" to KuGouMusicClient.signCloudKey(track.mid), "bucket" to "musicclound",
            "name" to track.name, "with_res_tag" to 0,
        )
        val root = KuGouMusicClient.requestBlocking("/bsstrackercdngz/v2/query_musicclound_url", params = params)
        val data = root.optJSONObject("data") ?: root
        val url = data.stringAny("url", "play_url").takeIf(String::isNotBlank)
            ?: data.optJSONArray("url")?.optString(0)?.takeIf(String::isNotBlank)
        return SongUrl(url?.replace("http://", "https://"), false)
    }

    suspend fun lyric(id: Long): List<LyricLine> {
        val track = tracks[id] ?: return emptyList()
        val common = mapOf(
            "appid" to KuGouMusicClient.APP_ID,
            "clientver" to KuGouMusicClient.CLIENT_VERSION,
            "lrctxt" to 1,
            "man" to "no",
        )
        suspend fun search(params: Map<String, Any?>): JSONObject? = runCatching {
            KuGouMusicClient.request(
                "/v1/search",
                params = common + mapOf(
                    "album_audio_id" to 0,
                    "duration" to 0,
                    "hash" to "",
                    "keyword" to "",
                ) + params,
                baseUrl = "https://lyrics.kugou.com",
                signType = SignType.ANDROID,
                clearDefaults = true,
            )
        }.getOrNull()

        val candidate = search(mapOf("hash" to track.mid))
            ?.arrayAny("candidates", "data")?.optJSONObject(0)
            ?: search(mapOf("keyword" to "${track.artistNames} - ${track.name}", "duration" to track.durationMs / 1000))
                ?.arrayAny("candidates", "data")?.optJSONObject(0)
            ?: return emptyList()
        val downloaded = KuGouMusicClient.request(
            "/download", params = mapOf("ver" to 1, "client" to "android", "id" to candidate.stringAny("id"),
                "accesskey" to candidate.stringAny("accesskey"), "fmt" to "lrc", "charset" to "utf8"),
            baseUrl = "https://lyrics.kugou.com", signType = SignType.ANDROID, clearDefaults = true,
        )
        val lrc = decodeLyricContent(downloaded)
        Log.i(
            "KuGouMusic",
            "Lyrics decoded: type=${downloaded.optInt("contenttype", -1)}, encoded=${downloaded.optString("content").length}, text=${lrc.length}",
        )
        return LyricsParser.merge(lrc, "")
    }

    suspend fun scrobble(action: String, trackId: Long, sourceId: Long, seconds: Long = 0) = Unit

    fun remember(track: Track) {
        tracks[track.id] = track
        KuGouRecentStore.record(track)
    }

    suspend fun searchSongs(keywords: String, limit: Int = 50): List<Track> {
        val root = KuGouMusicClient.request(
            "/v2/search/song", params = mapOf("keyword" to keywords, "page" to 1, "pagesize" to limit,
                "platform" to "AndroidFilter", "iscorrection" to 1, "privilegefilter" to 0, "area_code" to 1, "dopicfull" to 1),
            headers = mapOf("x-router" to "complexsearch.kugou.com"),
        )
        val data = root.optJSONObject("data") ?: root
        return register(data.arrayAny("lists", "info", "list").objects().map(Track::parse))
    }

    suspend fun searchPlaylists(keywords: String, limit: Int = 40): List<Playlist> {
        val root = KuGouMusicClient.request(
            "/v1/search/special", params = mapOf("keyword" to keywords, "page" to 1, "pagesize" to limit,
                "platform" to "AndroidFilter", "iscorrection" to 1), headers = mapOf("x-router" to "complexsearch.kugou.com"),
        )
        val data = root.optJSONObject("data") ?: root
        return parsePlaylists(data.arrayAny("lists", "info", "list").objects(), Kind.PUBLIC)
    }

    suspend fun searchDefaultKeyword(): String? = "搜索歌曲、歌手、专辑或歌单"

    private fun parsePlaylists(items: List<JSONObject>, kind: Kind): List<Playlist> = items.map { item ->
        val key = playlistRemoteKey(item)
        val id = item.longAny("listid", "specialid", "id").takeIf { it > 0 && kind == Kind.USER } ?: stableId(key)
        val name = item.stringAny("specialname", "name", "listname", "title").ifBlank { "歌单" }
        val coverUrl = item.stringAny("flexible_cover", "img", "imgurl", "pic", "cover").ifBlank { null }
        playlists[id] = RemotePlaylist(key, kind, name = name, coverUrl = coverUrl)
        Playlist(id, name, coverUrl,
            item.longAny("song_count", "count", "total").toInt(), item.longAny("play_count", "playcount"),
            item.longAny("suid", "userid"), item.stringAny("nickname", "username"), 0, item.stringAny("intro").ifBlank { null })
    }

    internal fun playlistRemoteKey(item: JSONObject): String =
        item.stringAny("global_collection_id", "gid", "listid", "specialid", "id")

    private suspend fun cloudPage(page: Int, pageSize: Int): JSONObject = withContext(Dispatchers.IO) {
        val auth = KuGouMusicClient.credential ?: error("请先登录")
        val cipher = KuGouMusicClient.encryptCloud(JSONObject().put("page", page).put("pagesize", pageSize).put("getkmr", 1).toString())
        val time = System.currentTimeMillis() / 1000
        val p = KuGouMusicClient.rsaEncrypt(JSONObject().put("aes", cipher.key).put("uid", auth.userId).put("token", auth.token).toString()).uppercase()
        val url = "https://mcloudservice.kugou.com/v1/get_list".toHttpUrl().newBuilder()
            .addQueryParameter("clienttime", time.toString()).addQueryParameter("mid", KuGouMusicClient.mid)
            .addQueryParameter("key", KuGouMusicClient.signParamsKey(time.toString()))
            .addQueryParameter("clientver", KuGouMusicClient.CLIENT_VERSION.toString()).addQueryParameter("appid", KuGouMusicClient.APP_ID.toString())
            .addQueryParameter("p", p).build()
        val request = Request.Builder().url(url)
            .header("User-Agent", KuGouMusicClient.USER_AGENT)
            .header("dfid", KuGouMusicClient.dfid)
            .header("mid", KuGouMusicClient.mid)
            .header("clienttime", time.toString())
            .header("kg-rc", "1")
            .header("kg-thash", "5d816a0")
            .header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .post(cipher.bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        KuGouMusicClient.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ApiException(response.code, "云盘加载失败 (${response.code})")
            JSONObject(KuGouMusicClient.decryptCloud(response.body?.bytes() ?: byteArrayOf(), cipher.key))
        }
    }

    private fun register(items: List<Track>): List<Track> = items.also { list -> list.forEach { tracks[it.id] = it } }

    internal fun findLikedPlaylistIndex(items: List<JSONObject>): Int {
        fun nameAt(index: Int) = items[index].stringAny("name", "listname", "specialname")
        return items.indices.firstOrNull { items[it].optInt("is_def") == 2 }
            ?: items.indices.firstOrNull { nameAt(it).contains("我喜欢") || nameAt(it).contains("红心") }
            ?: items.indices.firstOrNull { items[it].optInt("is_def") == 1 || items[it].optInt("is_default") == 1 }
            ?: items.indices.firstOrNull()
            ?: -1
    }

    internal fun decodeLyricContent(response: JSONObject): String {
        val encoded = response.optString("content")
        if (encoded.isBlank()) return ""
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return decodeLyricBytes(response.optInt("contenttype", 1), bytes)
    }

    internal fun decodeLyricBytes(contentType: Int, bytes: ByteArray): String {
        if (contentType != 0) return bytes.toString(Charsets.UTF_8)
        if (bytes.size <= 4) return ""

        val key = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
        val encrypted = bytes.copyOfRange(4, bytes.size)
        encrypted.indices.forEach { index -> encrypted[index] = (encrypted[index].toInt() xor key[index % key.size]).toByte() }
        return runCatching {
            InflaterInputStream(ByteArrayInputStream(encrypted)).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
}
