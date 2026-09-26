package com.kugoumusic.car.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.Track
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.player.PlayerHub
import com.kugoumusic.car.ui.Nav
import com.kugoumusic.car.ui.Route
import com.kugoumusic.car.ui.components.Artwork
import com.kugoumusic.car.ui.components.EmptyBox
import com.kugoumusic.car.ui.components.Label
import com.kugoumusic.car.ui.components.LoadContent
import com.kugoumusic.car.ui.components.PillButton
import com.kugoumusic.car.ui.components.TrackRow
import com.kugoumusic.car.ui.components.pressable
import com.kugoumusic.car.ui.components.glass
import com.kugoumusic.car.ui.components.rememberLoad
import com.kugoumusic.car.ui.LocalBottomInset
import com.kugoumusic.car.ui.pagePadding
import com.kugoumusic.car.ui.theme.K
import com.kugoumusic.car.ui.theme.LocalLandscape
import java.util.Calendar

@Composable
fun PlaylistScreen(nav: Nav, route: Route.PlaylistPage) {
    val loader = rememberLoad("playlist.${route.id}") { KuGouMusicApi.playlistDetail(route.id) }
    TrackListPage(
        nav = nav,
        title = route.name,
        subtitle = (loader.state as? com.kugoumusic.car.ui.components.Load.Ok)?.value?.let {
            listOfNotNull(it.playlist.creatorName.takeIf(String::isNotEmpty), "${it.tracks.size} 首").joinToString(" · ")
        },
        cover = { Artwork(route.cover, it, corner = 16.dp, px = 500) },
        showBack = true,
    ) {
        LoadContent(loader) { detail -> TrackList(detail.tracks, route.name, route.id, header = it) }
    }
}

@Composable
fun LikedScreen(nav: Nav) {
    val playlists by AccountStore.playlists.collectAsState()
    val liked = playlists.firstOrNull { it.isLikedSongs }
    val landscape = LocalLandscape.current
    if (liked == null) {
        EmptyBox("正在加载…")
        return
    }
    val loader = rememberLoad("playlist.${liked.id}") { KuGouMusicApi.playlistDetail(liked.id) }
    TrackListPage(
        nav = nav,
        title = "我喜欢的音乐",
        subtitle = (loader.state as? com.kugoumusic.car.ui.components.Load.Ok)?.value?.let { "${it.tracks.size} 首" },
        cover = { GradientCover(it, listOf(Color(0xFFFF5E7E), Color(0xFFFA2D48)), "♥") },
        showBack = !landscape,
    ) {
        LoadContent(loader) { detail -> TrackList(detail.tracks, "我喜欢的音乐", liked.id, header = it) }
    }
}

@Composable
fun DailyScreen(nav: Nav) {
    val loader = rememberLoad("daily.${Calendar.getInstance().get(Calendar.DAY_OF_YEAR)}") { KuGouMusicApi.dailySongs() }
    val day = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
    TrackListPage(
        nav = nav,
        title = "每日推荐",
        subtitle = "根据你的口味生成 · 每天 6:00 更新",
        cover = { GradientCover(it, listOf(Color(0xFFFF6A3D), Color(0xFFD4145A)), "$day") },
        showBack = true,
    ) {
        LoadContent(loader) { tracks -> TrackList(tracks, "每日推荐", 0, header = it) }
    }
}

@Composable
fun RecentScreen(nav: Nav) {
    val profile by AccountStore.profile.collectAsState()
    val uid = profile?.userId
    val landscape = LocalLandscape.current
    if (uid == null) {
        EmptyBox("正在加载…")
        return
    }
    val loader = rememberLoad("recent.$uid") { KuGouMusicApi.playRecords(uid) }
    TrackListPage(
        nav = nav,
        title = "最近播放",
        subtitle = null,
        cover = { GradientCover(it, listOf(Color(0xFF5AC8FA), Color(0xFF0A84FF)), "↺") },
        showBack = !landscape,
    ) {
        LoadContent(loader) { tracks -> TrackList(tracks, "最近播放", 0, header = it) }
    }
}

