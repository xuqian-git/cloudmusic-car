package com.cloudmusic.car.plugin

import android.content.Context
import android.os.Bundle
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.data.Settings
import com.cloudmusic.car.player.PlayerHub
import com.paopao.music.link.EngineLink
import java.io.File
import java.util.function.BiConsumer

/**
 * 跑在桌面 :music 子进程里的「大脑」（`plugin.json` 的 `engineClass`，HOST_API 3 起）：播放器、账号、
 * 接口、缓存都在这里，界面那边的 [CloudMusicPlugin] 只收镜像、发命令。
 *
 * 桌面按方法名反射调用 [start]、[call]、[close]，改签名要同步改桌面的 MusicEngineService。
 */
class CloudMusicEngine {
    fun start(context: Context, directory: File, events: BiConsumer<String, Bundle>) {
        EngineLink.EngineSide.attach(context, events)
        // 先登记网络转发：界面一连上就可能来请求，Runtime 起来之前 Cookie 已由 NeteaseClient.init 读好。
        CloudMusicPlugin.Runtime.ensureStarted(context.applicationContext)
        EngineLink.EngineSide.command(NeteaseClient.NET_COMMAND, NeteaseClient::serveRemote)
        PlayerHub.serveToUi()
        AccountStore.serveToUi()
        Settings.serveToUi()
        MusicCache.serveToUi()
    }

    fun call(method: String, args: Bundle): Bundle? = EngineLink.EngineSide.dispatch(method, args)

    fun close() = CloudMusicPlugin.Runtime.close()
}
