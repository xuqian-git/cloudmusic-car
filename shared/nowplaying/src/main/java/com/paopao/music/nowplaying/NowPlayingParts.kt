package com.paopao.music.nowplaying

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.floor

/** 以窗格高度的 1% 为单位的尺寸：三种尺寸下的比例与设计稿一致。 */
@Immutable
internal class Grid(val u: Dp) {
    operator fun invoke(x: Float): Dp = u * x
    /** 字号跟随系统字体缩放。 */
    fun sp(x: Float): TextUnit = (u.value * x).sp
}

internal val Dim = Color.White.copy(alpha = 0.62f)
internal val Faint = Color.White.copy(alpha = 0.55f)

// ---------------- 手势 ----------------

internal enum class Axis { X, Y }

/**
 * 单指拖动，过了触摸阈值后锁定方向。子组件（按钮、进度条、歌词列表）先消费了事件时整段放弃，
 * 所以按钮照常点击、进度条照常拖动、歌词列表照常滚动。
 */
internal fun Modifier.axisDrag(
    key: Any?,
    allowX: Boolean,
    allowY: Boolean,
    onStart: (Axis) -> Unit,
    onDrag: (Axis, Float) -> Unit,
    onEnd: (Axis, Velocity) -> Unit,
): Modifier = pointerInput(key, allowX, allowY) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var axis: Axis? = null
        var dx = 0f
        var dy = 0f
        val slop = viewConfiguration.touchSlop
        while (true) {
            val event = awaitPointerEvent()
            val change: PointerInputChange = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            if (change.isConsumed) {
                if (axis == null) return@awaitEachGesture else break
            }
            val delta = change.positionChange()
            tracker.addPointerInputChange(change)
            if (axis == null) {
                dx += delta.x
                dy += delta.y
                if (abs(dx) < slop && abs(dy) < slop) continue
                val candidate = if (abs(dx) > abs(dy)) Axis.X else Axis.Y
                if ((candidate == Axis.X && !allowX) || (candidate == Axis.Y && !allowY)) return@awaitEachGesture
                axis = candidate
                onStart(candidate)
            }
            change.consume()
            onDrag(axis, if (axis == Axis.X) delta.x else delta.y)
        }
        axis?.let { onEnd(it, tracker.calculateVelocity()) }
    }
}

// ---------------- 基础部件 ----------------

internal fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    composed {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "press")
        this
            .scale(scale)
            .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
    }

@Composable
internal fun Cover(
    source: NowPlayingSource,
    url: String?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    px: Int = 800,
    elevation: Dp = 0.dp,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .size(size)
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape) else Modifier)
            .background(Color(0xFF2A2A30), shape)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = Faint, modifier = Modifier.size(size * 0.4f))
        if (url != null) {
            AsyncImage(
                model = source.artworkUrl(url, px),
                contentDescription = null,
                imageLoader = source.imageLoader(context),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
internal fun IconButton(
    icon: ImageVector,
    g: Grid,
    iconSize: Float,
    touch: Float,
    tint: Color = Color.White,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    Box(Modifier.size(g(touch)).pressable(enabled, onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(g(iconSize)))
    }
}

@Composable
internal fun CloseButton(g: Grid, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(g(3f))
            .size(g(5.4f))
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .pressable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, "收起播放页", tint = Color.White, modifier = Modifier.size(g(3.6f)))
    }
}

@Composable
internal fun TitleBlock(
    track: NowPlayingTrack,
    g: Grid,
    titleSize: Float = 3.8f,
    align: TextAlign = TextAlign.Start,
    metaScale: Float = 1f,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            track.title, color = Color.White, fontSize = g.sp(titleSize), fontWeight = FontWeight.Bold,
            lineHeight = g.sp(titleSize * 1.25f), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            listOf(track.artists, track.album).filter { it.isNotEmpty() }.joinToString(" — "),
            color = Dim, fontSize = g.sp(2.1f * metaScale), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align,
            modifier = Modifier.fillMaxWidth().padding(top = g(0.3f)),
        )
    }
}

