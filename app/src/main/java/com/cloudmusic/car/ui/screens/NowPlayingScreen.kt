package com.cloudmusic.car.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.request.ImageRequest
import com.cloudmusic.car.api.LyricsParser
import com.cloudmusic.car.api.formatDuration
import com.cloudmusic.car.api.sized
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.player.PlayMode
import com.cloudmusic.car.player.PlayerHub
import com.cloudmusic.car.ui.components.Artwork
import com.cloudmusic.car.ui.components.BigIcon
import com.cloudmusic.car.ui.components.Label
import com.cloudmusic.car.ui.components.pressable
import com.cloudmusic.car.ui.theme.LocalLandscape
import kotlinx.coroutines.delay

private val DefaultTop = Color(0xFF3A3A40)
private val DefaultBottom = Color(0xFF111114)

@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val track by PlayerHub.current.collectAsState()
    val landscape = LocalLandscape.current
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }

    // 从封面取色做背景（全版本可用，无需实时模糊）
    val context = LocalContext.current
    var top by remember { mutableStateOf(DefaultTop) }
    var bottom by remember { mutableStateOf(DefaultBottom) }
    val cover = track?.album?.picUrl
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(cover.sized(96)).allowHardware(false).build()
        val bmp = (MusicCache.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap
            ?: return@LaunchedEffect
        val palette = Palette.from(bmp).generate()
        top = Color(palette.getVibrantColor(palette.getDominantColor(DefaultTop.toArgbCompat()))).darken(0.72f)
        bottom = Color(palette.getDarkMutedColor(palette.getDarkVibrantColor(DefaultBottom.toArgbCompat()))).darken(0.45f)
    }
    val topAnim by animateColorAsState(top, tween(800), label = "top")
    val bottomAnim by animateColorAsState(bottom, tween(800), label = "bottom")

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.linearGradient(listOf(topAnim, bottomAnim)))
            .pointerInput(Unit) { detectTapGestures { } } // 拦截点击，避免穿透到下层
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val t = track
        if (t == null) {
            Label("没有正在播放的歌曲", 30.sp, Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (landscape) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth / maxHeight < 1.55f
                val horizontalPadding = if (compact) 48.dp else 80.dp
                val verticalPadding = if (compact) 44.dp else 60.dp
                val gap = if (compact) 48.dp else 80.dp
                val availableHeight = maxHeight - verticalPadding * 2
                val coverWidthLimit = if (compact) maxWidth * 0.46f else 520.dp
                val coverSize = minOf(availableHeight, coverWidthLimit, if (compact) 600.dp else 520.dp)
                val availableInfoWidth = maxWidth - horizontalPadding * 2 - coverSize - gap
                val infoWidth = minOf(availableInfoWidth, 720.dp)

                Row(
                    Modifier.fillMaxSize().padding(horizontal = horizontalPadding, vertical = verticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Box(Modifier.size(coverSize), contentAlignment = Alignment.Center) {
                        if (showLyrics) LyricsView(Modifier.fillMaxSize()) else BigCover(t.album.picUrl, coverSize)
                    }
                    Spacer(Modifier.width(gap))
                    Column(
                        Modifier.width(infoWidth).fillMaxHeight(),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        TitleBlock(t.name, t.artistNames, t.album.name)
                        Spacer(Modifier.height(if (compact) 32.dp else 40.dp))
                        ProgressBar()
                        Spacer(Modifier.height(24.dp))
                        MainControls()
                        Spacer(Modifier.height(if (compact) 20.dp else 28.dp))
                        SecondaryControls(showLyrics, { showLyrics = !showLyrics }, { showQueue = true })
                    }
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(90.dp))
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (showLyrics) LyricsView(Modifier.fillMaxSize()) else BigCover(t.album.picUrl, 620.dp)
                }
                Spacer(Modifier.height(40.dp))
                TitleBlock(t.name, t.artistNames, t.album.name)
                Spacer(Modifier.height(36.dp))
                ProgressBar()
                Spacer(Modifier.height(24.dp))
                MainControls()
                Spacer(Modifier.height(28.dp))
                SecondaryControls(showLyrics, { showLyrics = !showLyrics }, { showQueue = true })
                Spacer(Modifier.height(24.dp))
            }
        }

        // 关闭
        Box(
            Modifier
                .padding(24.dp)
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .pressable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(48.dp))
        }

        // 播放队列
        AnimatedVisibility(
            visible = showQueue,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .pointerInput(Unit) { detectTapGestures { showQueue = false } },
            )
        }
        AnimatedVisibility(
            visible = showQueue,
            enter = slideInHorizontally { it },
            exit = slideOutHorizontally { it },
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            QueuePanel(if (landscape) 560.dp else 680.dp) { showQueue = false }
        }
    }
}

