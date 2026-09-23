package com.kugoumusic.car.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.Playlist
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.ui.Nav
import com.kugoumusic.car.ui.Route
import com.kugoumusic.car.ui.components.EmptyBox
import com.kugoumusic.car.ui.components.Label
import com.kugoumusic.car.ui.components.LargeTitle
import com.kugoumusic.car.ui.components.LoadContent
import com.kugoumusic.car.ui.components.PlaylistTile
import com.kugoumusic.car.ui.components.ShelfHeader
import com.kugoumusic.car.ui.components.pressable
import com.kugoumusic.car.ui.components.rememberLoad
import com.kugoumusic.car.ui.pagePadding
import com.kugoumusic.car.ui.theme.K
import com.kugoumusic.car.ui.theme.LocalLandscape

private fun tileSize(landscape: Boolean) = if (landscape) 200.dp else 218.dp

@Composable
fun ToplistsScreen(nav: Nav) {
    val loader = rememberLoad("toplists") { KuGouMusicApi.toplists() }
    Column(Modifier.fillMaxSize()) {
        LoadContent(loader) { lists ->
            PlaylistGrid(nav) {
                item(span = { GridItemSpan(maxLineSpan) }) { LargeTitle("排行榜", Modifier.padding(bottom = 26.dp)) }
                playlistItems(lists, nav)
            }
        }
    }
}

@Composable
fun MyPlaylistsScreen(nav: Nav) {
    val playlists by AccountStore.playlists.collectAsState()
    val profile by AccountStore.profile.collectAsState()
    val landscape = LocalLandscape.current
    val mine = playlists.filter { it.creatorId == profile?.userId && !it.isLikedSongs }
    val subscribed = playlists.filter { it.creatorId != profile?.userId }
    PlaylistGrid(nav) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            LargeTitle(if (landscape) "我的歌单" else "资料库", Modifier.padding(bottom = 22.dp))
        }
        if (!landscape) {
            // 竖屏没有侧边栏，把资料库入口放在这里
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(bottom = 30.dp)) {
                    LibraryRow(Icons.Rounded.Favorite, "我喜欢的音乐") { nav.open(Route.Liked) }
                    LibraryRow(Icons.Rounded.Schedule, "最近播放") { nav.open(Route.Recent) }
                    LibraryRow(Icons.Rounded.Cloud, "音乐云盘") { nav.open(Route.Cloud) }
                    LibraryRow(Icons.Rounded.Settings, "设置") { nav.open(Route.Settings) }
                }
            }
        }
        if (playlists.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("正在加载…", Modifier.height(300.dp)) }
        }
        if (mine.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { ShelfHeader("创建的歌单") }
            playlistItems(mine, nav)
        }
        if (subscribed.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.padding(top = 20.dp)) { ShelfHeader("收藏的歌单") }
            }
            playlistItems(subscribed, nav)
        }
    }
}

@Composable
private fun LibraryRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    val c = K.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(88.dp)
                .pressable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = c.accent, modifier = Modifier.size(38.dp))
            Spacer(Modifier.width(22.dp))
            Label(text, 30.sp, c.label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ChevronRight, null, tint = c.secondary, modifier = Modifier.size(34.dp))
        }
        Box(Modifier.fillMaxWidth().padding(start = 60.dp).height(1.dp).background(c.separator))
    }
}

@Composable
private fun PlaylistGrid(nav: Nav, content: LazyGridScope.() -> Unit) {
    val landscape = LocalLandscape.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(tileSize(landscape)),
        contentPadding = pagePadding(),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

private fun LazyGridScope.playlistItems(list: List<Playlist>, nav: Nav) {
    items(list, key = { it.id }) { p ->
        Box(contentAlignment = Alignment.TopCenter) {
            androidx.compose.foundation.layout.BoxWithConstraints {
                PlaylistTile(p, maxWidth) { nav.open(Route.PlaylistPage(p.id, p.name, p.coverUrl)) }
            }
        }
    }
}
