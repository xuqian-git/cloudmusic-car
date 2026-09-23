package com.qqmusic.car.api

import org.json.JSONArray
import org.json.JSONObject

data class Artist(val id: Long, val name: String)
data class Album(val id: Long, val name: String, val picUrl: String?)
data class Privilege(val id: Long, val fee: Int? = null, val pl: Int? = null, val st: Int? = null, val cs: Boolean? = null)

data class Track(
    val id: Long,
    val mid: String,
    val mediaMid: String,
    val songType: Int,
    val name: String,
    val artists: List<Artist>,
    val album: Album,
    val durationMs: Long,
    val privilege: Privilege? = null,
    val hasPlayableFile: Boolean = true,
) {
    val artistNames: String get() = artists.joinToString(" / ") { it.name }
    val coverUrl: String? get() = album.picUrl
    val isPlayable: Boolean get() = hasPlayableFile
    fun unplayableReason(loggedIn: Boolean, vipType: Int): String? = when {
        !isPlayable -> "暂无可播放音源"
        !loggedIn -> "登录后播放"
        else -> null
    }

    companion object {
        fun parse(o: JSONObject): Track {
            val albumJson = o.optJSONObject("album") ?: JSONObject()
            val albumMid = albumJson.optString("mid").ifEmpty { o.optString("albummid") }
            val file = o.optJSONObject("file")
            val mediaMid = file?.optString("media_mid").orEmpty().ifEmpty {
                o.optString("media_mid").ifEmpty { o.optString("strMediaMid").ifEmpty { o.optString("mid") } }
            }
            val hasFile = file?.let {
                it.optLong("size_128mp3") > 0 || it.optLong("size_96aac") > 0 || it.optLong("size_48aac") > 0
            } ?: (o.optInt("stream", 1) != 0)
            return Track(
                id = o.optLong("id").takeIf { it != 0L } ?: o.optLong("songid"),
                mid = o.optString("mid").ifEmpty { o.optString("songmid") },
                mediaMid = mediaMid,
                songType = o.optInt("type", o.optInt("songtype")),
                name = o.optString("title").ifEmpty { o.optString("name").ifEmpty { o.optString("songname") } },
                artists = (o.optJSONArray("singer") ?: o.optJSONArray("singers")).objects().map {
                    Artist(it.optLong("id"), it.optString("name").stripHighlight())
                },
                album = Album(
                    albumJson.optLong("id").takeIf { it != 0L } ?: o.optLong("albumid"),
                    albumJson.optString("title").ifEmpty { albumJson.optString("name").ifEmpty { o.optString("albumname") } },
                    albumCover(albumMid),
                ),
                durationMs = 1_000L * o.optLong("interval"),
                hasPlayableFile = hasFile,
            )
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
    val isLikedSongs: Boolean get() = specialType == 5

    companion object {
        fun parse(o: JSONObject): Playlist {
            val creator = o.optJSONObject("creator")
            val cover = o.optJSONObject("cover")
            return Playlist(
                id = o.optLong("tid").takeIf { it != 0L } ?: o.optLong("id").takeIf { it != 0L } ?: o.optLong("dissid"),
                name = o.optString("title").ifEmpty { o.optString("dissname").ifEmpty { o.optString("name") } },
                coverUrl = cover?.optString("default_url")?.takeIf(String::isNotBlank)
                    ?: o.optStringOrNull("picurl") ?: o.optStringOrNull("imgurl"),
                trackCount = o.optInt("song_cnt", o.optInt("songnum", o.optInt("song_count"))),
                playCount = o.optLong("play_cnt").takeIf { it != 0L } ?: o.optLong("listennum"),
                creatorId = creator?.optLong("uin") ?: o.optLong("host_uin"),
                creatorName = creator?.optString("nick").orEmpty().ifEmpty { o.optString("nickname") },
                specialType = o.optInt("specialType"),
                subtitle = o.optStringOrNull("desc") ?: o.optStringOrNull("subtitle"),
            )
        }
    }
}

data class Profile(val userId: Long, val nickname: String, val avatarUrl: String?, val vipType: Int)
data class LyricLine(val timeMs: Long, val text: String, val translation: String?)

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

fun String?.sized(size: Int): String? {
    if (this.isNullOrEmpty()) return null
    val secure = replace("http://", "https://")
    return when {
        "imageView2" in secure -> secure.replace(Regex("/w/\\d+/h/\\d+"), "/w/$size/h/$size")
        Regex("T\\d+R\\d+x\\d+M").containsMatchIn(secure) -> {
            // QQ's artwork CDN only serves a small set of fixed dimensions.
            val cdnSize = when {
                size <= 150 -> 150
                size <= 300 -> 300
                else -> 500
            }
            secure.replace(Regex("(T\\d+)R\\d+x\\d+(M)"), "$1R${cdnSize}x$cdnSize$2")
        }
        else -> secure
    }
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

private fun albumCover(mid: String): String? = mid.takeIf(String::isNotBlank)?.let {
    "https://y.gtimg.cn/music/photo_new/T002R500x500M000${it}.jpg"
}

private fun String.stripHighlight(): String = replace(Regex("</?em>"), "")