// ---------------- 进度条 ----------------

@Composable
internal fun ProgressBar(source: NowPlayingSource, clock: PlaybackClock, g: Grid, inline: Boolean, modifier: Modifier = Modifier) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val seconds by remember { derivedStateOf { clock.position.longValue / 1000 } }
    val durSeconds by remember { derivedStateOf { clock.duration.longValue / 1000 } }
    val shownSeconds = dragFraction?.let { (it * durSeconds).toLong() } ?: seconds
    val elapsed = formatClock(shownSeconds)
    val remaining = "-" + formatClock((durSeconds - shownSeconds).coerceAtLeast(0))
    val timeSize = g.sp(1.55f)

    val bar: @Composable (Modifier) -> Unit = { m ->
        Box(
            m
                .height(g(3.6f))
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val d = clock.duration.longValue
                        if (d > 0) source.seekTo((o.x / size.width * d).toLong())
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            val d = clock.duration.longValue
                            dragFraction?.let { if (d > 0) source.seekTo((it * d).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                }
                .drawBehind {
                    val dragging = dragFraction
                    val h = (if (dragging != null) g(1.1f) else g(0.7f)).toPx()
                    val top = (size.height - h) / 2
                    val r = CornerRadius(h / 2)
                    drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, top), Size(size.width, h), r)
                    val d = clock.duration.longValue
                    val f = dragging ?: if (d > 0) (clock.position.longValue.toFloat() / d).coerceIn(0f, 1f) else 0f
                    if (f > 0f) drawRoundRect(Color.White, Offset(0f, top), Size(size.width * f, h), r)
                },
        )
    }
    if (inline) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(elapsed, color = Faint, fontSize = timeSize, modifier = Modifier.width(g(5.2f)))
            bar(Modifier.weight(1f))
            Text(remaining, color = Faint, fontSize = timeSize, textAlign = TextAlign.End, modifier = Modifier.width(g(5.6f)))
        }
    } else {
        Column(modifier) {
            bar(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth()) {
                Text(elapsed, color = Faint, fontSize = timeSize)
                Spacer(Modifier.weight(1f))
                Text(remaining, color = Faint, fontSize = timeSize)
            }
        }
    }
}

private fun formatClock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

// ---------------- 按钮 ----------------

@Composable
internal fun MainControls(
    source: NowPlayingSource,
    g: Grid,
    canPrevious: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playSize: Float = 8.2f,
    skipSize: Float = 5.4f,
    gap: Float = 4f,
) {
    val playing by source.isPlaying.collectAsState()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(g(gap)), verticalAlignment = Alignment.CenterVertically) {
        IconButton(Icons.Rounded.SkipPrevious, g, skipSize, skipSize * 1.5f, enabled = canPrevious, contentDescription = "上一首", onClick = onPrevious)
        Box(
            Modifier
                .size(g(playSize))
                .clip(CircleShape)
                .background(Color.White)
                .pressable { source.togglePlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放",
                tint = Color(0xFF111114), modifier = Modifier.size(g(playSize * 0.56f)),
            )
        }
        IconButton(Icons.Rounded.SkipNext, g, skipSize, skipSize * 1.5f, contentDescription = "下一首", onClick = onNext)
    }
}

