package com.kugoumusic.car.player

import android.content.Context
import com.kugoumusic.car.api.Album
import com.kugoumusic.car.api.Artist
import com.kugoumusic.car.api.Track
import org.json.JSONArray
import org.json.JSONObject

internal data class PlaybackSnapshot(
    val tracks: List<Track>,
    val currentIndex: Int,
    val positionMs: Long,
    val sourceName: String?,
    val sourceId: Long,
    val mode: PlayMode,
    val shuffle: Boolean,
    val repeatMode: Int,
)

internal object PlaybackSnapshotStore {
    private const val PREFS = "kugoumusic_playback_snapshot"
    private const val KEY_QUEUE = "queue"
    private const val KEY_POSITION = "position_ms"
    private const val KEY_POSITION_MEDIA_ID = "position_media_id"

    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun saveQueue(
        tracks: List<Track>,
        currentIndex: Int,
        sourceName: String?,
        sourceId: Long,
        mode: PlayMode,
        shuffle: Boolean,
        repeatMode: Int,
    ) {
        if (tracks.isEmpty() || currentIndex !in tracks.indices) return
        val root = JSONObject().apply {
            put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
            put("currentIndex", currentIndex)
            put("sourceName", sourceName ?: JSONObject.NULL)
            put("sourceId", sourceId)
            put("mode", mode.name)
            put("shuffle", shuffle)
            put("repeatMode", repeatMode)
        }
        prefs().edit().putString(KEY_QUEUE, root.toString()).apply()
    }

    fun savePosition(mediaId: String?, positionMs: Long) {
        if (mediaId.isNullOrEmpty()) return
        prefs().edit()
            .putString(KEY_POSITION_MEDIA_ID, mediaId)
            .putLong(KEY_POSITION, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun restore(): PlaybackSnapshot? = runCatching {
        val root = JSONObject(prefs().getString(KEY_QUEUE, null) ?: return null)
        val tracksJson = root.getJSONArray("tracks")
        val tracks = (0 until tracksJson.length()).map { tracksJson.getJSONObject(it).toTrack() }
        val currentIndex = root.optInt("currentIndex", 0)
        if (tracks.isEmpty() || currentIndex !in tracks.indices) return null
        val currentId = tracks[currentIndex].id.toString()
        val position = if (prefs().getString(KEY_POSITION_MEDIA_ID, null) == currentId) {
            prefs().getLong(KEY_POSITION, 0L)
        } else 0L
        PlaybackSnapshot(
            tracks = tracks,
            currentIndex = currentIndex,
            positionMs = position.coerceAtMost(tracks[currentIndex].durationMs.coerceAtLeast(0L)),
            sourceName = root.optString("sourceName").takeIf { !root.isNull("sourceName") && it.isNotEmpty() },
            sourceId = root.optLong("sourceId"),
            mode = runCatching { PlayMode.valueOf(root.optString("mode")) }.getOrDefault(PlayMode.NORMAL),
            shuffle = root.optBoolean("shuffle"),
            repeatMode = root.optInt("repeatMode", androidx.media3.common.Player.REPEAT_MODE_ALL),
        )
    }.getOrNull()

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun Track.toJson() = JSONObject().apply {
        put("id", id)
        put("mid", mid)
        put("mediaMid", mediaMid)
        put("songType", songType)
        put("name", name)
        put("artists", JSONArray().apply {
            artists.forEach { artist -> put(JSONObject().put("id", artist.id).put("name", artist.name)) }
        })
        put("album", JSONObject().put("id", album.id).put("name", album.name).put("picUrl", album.picUrl ?: JSONObject.NULL))
        put("durationMs", durationMs)
        put("hasPlayableFile", hasPlayableFile)
    }

    private fun JSONObject.toTrack(): Track {
        val artistsJson = getJSONArray("artists")
        val artists = (0 until artistsJson.length()).map { index ->
            artistsJson.getJSONObject(index).let { Artist(it.getLong("id"), it.getString("name")) }
        }
        val albumJson = getJSONObject("album")
        return Track(
            id = getLong("id"),
            mid = optString("mid"),
            mediaMid = optString("mediaMid"),
            songType = optInt("songType"),
            name = getString("name"),
            artists = artists,
            album = Album(
                albumJson.getLong("id"),
                albumJson.getString("name"),
                albumJson.optString("picUrl").takeIf { !albumJson.isNull("picUrl") && it.isNotEmpty() },
            ),
            durationMs = getLong("durationMs"),
            hasPlayableFile = optBoolean("hasPlayableFile", true),
            privilege = null,
        )
    }
}
