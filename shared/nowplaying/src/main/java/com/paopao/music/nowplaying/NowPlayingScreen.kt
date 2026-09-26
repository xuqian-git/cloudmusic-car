package com.paopao.music.nowplaying

import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.zIndex
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val DefaultTop = Color(0xFF3A3A40)
private val DefaultBottom = Color(0xFF111114)

/** 竖屏：宽 < 高；横屏：宽高比 1 – 1.55；超宽屏：≥ 1.55。 */
private const val UltraWideRatio = 1.55f

/**
 * 播放页（方案 A 沉浸卡片）。
 *
 * - 封面上下滑切歌：上滑下一首、下滑上一首（三种尺寸一致）。
 * - 竖屏左滑进歌词页、右滑回封面；横屏和超宽屏封面与歌词同屏。
 */
@Composable
fun NowPlayingScreen(source: NowPlayingSource, accent: Color, onClose: () -> Unit) {
    val track by source.current.collectAsState()
    var showQueue by remember { mutableStateOf(false) }
    val clock = rememberPlaybackClock(source)
    val (top, bottom) = rememberCoverColors(source, track?.coverUrl)

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to top, 0.88f to bottom))
            .pointerInput(Unit) { detectTapGestures { } } // 拦截点击，避免穿透到下层
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val t = track
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val g = Grid(maxHeight / 100f)
            val ratio = maxWidth / maxHeight
            if (t == null) {
                Text("没有正在播放的歌曲", color = Faint, fontSize = g.sp(2.6f), modifier = Modifier.align(Alignment.Center))
            } else {
                val switcher = rememberTrackSwitcher(source, t)
                when {
                    ratio < 1f -> PortraitLayout(source, accent, clock, switcher, g, maxWidth) { showQueue = true }
                    ratio < UltraWideRatio -> LandscapeLayout(source, accent, clock, switcher, g, maxWidth) { showQueue = true }
                    else -> UltraWideLayout(source, accent, clock, switcher, g, maxWidth) { showQueue = true }
                }
            }
            CloseButton(g, onClose)

            AnimatedVisibility(visible = showQueue, enter = fadeIn(), exit = fadeOut()) {
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
                val panelWidth = minOf(maxWidth * 0.8f, g(if (ratio < 1f) 53f else 58f))
                QueuePanel(source, accent, g, panelWidth, onClose = { showQueue = false })
            }
        }
    }
}

// ---------------- 切歌 ----------------

/**
 * 上下切歌的状态。progress ∈ [-1, 1]：负数朝下一首推进，正数朝上一首推进。
 * 只在 graphicsLayer / 手势回调里读 progress，拖动时不重组。
 */
@Stable
internal class TrackSwitcher(
    private val source: NowPlayingSource,
    private val scope: CoroutineScope,
) {
    val progress = mutableFloatStateOf(0f)
    var previous by mutableStateOf<NowPlayingTrack?>(null)
        internal set
    var next by mutableStateOf<NowPlayingTrack?>(null)
        internal set
    internal var previousIndex = -1
    internal var anchorId: Long = Long.MIN_VALUE
    private var animating = false
    /** 手指拖动的参考距离（封面区高度）。 */
    var extentPx = 1f

    val canPrevious get() = previous != null

    fun dragBy(deltaPx: Float) {
        if (animating) return
        var p = progress.floatValue + deltaPx / extentPx
        // 没有上一首 / 下一首时只给一点阻尼
        if (p > 0f && previous == null) p = p.coerceAtMost(0.12f)
        if (p < 0f && next == null) p = p.coerceAtLeast(-0.12f)
        progress.floatValue = p.coerceIn(-1f, 1f)
    }

    fun release(velocityPx: Float) {
        if (animating) return
        val p = progress.floatValue
        val fling = extentPx * 1.2f // 约 1.2 个封面高度 / 秒
        val target = when {
            (p < -0.16f || velocityPx < -fling) && next != null -> -1f
            (p > 0.16f || velocityPx > fling) && previous != null -> 1f
            else -> 0f
        }
        settle(target)
    }

    fun skipNext() {
        if (next == null) source.next() else settle(-1f)
    }

    fun skipPrevious() {
        // 按钮语义与原来一致：唱了 3 秒以上先回到开头
        if (previous == null || source.positionMs() > 3000) source.previous() else settle(1f)
    }

    private fun settle(target: Float) {
        animating = true
        scope.launch {
            try {
                animate(progress.floatValue, target, animationSpec = tween(280)) { v, _ -> progress.floatValue = v }
                when {
                    // 下一首走播放器自己的逻辑（私人 FM 会顺带补货）
                    target < 0f -> source.next()
                    target > 0f -> if (previousIndex >= 0) source.jumpTo(previousIndex) else source.previous()
                }
            } finally {
                animating = false
            }
        }
    }
}