/** 红心 / 随机 / 循环（私人 FM 时换成不喜欢）/ 播放队列。 */
@Composable
internal fun secondaryButtons(source: NowPlayingSource, accent: Color, g: Grid, onQueue: () -> Unit): List<@Composable () -> Unit> {
    val track by source.current.collectAsState()
    val liked by source.likedIds.collectAsState()
    val fm by source.isFm.collectAsState()
    val shuffle by source.shuffle.collectAsState()
    val repeat by source.repeatMode.collectAsState()
    val isLiked = track?.id?.let { it in liked } == true
    val size = 3f
    val touch = 7f
    val like: @Composable () -> Unit = {
        IconButton(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, g, size, touch, if (isLiked) accent else Dim, contentDescription = "喜欢") {
            track?.let { source.toggleLike(it.id) }
        }
    }
    val queue: @Composable () -> Unit = {
        IconButton(Icons.AutoMirrored.Rounded.QueueMusic, g, size, touch, Dim, contentDescription = "播放队列", onClick = onQueue)
    }
    return if (fm) {
        listOf(like, { IconButton(Icons.Rounded.ThumbDown, g, size * 0.92f, touch, Dim, contentDescription = "不喜欢") { source.fmTrash() } }, queue)
    } else {
        listOf(
            like,
            { IconButton(Icons.Rounded.Shuffle, g, size, touch, if (shuffle) accent else Dim, contentDescription = "随机播放") { source.toggleShuffle() } },
            {
                IconButton(
                    if (repeat == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, g, size, touch,
                    if (repeat != Player.REPEAT_MODE_OFF) accent else Dim, contentDescription = "循环",
                ) { source.cycleRepeat() }
            },
            queue,
        )
    }
}

// ---------------- 歌词 ----------------

internal fun List<NowPlayingLyric>.activeIndex(positionMs: Long): Int {
    var low = 0
    var high = lastIndex
    var result = -1
    while (low <= high) {
        val mid = (low + high) / 2
        if (this[mid].timeMs <= positionMs) {
            result = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return result
}

/** 提前量：人耳对歌词略早出现更舒服。 */
internal const val LyricLeadMs = 250L

/**
 * 同步滚动歌词。手动滚动后 2.5 秒没有新操作才回到正在唱的那句；点某句跳到那句。
 * @param visible 不可见时不驱动逐帧填色。
 */
@Composable
internal fun LyricsList(
    source: NowPlayingSource,
    clock: PlaybackClock,
    g: Grid,
    modifier: Modifier,
    textSize: Float = 3.2f,
    centered: Boolean = false,
    anchor: Float = 0.34f,
    inactiveScale: Float = 0.88f,
    lineGap: Float = 1f,
    visible: Boolean = true,
) {
    val lines by source.lyrics.collectAsState()
    val active by remember(lines) { derivedStateOf { lines.activeIndex(clock.position.longValue + LyricLeadMs) } }
    val state = rememberLazyListState()
    var manualAt by remember { mutableLongStateOf(0L) }
    var manualTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect {
            if (it is DragInteraction.Start || it is DragInteraction.Stop || it is DragInteraction.Cancel) {
                manualAt = System.currentTimeMillis()
                manualTick++
            }
        }
    }
    // 正在跑的自动跟随数。换句时上一段跟随刚被取消、还没退出，isScrollInProgress 仍为 true，
    // 只看它会把这一句漏掉，等下一句再猛追；是自己的跟随就照样接着滚
    val following = remember { intArrayOf(0) }
    LaunchedEffect(lines) { state.scrollToItem(0) }
    LaunchedEffect(active, manualTick, lines) {
        val wait = 2500 - (System.currentTimeMillis() - manualAt)
        if (wait > 0) delay(wait)
        if (active >= 0 && (!state.isScrollInProgress || following[0] > 0)) {
            following[0]++
            try {
                state.followLine(active)
            } finally {
                following[0]--
            }
        }
    }

    BoxWithConstraints(modifier) {
        if (lines.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                Text("暂无歌词", color = Faint, fontSize = g.sp(textSize), fontWeight = FontWeight.Bold)
            }
            return@BoxWithConstraints
        }
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(top = maxHeight * anchor, bottom = maxHeight),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines) { i, line ->
                LyricRow(
                    line = line,
                    isActive = i == active,
                    edgeAlpha = { state.edgeAlpha(i) },
                    clock = clock,
                    g = g,
                    textSize = textSize,
                    centered = centered,
                    inactiveScale = inactiveScale,
                    lineGap = lineGap,
                    visible = visible,
                ) {
                    manualAt = 0L
                    manualTick++
                    source.seekTo(line.timeMs)
                }
            }
        }
    }
}

