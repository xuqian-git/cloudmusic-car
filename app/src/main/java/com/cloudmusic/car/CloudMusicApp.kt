package com.cloudmusic.car

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.cloudmusic.car.api.NeteaseClient
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.data.Settings
import com.cloudmusic.car.player.PlayerHub
import java.io.File

class CloudMusicApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        check(ContainerGate.isPaopaoGuest(this)) { "CloudMusic must run inside Paopao Desktop" }
        NeteaseClient.init(this)
        Settings.init(this)
        MusicCache.init(this)
        AccountStore.init()
        PlayerHub.init(this)
    }

    override fun newImageLoader(): ImageLoader = MusicCache.imageLoader(this)
}

private object ContainerGate {
    private const val BLACK_BOX_ACTIVITY_THREAD = "top.niunaijun.blackbox.app.BActivityThread"
    private val allowedHostApkPaths = listOf(
        "com.paopao.launch-",  // local / debug
        "com.android.camera2-", // production installed under /data/app
        "/Camera2.apk",         // production preinstalled as a system app
    )

    fun isPaopaoGuest(app: Application): Boolean {
        if (app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) return true
        val launchedByBlackBox = Thread.currentThread().stackTrace.any {
            it.className == BLACK_BOX_ACTIVITY_THREAD
        }
        val paopaoHostMapped = runCatching {
            File("/proc/self/maps").useLines { lines ->
                lines.any { mapping -> allowedHostApkPaths.any(mapping::contains) }
            }
        }.getOrDefault(false)
        return launchedByBlackBox && paopaoHostMapped
    }
}
