package com.kugoumusic.car.player

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.kugoumusic.car.MainActivity
import com.kugoumusic.car.api.LyricsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** 媒体会话服务：提供系统通知栏 / 锁屏 / 车机媒体中心的播放控制。 */
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, PlayerHub.player)
            .setSessionActivity(openApp)
            .build()
        scope.launch {
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
        scope.launch {
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
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = PlayerHub.player
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        // 播放器是进程级单例，这里只释放会话。
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val METADATA_KEY_LYRIC = "android.media.metadata.LYRIC"
        const val STATUS_TEXT = "com.paopao.music.STATUS_TEXT"
        const val STATUS_KIND = "com.paopao.music.STATUS_KIND"
    }
}
