package com.kugoumusic.car.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import kotlin.math.min

/** Apple 风格的语义色（参考 iOS systemBackground / label / secondaryLabel 等）。 */
@Immutable
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val sidebar: Color,
    val card: Color,
    val fill: Color,
    val label: Color,
    val secondary: Color,
    val separator: Color,
    val glass: Color,
    val glassBorder: Color,
    val accent: Color,
)

private val Light = AppColors(
    isDark = false,
    background = Color(0xFFFFFFFF),
    sidebar = Color(0xFFF5F5F7),
    card = Color(0xFFFFFFFF),
    fill = Color(0x1F787880),
    label = Color(0xFF000000),
    secondary = Color(0x993C3C43),
    separator = Color(0x2E3C3C43),
    glass = Color(0xF2FAFAFC),
    glassBorder = Color(0xFFFFFFFF),
    accent = Color(0xFF1677FF),
)

private val Dark = AppColors(
    isDark = true,
    background = Color(0xFF000000),
    sidebar = Color(0xFF1C1C1E),
    card = Color(0xFF1C1C1E),
    fill = Color(0x3D787880),
    label = Color(0xFFFFFFFF),
    secondary = Color(0x99EBEBF5),
    separator = Color(0x99545458),
    glass = Color(0xF22A2A2E),
    glassBorder = Color(0x1FFFFFFF),
    accent = Color(0xFF4DA3FF),
)

val LocalAppColors = staticCompositionLocalOf { Light }

/** 是否横屏布局（侧边栏）。 */
val LocalLandscape = staticCompositionLocalOf { true }

object K {
    val colors: AppColors @Composable get() = LocalAppColors.current
}

/**
 * 主题 + 车机自适应缩放。
 *
 * 设计稿按横屏 1280×720、竖屏 800×1280 绘制（单位即 dp）。这里按实际窗口像素重新计算 density，
 * 让不同分辨率/DPI 的车机看到的比例与设计稿一致；更宽的屏幕（如 1920×720）会得到更多横向空间。
 */
@Composable
fun KuGouMusicTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) Dark else Light
    val scheme = if (colors.isDark) {
        darkColorScheme(primary = colors.accent, background = colors.background, surface = colors.background)
    } else {
        lightColorScheme(primary = colors.accent, background = colors.background, surface = colors.background)
    }
    BoxWithConstraints {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val landscape = widthPx >= heightPx
        val (baseW, baseH) = if (landscape) 1280f to 720f else 800f to 1280f
        val scale = min(widthPx / baseW, heightPx / baseH).coerceAtLeast(0.5f)
        val fontScale = LocalConfiguration.current.fontScale.coerceIn(0.85f, 1.3f)
        CompositionLocalProvider(
            LocalDensity provides Density(scale, fontScale),
            LocalAppColors provides colors,
            LocalLandscape provides landscape,
        ) {
            MaterialTheme(colorScheme = scheme) {
                // 去掉 Material 默认的固定行高，大字号多行文本才不会重叠
                CompositionLocalProvider(LocalTextStyle provides TextStyle.Default, content = content)
            }
        }
    }
}
