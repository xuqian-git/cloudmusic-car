package com.kugoumusic.car.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.kugoumusic.car.api.KuGouMusicApi
import com.kugoumusic.car.api.KuGouLoginApi
import com.kugoumusic.car.api.KuGouLoginState
import com.kugoumusic.car.api.KuGouLoginType
import com.kugoumusic.car.api.sized
import com.kugoumusic.car.data.AccountStore
import com.kugoumusic.car.data.AudioQuality
import com.kugoumusic.car.data.MusicCache
import com.kugoumusic.car.data.Settings
import com.kugoumusic.car.data.formatCacheSize
import com.kugoumusic.car.ui.Nav
import com.kugoumusic.car.ui.components.Label
import com.kugoumusic.car.ui.components.LargeTitle
import com.kugoumusic.car.ui.components.PillButton
import com.kugoumusic.car.ui.components.pressable
import com.kugoumusic.car.ui.pagePadding
import com.kugoumusic.car.ui.theme.K
import com.kugoumusic.car.ui.theme.LocalLandscape
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------- 扫码登录 ----------

private enum class QrState { LOADING, WAITING, SCANNED, EXPIRED, ERROR }

@Composable
fun LoginScreen() {
    val c = K.colors
    val landscape = LocalLandscape.current
    var attempt by remember { mutableIntStateOf(0) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf(QrState.LOADING) }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var loginType by remember { mutableStateOf(KuGouLoginType.MOBILE) }

    LaunchedEffect(attempt, loginType) {
        state = QrState.LOADING
        qr = null
        serverMessage = null
        val session = runCatching { KuGouLoginApi.create(loginType) }.getOrElse {
            serverMessage = it.message
            state = QrState.ERROR
            return@LaunchedEffect
        }
        qr = BitmapFactory.decodeByteArray(session.image, 0, session.image.size)
        state = QrState.WAITING
        while (true) {
            delay(2000)
            val checked = runCatching { KuGouLoginApi.check(session) }
            if (checked.isFailure) {
                serverMessage = checked.exceptionOrNull()?.message
                delay(2000)
                continue
            }
            val result = checked.getOrThrow()
            when (result) {
                KuGouLoginState.EXPIRED, KuGouLoginState.REFUSED -> {
                    state = QrState.EXPIRED
                    return@LaunchedEffect
                }
                KuGouLoginState.SCANNED -> state = QrState.SCANNED
                KuGouLoginState.DONE -> {
                    AccountStore.onLoginSucceeded()
                    return@LaunchedEffect
                }
                KuGouLoginState.WAITING -> state = QrState.WAITING
            }
        }
    }

    val qrPanel = @Composable {
        Box(
            Modifier
                .size(if (landscape) 400.dp else 460.dp)
                .background(Color.White, RoundedCornerShape(28.dp))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = qr
            when {
                state == QrState.LOADING -> CircularProgressIndicator(color = c.accent, modifier = Modifier.size(64.dp))
                bmp != null && state != QrState.ERROR -> {
                    Image(bmp.asImageBitmap(), null, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
                    if (state == QrState.EXPIRED || state == QrState.SCANNED) {
                        Box(
                            Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (state == QrState.SCANNED) {
                                Icon(Icons.Rounded.CheckCircle, null, tint = Color(0xFF34C759), modifier = Modifier.size(120.dp))
                            } else {
                                Icon(
                                    Icons.Rounded.Refresh, null, tint = Color.Black,
                                    modifier = Modifier.size(120.dp).pressable { attempt++ },
                                )
                            }
                        }
                    }
                }
                else -> Icon(Icons.Rounded.Refresh, null, tint = Color.Black, modifier = Modifier.size(120.dp).pressable { attempt++ })
            }
        }
    }
    val textPanel = @Composable {
        Column(horizontalAlignment = if (landscape) Alignment.Start else Alignment.CenterHorizontally) {
            Label("酷狗音乐", 64.sp, c.label, FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            Label("酷狗音乐车机版", 26.sp, c.secondary)
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.background(c.fill, RoundedCornerShape(16.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KuGouLoginType.entries.forEach { type ->
                    val selected = type == loginType
                    Box(
                        Modifier
                            .background(if (selected) c.accent else Color.Transparent, RoundedCornerShape(12.dp))
                            .pressable { loginType = type }
                            .padding(horizontal = 22.dp, vertical = 12.dp),
                    ) {
                        Label(type.label, 22.sp, if (selected) Color.White else c.label, FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            val status = when (state) {
                QrState.LOADING -> "正在生成二维码…"
                QrState.WAITING -> "打开${loginType.label} App\n扫一扫登录"
                QrState.SCANNED -> "已扫码\n请在手机上确认登录"
                QrState.EXPIRED -> "二维码已过期\n点击二维码刷新"
                QrState.ERROR -> "网络异常\n点击重试"
            }
            Label(status, 34.sp, c.label, FontWeight.SemiBold, maxLines = 3)
            serverMessage?.let {
                Spacer(Modifier.height(16.dp))
                Label(it, 24.sp, c.accent, maxLines = 3)
            }
            if (state == QrState.EXPIRED || state == QrState.ERROR) {
                Spacer(Modifier.height(28.dp))
                PillButton("刷新二维码", Icons.Rounded.Refresh) { attempt++ }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(c.background), contentAlignment = Alignment.Center) {
        if (landscape) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(90.dp)) {
                qrPanel()
                Box(Modifier.width(520.dp)) { textPanel() }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(60.dp)) {
                textPanel()
                qrPanel()
            }
        }
    }
}

// ---------- 设置 ----------

@Composable
fun SettingsScreen(nav: Nav) {
    val c = K.colors
    val context = LocalContext.current
    val profile by AccountStore.profile.collectAsState()
    val quality by Settings.quality.collectAsState()
    val cacheStats by MusicCache.stats.collectAsState()
    val scope = rememberCoroutineScope()
    var clearingCache by remember { mutableStateOf(false) }
    val landscape = LocalLandscape.current
    LaunchedEffect(Unit) { MusicCache.refreshStats() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(pagePadding()),
    ) {
        if (nav.stack.isNotEmpty() || !landscape) {
            Row(
                Modifier.padding(bottom = 16.dp).pressable { nav.back() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ArrowBackIosNew, null, tint = c.accent, modifier = Modifier.size(30.dp))
                Spacer(Modifier.width(6.dp))
                Label("返回", 28.sp, c.accent)
            }
        }
        LargeTitle("设置", Modifier.padding(bottom = 28.dp))

        // 账号
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.fill, RoundedCornerShape(22.dp))
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(c.fill), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, null, tint = c.secondary, modifier = Modifier.size(56.dp))
                profile?.avatarUrl?.let {
                    AsyncImage(
                        it.sized(200),
                        null,
                        imageLoader = MusicCache.imageLoader(context),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Label(profile?.nickname ?: "加载中…", 32.sp, c.label, FontWeight.Bold)
                val vip = when {
                    (profile?.vipType ?: 0) >= 100 -> "黑胶 SVIP"
                    (profile?.vipType ?: 0) > 0 -> "黑胶 VIP"
                    else -> "普通用户"
                }
                Label(vip, 22.sp, c.secondary)
            }
            PillButton("退出登录", null) { AccountStore.logout() }
        }

        Label("音质", 30.sp, c.label, FontWeight.Bold, Modifier.padding(top = 40.dp, bottom = 8.dp))
        Label("无权限时自动降到可用音质", 22.sp, c.secondary, modifier = Modifier.padding(bottom = 18.dp))
        val options = AudioQuality.entries
        val rows = if (landscape) options.chunked(4) else options.chunked(2)
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(bottom = 18.dp)) {
                row.forEach { q ->
                    val selected = q == quality
                    Column(
                        Modifier
                            .weight(1f)
                            .height(124.dp)
                            .background(if (selected) c.accent else c.fill, RoundedCornerShape(20.dp))
                            .pressable { Settings.setQuality(q) }
                            .padding(horizontal = 22.dp),
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Label(q.label, 30.sp, if (selected) Color.White else c.label, FontWeight.Bold)
                        Label(q.detail, 20.sp, if (selected) Color.White.copy(alpha = 0.85f) else c.secondary)
                    }
                }
            }
        }

        Label("缓存", 30.sp, c.label, FontWeight.Bold, Modifier.padding(top = 30.dp, bottom = 8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(c.fill, RoundedCornerShape(22.dp))
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label("已用 ${formatCacheSize(cacheStats.totalBytes)}", 28.sp, c.label, FontWeight.Bold)
                Label(
                    "音乐 ${formatCacheSize(cacheStats.audioBytes)}  ·  " +
                        "图片 ${formatCacheSize(cacheStats.imageBytes)}  ·  " +
                        "歌词 ${formatCacheSize(cacheStats.lyricBytes)}",
                    20.sp,
                    c.secondary,
                )
            }
            Spacer(Modifier.width(24.dp))
            PillButton(
                text = if (clearingCache) "清理中" else "清理缓存",
                icon = Icons.Rounded.DeleteSweep,
            ) {
                if (!clearingCache) {
                    clearingCache = true
                    scope.launch {
                        MusicCache.clear()
                        clearingCache = false
                    }
                }
            }
        }

        Label("关于", 30.sp, c.label, FontWeight.Bold, Modifier.padding(top = 30.dp, bottom = 10.dp))
        Label(
            "酷狗音乐车机版 1.0.0\n根据公开 酷狗音乐接口行为独立实现。\n本应用为非官方客户端，与腾讯及 酷狗音乐无关。",
            21.sp, c.secondary, maxLines = 4,
        )
    }
}