@Composable
private fun rememberTrackSwitcher(source: NowPlayingSource, current: NowPlayingTrack): TrackSwitcher {
    val scope = rememberCoroutineScope()
    val switcher = remember(source) { TrackSwitcher(source, scope) }
    val queue by source.queue.collectAsState()
    val fm by source.isFm.collectAsState()
    val repeat by source.repeatMode.collectAsState()
    val pos = queue.indexOfFirst { it.track.id == current.id }
    val wrap = repeat == Player.REPEAT_MODE_ALL && queue.size > 1
    val prev = when {
        fm || pos < 0 -> null
        pos > 0 -> queue[pos - 1]
        wrap -> queue.last()
        else -> null
    }
    val next = when {
        pos < 0 -> null
        pos < queue.lastIndex -> queue[pos + 1]
        wrap -> queue.first()
        else -> null
    }
    switcher.previous = prev?.track
    switcher.previousIndex = prev?.index ?: -1
    switcher.next = next?.track
    // 换歌后在同一帧把舞台归位：新的“当前”正好停在原来“下一首”的位置，看不出跳变
    if (switcher.anchorId != current.id) {
        switcher.anchorId = current.id
        switcher.progress.floatValue = 0f
    }
    return switcher
}

/** 三张卡片叠放：上滑时当前卡片被推走、下一张从后面放大浮上来；下滑时上一张从顶上盖下来。 */
@Composable
private fun CardStage(
    switcher: TrackSwitcher,
    current: NowPlayingTrack,
    modifier: Modifier,
    content: @Composable (NowPlayingTrack, isCurrent: Boolean) -> Unit,
) {
    Box(
        modifier
            .clipToBounds()
            .onSizeChanged { switcher.extentPx = it.height.coerceAtLeast(1).toFloat() },
    ) {
        switcher.next?.let { t ->
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(0f)
                    .graphicsLayer {
                        val a = (-switcher.progress.floatValue).coerceAtLeast(0f)
                        val s = 0.86f + 0.14f * a
                        scaleX = s
                        scaleY = s
                        alpha = if (a > 0f) (1.6f * a - 0.4f).coerceIn(0f, 1f) else 0f
                    },
            ) { content(t, false) }
        }
        Box(
            Modifier
                .fillMaxSize()
                .zIndex(1f)
                .graphicsLayer {
                    val p = switcher.progress.floatValue
                    if (p <= 0f) {
                        translationY = p * size.height
                        alpha = 1f + 0.5f * p
                    } else {
                        val s = 1f - 0.12f * p
                        scaleX = s
                        scaleY = s
                        // 被上一首盖住的部分要尽快淡掉，免得两层标题叠在一起
                        alpha = (1f - 3f * p).coerceAtLeast(0f)
                    }
                },
        ) { content(current, true) }
        switcher.previous?.let { t ->
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .graphicsLayer {
                        val a = switcher.progress.floatValue.coerceAtLeast(0f)
                        translationY = (a - 1f) * size.height
                        alpha = if (a > 0f) 1f else 0f
                    },
            ) { content(t, false) }
        }
    }
}