@Composable
fun CloudScreen(nav: Nav) {
    val profile by AccountStore.profile.collectAsState()
    val landscape = LocalLandscape.current
    val loader = rememberLoad("cloud.${profile?.userId}") { KuGouMusicApi.cloudDrive() }
    val drive = (loader.state as? com.kugoumusic.car.ui.components.Load.Ok)?.value
    TrackListPage(
        nav = nav,
        title = "音乐云盘",
        subtitle = drive?.let {
            val usage = if (it.maxBytes > 0) " · ${formatBytes(it.usedBytes)} / ${formatBytes(it.maxBytes)}" else ""
            "${it.tracks.size} 首$usage"
        },
        cover = { GradientCover(it, listOf(Color(0xFF4DA3FF), Color(0xFF1261D8)), "♪") },
        showBack = !landscape,
    ) {
        LoadContent(loader) { d -> TrackList(d.tracks, "音乐云盘", 0, header = it) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    else -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
}

/**
 * 歌单类页面的通用骨架：返回按钮 + 封面 + 标题 + 播放/随机按钮 + 歌曲列表。
 * 头部作为列表的第一项，跟随滚动。
 */
@Composable
private fun TrackListPage(
    nav: Nav,
    title: String,
    subtitle: String?,
    cover: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
    showBack: Boolean,
    content: @Composable (header: @Composable (List<Track>, String, Long) -> Unit) -> Unit,
) {
    val c = K.colors
    val landscape = LocalLandscape.current
    val header: @Composable (List<Track>, String, Long) -> Unit = { tracks, source, sourceId ->
        Column {
            if (showBack) {
                Row(
                    Modifier.padding(bottom = 16.dp).pressable { nav.back() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.ArrowBackIosNew, null, tint = c.accent, modifier = Modifier.size(30.dp))
                    Spacer(Modifier.width(6.dp))
                    Label("返回", 28.sp, c.accent)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 18.dp)) {
                cover(if (landscape) 220.dp else 230.dp)
                Spacer(Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Label(title, 44.sp, c.label, FontWeight.ExtraBold, maxLines = 2)
                    if (subtitle != null) Label(subtitle, 24.sp, c.secondary)
                    Spacer(Modifier.height(22.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        PillButton("播放", Icons.Rounded.PlayArrow) {
                            PlayerHub.play(tracks, source = source, sourceId = sourceId)
                            nav.nowPlayingOpen = true
                        }
                        PillButton("随机", Icons.Rounded.Shuffle) {
                            PlayerHub.play(tracks, shuffle = true, source = source, sourceId = sourceId)
                            nav.nowPlayingOpen = true
                        }
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        content(header)
    }
}

@Composable
private fun TrackList(
    tracks: List<Track>,
    source: String,
    sourceId: Long,
    header: @Composable (List<Track>, String, Long) -> Unit,
) {
    val current by PlayerHub.current.collectAsState()
    AccountStore.loggedIn.collectAsState().value // 登录状态变化时重新计算可播放性
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var fabExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            fabExpanded = true
        } else {
            delay(2000)
            fabExpanded = false
        }
    }

    LaunchedEffect(fabExpanded) {
        if (fabExpanded && !listState.isScrollInProgress) {
            delay(2000)
            fabExpanded = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = pagePadding()) {
            item { header(tracks, source, sourceId) }
            if (tracks.isEmpty()) {
                item { EmptyBox("这里还没有歌曲", Modifier.height(200.dp)) }
            }
            itemsIndexed(tracks, key = { i, t -> "$i-${t.id}" }) { i, t ->
                TrackRow(t, i + 1, current?.id == t.id, AccountStore.unplayableReason(t)) {
                    PlayerHub.play(tracks, start = t, source = source, sourceId = sourceId)
                }
            }
            item { Spacer(Modifier.height(FAB_COLLAPSED_HEIGHT + 16.dp)) }
        }
        ScrollEdgeFab(
            expanded = fabExpanded,
            listState = listState,
            itemCount = tracks.size,
            onExpand = { fabExpanded = true },
            scope = scope,
        )
    }
}

// 车机触控底线：收起态也要够手指点
private val FAB_COLLAPSED_HEIGHT = 88.dp

/**
 * 右下角浮动滚动按钮。
 * 收缩时贴在右边缘显示一个半露指示条；展开时显示上下三角形分别跳到列表顶部和底部。
 */
@Composable
private fun ScrollEdgeFab(
    expanded: Boolean,
    listState: LazyListState,
    itemCount: Int,
    onExpand: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val c = K.colors
    val frosted = c.glass.copy(alpha = 0.65f)
    val frostedBorder = c.glassBorder.copy(alpha = 0.4f)
    val bottomOffset = LocalBottomInset.current + 16.dp
    Box(Modifier.fillMaxSize()) {
        // 收缩：右下角双三角形指示
        AnimatedVisibility(
            visible = !expanded,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottomOffset),
        ) {
            Box(
                Modifier
                    .size(width = 64.dp, height = FAB_COLLAPSED_HEIGHT)
                    .clip(RoundedCornerShape(14.dp))
                    .background(frosted)
                    .border(1.dp, frostedBorder, RoundedCornerShape(14.dp))
                    .pressable { onExpand() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = c.secondary, modifier = Modifier.size(32.dp))
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.secondary, modifier = Modifier.size(32.dp))
                }
            }
        }
        // 展开：毛玻璃浮块（右下角，与收缩位置一致）
        AnimatedVisibility(
            visible = expanded,
            enter = slideInHorizontally { it / 2 } + fadeIn(),
            exit = slideOutHorizontally { it / 2 } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = bottomOffset),
        ) {
            Column(
                Modifier
                    .size(width = 72.dp, height = 148.dp)
                    .glass(RoundedCornerShape(24.dp), frosted, frostedBorder),
            ) {
                Box(
                    Modifier.weight(1f).fillMaxSize().pressable {
                        scope.launch { listState.animateScrollToItem(0) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, null, tint = c.accent, modifier = Modifier.size(44.dp))
                }
                Box(
                    Modifier.weight(1f).fillMaxSize().pressable {
                        scope.launch { listState.animateScrollToItem(itemCount + 1) }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = c.accent, modifier = Modifier.size(44.dp))
                }
            }
        }
    }
}

@Composable
private fun GradientCover(size: androidx.compose.ui.unit.Dp, colors: List<Color>, mark: String) {
    Box(
        Modifier
            .size(size)
            .background(Brush.linearGradient(colors), RoundedCornerShape(16.dp))
            .padding(18.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Label(mark, 76.sp, Color.White, FontWeight.ExtraBold)
    }
}