/**
 * 上下边缘渐隐：按行中心在视口里的位置整行调透明度。
 * 不用整列表离屏 + DstIn 蒙版，那样滚动时每帧都要把整块区域离屏重画一遍，车机 GPU 吃不消。
 */
private fun LazyListState.edgeAlpha(index: Int): Float {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return 1f
    val h = info.viewportSize.height.toFloat()
    if (h <= 0f) return 1f
    val center = item.offset - info.viewportStartOffset + item.size / 2f
    val edge = h * 0.12f
    return minOf(center / edge, (h - center) / (edge * 1.6f)).coerceIn(0f, 1f)
}

/**
 * 把第 [index] 句平滑地送到锚点。换句时上一句在缩、这一句在放大，行高一直在变，
 * 一次算好终点的 animateScrollToItem 会在最后补一下位置，看着像顿了一下；
 * 这里每帧按实际位置用临界阻尼弹簧追，从静止缓起、缓停，行高怎么变都跟得上，收尾没有跳变。
 */
private suspend fun LazyListState.followLine(index: Int) {
    // 离得远（手动翻走过）先用自带动画带回视野，再接着追
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) animateScrollToItem(index)
    scroll {
        val start = withFrameNanos { it }
        var last = start
        var velocity = 0f
        while (true) {
            val now = withFrameNanos { it }
            // 掉帧时别一步迈太大
            val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
            last = now
            val remaining = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset?.toFloat() ?: return@scroll
            // 行的缩放动画 450ms，过了它且已贴住锚点才收手
            if (abs(remaining) < 0.5f && now - start > LYRIC_SETTLE_NS) {
                scrollBy(remaining)
                return@scroll
            }
            velocity += (LYRIC_FOLLOW_OMEGA * LYRIC_FOLLOW_OMEGA * remaining - 2f * LYRIC_FOLLOW_OMEGA * velocity) * dt
            scrollBy(velocity * dt)
        }
    }
}

/** 弹簧角频率（rad/s），约 0.4s 基本到位。 */
private const val LYRIC_FOLLOW_OMEGA = 14f
private const val LYRIC_SETTLE_NS = 480_000_000L

@Composable
private fun LyricRow(
    line: NowPlayingLyric,
    isActive: Boolean,
    edgeAlpha: () -> Float,
    clock: PlaybackClock,
    g: Grid,
    textSize: Float,
    centered: Boolean,
    inactiveScale: Float,
    lineGap: Float,
    visible: Boolean,
    onClick: () -> Unit,
) {
    val alpha by animateFloatAsState(if (isActive) 1f else 0.34f, tween(450), label = "lyricAlpha")
    val scale by animateFloatAsState(if (isActive) 1f else inactiveScale, tween(450), label = "lyricScale")
    val words = line.words
    val karaoke = isActive && words.isNotEmpty()
    if (karaoke && visible) {
        DisposableEffect(clock) {
            clock.frameClients++
            onDispose { clock.frameClients-- }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        Modifier
            .fillMaxWidth()
            // 行高跟着缩放走，小字行之间不留空洞；scale 只在布局阶段读，不触发重组
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val h = (p.height * scale).roundToInt()
                layout(p.width, h) { p.place(0, (h - p.height) / 2) }
            }
            .graphicsLayer {
                this.alpha = alpha * edgeAlpha()
                // 文字互不重叠，逐个绘制乘透明度即可，免得每个半透明行都开一块离屏缓冲
                compositingStrategy = CompositingStrategy.ModulateAlpha
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(if (centered) 0.5f else 0f, 0.5f)
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = g(lineGap)),
    ) {
        Text(
            text = if (words.isNotEmpty()) words.joinToString("") { it.text } else line.text,
            color = Color.White,
            fontSize = g.sp(textSize),
            lineHeight = g.sp(textSize * 1.38f),
            fontWeight = FontWeight.Bold,
            textAlign = align,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (karaoke) {
                        Modifier
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                val l = layout ?: return@drawWithContent
                                val lit = litCharacters(words, clock.position.longValue + LyricLeadMs)
                                for (row in 0 until l.lineCount) {
                                    val start = l.getLineStart(row)
                                    val end = l.getLineEnd(row, visibleEnd = true)
                                    val left = l.getLineLeft(row)
                                    val right = l.getLineRight(row)
                                    val x = when {
                                        lit >= end -> right
                                        lit <= start -> left
                                        else -> {
                                            val i = floor(lit).toInt()
                                            val x0 = l.getHorizontalPosition(i, usePrimaryDirection = true)
                                            val x1 = if (i + 1 < end) l.getHorizontalPosition(i + 1, usePrimaryDirection = true) else right
                                            x0 + (x1 - x0) * (lit - i)
                                        }
                                    }
                                    if (x < right) {
                                        drawRect(
                                            Color.White.copy(alpha = 0.36f),
                                            topLeft = Offset(x, l.getLineTop(row)),
                                            size = Size(right - x, l.getLineBottom(row) - l.getLineTop(row)),
                                            blendMode = BlendMode.SrcIn,
                                        )
                                    }
                                }
                            }
                    } else {
                        Modifier
                    },
                ),
        )
        line.translation?.let {
            Text(
                it, color = Color.White.copy(alpha = 0.72f), fontSize = g.sp(textSize * 0.6f), lineHeight = g.sp(textSize * 0.82f),
                fontWeight = FontWeight.Medium, textAlign = align, modifier = Modifier.fillMaxWidth().padding(top = g(0.3f)),
            )
        }
    }
}