private fun Modifier.switchGesture(switcher: TrackSwitcher): Modifier = axisDrag(
    key = switcher,
    allowX = false,
    allowY = true,
    onStart = {},
    onDrag = { _, d -> switcher.dragBy(d) },
    onEnd = { _, v -> switcher.release(v.y) },
)

// ---------------- 竖屏 ----------------

@Composable
private fun PortraitLayout(
    source: NowPlayingSource,
    accent: Color,
    clock: PlaybackClock,
    switcher: TrackSwitcher,
    g: Grid,
    width: Dp,
    onQueue: () -> Unit,
) {
    val track = source.current.collectAsState().value ?: return
    val fm by source.isFm.collectAsState()
    val scope = rememberCoroutineScope()
    // 0 = 封面页，1 = 歌词页
    val page = remember { mutableFloatStateOf(0f) }
    var target by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(1f) }
    val lyricsVisible by remember { derivedStateOf { page.floatValue > 0.01f } }
    val cw = minOf(width - g(12f), g(40f))

    fun settlePage(to: Float, velocity: Float = 0f) {
        target = to
        scope.launch {
            animate(page.floatValue, to, initialVelocity = velocity, animationSpec = tween(380)) { v, _ -> page.floatValue = v }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1).toFloat() }
            .axisDrag(
                key = switcher,
                allowX = true,
                allowY = true,
                onStart = {},
                onDrag = { axis, d ->
                    if (axis == Axis.X) {
                        val step = -d / widthPx
                        val p = page.floatValue
                        // 两端外拉只给阻尼
                        val overscroll = (p <= 0f && step < 0f) || (p >= 1f && step > 0f)
                        page.floatValue = (p + if (overscroll) step * 0.25f else step).coerceIn(-0.06f, 1.06f)
                    } else if (target == 0f) {
                        switcher.dragBy(d)
                    }
                },
                onEnd = { axis, v -> onPortraitRelease(axis, v, page.floatValue, widthPx, target, switcher) { to, vel -> settlePage(to, vel) } },
            ),
    ) {
        // 翻页区：封面页 / 歌词页
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = g(10f))
                .height(g(62f))
                .clipToBounds(),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = -page.floatValue * size.width }
                    // 除了左滑，点封面页也进歌词页
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (target == 0f) settlePage(1f)
                    },
            ) {
                CardStage(switcher, track, Modifier.fillMaxSize()) { t, isCurrent ->
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(g(2f)))
                        Cover(source, t.coverUrl, cw, g(1.8f), elevation = g(3f))
                        if (isCurrent) LyricTeaser(source, clock, accent, g, Modifier.width(cw).padding(top = g(2.4f)))
                        Spacer(Modifier.weight(1f))
                        // 歌名贴着进度条，和它左对齐
                        TitleBlock(t, g, modifier = Modifier.fillMaxWidth().padding(horizontal = g(6f)))
                    }
                }
            }
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = (1f - page.floatValue) * size.width }) {
                Row(Modifier.padding(horizontal = g(6f)), verticalAlignment = Alignment.CenterVertically) {
                    Cover(source, track.coverUrl, g(6.4f), g(0.9f), px = 200)
                    Spacer(Modifier.width(g(1.8f)))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = Color.White, fontSize = g.sp(2.5f), fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artists, color = Dim, fontSize = g.sp(1.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                LyricsList(
                    source, clock, g,
                    Modifier.fillMaxSize().padding(start = g(6f), end = g(6f), top = g(9f)),
                    textSize = 4.0f,
                    inactiveScale = 0.7f,
                    lineGap = 1.6f,
                    visible = lyricsVisible,
                )
            }
        }

        // 两页之间的圆点，可点
        PageDots(g, page, Modifier.align(Alignment.TopCenter).padding(top = g(4.4f))) { settlePage(it) }

        // 底部控制区：两页共用
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = g(6f))
                .padding(bottom = g(2.6f)),
            verticalArrangement = Arrangement.spacedBy(g(1.2f)),
        ) {
            ProgressBar(source, clock, g, inline = false, modifier = Modifier.fillMaxWidth())
            MainControls(
                source, g, switcher.canPrevious || !fm,
                onPrevious = { switcher.skipPrevious() }, onNext = { switcher.skipNext() },
                modifier = Modifier.align(Alignment.CenterHorizontally), gap = 3f,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                secondaryButtons(source, accent, g, onQueue).forEach { it() }
            }
        }
    }
}

