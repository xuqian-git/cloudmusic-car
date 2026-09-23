package com.cloudmusic.car.plugin

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.media3.session.MediaSession
import com.cloudmusic.car.api.LyricsParser
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.data.Settings
import com.cloudmusic.car.player.PlayerHub
import com.cloudmusic.car.ui.AppRoot
import com.cloudmusic.car.ui.theme.CloudMusicTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Entry loaded by Paopao Desktop from a signed .ppmusic pack. */
class CloudMusicPlugin {
    fun createView(context: Context): View {
        Runtime.ensureStarted(context.applicationContext)
        return ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                CloudMusicTheme { AppRoot(embedded = true) }
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

        @Synchronized
        fun ensureStarted(context: Context) {
            if (started) return
            NeteaseClient.init(context)
            Settings.init(context)
            MusicCache.init(context)
            AccountStore.init()
            PlayerHub.init(context)
            session = MediaSession.Builder(context, PlayerHub.player).build()
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
    }
}
