package com.kugoumusic.car.plugin

import android.content.Context
import android.os.Bundle
import com.kugoumusic.car.api.KuGouLoginApi
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.data.Settings
import com.kugoumusic.car.player.PlayerHub
import com.paopao.music.link.EngineLink
import java.io.File
import java.util.function.BiConsumer

/**
 * 跑在桌面 :music 子进程里的「大脑」（`plugin.json` 的 `engineClass`，HOST_API 3 起）：播放器、账号、
 * 扫码登录、接口、缓存都在这里，界面那边的 [KuGouMusicPlugin] 只收镜像、发命令。
 *
 * 桌面按方法名反射调用 [start]、[call]、[close]，改签名要同步改桌面的 MusicEngineService。
 */
class KuGouMusicEngine {
    fun start(context: Context, directory: File, events: BiConsumer<String, Bundle>) {
        EngineLink.EngineSide.attach(context, events)
        // Runtime 起来时 KuGouMusicClient.init 已读好凭证、dfid；之后界面来的接口调用、扫码都在这边跑。
        KuGouMusicPlugin.Runtime.ensureStarted(context.applicationContext)
        EngineLink.EngineSide.command(KuGouMusicApi.NET_COMMAND, KuGouMusicApi::serveRemote)
        KuGouLoginApi.serveToUi()
        PlayerHub.serveToUi()
        AccountStore.serveToUi()
        Settings.serveToUi()
        MusicCache.serveToUi()
    }

    fun call(method: String, args: Bundle): Bundle? = EngineLink.EngineSide.dispatch(method, args)

    fun close() = KuGouMusicPlugin.Runtime.close()
}