private fun onPortraitRelease(
    axis: Axis,
    v: Velocity,
    page: Float,
    widthPx: Float,
    current: Float,
    switcher: TrackSwitcher,
    settlePage: (Float, Float) -> Unit,
) {
    if (axis == Axis.X) {
        val fling = widthPx * 0.9f
        val to = when {
            current == 0f && (page > 0.18f || v.x < -fling) -> 1f
            current == 1f && (page < 0.82f || v.x > fling) -> 0f
            else -> current
        }
        settlePage(to, -v.x / widthPx)
    } else if (current == 0f) {
        switcher.release(v.y)
    }
}

@Composable
private fun PageDots(g: Grid, page: androidx.compose.runtime.FloatState, modifier: Modifier, onSelect: (Float) -> Unit) {
    val onLyrics by remember { derivedStateOf { page.floatValue > 0.5f } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(g(0.4f))) {
        listOf(false, true).forEach { lyrics ->
            val on = lyrics == onLyrics
            Box(
                Modifier
                    .size(width = g(3.6f), height = g(3f))
                    .pointerInput(lyrics) { detectTapGestures { onSelect(if (lyrics) 1f else 0f) } },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = if (on) g(2.6f) else g(0.9f), height = g(0.9f))
                        .clip(CircleShape)
                        .background(if (on) Color.White else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }
}

/** 封面下露出正在唱的一句，提示左滑还有歌词页。 */
@Composable
private fun LyricTeaser(source: NowPlayingSource, clock: PlaybackClock, accent: Color, g: Grid, modifier: Modifier) {
    val lines by source.lyrics.collectAsState()
    if (lines.isEmpty()) return
    val active by remember(lines) { derivedStateOf { lines.activeIndex(clock.position.longValue + LyricLeadMs) } }
    val text = lines.getOrNull(active)?.text ?: "前奏"
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("♪ ", color = accent, fontSize = g.sp(2.2f))
        Text(text, color = Color.White.copy(alpha = 0.72f), fontSize = g.sp(2.2f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------- 横屏 ----------------

@Composable
private fun LandscapeLayout(
    source: NowPlayingSource,
    accent: Color,
    clock: PlaybackClock,
    switcher: TrackSwitcher,
    g: Grid,
    width: Dp,
    onQueue: () -> Unit,
) {
    val track = source.current.collectAsState().value ?: return
    val fm by source.isFm.collectAsState()
    val cover = minOf(g(46f), width * 0.4f)
    val lyricsStart = g(6f) + cover + g(8f)

    Box(Modifier.fillMaxSize()) {
        // 舞台比封面四周多留 3u 给阴影
        CardStage(
            switcher, track,
            Modifier
                .padding(start = g(3f), top = g(9f))
                .size(cover + g(6f))
                .switchGesture(switcher),
        ) { t, _ ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Cover(source, t.coverUrl, cover, g(1.8f), elevation = g(2.6f))
            }
        }
        TitleBlock(
            track, g,
            modifier = Modifier.padding(start = g(6f), top = g(12f) + cover + g(3f)).width(cover),
        )
        LyricsList(
            source, clock, g,
            Modifier.padding(start = lyricsStart, end = g(6f), top = g(6f), bottom = g(26f)).fillMaxSize(),
            anchor = 0.36f,
            textSize = 4.25f,
            inactiveScale = 0.7f,
            lineGap = 1.6f,
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = g(6f))
                .padding(bottom = g(2.4f)),
            verticalArrangement = Arrangement.spacedBy(g(0.6f)),
        ) {
            ProgressBar(source, clock, g, inline = true, modifier = Modifier.fillMaxWidth())
            val buttons = secondaryButtons(source, accent, g, onQueue)
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.CenterStart)) { buttons.first()() }
                MainControls(
                    source, g, switcher.canPrevious || !fm,
                    onPrevious = { switcher.skipPrevious() }, onNext = { switcher.skipNext() },
                    modifier = Modifier.align(Alignment.Center), playSize = 7.6f, skipSize = 5f, gap = 3.6f,
                )
                Row(Modifier.align(Alignment.CenterEnd)) { buttons.drop(1).forEach { it() } }
            }
        }
    }
}

