package com.qqmusic.car.plugin

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.session.MediaSession
import com.qqmusic.car.api.LyricsParser
import com.qqmusic.car.api.QQMusicClient
import com.qqmusic.car.data.AccountStore
import com.qqmusic.car.data.MusicCache
import com.qqmusic.car.data.Settings
import com.qqmusic.car.player.PlayerHub
import com.qqmusic.car.ui.AppRoot
import com.qqmusic.car.ui.theme.QQMusicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Entry loaded by Paopao Desktop from a signed .ppmusic pack. */
class QQMusicPlugin {
    fun close() = Runtime.close()

    fun createView(context: Context): View {
        Runtime.ensureStarted(context.applicationContext)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                QQMusicTheme { AppRoot(embedded = true) }
            }
        }
    }

    fun canGoBack(): Boolean = false

    fun back() = Unit

    fun disposeView(view: View) {
        (view as? ComposeView)?.disposeComposition()
    }

    private object Runtime {
        private const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"
        private const val STATUS_TEXT = "com.paopao.music.STATUS_TEXT"
        private const val STATUS_KIND = "com.paopao.music.STATUS_KIND"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var started = false
        private var session: MediaSession? = null
        private var bridgeHandle: Long? = null
        private var lyricsBridgeJob: Job? = null
        private var statusBridgeJob: Job? = null

        @Synchronized
        fun ensureStarted(context: Context) {
            if (started) return
            QQMusicClient.init(context)
            Settings.init(context)
            MusicCache.init(context)
            AccountStore.init()
            PlayerHub.init(context)
            session = MediaSession.Builder(context, PlayerHub.player)
                .setId("paopao.qqmusic")
                .build()
                .also { mediaSession ->
                bridgeHandle = HostMediaBridge.register(
                    "com.carhome.music.plugin.qqmusic",
                    mediaSession.platformToken,
                )
            }
            lyricsBridgeJob = scope.launch {
                PlayerHub.lyrics.collectLatest { lines ->
                    val mediaSession = session ?: return@collectLatest
                    val extras = Bundle(mediaSession.sessionExtras)
                    extras.remove(METADATA_KEY_LYRIC)
                    LyricsParser.toLrc(lines).takeIf(String::isNotEmpty)?.let {
                        extras.putString(METADATA_KEY_LYRIC, it)
                    }
                    mediaSession.setSessionExtras(extras)
                }
            }
            statusBridgeJob = scope.launch {
                PlayerHub.status.collectLatest { notice ->
                    val mediaSession = session ?: return@collectLatest
                    val extras = Bundle(mediaSession.sessionExtras)
                    extras.remove(STATUS_TEXT)
                    extras.remove(STATUS_KIND)
                    notice?.let {
                        extras.putString(STATUS_TEXT, it.text)
                        extras.putString(STATUS_KIND, it.kind)
                    }
                    mediaSession.setSessionExtras(extras)
                }
            }
            started = true
        }

        @Synchronized
        fun close() {
            if (!started) return
            lyricsBridgeJob?.cancel()
            lyricsBridgeJob = null
            statusBridgeJob?.cancel()
            statusBridgeJob = null
            bridgeHandle?.let(HostMediaBridge::unregister)
            bridgeHandle = null
            runCatching { session?.release() }
            session = null
            runCatching(PlayerHub::release)
            MusicCache.close()
            started = false
        }
    }
}

private object HostMediaBridge {
    private const val BRIDGE_CLASS = "com.carhome.musicplugin.MusicPluginMediaBridge"

    fun register(packageName: String, token: android.media.session.MediaSession.Token): Long? = runCatching {
        Class.forName(BRIDGE_CLASS)
            .getMethod("register", String::class.java, android.media.session.MediaSession.Token::class.java)
            .invoke(null, packageName, token) as Long
    }.getOrNull()

    fun unregister(handle: Long) {
        runCatching {
            Class.forName(BRIDGE_CLASS)
                .getMethod("unregister", java.lang.Long.TYPE)
                .invoke(null, handle)
        }
    }
}
