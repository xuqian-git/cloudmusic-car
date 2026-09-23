package com.qqmusic.car.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.qqmusic.car.api.Playlist
import com.qqmusic.car.api.Track
import com.qqmusic.car.api.formatDuration
import com.qqmusic.car.api.sized
import com.qqmusic.car.data.MusicCache
import com.qqmusic.car.ui.theme.K

// ---------- 基础 ----------

/** 点击时轻微缩小的反馈（苹果式）。 */
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "press")
    this
        .scale(scale)
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

@Composable
fun Artwork(url: String?, size: Dp, modifier: Modifier = Modifier, corner: Dp = 12.dp, px: Int = 400) {
    val c = K.colors
    val context = LocalContext.current
    Box(
        modifier
            .size(size)
            .background(c.fill, RoundedCornerShape(corner))
            .clip(RoundedCornerShape(corner)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = c.secondary, modifier = Modifier.size(size * 0.4f))
        if (url != null) {
            AsyncImage(
                model = url.sized(px),
                contentDescription = null,
                imageLoader = MusicCache.imageLoader(context),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = K.colors.label,
        fontSize = 52.sp,
        fontWeight = FontWeight.ExtraBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun ShelfHeader(text: String, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(bottom = 16.dp)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier),
    ) {
        Text(text, color = K.colors.label, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        if (onClick != null) {
            Icon(Icons.Rounded.ChevronRight, null, tint = K.colors.secondary, modifier = Modifier.size(34.dp))
        }
    }
}

/** 半透明浮层（低版本 Android 不支持实时模糊，用高不透明度 + 阴影模拟玻璃）。 */
fun Modifier.glass(shape: Shape, fill: Color, border: Color): Modifier =
    this
        .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.25f))
        .clip(shape)
        .background(fill)
        .border(1.dp, border, shape)

@Composable
fun BigIcon(
    icon: ImageVector,
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = K.colors.label,
    touch: Dp = size + 28.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(touch)
            .pressable(enabled, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size))
    }
}

@Composable
fun PillButton(text: String, icon: ImageVector?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = K.colors
    Row(
        modifier
            .height(72.dp)
            .background(c.fill, RoundedCornerShape(18.dp))
            .pressable(onClick = onClick)
            .padding(horizontal = 34.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
        }
        Text(text, color = c.accent, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- 列表 ----------

@Composable
fun TrackRow(
    track: Track,
    index: Int?,
    playing: Boolean,
    unplayableReason: String?,
    onClick: () -> Unit,
) {
    val c = K.colors
    val alpha = if (unplayableReason != null) 0.38f else 1f
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(112.dp)
                .pressable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index != null) {
                Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterStart) {
                    if (playing) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = c.accent, modifier = Modifier.size(30.dp))
                    } else {
                        Text("$index", color = c.secondary.copy(alpha = c.secondary.alpha * alpha), fontSize = 22.sp)
                    }
                }
            }
            Artwork(track.album.picUrl, 80.dp, Modifier.alpha(alpha), corner = 10.dp, px = 160)
            Spacer(Modifier.width(22.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.name,
                    color = (if (playing) c.accent else c.label).copy(alpha = alpha),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(unplayableReason, track.artistNames).joinToString(" · "),
                    color = c.secondary.copy(alpha = c.secondary.alpha * alpha),
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Text(formatDuration(track.durationMs), color = c.secondary, fontSize = 22.sp)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = if (index != null) 158.dp else 102.dp)
                .height(1.dp)
                .background(c.separator),
        )
    }
}

@Composable
fun PlaylistTile(playlist: Playlist, size: Dp, onClick: () -> Unit) {
    val c = K.colors
    Column(Modifier.width(size).pressable(onClick = onClick)) {
        Artwork(playlist.coverUrl, size, corner = 14.dp, px = 400)
        Text(
            playlist.name,
            color = c.label,
            fontSize = 23.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            lineHeight = 29.sp,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 12.dp),
        )
        val sub = playlist.subtitle?.takeIf { it.length <= 24 }
            ?: if (playlist.trackCount > 0) "${playlist.trackCount} 首" else null
        if (sub != null) {
            Text(sub, color = c.secondary, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- 状态 ----------

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = K.colors.accent, strokeWidth = 4.dp, modifier = Modifier.size(56.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, color = K.colors.secondary, fontSize = 26.sp)
        Spacer(Modifier.height(24.dp))
        PillButton("重试", null, onClick = onRetry)
    }
}

@Composable
fun EmptyBox(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = K.colors.secondary, fontSize = 26.sp)
    }
}

// ---------- 加载 ----------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val message: String) : Load<Nothing>
}

/** 进程内缓存：切换页面时先显示上次结果，后台静默刷新。 */
object MemoryCache {
    val map = mutableMapOf<String, Any?>()
}

class Loader<T>(val state: Load<T>, val reload: () -> Unit)

@Suppress("UNCHECKED_CAST")
@Composable
fun <T> rememberLoad(key: String, block: suspend () -> T): Loader<T> {
    var state by remember(key) {
        mutableStateOf<Load<T>>(
            if (MemoryCache.map.containsKey(key)) Load.Ok(MemoryCache.map[key] as T) else Load.Loading,
        )
    }
    var attempt by remember(key) { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        if (state !is Load.Ok) state = Load.Loading
        runCatching { block() }
            .onSuccess {
                MemoryCache.map[key] = it
                state = Load.Ok(it)
            }
            .onFailure { if (state !is Load.Ok) state = Load.Err(it.message ?: "加载失败") }
    }
    return Loader(state) { attempt++ }
}

@Composable
fun <T> LoadContent(loader: Loader<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = loader.state) {
        Load.Loading -> LoadingBox(modifier)
        is Load.Err -> ErrorBox(s.message, loader.reload, modifier)
        is Load.Ok -> content(s.value)
    }
}

@Composable
fun Label(text: String, size: TextUnit, color: Color = K.colors.label, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(text, color = color, fontSize = size, fontWeight = weight, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}
