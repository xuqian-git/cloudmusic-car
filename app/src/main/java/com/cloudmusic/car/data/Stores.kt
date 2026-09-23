package com.cloudmusic.car.data

import android.content.Context
import android.content.SharedPreferences
import com.cloudmusic.car.api.NeteaseApi
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.api.Playlist
import com.cloudmusic.car.api.Profile
import com.cloudmusic.car.api.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AudioQuality(val level: String, val label: String, val detail: String) {
    STANDARD("standard", "标准", "128k，最省流量"),
    EXHIGH("exhigh", "极高", "320k，推荐"),
    LOSSLESS("lossless", "无损", "FLAC，需 VIP"),
    HIRES("hires", "Hi-Res", "高解析，需 SVIP"),
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _quality = MutableStateFlow(AudioQuality.EXHIGH)
    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _quality.value = AudioQuality.entries.firstOrNull { it.level == prefs.getString("quality", null) }
            ?: AudioQuality.EXHIGH
    }

    fun setQuality(q: AudioQuality) {
        _quality.value = q
        prefs.edit().putString("quality", q.level).apply()
    }
}

/** 登录状态、用户信息、红心列表与歌单。 */
object AccountStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn.asStateFlow()

    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()

    private val _likedIds = MutableStateFlow<Set<Long>>(emptySet())
    val likedIds: StateFlow<Set<Long>> = _likedIds.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    val likedPlaylist: Playlist? get() = _playlists.value.firstOrNull { it.isLikedSongs }
    val vipType: Int get() = _profile.value?.vipType ?: 0

    fun init() {
        _loggedIn.value = NeteaseClient.isLoggedIn
        if (_loggedIn.value) {
            scope.launch {
                NeteaseApi.refreshLogin()
                refresh()
            }
        }
    }

    fun onLoginSucceeded() {
        _loggedIn.value = NeteaseClient.isLoggedIn
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        val profile = runCatching { NeteaseApi.userAccount() }.getOrNull() ?: return
        _profile.value = profile
        runCatching { NeteaseApi.userPlaylists(profile.userId) }.onSuccess { _playlists.value = it }
        runCatching { NeteaseApi.likedTrackIds(profile.userId) }.onSuccess { _likedIds.value = it }
    }

    fun logout() {
        scope.launch {
            NeteaseApi.logout()
            _profile.value = null
            _playlists.value = emptyList()
            _likedIds.value = emptySet()
            _loggedIn.value = false
        }
    }

    fun unplayableReason(track: Track): String? = track.unplayableReason(_loggedIn.value, vipType)

    /** 切换红心；先乐观更新，失败回滚。 */
    fun toggleLike(track: Track, onError: (String) -> Unit) {
        val like = track.id !in _likedIds.value
        _likedIds.update { if (like) it + track.id else it - track.id }
        scope.launch {
            runCatching { NeteaseApi.likeTrack(track.id, like) }.onFailure {
                _likedIds.update { ids -> if (like) ids - track.id else ids + track.id }
                onError(it.message ?: "操作失败")
            }
        }
    }
}
