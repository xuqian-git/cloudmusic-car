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
