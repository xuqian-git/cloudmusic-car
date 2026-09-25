package com.cloudmusic.car.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.player.PlayerHub
import com.cloudmusic.car.ui.components.Artwork
import com.cloudmusic.car.ui.components.BigIcon
import com.cloudmusic.car.ui.components.Label
import com.cloudmusic.car.ui.components.glass
import com.cloudmusic.car.ui.components.pressable
import com.cloudmusic.car.ui.screens.CloudScreen
import com.cloudmusic.car.ui.screens.DailyScreen
import com.cloudmusic.car.ui.screens.HomeScreen
import com.cloudmusic.car.ui.screens.LikedScreen
import com.cloudmusic.car.ui.screens.LoginScreen
import com.cloudmusic.car.ui.screens.MyPlaylistsScreen
import com.cloudmusic.car.ui.screens.NowPlayingScreen
import com.cloudmusic.car.ui.screens.PlaylistScreen
import com.cloudmusic.car.ui.screens.RecentScreen
import com.cloudmusic.car.ui.screens.SearchScreen
import com.cloudmusic.car.ui.screens.SettingsScreen
import com.cloudmusic.car.ui.screens.ToplistsScreen
import com.cloudmusic.car.ui.theme.K
import com.cloudmusic.car.ui.theme.LocalLandscape
import kotlinx.coroutines.delay

/** 内容区底部需要为迷你播放条（和竖屏标签栏）留出的空间。 */
val LocalBottomInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

@Composable
fun AppRoot(embedded: Boolean = false) {
    val loggedIn by AccountStore.loggedIn.collectAsState()
    val c = K.colors
    Box(Modifier.fillMaxSize().background(c.background)) {
        if (!loggedIn) {
            val modifier = if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)
            Box(modifier) { LoginScreen() }
        } else {
            val nav = remember { Nav() }
            BackHandler(enabled = nav.nowPlayingOpen || nav.stack.isNotEmpty() || nav.root != Route.Home) { nav.back() }
            val modifier = if (embedded) Modifier else Modifier.windowInsetsPadding(WindowInsets.systemBars)
            Box(modifier) {
                if (LocalLandscape.current) LandscapeShell(nav) else PortraitShell(nav)
            }
            AnimatedVisibility(
                visible = nav.nowPlayingOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                NowPlayingScreen(onClose = { nav.nowPlayingOpen = false })
            }
        }
        StatusHost()
        ToastHost()
    }
}

// ---------- 横屏：左侧边栏 ----------

private data class NavItem(val route: Route, val label: String, val icon: ImageVector)

private val mainItems = listOf(
    NavItem(Route.Home, "主页", Icons.Rounded.Home),
    NavItem(Route.Toplists, "排行榜", Icons.Rounded.BarChart),
)
private val libraryItems = listOf(
    NavItem(Route.Liked, "我喜欢的音乐", Icons.Rounded.Favorite),
    NavItem(Route.Recent, "最近播放", Icons.Rounded.Schedule),
    NavItem(Route.Cloud, "音乐云盘", Icons.Rounded.Cloud),
    NavItem(Route.MyPlaylists, "我的歌单", Icons.Rounded.QueueMusic),
)

@Composable
private fun LandscapeShell(nav: Nav) {
    val c = K.colors
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .width(300.dp)
                .fillMaxHeight()
                .background(c.sidebar)
                .padding(horizontal = 18.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // 搜索框
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(if (nav.root == Route.Search) c.accent else c.fill, RoundedCornerShape(14.dp))
                    .pressable { nav.select(Route.Search) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (nav.root == Route.Search) Color.White else c.secondary
                Icon(Icons.Rounded.Search, null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Text("搜索", color = tint, fontSize = 24.sp)
            }
            Spacer(Modifier.height(14.dp))
            mainItems.forEach { SidebarItem(it, nav) }
            Text(
                "资料库",
                color = c.secondary,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 4.dp),
            )
            libraryItems.forEach { SidebarItem(it, nav) }
            Spacer(Modifier.weight(1f))
            SidebarItem(NavItem(Route.Settings, "设置", Icons.Rounded.Settings), nav)
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalBottomInset provides 136.dp) {
                Content(nav)
            }
            MiniPlayer(
                nav,
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 44.dp, end = 44.dp, bottom = 22.dp),
            )
        }
    }
}

