package com.kugoumusic.car.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

sealed interface Route {
    // 一级页面（侧边栏 / 底部标签）
    data object Home : Route
    data object Toplists : Route
    data object Liked : Route
    data object Recent : Route
    data object Cloud : Route
    data object MyPlaylists : Route
    data object Search : Route
    data object Settings : Route

    // 二级页面
    data object Daily : Route
    data class PlaylistPage(val id: Long, val name: String, val cover: String?) : Route
}

/** 极简导航：一个一级页面 + 二级页面栈。 */
class Nav {
    var root by mutableStateOf<Route>(Route.Home)
        private set
    val stack = mutableStateListOf<Route>()
    var nowPlayingOpen by mutableStateOf(false)

    val current: Route get() = stack.lastOrNull() ?: root

    fun select(route: Route) {
        root = route
        stack.clear()
    }

    fun open(route: Route) {
        stack.add(route)
    }

    /** @return 是否处理了返回。 */
    fun back(): Boolean = when {
        nowPlayingOpen -> {
            nowPlayingOpen = false
            true
        }
        stack.isNotEmpty() -> {
            stack.removeAt(stack.lastIndex)
            true
        }
        root != Route.Home -> {
            root = Route.Home
            true
        }
        else -> false
    }
}