@Composable
private fun BigCover(url: String?, max: Dp) {
    BoxWithConstraints(contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight, max)
        Box(Modifier.shadow(40.dp, RoundedCornerShape(22.dp))) {
            Artwork(url, size, corner = 22.dp, px = 800)
        }
    }
}

@Composable
private fun TitleBlock(name: String, artists: String, album: String) {
    val sourceName by PlayerHub.sourceName.collectAsState()
    Column(Modifier.fillMaxWidth()) {
        sourceName?.let {
            Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp, letterSpacing = 2.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
        }
        Text(name, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 54.sp, overflow = TextOverflow.Ellipsis)
        Text(
            listOf(artists, album).filter { it.isNotEmpty() }.joinToString(" — "),
            color = Color.White.copy(alpha = 0.65f), fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 每 250ms 读取一次播放进度。 */
@Composable
private fun rememberPosition(): Pair<Long, Long> {
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            val p = PlayerHub.player
            pos = p.currentPosition
            dur = p.duration.takeIf { it > 0 } ?: PlayerHub.current.value?.durationMs ?: 0L
            delay(250)
        }
    }
    return pos to dur
}

@Composable
private fun ProgressBar() {
    val (pos, dur) = rememberPosition()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) PlayerHub.seekTo((o.x / size.width * dur).toLong()) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { if (dur > 0) PlayerHub.seekTo((it * dur).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val barHeight = if (dragFraction != null) 16.dp else 10.dp
            Box(Modifier.fillMaxWidth().height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            Box(Modifier.fillMaxWidth(fraction).height(barHeight).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)))
        }
        val shown = if (dragFraction != null) (fraction * dur).toLong() else pos
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(shown), color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            Text("-" + formatDuration((dur - shown).coerceAtLeast(0)), color = Color.White.copy(alpha = 0.6f), fontSize = 22.sp)
        }
    }
}

@Composable
private fun MainControls() {
    val playing by PlayerHub.isPlaying.collectAsState()
    val mode by PlayerHub.mode.collectAsState()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigIcon(Icons.Rounded.FastRewind, 72.dp, tint = Color.White, touch = 120.dp, enabled = mode != PlayMode.FM) { PlayerHub.previous() }
        BigIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 104.dp, tint = Color.White, touch = 140.dp) { PlayerHub.togglePlay() }
        BigIcon(Icons.Rounded.FastForward, 72.dp, tint = Color.White, touch = 120.dp) { PlayerHub.next() }
    }
}

