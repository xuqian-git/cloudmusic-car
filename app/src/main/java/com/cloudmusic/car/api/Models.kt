package com.cloudmusic.car.api

import org.json.JSONArray
import org.json.JSONObject

data class Artist(val id: Long, val name: String)

data class Album(val id: Long, val name: String, val picUrl: String?)

/** 每首歌的可播放性信息（接口里平行返回的 privileges，或内嵌在歌曲里）。 */
data class Privilege(val id: Long, val fee: Int?, val pl: Int?, val st: Int?, val cs: Boolean?)

data class Track(
    val id: Long,
    val name: String,
    val artists: List<Artist>,
    val album: Album,
    val durationMs: Long,
    val fee: Int,
    val noCopyright: Boolean,
    val privilege: Privilege?,
) {
    val artistNames: String get() = artists.joinToString(" / ") { it.name }

    /** 与 Kumone 的 playability 判断一致（参考 YesPlayMusic）。返回 null 表示可播放，否则为原因。 */
    fun unplayableReason(isLoggedIn: Boolean, vipType: Int): String? {
        val p = privilege
        if ((p?.pl ?: 0) > 0) return null
        if (isLoggedIn && p?.cs == true) return null
        when (p?.fee ?: fee) {
            1 -> return if (vipType > 0) null else "VIP 专属"
            4 -> return "付费专辑"
        }
        if (noCopyright) return "无版权"
        if (p?.st != null && p.st < 0 && isLoggedIn) return "已下架"
        return null
    }

    companion object {
        /** 兼容 v3 结构（ar/al/dt）与旧结构（artists/album/duration）。 */
        fun parse(o: JSONObject, privilege: Privilege? = null): Track {
            val artists = (o.optJSONArray("ar") ?: o.optJSONArray("artists"))
                .objects().map { Artist(it.optLong("id"), it.optString("name")) }
            val al = o.optJSONObject("al") ?: o.optJSONObject("album")
            return Track(
                id = o.optLong("id"),
                name = o.optString("name"),
                artists = artists,
                album = Album(al?.optLong("id") ?: 0, al?.optString("name").orEmpty(), al?.optStringOrNull("picUrl")),
                durationMs = if (o.has("dt")) o.optLong("dt") else o.optLong("duration"),
                fee = o.optInt("fee"),
                noCopyright = o.has("noCopyrightRcmd") && !o.isNull("noCopyrightRcmd"),
                privilege = privilege ?: o.optJSONObject("privilege")?.let(::parsePrivilege),
            )
        }

        fun parsePrivilege(o: JSONObject) = Privilege(
            id = o.optLong("id"),
            fee = o.optIntOrNull("fee"),
            pl = o.optIntOrNull("pl"),
            st = o.optIntOrNull("st"),
            cs = if (o.has("cs")) o.optBoolean("cs") else null,
        )

        /** 解析歌曲数组，并按 id 挂上平行的 privileges。 */
        fun parseList(songs: JSONArray?, privileges: JSONArray?): List<Track> {
            val byId = privileges.objects().associateBy({ it.optLong("id") }, ::parsePrivilege)
            return songs.objects().map { parse(it, byId[it.optLong("id")]) }
        }
    }
}

data class Playlist(
    val id: Long,
    val name: String,
    val coverUrl: String?,
    val trackCount: Int,
    val playCount: Long,
    val creatorId: Long,
    val creatorName: String,
    val specialType: Int,
    val subtitle: String? = null,
) {
    /** 自动创建的"我喜欢的音乐"歌单。 */
    val isLikedSongs: Boolean get() = specialType == 5

    companion object {
        fun parse(o: JSONObject): Playlist {
            val creator = o.optJSONObject("creator")
            return Playlist(
                id = o.optLong("id"),
                name = o.optString("name"),
                coverUrl = o.optStringOrNull("picUrl") ?: o.optStringOrNull("coverImgUrl"),
                trackCount = o.optInt("trackCount"),
                playCount = if (o.has("playCount")) o.optDouble("playCount").toLong() else o.optLong("playcount"),
                creatorId = creator?.optLong("userId") ?: 0,
                creatorName = creator?.optString("nickname").orEmpty(),
                specialType = o.optInt("specialType"),
                subtitle = o.optStringOrNull("copywriter") ?: o.optStringOrNull("updateFrequency"),
            )
        }
    }
}

data class Profile(val userId: Long, val nickname: String, val avatarUrl: String?, val vipType: Int)

data class LyricLine(val timeMs: Long, val text: String, val translation: String?)

// ---------- JSON helpers ----------

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

/** 网易云图片 CDN 缩放：`<url>?param=WyH`，并升级为 https。 */
fun String?.sized(size: Int): String? {
    if (this.isNullOrEmpty()) return null
    val s = replace("http://", "https://")
    return s + (if ("?" in s) "&" else "?") + "param=${size}y$size"
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

fun formatCount(count: Long): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
}
