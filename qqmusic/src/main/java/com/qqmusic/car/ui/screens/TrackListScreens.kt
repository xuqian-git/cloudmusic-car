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
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

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
    // 滚动/拖动时浮出滚动轨，停下 2 秒后收起
    var railVisible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    // 超过两屏才给滚动轨；derivedStateOf 只在结论翻转时重组
    val longList by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.visibleItemsInfo.isNotEmpty() && info.totalItemsCount > info.visibleItemsInfo.size * 2
        }
    }
    LaunchedEffect(listState.isScrollInProgress, dragging) {
        if (listState.isScrollInProgress || dragging) {
            railVisible = true
        } else {
            delay(2000)
            railVisible = false
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
            // 末尾留白：「正在播放」胶囊不压最后一行
            item { Spacer(Modifier.height(PLAYING_CHIP_HEIGHT + 24.dp)) }
        }
        if (longList && tracks.isNotEmpty()) {
            ScrollRail(listState, tracks, railVisible || dragging) { dragging = it }
        }
        if (playingIndex >= 0) {
            PlayingChip(listState, tracks[playingIndex], playingIndex + 1) // +1：第 0 项是头部
        }
    }
}

private val PLAYING_CHIP_HEIGHT = 64.dp
private val THUMB_TOUCH_WIDTH = 64.dp
private val THUMB_TOUCH_HEIGHT = 104.dp
private val RAIL_TOP_PADDING = 24.dp

/**
 * 右边缘整条滚动轨：轨道从第一首歌那一行开始（头部还在屏幕上时让开头部），到底栏上方结束；
 * 按住滑块拖到哪，列表就跳到哪，屏幕中央大气泡显示封面、序号和歌名。
 * 轨道和滑块位置只在 drawBehind / graphicsLayer 里读列表状态，滚动不触发重组；拖动时只有气泡随序号重组。
 */
@Composable
private fun BoxScope.ScrollRail(
    listState: LazyListState,
    tracks: List<Track>,
    visible: Boolean,
    onDragging: (Boolean) -> Unit,
) {
    val c = K.colors
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val thumbHeightPx = with(density) { THUMB_TOUCH_HEIGHT.toPx() }
    val topPadPx = with(density) { RAIL_TOP_PADDING.toPx() }
    val bottomPadPx = with(density) { (LocalBottomInset.current + 16.dp).toPx() }
    val railWidthPx = with(density) { 4.dp.toPx() }
    val railEndPx = with(density) { 11.dp.toPx() }
    var heightPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    var dragTopPx by remember { mutableFloatStateOf(0f) }
    val placedTop = remember { floatArrayOf(0f) }

    // 轨道上沿：头部还露在屏幕上时贴着头部下沿，否则贴顶部留白
    fun railTop(): Float {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull()
        val headerBottom = if (first?.index == 0) (first.offset - info.viewportStartOffset + first.size).toFloat() else 0f
        return maxOf(topPadPx, headerBottom)
    }
    fun railBottom(): Float = heightPx - bottomPadPx
    fun thumbRange(top: Float): Float = (railBottom() - top - thumbHeightPx).coerceAtLeast(1f)
    fun listThumbTop(): Float {
        val info = listState.layoutInfo
        val first = info.visibleItemsInfo.firstOrNull() ?: return railTop()
        val scrollable = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(1)
        val progress = (listState.firstVisibleItemIndex + listState.firstVisibleItemScrollOffset.toFloat() / first.size.coerceAtLeast(1)) / scrollable
        val top = railTop()
        return top + progress.coerceIn(0f, 1f) * thumbRange(top)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { heightPx = it.height }
                .drawBehind {
                    val top = railTop()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.12f),
                        topLeft = Offset(size.width - railEndPx - railWidthPx, top),
                        size = Size(railWidthPx, (railBottom() - top).coerceAtLeast(0f)),
                        cornerRadius = CornerRadius(railWidthPx / 2),
                    )
                },
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .graphicsLayer {
                        val top = if (dragging) dragTopPx else listThumbTop()
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
                            // change.position 相对滑块当前摆放位置，用 placedTop 换回整屏坐标
                            val top = railTop()
                            val range = thumbRange(top)
                            dragTopPx = (placedTop[0] + change.position.y - grabY).coerceIn(top, top + range)
                            val index = (((dragTopPx - top) / range) * (tracks.size - 1)).roundToInt()
                            if (index != dragIndex) {
                                dragIndex = index
                                scope.launch { listState.scrollToItem(index + 1) }
                            }
                        }
                    },
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .padding(end = if (dragging) 6.dp else 8.dp)
                        .size(width = if (dragging) 16.dp else 12.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (dragging) c.accent else Color.White.copy(alpha = 0.75f)),
                )
            }
            if (dragging) {
                ScrollBubble({ dragIndex }, tracks, Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ScrollBubble(index: () -> Int, tracks: List<Track>, modifier: Modifier) {
    val c = K.colors
    val track = tracks[index().coerceIn(0, tracks.lastIndex)]
    Row(
        modifier
            .width(460.dp)
            .glass(RoundedCornerShape(28.dp), c.glass, c.glassBorder)
            .padding(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.album.picUrl, 96.dp, corner = 14.dp, px = 200)
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text("第 ${index().coerceIn(0, tracks.lastIndex) + 1} 首", color = c.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(track.name, color = c.label, fontSize = 26.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artistNames, color = c.secondary, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 底部居中的「正在播放」胶囊：歌不在屏幕上时出现，箭头指明在上面还是下面，点一下跳过去。 */
@Composable
private fun BoxScope.PlayingChip(listState: LazyListState, track: Track, itemIndex: Int) {
    val c = K.colors
    val scope = rememberCoroutineScope()
    // 0 = 在屏幕上（隐藏），-1 = 在上面，1 = 在下面；只在结论变化时重组
    val direction by remember(itemIndex) {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            when {
                visible.isEmpty() || visible.any { it.index == itemIndex } -> 0
                itemIndex < visible.first().index -> -1
                else -> 1
            }
        }
    }
    AnimatedVisibility(
        visible = direction != 0,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomInset.current + 16.dp),
    ) {
        Row(
            Modifier
                .height(PLAYING_CHIP_HEIGHT)
                .widthIn(max = 560.dp)
                .glass(RoundedCornerShape(32.dp), c.glass, c.glassBorder)
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
                }
                .padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.GraphicEq, null, tint = c.accent, modifier = Modifier.size(30.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                "正在播放：${track.name}",
                color = c.label,
                fontSize = 24.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(12.dp))
            Text(if (direction < 0) "↑" else "↓", color = c.accent, fontSize = 28.sp, fontWeight = FontWeight.Bold)
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