@Composable
private fun SecondaryControls(showLyrics: Boolean, onLyrics: () -> Unit, onQueue: () -> Unit) {
    val track by PlayerHub.current.collectAsState()
    val liked by AccountStore.likedIds.collectAsState()
    val mode by PlayerHub.mode.collectAsState()
    val shuffle by PlayerHub.shuffle.collectAsState()
    val repeat by PlayerHub.repeatMode.collectAsState()
    val isLiked = track?.id in liked
    val dim = Color.White.copy(alpha = 0.55f)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BigIcon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, 44.dp, tint = if (isLiked) Color(0xFFFF4D67) else dim, touch = 88.dp) {
            track?.let { AccountStore.toggleLike(it, PlayerHub::toast) }
        }
        ToggleIcon(Icons.Rounded.Lyrics, showLyrics, onLyrics)
        if (mode == PlayMode.FM) {
            BigIcon(Icons.Rounded.ThumbDown, 40.dp, tint = dim, touch = 88.dp) { PlayerHub.fmTrash() }
        } else {
            ToggleIcon(Icons.Rounded.Shuffle, shuffle) { PlayerHub.toggleShuffle() }
            ToggleIcon(if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, repeat != Player.REPEAT_MODE_OFF) {
                PlayerHub.cycleRepeat()
            }
        }
        BigIcon(Icons.AutoMirrored.Rounded.QueueMusic, 44.dp, tint = dim, touch = 88.dp, onClick = onQueue)
    }
}

/** 苹果式开关按钮：开启时加白色圆角底。 */
@Composable
private fun ToggleIcon(icon: ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(88.dp)
            .background(if (on) Color.White.copy(alpha = 0.9f) else Color.Transparent, RoundedCornerShape(20.dp))
            .pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on) Color.Black.copy(alpha = 0.75f) else Color.White.copy(alpha = 0.55f), modifier = Modifier.size(42.dp))
    }
}

@Composable
private fun LyricsView(modifier: Modifier) {
    val lines by PlayerHub.lyrics.collectAsState()
    val (pos, _) = rememberPosition()
    val active = LyricsParser.activeIndex(lines, pos + 300)
    val state = rememberLazyListState()
    BoxWithConstraints(modifier) {
        val half = maxHeight / 3
        LaunchedEffect(active) {
            if (active >= 0) state.animateScrollToItem(active)
        }
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Label("暂无歌词", 34.sp, Color.White.copy(alpha = 0.6f), FontWeight.Bold)
            }
        } else {
            LazyColumn(
                state = state,
                contentPadding = PaddingValues(top = half, bottom = maxHeight),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(lines) { i, line ->
                    val isActive = i == active
                    Column(Modifier.fillMaxWidth().pressable { PlayerHub.seekTo(line.timeMs) }) {
                        Text(
                            line.text,
                            color = Color.White.copy(alpha = if (isActive) 1f else 0.35f),
                            fontSize = if (isActive) 42.sp else 32.sp,
                            lineHeight = if (isActive) 52.sp else 42.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        line.translation?.let {
                            Text(it, color = Color.White.copy(alpha = if (isActive) 0.75f else 0.3f), fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueuePanel(width: Dp, onClose: () -> Unit) {
    val queue by PlayerHub.queue.collectAsState()
    val current by PlayerHub.current.collectAsState()
    val currentPos = queue.indexOfFirst { it.second.id == current?.id }.coerceAtLeast(0)
    val upcoming = queue.drop(currentPos)
    val state = rememberLazyListState()
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(Color(0xF2141416))
            .padding(horizontal = 28.dp, vertical = 30.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("播放队列", 36.sp, Color.White, FontWeight.Bold, Modifier.weight(1f))
            BigIcon(Icons.Rounded.Close, 40.dp, tint = Color.White, touch = 72.dp, onClick = onClose)
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(upcoming, key = { _, it -> it.first }) { i, (index, t) ->
                val playing = i == 0
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .background(if (playing) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(16.dp))
                        .pressable { PlayerHub.jumpTo(index) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(t.album.picUrl, 76.dp, corner = 10.dp, px = 160)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Label(t.name, 27.sp, if (playing) Color(0xFFFF4D67) else Color.White, FontWeight.Medium)
                        Label(t.artistNames, 21.sp, Color.White.copy(alpha = 0.55f))
                    }
                    if (playing) Icon(Icons.Rounded.GraphicEq, null, tint = Color(0xFFFF4D67), modifier = Modifier.size(34.dp))
                }
            }
        }
    }
}

private fun Color.darken(factor: Float) = Color(red * factor, green * factor, blue * factor, 1f)

private fun Color.toArgbCompat(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
