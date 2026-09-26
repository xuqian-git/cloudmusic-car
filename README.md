# 云音乐 车机版（Android）

网易云音乐非官方车机客户端，Apple Music 风格，横屏 / 竖屏自适应。
接口与 weapi/eapi 加密移植自 [missuo/kumone](https://github.com/missuo/kumone)（LGPL-3.0）。

## 功能
- 扫码登录（请用**网易云音乐 App** 扫码，微信扫码无效）
- 主页：每日推荐、私人 FM、心动模式、推荐歌单
- 排行榜、我喜欢的音乐、最近播放、我的歌单（创建 / 收藏）
- 搜索（歌曲 / 歌单）
- 播放页：封面取色背景、同步滚动歌词（含翻译，点击跳转）、红心、随机 / 循环、播放队列
- 系统媒体通知 / 车机媒体中心 / 方向盘按键控制（Media3 MediaSession）
- 音质：标准 / 极高（默认）/ 无损 / Hi-Res，无权限自动降级；VIP、无版权歌曲置灰并跳过

## 构建
要求 JDK 17+、Android SDK 36。最低支持 Android 9（API 28）。

```bash
./gradlew assembleRelease   # 输出 app/build/outputs/apk/release/app-release.apk
```
Release 使用正式签名：读取根目录 `keystore.properties` 与 `keystore/cloudmusic-release.jks`（两者均不入库）。
**请备份这两个文件**：丢失后无法再发布可覆盖安装的更新。文件缺失时自动回退为 debug 签名。

## 技术栈与开发环境

| 层面 | 技术 |
|------|------|
| 语言 | Kotlin 2.0.21（JVM 17） |
| UI | Jetpack Compose + Material 3（BOM 2024.09.03） |
| 播放器 | Media3 / ExoPlayer 1.4.1 + MediaSessionService |
| 网络 | OkHttp 4.12，网易云接口自行实现 weapi/eapi 加密（AES + RSA） |
| 图片 | Coil 2.7 |
| 其他 | Palette（封面取色）、ZXing（扫码登录）、kotlinx-coroutines |
| 构建 | Gradle 8.x + AGP 8.7.3，Kotlin DSL |

开发环境要求：

- JDK 17+
- Android SDK，`compileSdk = 36`，`minSdk = 28`
- Android Studio（当前版本自带即可）或命令行 `./gradlew`
- Windows 上用 `gradlew.bat`；打包脚本 `tools/package-plugin.sh` 为 shell 脚本，需在 Git Bash / WSL 中运行
- 正式签名需要根目录 `keystore.properties` + `keystore/cloudmusic-release.jks`（不入库），缺失时自动回退 debug 签名

## 产物格式

| 构建命令 | 产物 | 说明 |
|----------|------|------|
| `./gradlew assembleRelease` | `app/build/outputs/apk/release/app-release.apk` | 容器内运行的 APK：启动时校验宿主，不在跑跑桌面容器里会直接退出 |
| `./tools/package-plugin.sh` | `app/build/outputs/plugin/CloudMusic-v<版本>.ppmusic` | 跑跑桌面功能包：`plugin.json + classes.dex + icon.png` 的签名 JAR，不是 APK，只能被跑跑桌面验签加载 |
| `./tools/package-qqmusic-plugin.sh` | `qqmusic/build/outputs/plugin/QQMusic-v<版本>.ppmusic` | QQ 音乐功能包，同理 |
| `./tools/package-kugoumusic-plugin.sh` | `kugoumusic/build/outputs/plugin/KuGouMusic-v<版本>.ppmusic` | 酷狗音乐功能包，同理 |

`.ppmusic` 功能包没有 Activity / Manifest / 资源，只含 DEX + 元数据 + 图标。
宿主通过反射调用 `CloudMusicPlugin.createView(context)` 挂载 ComposeView，
Compose 运行库由宿主提供，插件编译时的 Compose BOM 版本必须与宿主 ABI 对齐
（打包脚本中的 `tools/check-host-abi.py` 负责校验）。

## 本地 / 手机测试

日常开发完全不需要在车机上测试，分两种模式：

1. **独立 APK 模式（推荐日常调试）**：`./gradlew assembleDebug` 生成 debug APK，
   直接安装到手机或模拟器上运行（debug 包跳过宿主校验，三个模块都一样；release 包不行）。
   UI、登录、播放、搜索等功能全部可用，也可用 Android Studio 直接 Run。横竖屏自适应已内置。
2. **功能包模式（.ppmusic）**：需要跑跑桌面作为宿主才能加载。手机上装有跑跑桌面
   （debug 或 release）即可将打包出的 `.ppmusic` 推入测试验签加载流程；
   没有宿主时 `.ppmusic` 文件本身无法安装和运行。

**结论**：日常 UI / 逻辑 / 接口开发用独立 APK 在手机或模拟器上调试即可；
仅最后验证"作为插件被跑跑桌面加载"这一环节需要跑跑桌面环境，两种模式可交替进行。

## 自适应
界面按设计稿尺寸（横屏 1280×720、竖屏 800×1280）换算 density，
不同分辨率 / DPI 的车机显示比例一致；更宽的屏幕（如 1920×720）获得更多横向空间。

## 目录
- `api/`：加密、传输、接口与数据模型
- `player/`：ExoPlayer 队列 + 播放地址按需解析、MediaSessionService
- `data/`：账号、红心、设置
- `ui/`：主题、导航、各页面
# 跑跑桌面音乐功能包

云音乐既可以构建为独立调试 APK，也可以构建为跑跑桌面直接加载的签名功能包。功能包不是
Android APK，没有 Activity、桌面图标或独立进程入口，只能由跑跑桌面验签后加载。

```bash
./tools/package-plugin.sh
```

产物位于 `app/build/outputs/plugin/CloudMusic-v1.0.0.ppmusic`。功能包继续使用现有云音乐
Compose 界面，桌面只提供窗格、生命周期与通用音乐运行库。

## QQ 音乐功能包

同一仓库的 `qqmusic` 模块提供独立的 QQ 音乐功能包。它沿用相同的自适应车机界面、
播放队列恢复和音频/图片/歌词缓存，但账号、设置、队列与缓存目录均和云音乐隔离。
接口按 QQ 音乐公开网络行为独立实现，支持 QQ音乐、QQ、微信三种二维码登录。

```bash
./tools/package-qqmusic-plugin.sh
```

产物位于 `qqmusic/build/outputs/plugin/QQMusic-v1.0.0.ppmusic`。

## 酷狗音乐功能包

`kugoumusic` 模块提供独立的酷狗音乐功能包，沿用相同的自适应车机界面、播放恢复与缓存，
支持酷狗、QQ、微信扫码登录，并使用酷狗的真实音乐云盘接口。

```bash
./tools/package-kugoumusic-plugin.sh
```

产物位于 `kugoumusic/build/outputs/plugin/KuGouMusic-v1.0.0.ppmusic`。

## 许可证

本项目以 GNU LGPL-3.0 发布。许可证正文见 [`LICENSE`](LICENSE)，其引用的 GNU GPL-3.0 条款见 [`COPYING.GPL`](COPYING.GPL)。
