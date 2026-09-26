package com.qqmusic.car.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqmusic.car.api.QQMusicApi
import com.qqmusic.car.api.Track
import com.qqmusic.car.data.AccountStore
import com.qqmusic.car.player.PlayerHub
import com.qqmusic.car.ui.Nav
import com.qqmusic.car.ui.Route
import com.qqmusic.car.ui.components.Artwork
import com.qqmusic.car.ui.components.EmptyBox
import com.qqmusic.car.ui.components.Label
import com.qqmusic.car.ui.components.LoadContent
import com.qqmusic.car.ui.components.PillButton
import com.qqmusic.car.ui.components.TrackRow
import com.qqmusic.car.ui.components.pressable
import com.qqmusic.car.ui.components.rememberLoad
import com.qqmusic.car.ui.pagePadding
import com.qqmusic.car.ui.theme.K
import com.qqmusic.car.ui.theme.LocalLandscape
import java.util.Calendar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.qqmusic.car.ui.components.glass
import com.qqmusic.car.ui.LocalBottomInset
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun PlaylistScreen(nav: Nav, route: Route.PlaylistPage) {
    val loader = rememberLoad("playlist.${route.id}") { QQMusicApi.playlistDetail(route.id) }
    TrackListPage(
        nav = nav,
        title = route.name,
        subtitle = (loader.state as? com.qqmusic.car.ui.components.Load.Ok)?.value?.let {
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
    val loader = rememberLoad("playlist.${liked.id}") { QQMusicApi.playlistDetail(liked.id) }
    TrackListPage(
        nav = nav,
        title = "我喜欢的音乐",
        subtitle = (loader.state as? com.qqmusic.car.ui.components.Load.Ok)?.value?.let { "${it.tracks.size} 首" },
        cover = { GradientCover(it, listOf(Color(0xFFFF5E7E), Color(0xFFFA2D48)), "♥") },
        showBack = !landscape,
    ) {
        LoadContent(loader) { detail -> TrackList(detail.tracks, "我喜欢的音乐", liked.id, header = it) }
    }
}

@Composable
fun DailyScreen(nav: Nav) {
    val loader = rememberLoad("daily.${Calendar.getInstance().get(Calendar.DAY_OF_YEAR)}") { QQMusicApi.dailySongs() }
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
    val loader = rememberLoad("recent.$uid") { QQMusicApi.playRecords(uid) }
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
    val loader = rememberLoad("cloud.${profile?.userId}") { QQMusicApi.cloudDrive() }
    val drive = (loader.state as? com.qqmusic.car.ui.components.Load.Ok)?.value
    TrackListPage(
        nav = nav,
        title = "新歌推荐",
        subtitle = drive?.let { "${it.tracks.size} 首 · 每日更新" },
        cover = { GradientCover(it, listOf(Color(0xFF16D991), Color(0xFF00A86B)), "♪") },
        showBack = !landscape,
    ) {
        LoadContent(loader) { d -> TrackList(d.tracks, "新歌推荐", 0, header = it) }
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
    val playingIndex = remember(tracks, current?.id) { tracks.indexOfFirst { it.id == current?.id } }
    // 滚动/拖动时浮出快速滚动条和「定位正在播放」，停下 2 秒后收起
    var toolsVisible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress, dragging) {
        if (listState.isScrollInProgress || dragging) {
            toolsVisible = true
        } else {
            delay(2000)
            toolsVisible = false
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
            // 末尾留白：浮钮不压最后一行
            item { Spacer(Modifier.height(LOCATE_SIZE + 16.dp)) }
        }
        if (tracks.size >= FAST_SCROLL_MIN_TRACKS) {
            FastScroller(listState, tracks, toolsVisible) { dragging = it }
        }
        if (playingIndex >= 0) {
            LocatePlayingButton(toolsVisible, listState, playingIndex + 1) // +1：第 0 项是头部
        }
    }
}

private const val FAST_SCROLL_MIN_TRACKS = 40
private val LOCATE_SIZE = 80.dp
private val THUMB_TOUCH_WIDTH = 64.dp
private val THUMB_TOUCH_HEIGHT = 104.dp

/**
 * 右边缘可拖动的快速滚动条：按住滑块拖到哪，列表就跳到哪，左侧气泡显示「第 N 首 · 歌名」。
 * 滚动位置只在 graphicsLayer 的绘制 lambda 里读，列表滚动不触发这里重组；拖动时只有气泡随序号重组。
 */
@Composable
private fun BoxScope.FastScroller(
    listState: LazyListState,
    tracks: List<Track>,
    visible: Boolean,
    onDragging: (Boolean) -> Unit,
) {
    val c = K.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { THUMB_TOUCH_HEIGHT.toPx() }
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    // 拖动时滑块跟手的位置；不拖时由列表位置推算
    var dragTopPx by remember { mutableFloatStateOf(0f) }
    val placedTop = remember { floatArrayOf(0f) }
    val range = { (trackHeightPx - thumbHeightPx).coerceAtLeast(1f) }

    fun listTopPx(): Float {
        val info = listState.layoutInfo
        val scrollable = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
        return (listState.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f) * range()
    }

    AnimatedVisibility(
        visible = visible || dragging,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            .fillMaxHeight()
            .padding(top = 24.dp, bottom = LocalBottomInset.current + LOCATE_SIZE + 32.dp, end = 12.dp)
            .onSizeChanged { trackHeightPx = it.height },
    ) {
        Box(Modifier.fillMaxHeight()) {
            if (dragging) {
                Box(
                    Modifier
                        .graphicsLayer { translationY = placedTop[0] }
                        .height(THUMB_TOUCH_HEIGHT)
                        .padding(end = THUMB_TOUCH_WIDTH + 8.dp)
                        .align(Alignment.TopEnd),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    ScrollBubble({ dragIndex }, tracks)
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        val top = if (dragging) dragTopPx else listTopPx()
                        placedTop[0] = top
                        translationY = top
                    }
                    .size(THUMB_TOUCH_WIDTH, THUMB_TOUCH_HEIGHT)
                    .pointerInput(tracks) {
                        var grabY = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                grabY = it.y
                                dragTopPx = placedTop[0]
                                dragging = true
                                onDragging(true)
                            },
                            onDragEnd = { dragging = false; onDragging(false) },
                            onDragCancel = { dragging = false; onDragging(false) },
                        ) { change, _ ->
                            change.consume()
                            // change.position 相对滑块当前摆放位置，所以用 placedTop 换算成轨道坐标
                            dragTopPx = (placedTop[0] + change.position.y - grabY).coerceIn(0f, range())
                            val index = ((dragTopPx / range()) * (tracks.size - 1)).roundToInt()
                            if (index != dragIndex) {
                                dragIndex = index
                                scope.launch { listState.scrollToItem(index + 1) }
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = if (dragging) 16.dp else 10.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (dragging) c.accent else c.secondary.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun ScrollBubble(index: () -> Int, tracks: List<Track>) {
    val c = K.colors
    val i = index().coerceIn(0, tracks.lastIndex)
    Row(
        Modifier
            .widthIn(max = 420.dp)
            .glass(RoundedCornerShape(20.dp), c.glass, c.glassBorder)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("第 ${i + 1} 首", color = c.accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.width(14.dp))
        Text(tracks[i].name, color = c.label, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 右下角「定位正在播放」：跳到当前歌曲所在行（行本身已高亮）。 */
@Composable
private fun BoxScope.LocatePlayingButton(visible: Boolean, listState: LazyListState, itemIndex: Int) {
    val c = K.colors
    val scope = rememberCoroutineScope()
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = LocalBottomInset.current + 16.dp),
    ) {
        Box(
            Modifier
                .size(LOCATE_SIZE)
                .glass(CircleShape, c.glass, c.glassBorder)
                .pressable {
                    scope.launch {
                        // 留两行上文；离得远直接跳，近的才动画，免得几千首滚半天
                        val target = (itemIndex - 2).coerceAtLeast(0)
                        if (abs(listState.firstVisibleItemIndex - target) > 30) {
                            listState.scrollToItem(target)
                        } else {
                            listState.animateScrollToItem(target)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.GraphicEq, "定位正在播放", tint = c.accent, modifier = Modifier.size(40.dp))
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