@Composable
private fun SidebarItem(item: NavItem, nav: Nav) {
    val c = K.colors
    val selected = nav.root == item.route
    Row(
        Modifier
            .fillMaxWidth()
            .height(66.dp)
            .background(if (selected) c.accent else Color.Transparent, RoundedCornerShape(14.dp))
            .pressable { nav.select(item.route) }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(item.icon, null, tint = if (selected) Color.White else c.accent, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Label(item.label, 25.sp, if (selected) Color.White else c.label, FontWeight.Medium)
    }
}

// ---------- 竖屏：底部标签栏 ----------

private val tabItems = listOf(
    NavItem(Route.Home, "主页", Icons.Rounded.Home),
    NavItem(Route.Toplists, "排行榜", Icons.Rounded.BarChart),
    NavItem(Route.MyPlaylists, "资料库", Icons.Rounded.LibraryMusic),
    NavItem(Route.Search, "搜索", Icons.Rounded.Search),
)

@Composable
private fun PortraitShell(nav: Nav) {
    val c = K.colors
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBottomInset provides 270.dp) {
            Content(nav)
        }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 28.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MiniPlayer(nav, Modifier)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(108.dp)
                    .glass(RoundedCornerShape(40.dp), c.glass, c.glassBorder),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val libraryRoots = setOf(Route.MyPlaylists, Route.Liked, Route.Recent, Route.Cloud, Route.Settings)
                tabItems.forEach { item ->
                    val selected = nav.root == item.route || (item.route == Route.MyPlaylists && nav.root in libraryRoots)
                    val tint = if (selected) c.accent else c.secondary
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .pressable { nav.select(item.route) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(item.icon, null, tint = tint, modifier = Modifier.size(40.dp))
                        Text(item.label, color = tint, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ---------- 页面切换 ----------

@Composable
private fun Content(nav: Nav) {
    AnimatedContent(
        targetState = nav.current,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "page",
        modifier = Modifier.fillMaxSize(),
    ) { route ->
        when (route) {
            Route.Home -> HomeScreen(nav)
            Route.Toplists -> ToplistsScreen(nav)
            Route.Liked -> LikedScreen(nav)
            Route.Recent -> RecentScreen(nav)
            Route.Cloud -> CloudScreen(nav)
            Route.MyPlaylists -> MyPlaylistsScreen(nav)
            Route.Search -> SearchScreen(nav)
            Route.Settings -> SettingsScreen(nav)
            Route.Daily -> DailyScreen(nav)
            is Route.PlaylistPage -> PlaylistScreen(nav, route)
        }
    }
}

/** 统一的内容区内边距。 */
@Composable
fun pagePadding(): PaddingValues {
    val landscape = LocalLandscape.current
    return if (landscape) {
        PaddingValues(start = 44.dp, end = 44.dp, top = 34.dp, bottom = LocalBottomInset.current)
    } else {
        PaddingValues(start = 36.dp, end = 36.dp, top = 56.dp, bottom = LocalBottomInset.current)
    }
}

// ---------- 迷你播放条 ----------

@Composable
private fun MiniPlayer(nav: Nav, modifier: Modifier) {
    val track by PlayerHub.current.collectAsState()
    val playing by PlayerHub.isPlaying.collectAsState()
    val c = K.colors
    val t = track ?: return
    Row(
        modifier
            .fillMaxWidth()
            .height(96.dp)
            .glass(RoundedCornerShape(28.dp), c.glass, c.glassBorder)
            .pressable { nav.nowPlayingOpen = true }
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.album.picUrl, 68.dp, corner = 10.dp, px = 160)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Label(t.name, 25.sp, c.label, FontWeight.SemiBold)
            Label(t.artistNames, 20.sp, c.secondary)
        }
        BigIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 48.dp, touch = 84.dp) { PlayerHub.togglePlay() }
        BigIcon(Icons.Rounded.FastForward, 48.dp, touch = 84.dp) { PlayerHub.next() }
    }
}

// ---------- 提示 ----------

@Composable
private fun StatusHost() {
    val status by PlayerHub.status.collectAsState()
    var displayedText by remember { mutableStateOf(status?.text.orEmpty()) }
    LaunchedEffect(status) {
        status?.let { displayedText = it.text }
    }
    Box(Modifier.fillMaxSize().padding(top = 28.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = status != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                displayedText,
                color = Color(0xFFFFC46B),
                fontSize = 26.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xE6202024))
                    .padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
}

@Composable
private fun ToastHost() {
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        PlayerHub.messages.collect { message = it }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(2600)
            message = null
        }
    }
    Box(Modifier.fillMaxSize().padding(top = 100.dp), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                message.orEmpty(),
                color = Color.White,
                fontSize = 24.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color(0xE6202024))
                    .padding(horizontal = 32.dp, vertical = 18.dp),
            )
        }
    }
}