// ---------------- 超宽屏 ----------------

@Composable
private fun UltraWideLayout(
    source: NowPlayingSource,
    accent: Color,
    clock: PlaybackClock,
    switcher: TrackSwitcher,
    g: Grid,
    width: Dp,
    onQueue: () -> Unit,
) {
    val track = source.current.collectAsState().value ?: return
    val fm by source.isFm.collectAsState()
    val cover = minOf(g(64f), width * 0.34f)
    val stageWidth = cover + g(6f)
    val rightStart = g(3f) + stageWidth + g(6f)

    Box(Modifier.fillMaxSize()) {
        // 歌名跟封面一起放在卡片里，切歌时一起动
        CardStage(
            switcher, track,
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = g(3f))
                .size(stageWidth, cover + g(20f))
                .switchGesture(switcher),
        ) { t, _ ->
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Cover(source, t.coverUrl, cover, g(1.8f), elevation = g(2.6f))
                Spacer(Modifier.height(g(3.4f)))
                TitleBlock(t, g, titleSize = 4.4f, align = TextAlign.Center, metaScale = 1.2f, modifier = Modifier.width(cover))
            }
        }
        Column(Modifier.fillMaxHeight().padding(start = rightStart, end = g(6f))) {
            // 当前句大、其余小，行距拉开：屏上约 6 句
            LyricsList(
                source, clock, g,
                Modifier.weight(1f).fillMaxWidth().padding(top = g(6f), bottom = g(3f)),
                textSize = 6.3f,
                centered = true,
                anchor = 0.4f,
                inactiveScale = 0.68f,
                lineGap = 3f,
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = g(4f)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(g(3f)),
            ) {
                ProgressBar(source, clock, g, inline = true, modifier = Modifier.weight(1f))
                MainControls(
                    source, g, switcher.canPrevious || !fm,
                    onPrevious = { switcher.skipPrevious() }, onNext = { switcher.skipNext() },
                    playSize = 7.6f, skipSize = 5f, gap = 2.4f,
                )
                Row { secondaryButtons(source, accent, g, onQueue).forEach { it() } }
            }
        }
    }
}

// ---------------- 背景 ----------------

/** 从封面取色做背景（全版本可用，无需实时模糊）。 */
@Composable
private fun rememberCoverColors(source: NowPlayingSource, cover: String?): Pair<Color, Color> {
    val context = LocalContext.current
    var top by remember { mutableStateOf(DefaultTop) }
    var bottom by remember { mutableStateOf(DefaultBottom) }
    LaunchedEffect(cover) {
        if (cover == null) return@LaunchedEffect
        val request = ImageRequest.Builder(context).data(source.artworkUrl(cover, 96)).allowHardware(false).build()
        val bmp = (source.imageLoader(context).execute(request).drawable as? BitmapDrawable)?.bitmap ?: return@LaunchedEffect
        val palette = Palette.from(bmp).generate()
        top = Color(palette.getVibrantColor(palette.getDominantColor(DefaultTop.toArgbCompat()))).darken(0.62f)
        bottom = Color(palette.getDarkMutedColor(palette.getDarkVibrantColor(DefaultBottom.toArgbCompat()))).darken(0.5f)
    }
    val topAnim by animateColorAsState(top, tween(800), label = "top")
    val bottomAnim by animateColorAsState(bottom, tween(800), label = "bottom")
    return topAnim to bottomAnim
}

private fun Color.darken(factor: Float) = Color(red * factor, green * factor, blue * factor, 1f)

private fun Color.toArgbCompat(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
