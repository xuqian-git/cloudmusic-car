package com.qqmusic.car.plugin

import android.content.Context
import android.os.Bundle
import com.qqmusic.car.api.QQLoginApi
import com.qqmusic.car.api.QQMusicApi
import com.qqmusic.car.api.QQMusicClient
import com.qqmusic.car.data.AccountStore
import com.qqmusic.car.data.MusicCache
import com.qqmusic.car.data.Settings
import com.qqmusic.car.player.PlayerHub
import com.paopao.music.link.EngineLink
import java.io.File
import java.util.function.BiConsumer

/**
 * 跑在桌面 :music 子进程里的「大脑」（`plugin.json` 的 `engineClass`，HOST_API 3 起）：播放器、账号（凭证、
 * 安卓身份、扫码登录会话）、接口、缓存都在这里，界面那边的 [QQMusicPlugin] 只收镜像、发命令。
 *
 * 桌面按方法名反射调用 [start]、[call]、[close]，改签名要同步改桌面的 MusicEngineService。
 */
class QQMusicEngine {
    fun start(context: Context, directory: File, events: BiConsumer<String, Bundle>) {
        EngineLink.EngineSide.attach(context, events)
        // 先起 Runtime：凭证由 QQMusicClient.init 读好之后，界面转来的请求才带得上登录态。
        QQMusicPlugin.Runtime.ensureStarted(context.applicationContext)
        EngineLink.EngineSide.command(QQMusicClient.NET_COMMAND, QQMusicClient::serveRemote)
        QQMusicApi.serveToUi()
        QQLoginApi.serveToUi()
        PlayerHub.serveToUi()
        AccountStore.serveToUi()
        Settings.serveToUi()
        MusicCache.serveToUi()
    }

    fun call(method: String, args: Bundle): Bundle? = EngineLink.EngineSide.dispatch(method, args)

    fun close() = QQMusicPlugin.Runtime.close()
}
