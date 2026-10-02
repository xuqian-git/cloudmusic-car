package com.kugoumusic.car.plugin

import android.content.Context
import android.os.Bundle
import android.view.View
import com.paopao.music.link.EngineLink
import java.io.File
import java.util.function.BiFunction
import java.util.function.Consumer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.session.MediaSession
import com.kugoumusic.car.api.LyricsParser
import com.kugoumusic.car.api.KuGouMusicClient
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.data.Settings
import com.kugoumusic.car.player.PlayerHub
import com.kugoumusic.car.ui.AppRoot
import com.kugoumusic.car.ui.theme.KuGouMusicTheme
import com.paopao.music.nowplaying.KrcSource
import com.paopao.music.nowplaying.METADATA_KEY_KRC_LYRIC
import com.paopao.music.nowplaying.WordLyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Entry loaded by Paopao Desktop from a signed .ppmusic pack. */
class KuGouMusicPlugin {
    private var subscription: Any? = null

    fun close() {
        if (EngineLink.isUi) {
            EngineLink.UiSide.detach(subscription)
            subscription = null
            return
        }
        Runtime.close()
    }

    /** 老桌面（HOST_API 2）：播放器和界面都在桌面主进程里。 */
    fun createView(context: Context): View {
        Runtime.ensureStarted(context.applicationContext)
        return contentView(context)
    }

    /**
     * HOST_API 3：引擎（[KuGouMusicEngine]）已在桌面的 :music 子进程里跑，这里只画界面。
     * [call]、[subscribe] 是桌面给的传输口，见 [EngineLink.UiSide.attach]。
     */
    fun createView(context: Context, directory: File, call: BiFunction<*, *, *>, subscribe: Consumer<*>): View {
        attachUi(context.applicationContext, call, subscribe)
        return contentView(context)
    }

    @Synchronized
    private fun attachUi(context: Context, call: Any, subscribe: Any) {
        if (subscription === subscribe) return
        PlayerHub.mirrorFromEngine()
        AccountStore.mirrorFromEngine()
        Settings.mirrorFromEngine()
        MusicCache.mirrorFromEngine()
        EngineLink.UiSide.attach(context, call, subscribe)
        subscription = subscribe
        MusicCache.init(context)
    }

    private fun contentView(context: Context): View =
        ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                KuGouMusicTheme { AppRoot(embedded = true) }
            }
        }

    fun canGoBack(): Boolean = false

    fun back() = Unit

    fun disposeView(view: View) {
        (view as? ComposeView)?.disposeComposition()
    }

    internal object Runtime {
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
            KuGouMusicClient.init(context)
            Settings.init(context)
            MusicCache.init(context)
            AccountStore.init()
            PlayerHub.init(context)
            session = MediaSession.Builder(context, PlayerHub.player)
                .setId("paopao.kugoumusic")
                .build()
                .also { mediaSession ->
                bridgeHandle = HostMediaBridge.register(
                    "com.carhome.music.plugin.kugoumusic",
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
                    extras.remove(METADATA_KEY_KRC_LYRIC)
                    WordLyricsParser.toKrc(lines.map { KrcSource(it.timeMs, it.text, it.words) })
                        .takeIf(String::isNotEmpty)?.let { extras.putString(METADATA_KEY_KRC_LYRIC, it) }
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
