package com.cloudmusic.car.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.cloudmusic.car.api.NeteaseApi
import com.cloudmusic.car.api.sized
import com.cloudmusic.car.data.AccountStore
import com.cloudmusic.car.data.MusicCache
import com.cloudmusic.car.player.PlayerHub
import com.cloudmusic.car.ui.Nav
import com.cloudmusic.car.ui.Route
import com.cloudmusic.car.ui.components.LargeTitle
import com.cloudmusic.car.ui.components.LoadContent
import com.cloudmusic.car.ui.components.PlaylistTile
import com.cloudmusic.car.ui.components.ShelfHeader
import com.cloudmusic.car.ui.components.pressable
import com.cloudmusic.car.ui.components.rememberLoad
import com.cloudmusic.car.ui.pagePadding
import com.cloudmusic.car.ui.theme.K
import com.cloudmusic.car.ui.theme.LocalLandscape
import java.util.Calendar

@Composable
fun HomeScreen(nav: Nav) {
    val landscape = LocalLandscape.current
    val context = LocalContext.current
    val profile by AccountStore.profile.collectAsState()
    val pad = pagePadding()
    val recommend = rememberLoad("home.recommend") {
        runCatching { NeteaseApi.recommendResource() }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: NeteaseApi.personalizedPlaylists()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(pad),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LargeTitle("主页", Modifier.weight(1f))
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(K.colors.fill)
                    .pressable { nav.select(Route.Settings) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Person, null, tint = K.colors.secondary, modifier = Modifier.size(36.dp))
                profile?.avatarUrl?.let {
                    AsyncImage(
                        it.sized(128),
                        null,
                        imageLoader = MusicCache.imageLoader(context),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(22.dp))

        // 精选大卡
        val day = Calendar.getInstance()
        val month = "${day.get(Calendar.MONTH) + 1} 月"
        val cardHeight = if (landscape) 244.dp else 360.dp
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxWidth()) {
            PickCard(
                Modifier.weight(1f), cardHeight,
                colors = listOf(Color(0xFFFF6A3D), Color(0xFFD4145A)), footer = Color(0xFFB8324A),
                caption = "为你推荐", title = "每日推荐",
                big = { DayBadge(day.get(Calendar.DAY_OF_MONTH), month, landscape) },
            ) { nav.open(Route.Daily) }
            PickCard(
                Modifier.weight(1f), cardHeight,
                colors = listOf(Color(0xFF8E2DE2), Color(0xFF2A0845)), footer = Color(0xFF4B1F78),
                caption = "无限漫游", title = "私人 FM",
                big = { CardIcon(Icons.Rounded.Radio, landscape) },
            ) {
                PlayerHub.startFm()
                nav.nowPlayingOpen = true
            }
            if (landscape) {
                PickCard(
                    Modifier.weight(1f), cardHeight,
                    colors = listOf(Color(0xFF1D4350), Color(0xFFA43931)), footer = Color(0xFF6A2B30),
                    caption = "根据红心推荐", title = "心动模式",
                    big = { CardIcon(Icons.Rounded.Favorite, true) },
                ) {
                    PlayerHub.startHeartbeat()
                    nav.nowPlayingOpen = true
                }
            }
        }
        if (!landscape) {
            Spacer(Modifier.height(24.dp))
            PickCard(
                Modifier.fillMaxWidth(), 170.dp,
                colors = listOf(Color(0xFF1D4350), Color(0xFFA43931)), footer = Color(0xFF6A2B30),
                caption = "根据红心推荐", title = "心动模式",
                big = null,
            ) {
                PlayerHub.startHeartbeat()
                nav.nowPlayingOpen = true
            }
        }

        Spacer(Modifier.height(34.dp))
        ShelfHeader("推荐歌单")
        LoadContent(recommend, Modifier.height(260.dp)) { list ->
            val tile = if (landscape) 180.dp else 218.dp
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(end = 24.dp),
            ) {
                items(list, key = { it.id }) { p ->
                    PlaylistTile(p, tile) { nav.open(Route.PlaylistPage(p.id, p.name, p.coverUrl)) }
                }
            }
        }
    }
}

@Composable
private fun PickCard(
    modifier: Modifier,
    height: Dp,
    colors: List<Color>,
    footer: Color,
    caption: String,
    title: String,
    big: (@Composable () -> Unit)?,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .pressable(onClick = onClick),
    ) {
        if (big != null) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Brush.linearGradient(colors))
                    .padding(20.dp),
                contentAlignment = Alignment.BottomStart,
            ) { big() }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (big == null) Modifier.weight(1f).background(Brush.linearGradient(colors)) else Modifier.background(footer))
                .padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(caption, color = Color.White.copy(alpha = 0.8f), fontSize = 20.sp)
            Text(title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DayBadge(day: Int, month: String, landscape: Boolean) {
    Column {
        Text(month, color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text("$day", color = Color.White, fontSize = if (landscape) 64.sp else 88.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 70.sp)
    }
}

@Composable
private fun CardIcon(icon: ImageVector, landscape: Boolean) {
    Icon(icon, null, tint = Color.White, modifier = Modifier.size(if (landscape) 64.dp else 80.dp))
}