/** 已唱到的字符数（UTF-16），含正在唱的那个字的小数部分。 */
internal fun litCharacters(words: List<LyricWord>, positionMs: Long): Float {
    var lit = 0f
    for (w in words) {
        val p = when {
            positionMs <= w.startMs -> return lit
            positionMs >= w.endMs || w.endMs <= w.startMs -> 1f
            else -> (positionMs - w.startMs).toFloat() / (w.endMs - w.startMs)
        }
        lit += w.text.length * p
        if (p < 1f) return lit
    }
    return lit
}

// ---------------- 播放队列 ----------------

@Composable
internal fun QueuePanel(source: NowPlayingSource, accent: Color, g: Grid, width: Dp, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val queue by source.queue.collectAsState()
    val current by source.current.collectAsState()
    val currentPos = queue.indexOfFirst { it.track.id == current?.id }.coerceAtLeast(0)
    // 整张队列都列出来：放过的留在上面变淡、点了能回去；打开时停在正在放的那首，前面露一首。
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentPos - 1).coerceAtLeast(0))
    LaunchedEffect(currentPos) {
        if (!listState.isScrollInProgress) listState.animateScrollToItem((currentPos - 1).coerceAtLeast(0))
    }
    Column(
        modifier
            .width(width)
            .fillMaxSize()
            .background(Color(0xF2141416))
            .padding(horizontal = g(2.4f), vertical = g(2.6f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("播放队列", color = Color.White, fontSize = g.sp(3.1f), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(Icons.Rounded.Close, g, 3.4f, 6.2f, contentDescription = "关闭", onClick = onClose)
        }
        Spacer(Modifier.height(g(1f)))
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(queue, key = { _, it -> it.index }) { i, item ->
                val playing = i == currentPos
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(g(8.8f))
                        .alpha(if (i < currentPos) 0.45f else 1f)
                        .background(if (playing) Color.White.copy(alpha = 0.1f) else Color.Transparent, RoundedCornerShape(g(1.4f)))
                        .pressable { source.jumpTo(item.index) }
                        .padding(horizontal = g(1f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Cover(source, item.track.coverUrl, g(6.4f), g(0.9f), px = 160)
                    Spacer(Modifier.width(g(1.6f)))
                    Column(Modifier.weight(1f)) {
                        Text(item.track.title, color = if (playing) accent else Color.White, fontSize = g.sp(2.3f), fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.track.artists, color = Faint, fontSize = g.sp(1.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (playing) Icon(Icons.Rounded.GraphicEq, null, tint = accent, modifier = Modifier.size(g(2.9f)))
                }
            }
        }
    }
}
