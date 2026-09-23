package com.kugoumusic.car.plugin

import android.content.Context
import android.os.Bundle
import android.view.View
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Entry loaded by Paopao Desktop from a signed .ppmusic pack. */
class KuGouMusicPlugin {
    fun close() = Runtime.close()

    fun createView(context: Context): View {
        Runtime.ensureStarted(context.applicationContext)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                KuGouMusicTheme { AppRoot(embedded = true) }
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
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private var started = false
        private var session: MediaSession? = null
        private var bridgeHandle: Long? = null

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
            scope.launch {
                PlayerHub.lyrics.collectLatest { lines ->
                    val extras = Bundle()
                    LyricsParser.toLrc(lines).takeIf(String::isNotEmpty)?.let {
                        extras.putString(METADATA_KEY_LYRIC, it)
                    }
                    session?.setSessionExtras(extras)
                }
            }
            started = true
        }

        @Synchronized
        fun close() {
            if (!started) return
            PlayerHub.player.pause()
            bridgeHandle?.let(HostMediaBridge::unregister)
            bridgeHandle = null
            session?.release()
            session = null
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
