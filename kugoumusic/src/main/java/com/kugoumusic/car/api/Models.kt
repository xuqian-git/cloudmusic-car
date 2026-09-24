package com.kugoumusic.car.api

import com.paopao.music.nowplaying.LyricWord
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

data class Artist(val id: Long, val name: String)
data class Album(val id: Long, val name: String, val picUrl: String?)
data class Privilege(val id: Long, val fee: Int? = null, val pl: Int? = null, val st: Int? = null, val cs: Boolean? = null)

data class Track(
    val id: Long,
    /** 酷狗标准音质 hash。 */
    val mid: String,
    /** album_audio_id；酷狗大多数详情、歌词和云盘接口都需要它。 */
    val mediaMid: String,
    /** 1 表示用户云盘文件，0 表示普通曲库歌曲。 */
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
    val isPlayable: Boolean get() = hasPlayableFile && mid.isNotBlank()

    fun unplayableReason(loggedIn: Boolean, vipType: Int): String? = when {
        !isPlayable -> "暂无可播放音源"
        !loggedIn -> "登录后播放"
        else -> null
    }

    companion object {
        fun parse(o: JSONObject, cloud: Boolean = false): Track {
            val trans = o.objectAny("trans_param", "transParam")
            val hash = o.stringAny("hash", "FileHash", "filehash", "hash_128")
                .ifBlank { trans?.stringAny("ogg_128_hash").orEmpty() }
            val audioId = o.longAny("album_audio_id", "mixsongid", "MixSongID", "audio_id", "songid", "id")
            val fullName = o.stringAny("songname", "SongName", "song_name", "name", "audio_name", "filename")
                .stripHighlight()
            val singer = o.stringAny("author_name", "SingerName", "singername", "singer_name", "author")
                .stripHighlight()
                .ifBlank { fullName.substringBefore(" - ", "") }
            val name = o.stringAny("songname", "SongName", "song_name", "audio_name")
                .stripHighlight()
                .ifBlank { fullName.substringAfter(" - ", fullName) }
                .removeSuffixIgnoreCase(".mp3")
            val albumName = o.stringAny("album_name", "AlbumName", "albumname")
            val albumId = o.longAny("album_id", "AlbumID", "albumid")
            val cover = o.stringAny("sizable_cover", "Image", "image", "img", "imgurl", "cover", "cover_url")
                .ifBlank { trans?.stringAny("union_cover").orEmpty() }
                .takeIf(String::isNotBlank)
            val duration = o.longAny("duration", "Duration", "timelen", "time_length", "timelength", "time")
            val id = audioId.takeIf { it > 0 } ?: stableId(hash.ifBlank { "$fullName|$albumName" })
            return Track(
                id = id,
                mid = hash,
                mediaMid = audioId.takeIf { it > 0 }?.toString().orEmpty(),
                songType = if (cloud) 1 else 0,
                name = name.ifBlank { "未知歌曲" },
                artists = singer.split("、", "/", "&").map(String::trim).filter(String::isNotEmpty)
                    .map { Artist(stableId(it), it) }.ifEmpty { listOf(Artist(0, "未知歌手")) },
                album = Album(albumId, albumName, cover),
                durationMs = if (duration in 1..100_000) duration * 1000 else duration,
                hasPlayableFile = hash.isNotBlank(),
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
}

data class Profile(val userId: Long, val nickname: String, val avatarUrl: String?, val vipType: Int)
data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String?,
    /** 逐字片段；源没有逐字数据时为空。 */
    val words: List<LyricWord> = emptyList(),
)

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

fun JSONObject.stringAny(vararg keys: String): String = keys.firstNotNullOfOrNull { key -> optStringOrNull(key) } ?: ""

fun JSONObject.longAny(vararg keys: String): Long = keys.firstNotNullOfOrNull { key ->
    if (!has(key) || isNull(key)) null else optLong(key).takeIf { it != 0L }
        ?: optString(key).toLongOrNull()?.takeIf { it != 0L }
} ?: 0L

fun JSONObject.objectAny(vararg keys: String): JSONObject? = keys.firstNotNullOfOrNull(::optJSONObject)
fun JSONObject.arrayAny(vararg keys: String): JSONArray? = keys.firstNotNullOfOrNull(::optJSONArray)

fun String?.sized(size: Int): String? {
    if (this.isNullOrBlank()) return null
    return replace("http://", "https://").replace("{size}", size.toString())
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

internal fun stableId(value: String): Long {
    val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray())
    return BigInteger(1, bytes.copyOfRange(0, 8)).and(BigInteger.valueOf(Long.MAX_VALUE)).toLong().coerceAtLeast(1)
}

private fun String.stripHighlight(): String = replace(Regex("</?em>"), "").replace("&amp;", "&")

private fun String.removeSuffixIgnoreCase(suffix: String): String =
    if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
