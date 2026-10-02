package com.qqmusic.car.link

import com.qqmusic.car.api.Album
import com.qqmusic.car.api.Artist
import com.qqmusic.car.api.LyricLine
import com.qqmusic.car.api.Playlist
import com.qqmusic.car.api.Privilege
import com.qqmusic.car.api.Profile
import com.qqmusic.car.api.QQMusicApi
import com.qqmusic.car.api.Track
import com.qqmusic.car.data.MusicCacheStats
import com.qqmusic.car.player.PlayMode
import com.qqmusic.car.player.PlaybackNotice
import com.qqmusic.car.player.PlaybackPosition
import com.paopao.music.nowplaying.LyricWord
import com.paopao.music.nowplaying.NowPlayingNeighbors
import org.json.JSONArray
import org.json.JSONObject

/** 界面 ↔ 引擎之间传递的模型编码（JSON 文本，见 EngineLink）。字段逐个对应模型构造参数，往返无损。 */
internal object Codecs {
    fun track(t: Track): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("mid", t.mid)
        put("mediaMid", t.mediaMid)
        put("songType", t.songType)
        put("name", t.name)
        put("artists", JSONArray().apply { t.artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
        put("album", JSONObject().put("id", t.album.id).put("name", t.album.name).put("picUrl", t.album.picUrl ?: JSONObject.NULL))
        put("durationMs", t.durationMs)
        put("playable", t.hasPlayableFile)
        t.privilege?.let { p ->
            put("privilege", JSONObject().apply {
                put("id", p.id)
                p.fee?.let { put("fee", it) }
                p.pl?.let { put("pl", it) }
                p.st?.let { put("st", it) }
                p.cs?.let { put("cs", it) }
            })
        }
    }

    fun track(o: JSONObject): Track {
        val artists = o.getJSONArray("artists").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { x -> Artist(x.getLong("id"), x.getString("name")) } }
        }
        val album = o.getJSONObject("album")
        val privilege = o.optJSONObject("privilege")?.let { p ->
            Privilege(
                id = p.optLong("id"),
                fee = p.optIntOrNull("fee"),
                pl = p.optIntOrNull("pl"),
                st = p.optIntOrNull("st"),
                cs = if (p.has("cs")) p.optBoolean("cs") else null,
            )
        }
        return Track(
            id = o.getLong("id"),
            mid = o.getString("mid"),
            mediaMid = o.getString("mediaMid"),
            songType = o.getInt("songType"),
            name = o.getString("name"),
            artists = artists,
            album = Album(album.getLong("id"), album.getString("name"), album.optNullableString("picUrl")),
            durationMs = o.getLong("durationMs"),
            privilege = privilege,
            hasPlayableFile = o.optBoolean("playable", true),
        )
    }

    fun tracks(list: List<Track>): String = JSONArray().apply { list.forEach { put(track(it)) } }.toString()
    fun tracks(text: String): List<Track> = JSONArray(text).let { a -> (0 until a.length()).map { track(a.getJSONObject(it)) } }

    fun trackOrNull(t: Track?): String = t?.let { track(it).toString() } ?: "null"
    fun trackOrNull(text: String): Track? = if (text == "null") null else track(JSONObject(text))

    fun queue(list: List<Pair<Int, Track>>): String =
        JSONArray().apply { list.forEach { (i, t) -> put(track(t).put("_index", i)) } }.toString()

    fun queue(text: String): List<Pair<Int, Track>> = JSONArray(text).let { a ->
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getInt("_index") to track(o) } }
    }

    fun lyrics(lines: List<LyricLine>): String = JSONArray().apply {
        lines.forEach { l ->
            put(JSONObject().apply {
                put("t", l.timeMs)
                put("x", l.text)
                l.translation?.let { put("tr", it) }
                if (l.words.isNotEmpty()) {
                    put("w", JSONArray().apply { l.words.forEach { put(JSONArray().put(it.text).put(it.startMs).put(it.endMs)) } })
                }
            })
        }
    }.toString()

    fun lyrics(text: String): List<LyricLine> = JSONArray(text).let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val words = o.optJSONArray("w")?.let { w ->
                (0 until w.length()).map { j -> w.getJSONArray(j).let { x -> LyricWord(x.getString(0), x.getLong(1), x.getLong(2)) } }
            }.orEmpty()
            LyricLine(o.getLong("t"), o.getString("x"), if (o.has("tr")) o.getString("tr") else null, words)
        }
    }

    fun playlist(p: Playlist): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("coverUrl", p.coverUrl ?: JSONObject.NULL)
        put("trackCount", p.trackCount)
        put("playCount", p.playCount)
        put("creatorId", p.creatorId)
        put("creatorName", p.creatorName)
        put("specialType", p.specialType)
        put("subtitle", p.subtitle ?: JSONObject.NULL)
    }

    fun playlist(o: JSONObject): Playlist = Playlist(
        id = o.getLong("id"),
        name = o.getString("name"),
        coverUrl = o.optNullableString("coverUrl"),
        trackCount = o.getInt("trackCount"),
        playCount = o.getLong("playCount"),
        creatorId = o.getLong("creatorId"),
        creatorName = o.getString("creatorName"),
        specialType = o.getInt("specialType"),
        subtitle = o.optNullableString("subtitle"),
    )

    fun playlists(list: List<Playlist>): String = JSONArray().apply { list.forEach { put(playlist(it)) } }.toString()
    fun playlists(text: String): List<Playlist> = JSONArray(text).let { a -> (0 until a.length()).map { playlist(a.getJSONObject(it)) } }

    fun playlistDetail(d: QQMusicApi.PlaylistDetail): String = JSONObject()
        .put("playlist", playlist(d.playlist))
        .put("description", d.description ?: JSONObject.NULL)
        .put("tracks", JSONArray().apply { d.tracks.forEach { put(track(it)) } })
        .toString()

    fun playlistDetail(text: String): QQMusicApi.PlaylistDetail = JSONObject(text).let { o ->
        val a = o.getJSONArray("tracks")
        QQMusicApi.PlaylistDetail(
            playlist(o.getJSONObject("playlist")),
            o.optNullableString("description"),
            (0 until a.length()).map { track(a.getJSONObject(it)) },
        )
    }

    fun profile(p: Profile?): String = p?.let {
        JSONObject().put("userId", it.userId).put("nickname", it.nickname)
            .put("avatarUrl", it.avatarUrl ?: JSONObject.NULL).put("vipType", it.vipType).toString()
    } ?: "null"

    fun profile(text: String): Profile? = if (text == "null") null else JSONObject(text).let {
        Profile(it.getLong("userId"), it.getString("nickname"), it.optNullableString("avatarUrl"), it.optInt("vipType"))
    }

    fun ids(ids: Set<Long>): String = JSONArray().apply { ids.forEach { put(it) } }.toString()
    fun ids(text: String): Set<Long> = JSONArray(text).let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() }

    fun neighbors(n: NowPlayingNeighbors): String =
        JSONObject().put("p", n.previousIndex ?: -1).put("n", n.nextIndex ?: -1).toString()

    fun neighbors(text: String): NowPlayingNeighbors = JSONObject(text).let {
        NowPlayingNeighbors(it.getInt("p").takeIf { i -> i >= 0 }, it.getInt("n").takeIf { i -> i >= 0 })
    }

    fun notice(n: PlaybackNotice?): String = n?.let { JSONObject().put("k", it.kind).put("t", it.text).toString() } ?: "null"
    fun notice(text: String): PlaybackNotice? =
        if (text == "null") null else JSONObject(text).let { PlaybackNotice(it.getString("k"), it.getString("t")) }

    fun mode(m: PlayMode): String = m.name
    fun mode(text: String): PlayMode = PlayMode.valueOf(text)

    fun position(p: PlaybackPosition): String = JSONObject()
        .put("p", p.positionMs).put("d", p.durationMs).put("a", p.atElapsedMs).put("r", p.advancing).put("s", p.speed.toDouble())
        .toString()

    fun position(text: String): PlaybackPosition = JSONObject(text).let {
        PlaybackPosition(it.getLong("p"), it.getLong("d"), it.getLong("a"), it.getBoolean("r"), it.getDouble("s").toFloat())
    }

    fun stats(s: MusicCacheStats): String =
        JSONObject().put("a", s.audioBytes).put("i", s.imageBytes).put("l", s.lyricBytes).toString()

    fun stats(text: String): MusicCacheStats =
        JSONObject(text).let { MusicCacheStats(it.getLong("a"), it.getLong("i"), it.getLong("l")) }

    /** 原样还原（空串也保留），只有 JSON null / 缺键才是 null。 */
    private fun JSONObject.optNullableString(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null

    private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null
}
