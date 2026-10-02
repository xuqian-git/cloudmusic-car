package com.cloudmusic.car.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import com.cloudmusic.car.link.Codecs
import com.paopao.music.link.EngineLink
import com.cloudmusic.car.api.NeteaseApi
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.api.Playlist
import com.cloudmusic.car.api.Profile
import com.cloudmusic.car.api.Track
import com.cloudmusic.car.player.PlayerHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
        if (_quality.value == q) return
        _quality.value = q
        if (EngineLink.isUi) {
            EngineLink.UiSide.fire(SET_QUALITY, Bundle().apply { putString("level", q.level) })
            return
        }
        prefs.edit().putString("quality", q.level).apply()
        PlayerHub.onQualityChanged()
    }

    private const val MIRROR_QUALITY = "settings.quality"
    private const val SET_QUALITY = "settings.setQuality"

    /** 界面进程：音质由引擎持有，这里只收镜像。 */
    fun mirrorFromEngine() {
        EngineLink.UiSide.mirror(MIRROR_QUALITY, _quality) { level ->
            AudioQuality.entries.firstOrNull { it.level == level } ?: AudioQuality.EXHIGH
        }
    }

    fun serveToUi() {
        EngineLink.EngineSide.mirror(MIRROR_QUALITY, quality) { it.level }
        EngineLink.EngineSide.command(SET_QUALITY) { args ->
            val level = args.getString("level")
            AudioQuality.entries.firstOrNull { it.level == level }?.let { q -> EngineLink.EngineSide.onMain { setQuality(q) } }
            null
        }
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
        NeteaseClient.onSessionExpired = {
            scope.launch {
                clearAccount()
                PlayerHub.toast("网易云登录已过期，请重新扫码登录")
            }
        }
        if (_loggedIn.value) {
            scope.launch {
                NeteaseClient.refreshIfDue()
                refresh()
            }
        }
        // 进程常驻多日：每 10 分钟看一眼是否满 12 小时（休眠时 delay 不走，所以取地址前还会再查一次）
        scope.launch {
            while (true) {
                delay(10 * 60 * 1000L)
                NeteaseClient.refreshIfDue()
            }
        }
    }

    fun onLoginSucceeded() {
        if (EngineLink.isUi) {
            EngineLink.UiSide.fire(LOGIN_SUCCEEDED)
            return
        }
        NeteaseClient.markLoginFresh()
        _loggedIn.value = NeteaseClient.isLoggedIn
        scope.launch { refresh() }
    }

    private fun clearAccount() {
        _profile.value = null
        _playlists.value = emptyList()
        _likedIds.value = emptySet()
        _loggedIn.value = false
    }

    suspend fun refresh() {
        val profile = runCatching { NeteaseApi.userAccount() }.getOrNull() ?: return
        _profile.value = profile
        runCatching { NeteaseApi.userPlaylists(profile.userId) }.onSuccess { _playlists.value = it }
        runCatching { NeteaseApi.likedTrackIds(profile.userId) }.onSuccess { _likedIds.value = it }
    }

    fun logout() {
        if (EngineLink.isUi) {
            EngineLink.UiSide.fire(LOGOUT)
            return
        }
        scope.launch {
            NeteaseApi.logout()
            clearAccount()
        }
    }

    fun unplayableReason(track: Track): String? = track.unplayableReason(_loggedIn.value, vipType)

    /** 切换红心；先乐观更新，失败回滚。 */
    fun toggleLike(track: Track, onError: (String) -> Unit) {
        val like = track.id !in _likedIds.value
        _likedIds.update { if (like) it + track.id else it - track.id }
        scope.launch {
            runCatching {
                if (EngineLink.isUi) {
                    EngineLink.UiSide.callAsync(SET_LIKE, Bundle().apply {
                        putLong("id", track.id)
                        putBoolean("like", like)
                    })
                } else {
                    NeteaseApi.likeTrack(track.id, like)
                }
            }.onFailure {
                _likedIds.update { ids -> if (like) ids - track.id else ids + track.id }
                onError(it.message ?: "操作失败")
            }
        }
    }

    private const val LOGIN_SUCCEEDED = "account.loginSucceeded"
    private const val LOGOUT = "account.logout"
    private const val SET_LIKE = "account.setLike"

    /** 界面进程：登录态、资料、红心、歌单都由引擎持有，这里只收镜像。 */
    fun mirrorFromEngine() {
        EngineLink.UiSide.mirror("account.loggedIn", _loggedIn) { it.toBoolean() }
        EngineLink.UiSide.mirror("account.profile", _profile, Codecs::profile)
        EngineLink.UiSide.mirror("account.likedIds", _likedIds, Codecs::ids)
        EngineLink.UiSide.mirror("account.playlists", _playlists, Codecs::playlists)
    }

    fun serveToUi() {
        EngineLink.EngineSide.mirror("account.loggedIn", loggedIn) { it.toString() }
        EngineLink.EngineSide.mirror("account.profile", profile, Codecs::profile)
        EngineLink.EngineSide.mirror("account.likedIds", likedIds, Codecs::ids)
        EngineLink.EngineSide.mirror("account.playlists", playlists, Codecs::playlists)
        EngineLink.EngineSide.command(LOGIN_SUCCEEDED) { EngineLink.EngineSide.onMain(::onLoginSucceeded); null }
        EngineLink.EngineSide.command(LOGOUT) { EngineLink.EngineSide.onMain(::logout); null }
        EngineLink.EngineSide.command(SET_LIKE) { args ->
            val id = args.getLong("id")
            val like = args.getBoolean("like")
            _likedIds.update { if (like) it + id else it - id }
            runBlocking {
                runCatching { NeteaseApi.likeTrack(id, like) }.onFailure {
                    _likedIds.update { ids -> if (like) ids - id else ids + id }
                    throw it
                }
            }
            null
        }
    }
}
