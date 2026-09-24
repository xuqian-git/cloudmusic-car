package com.qqmusic.car.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qqmusic.car.api.QQMusicApi
import com.qqmusic.car.data.AccountStore
import com.qqmusic.car.player.PlayerHub
import com.qqmusic.car.ui.Nav
import com.qqmusic.car.ui.Route
import com.qqmusic.car.ui.components.EmptyBox
import com.qqmusic.car.ui.components.Label
import com.qqmusic.car.ui.components.LargeTitle
import com.qqmusic.car.ui.components.LoadContent
import com.qqmusic.car.ui.components.PlaylistTile
import com.qqmusic.car.ui.components.TrackRow
import com.qqmusic.car.ui.components.pressable
import com.qqmusic.car.ui.components.rememberLoad
import com.qqmusic.car.ui.pagePadding
import com.qqmusic.car.ui.theme.K
import com.qqmusic.car.ui.theme.LocalLandscape

private enum class SearchTab(val label: String) { SONGS("歌曲"), PLAYLISTS("歌单") }

/** 进程内保存上次的搜索，返回搜索页时不丢失。 */
private object SearchMemory {
    var input = ""
    var submitted = ""
    var tab = SearchTab.SONGS
}

@Composable
fun SearchScreen(nav: Nav) {
    val c = K.colors
    val pad = pagePadding()
    var input by rememberSaveable { mutableStateOf(SearchMemory.input) }
    var submitted by remember { mutableStateOf(SearchMemory.submitted) }
    var tab by remember { mutableStateOf(SearchMemory.tab) }
    var hint by remember { mutableStateOf("歌曲、歌手、歌单") }
    val keyboard = LocalSoftwareKeyboardController.current
    val hostView = LocalView.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        QQMusicApi.searchDefaultKeyword()?.let { hint = it }
        if (submitted.isEmpty()) runCatching { focus.requestFocus() }
    }

    fun submit() {
        val q = input.trim().ifEmpty { hint.takeIf { it != "歌曲、歌手、歌单" }.orEmpty() }
        if (q.isEmpty()) return
        input = q
        submitted = q
        SearchMemory.input = q
        SearchMemory.submitted = q
        keyboard?.hide()
        // 桌面分屏里键盘由桌面代管，只认输入框失焦；FocusManager 不在桌面共享库清单里，走 View。
        hostView.clearFocus()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(start = pad.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = pad.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = pad.calculateTopPadding()),
    ) {
        LargeTitle("搜索", Modifier.padding(bottom = 20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(84.dp)
                    .background(c.fill, RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = c.secondary, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(14.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) Label(hint, 28.sp, c.secondary)
                    BasicTextField(
                        value = input,
                        onValueChange = {
                            input = it
                            SearchMemory.input = it
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = c.label, fontSize = 28.sp),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (input.isNotEmpty()) {
                    Icon(
                        Icons.Rounded.Cancel, null, tint = c.secondary,
                        modifier = Modifier.size(56.dp).padding(10.dp).pressable {
                            input = ""
                            SearchMemory.input = ""
                            runCatching { focus.requestFocus() }
                        },
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Box(
                Modifier
                    .height(84.dp)
                    .background(c.accent, RoundedCornerShape(20.dp))
                    .pressable { submit() }
                    .padding(horizontal = 34.dp),
                contentAlignment = Alignment.Center,
            ) { Label("搜索", 28.sp, Color.White, FontWeight.SemiBold) }
        }
        Spacer(Modifier.height(20.dp))
        // 分段控件
        Row(
            Modifier
                .background(c.fill, RoundedCornerShape(18.dp))
                .padding(6.dp),
        ) {
            SearchTab.entries.forEach { t ->
                val selected = t == tab
                Box(
                    Modifier
                        .width(160.dp)
                        .height(60.dp)
                        .background(if (selected) c.card else Color.Transparent, RoundedCornerShape(14.dp))
                        .pressable {
                            tab = t
                            SearchMemory.tab = t
                        },
                    contentAlignment = Alignment.Center,
                ) { Label(t.label, 25.sp, c.label, if (selected) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.weight(1f)) {
            if (submitted.isEmpty()) {
                EmptyBox("输入关键词后点“搜索”")
            } else if (tab == SearchTab.SONGS) {
                SongResults(submitted)
            } else {
                PlaylistResults(submitted, nav)
            }
        }
    }
}

@Composable
private fun SongResults(q: String) {
    val loader = rememberLoad("search.songs.$q") { QQMusicApi.searchSongs(q) }
    val current by PlayerHub.current.collectAsState()
    LoadContent(loader) { tracks ->
        if (tracks.isEmpty()) {
            EmptyBox("没有找到相关歌曲")
        } else {
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = pagePadding().calculateBottomPadding())) {
                items(tracks, key = { it.id }) { t ->
                    TrackRow(t, null, current?.id == t.id, AccountStore.unplayableReason(t)) {
                        // 搜索结果点歌：以整份结果作为队列
                        PlayerHub.play(tracks, start = t, source = "搜索：$q")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistResults(q: String, nav: Nav) {
    val landscape = LocalLandscape.current
    val loader = rememberLoad("search.playlists.$q") { QQMusicApi.searchPlaylists(q) }
    LoadContent(loader) { lists ->
        if (lists.isEmpty()) {
            EmptyBox("没有找到相关歌单")
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (landscape) 200.dp else 218.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp, bottom = pagePadding().calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(26.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                items(lists, key = { it.id }) { p ->
                    androidx.compose.foundation.layout.BoxWithConstraints {
                        PlaylistTile(p, maxWidth) { nav.open(Route.PlaylistPage(p.id, p.name, p.coverUrl)) }
                    }
                }
            }
        }
    }
}
