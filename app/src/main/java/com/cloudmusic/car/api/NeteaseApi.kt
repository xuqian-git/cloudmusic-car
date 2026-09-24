package com.cloudmusic.car.api

import com.paopao.music.nowplaying.WordLyricsParser
import com.cloudmusic.car.api.NeteaseClient.checked
import org.json.JSONArray
import org.json.JSONObject

/** 网易云音乐接口，对应 Kumone 的 NeteaseAPI.swift（只保留车机版用到的部分）。 */
object NeteaseApi {
    private val client = NeteaseClient

    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (k, v) -> put(k, v) }
    }

    // ---------- 登录 ----------

    // 网易云已不再认 weapi + type 1 的扫码结果，这里与 NeteaseCloudMusicApi 当前实现一致：eapi + type 3。
    suspend fun qrKey(): String =
        client.eapi("/login/qrcode/unikey", json("type" to 3)).checked().getString("unikey")

    fun qrLoginUrl(unikey: String) = "https://music.163.com/login?codekey=$unikey"

    /** 800 过期 · 801 等待扫码 · 802 已扫码待确认 · 803 成功（Cookie 通过 Set-Cookie 下发）。 */
    suspend fun qrCheck(unikey: String): Pair<Int, String> {
        val resp = client.eapi("/login/qrcode/client/login", json("key" to unikey, "type" to 3))
        return resp.optInt("code") to resp.optString("message")
    }

    suspend fun logout() {
        runCatching { client.weapi("/logout") }
        client.clearAuthCookies()
    }

    suspend fun refreshLogin() {
        runCatching { client.weapi("/login/token/refresh") }
    }

    suspend fun userAccount(): Profile? {
        val p = client.weapi("/w/nuser/account/get").checked().optJSONObject("profile") ?: return null
        return Profile(p.optLong("userId"), p.optString("nickname"), p.optStringOrNull("avatarUrl"), p.optInt("vipType"))
    }

    // ---------- 资料库 ----------

    suspend fun userPlaylists(uid: Long): List<Playlist> =
        client.weapi("/user/playlist", json("uid" to uid, "limit" to 1000, "offset" to 0, "includeVideo" to true))
            .checked().optJSONArray("playlist").objects().map(Playlist::parse)

    suspend fun likedTrackIds(uid: Long): Set<Long> {
        val ids = client.weapi("/song/like/get", json("uid" to uid)).checked().optJSONArray("ids") ?: JSONArray()
        return (0 until ids.length()).map { ids.optLong(it) }.toSet()
    }

    suspend fun likeTrack(id: Long, like: Boolean) {
        client.weapi("/radio/like?alg=itembased&trackId=$id&time=3", json("trackId" to id, "like" to like)).checked()
    }

    suspend fun playRecords(uid: Long): List<Track> =
        client.weapi("/v1/play/record", json("uid" to uid, "type" to 0)).checked()
            .optJSONArray("allData").objects().mapNotNull { it.optJSONObject("song")?.let(Track::parse) }

    class CloudDrive(val tracks: List<Track>, val usedBytes: Long, val maxBytes: Long)

    /** 我的音乐云盘（用户上传的歌曲）。分页拉取，最多 [maxTracks] 首。 */
    suspend fun cloudDrive(maxTracks: Int = 3000): CloudDrive {
        val tracks = mutableListOf<Track>()
        var used = 0L
        var max = 0L
        var offset = 0
        while (offset < maxTracks) {
            val resp = client.weapi("/v1/cloud/get", json("limit" to 500, "offset" to offset)).checked()
            // size / maxSize 有时是数字、有时是字符串
            used = resp.optString("size").toLongOrNull() ?: used
            max = resp.optString("maxSize").toLongOrNull() ?: max
            val page = resp.optJSONArray("data").objects()
            for (item in page) {
                val song = item.optJSONObject("simpleSong") ?: continue
                // 云盘歌曲属于用户自己，始终可播放
                tracks += Track.parse(song, Privilege(song.optLong("id"), fee = 0, pl = 1, st = 0, cs = true))
            }
            if (page.isEmpty() || !resp.optBoolean("hasMore")) break
            offset += page.size
        }
        return CloudDrive(tracks, used, max)
    }

    // ---------- 推荐 ----------

    suspend fun recommendResource(): List<Playlist> =
        client.weapi("/v1/discovery/recommend/resource").checked().optJSONArray("recommend").objects().map(Playlist::parse)

    suspend fun personalizedPlaylists(limit: Int = 30): List<Playlist> =
        client.weapi("/personalized/playlist", json("limit" to limit, "total" to true, "n" to 1000))
            .checked().optJSONArray("result").objects().map(Playlist::parse)

    suspend fun dailySongs(): List<Track> {
        val data = client.weapi("/v3/discovery/recommend/songs").checked().optJSONObject("data") ?: return emptyList()
        return Track.parseList(data.optJSONArray("dailySongs"), data.optJSONArray("privileges"))
    }

    suspend fun personalFm(): List<Track> =
        Track.parseList(client.weapi("/v1/radio/get").checked().optJSONArray("data"), null)

    suspend fun fmTrash(id: Long) {
        client.weapi("/radio/trash/add?alg=RT&songId=$id&time=25", json("songId" to id)).checked()
    }

    /** 心动模式：以一首歌为种子生成队列。 */
    suspend fun intelligenceList(songId: Long, playlistId: Long): List<Track> =
        client.weapi(
            "/playmode/intelligence/list",
            json(
                "songId" to songId, "type" to "fromPlayOne", "playlistId" to playlistId,
                "startMusicId" to songId, "count" to 1,
            ),
        ).checked().optJSONArray("data").objects().mapNotNull { it.optJSONObject("songInfo")?.let(Track::parse) }

    suspend fun toplists(): List<Playlist> =
        client.eapi("/toplist").checked().optJSONArray("list").objects().map(Playlist::parse)

    // ---------- 歌单 ----------

    class PlaylistDetail(val playlist: Playlist, val description: String?, val tracks: List<Track>)

    /** 歌单详情。/v6/playlist/detail 只带第一页歌曲，其余按 trackIds 分批补全（最多 [maxTracks] 首）。 */
    suspend fun playlistDetail(id: Long, maxTracks: Int = 3000): PlaylistDetail {
        val resp = client.weapi("/v6/playlist/detail", json("id" to id, "n" to 100_000, "s" to 8)).checked()
        val p = resp.getJSONObject("playlist")
        val tracks = Track.parseList(p.optJSONArray("tracks"), resp.optJSONArray("privileges")).toMutableList()
        val allIds = p.optJSONArray("trackIds").objects().map { it.optLong("id") }
        val remaining = allIds.drop(tracks.size).take((maxTracks - tracks.size).coerceAtLeast(0))
        for (chunk in remaining.chunked(500)) {
            tracks += runCatching { songDetails(chunk) }.getOrNull() ?: break
        }
        return PlaylistDetail(Playlist.parse(p), p.optStringOrNull("description"), tracks)
    }

    suspend fun songDetails(ids: List<Long>): List<Track> {
        if (ids.isEmpty()) return emptyList()
        val c = ids.joinToString(",", "[", "]") { "{\"id\":$it}" }
        val resp = client.weapi("/v3/song/detail", json("c" to c)).checked()
        return Track.parseList(resp.optJSONArray("songs"), resp.optJSONArray("privileges"))
    }

    // ---------- 播放 ----------

    class SongUrl(val url: String?, val isTrial: Boolean)

    /** 阻塞调用，供播放器的 DataSource 在加载线程里解析真实地址。 */
    fun songUrlBlocking(id: Long, level: String): SongUrl {
        val payload = json("ids" to "[$id]", "level" to level, "encodeType" to "flac")
        if (level == "sky") payload.put("immerseType", "c51")
        val item = client.eapiBlocking("/song/enhance/player/url/v1", payload).checked()
            .optJSONArray("data").objects().firstOrNull()
        return SongUrl(
            url = item?.optStringOrNull("url")?.replace("http://", "https://"),
            isTrial = item?.has("freeTrialInfo") == true && !item.isNull("freeTrialInfo"),
        )
    }

    suspend fun lyric(id: Long): List<LyricLine> {
        // 新版接口带 YRC 逐字歌词；失败时退回老接口的逐行歌词
        val v1 = runCatching {
            client.eapi("/song/lyric/v1", json("id" to id, "cp" to false, "lv" to 0, "tv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0))
        }.getOrNull()?.takeIf { it.optJSONObject("lrc") != null || it.optJSONObject("yrc") != null }
        val resp = v1 ?: client.weapi("/song/lyric", json("id" to id, "lv" to -1, "kv" to -1, "tv" to -1, "rv" to -1))
        val lrc = resp.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val tlyric = resp.optJSONObject("tlyric")?.optString("lyric").orEmpty()
        val lines = LyricsParser.merge(lrc, tlyric)
        val yrc = WordLyricsParser.parseYrc(resp.optJSONObject("yrc")?.optString("lyric").orEmpty())
        return WordLyricsParser.attach(
            lines, yrc,
            timeOf = { it.timeMs },
            withWords = { line, w -> line.copy(words = w.words) },
            create = { LyricLine(it.timeMs, it.text, null, it.words) },
        ) ?: lines
    }

    /** 写入"最近播放"（startplay）与听歌排行（play）。 */
    suspend fun scrobble(action: String, trackId: Long, sourceId: Long, seconds: Long = 0) {
        val body = if (action == "startplay") {
            json("id" to trackId, "type" to "song", "mainsite" to "1", "mainsiteWeb" to "1", "content" to "id=$sourceId")
        } else {
            json(
                "download" to 0, "end" to "playend", "id" to trackId, "sourceId" to sourceId.toString(),
                "time" to seconds, "type" to "song", "wifi" to 0, "source" to "list",
                "mainsite" to "1", "mainsiteWeb" to "1", "content" to "id=$sourceId",
            )
        }
        val logs = JSONArray().put(json("action" to action, "json" to body)).toString()
        runCatching { client.eapi("/feedback/weblog", json("logs" to logs), mapOf("os" to "osx")) }
    }

    // ---------- 搜索 ----------

    suspend fun searchSongs(keywords: String, limit: Int = 50): List<Track> {
        val result = client.eapi(
            "/cloudsearch/pc",
            json("s" to keywords, "type" to 1, "limit" to limit, "offset" to 0, "total" to true),
        ).checked().optJSONObject("result")
        return Track.parseList(result?.optJSONArray("songs"), null)
    }

    suspend fun searchPlaylists(keywords: String, limit: Int = 40): List<Playlist> {
        val result = client.eapi(
            "/cloudsearch/pc",
            json("s" to keywords, "type" to 1000, "limit" to limit, "offset" to 0, "total" to true),
        ).checked().optJSONObject("result")
        return result?.optJSONArray("playlists").objects().map(Playlist::parse)
    }

    suspend fun searchDefaultKeyword(): String? =
        runCatching { client.eapi("/search/defaultkeyword/get").optJSONObject("data")?.optStringOrNull("showKeyword") }
            .getOrNull()
}
