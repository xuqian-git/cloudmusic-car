package com.kugoumusic.car.data

import android.content.Context
import android.content.SharedPreferences
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.KuGouMusicClient
import com.kugoumusic.car.api.Playlist
import com.kugoumusic.car.api.Profile
import com.kugoumusic.car.api.Track
import com.kugoumusic.car.player.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AudioQuality(val level: String, val label: String, val detail: String) {
    STANDARD("128", "标准", "MP3 128k，最省流量"),
    EXHIGH("320", "HQ", "MP3 320k，推荐"),
    LOSSLESS("flac", "无损", "FLAC，视歌曲与权益"),
    MASTER("viper_clear", "蝰蛇超清", "酷狗超清音质，视歌曲与权益"),
}

object Settings {
    private lateinit var prefs: SharedPreferences
    private val _quality = MutableStateFlow(AudioQuality.EXHIGH)
    val quality: StateFlow<AudioQuality> = _quality.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("kugoumusic_settings", Context.MODE_PRIVATE)
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
        _loggedIn.value = KuGouMusicClient.isLoggedIn
        KuGouMusicClient.onSessionExpired = {
            scope.launch {
                clearAccount()
                PlayerHub.toast("酷狗登录已过期，请重新扫码登录")
            }
        }
        if (_loggedIn.value) {
            scope.launch {
                KuGouMusicClient.refreshIfDue()
                refresh()
            }
        }
        // 进程常驻多日：每 10 分钟看一眼是否到期（休眠时 delay 不走，所以取地址前还会再查一次）
        scope.launch {
            while (true) {
                delay(10 * 60 * 1000L)
                KuGouMusicClient.refreshIfDue()
            }
        }
    }

    fun onLoginSucceeded() {
        KuGouMusicClient.markLoginFresh()
        _loggedIn.value = KuGouMusicClient.isLoggedIn
        scope.launch { refresh() }
    }

    private fun clearAccount() {
        _profile.value = null
        _playlists.value = emptyList()
        _likedIds.value = emptySet()
        _loggedIn.value = false
    }

    suspend fun refresh() {
        val profile = runCatching { KuGouMusicApi.userAccount() }.getOrNull() ?: return
        _profile.value = profile
        runCatching { KuGouMusicApi.userPlaylists(profile.userId) }.onSuccess { _playlists.value = it }
        runCatching { KuGouMusicApi.likedTrackIds(profile.userId) }.onSuccess { _likedIds.value = it }
    }

    fun logout() {
        scope.launch {
            KuGouMusicApi.logout()
            clearAccount()
        }
    }

    fun unplayableReason(track: Track): String? = track.unplayableReason(_loggedIn.value, vipType)

    /** 切换红心；先乐观更新，失败回滚。 */
    fun toggleLike(track: Track, onError: (String) -> Unit) {
        val like = track.id !in _likedIds.value
        _likedIds.update { if (like) it + track.id else it - track.id }
        scope.launch {
            runCatching { KuGouMusicApi.likeTrack(track.id, like) }.onFailure {
                _likedIds.update { ids -> if (like) ids - track.id else ids + track.id }
                onError(it.message ?: "操作失败")
            }
        }
    }
}
