package com.kugoumusic.car.api

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal object KuGouRecentStore {
    private const val PREFS = "kugoumusic_recent"
    private const val KEY = "tracks"
    private lateinit var context: Context

    fun init(context: Context) {
        this.context = context.applicationContext
    }

    fun record(track: Track) {
        if (!::context.isInitialized) return
        val updated = (listOf(track) + load()).distinctBy(Track::id).take(100)
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }

    fun load(): List<Track> {
        if (!::context.isInitialized) return emptyList()
        return runCatching {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
            JSONArray(raw).objects().map { it.toTrack() }
        }.getOrDefault(emptyList())
    }

    private fun Track.toJson() = JSONObject()
        .put("id", id).put("mid", mid).put("mediaMid", mediaMid).put("songType", songType)
        .put("name", name).put("durationMs", durationMs).put("hasPlayableFile", hasPlayableFile)
        .put("artists", JSONArray().apply {
            artists.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        })
        .put("album", JSONObject().put("id", album.id).put("name", album.name).put("picUrl", album.picUrl ?: JSONObject.NULL))

    private fun JSONObject.toTrack(): Track {
        val album = getJSONObject("album")
        return Track(
            id = getLong("id"), mid = optString("mid"), mediaMid = optString("mediaMid"), songType = optInt("songType"),
            name = getString("name"),
            artists = getJSONArray("artists").objects().map { Artist(it.optLong("id"), it.optString("name")) },
            album = Album(album.optLong("id"), album.optString("name"), album.optString("picUrl").takeIf { !album.isNull("picUrl") }),
            durationMs = optLong("durationMs"), hasPlayableFile = optBoolean("hasPlayableFile", true),
        )
    }
}
