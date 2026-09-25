# 断网时的播放器错误处理

## 修改文件

- `app/src/main/java/com/cloudmusic/car/player/PlayerHub.kt`、`qqmusic/src/main/java/com/qqmusic/car/player/PlayerHub.kt`、`kugoumusic/src/main/java/com/kugoumusic/car/player/PlayerHub.kt`：三份相同的断网等待、恢复、连续失败处理。此逻辑与各自的播放器状态和入口紧耦合，没有为抽取到 `shared/` 增加额外源码目录。
- `app/build.gradle.kts`、`qqmusic/build.gradle.kts`、`kugoumusic/build.gradle.kts`：各自的 `versionCode` 和 `versionName` 增加一个 patch 版本。

## 行为

报错时，无已验证的互联网连接，或 Media3 报网络连接失败、超时、未明确分类的 I/O 错误，就保留当前歌曲及位置并暂停，不自动跳过。只为这次等待注册默认网络回调；网络重新得到 `INTERNET` 和 `VALIDATED` 能力后，从保存的位置 `prepare()`、`play()`。如果报错时系统仍报告网络已验证，等待回调观察到网络失效后再次验证，避免立即重试循环。手动播放、切歌、跳转、换播放列表或切换播放设置会取消等待；释放 PlayerHub 也注销回调。同一次断网只提示一次「网络断开，恢复后自动继续」，连续正常播放十秒后才允许下次提示。没有为等待网络添加轮询。

解析接口明确返回「无播放地址」时，即使 Media3 将它包装成未分类 I/O 错误，有网情况下也按不可播放歌曲处理。

有网时非网络错误继续跳下一首；连续第五次失败暂停并提示「连续几首都放不了，已暂停」。计数不再随 `isPlaying` 变成 `true` 清零，而是利用原有每五秒的位置持久化周期核对同一首歌曲连续前进的播放位置；暂停、切歌、跳转会重置这段进度。用户手动选择歌曲或点播放会清零。

## 新类与 ABI

只新增 Android framework 的 `ConnectivityManager`、`Network`、`NetworkCapabilities` 用法；没有新增 `androidx` 或 `kotlinx` 类或依赖。Media3 的 `PlaybackException`、`Player` 沿用已有依赖。

## 模拟器验证（交给 Claude）

1. 在三款插件分别播放一首未完整缓存的歌曲，运行 `adb shell svc wifi disable; adb shell svc data disable`，等到网络读取失败：歌曲和进度不跳转，只出现一次断网提示，等待时没有不断重试。
2. 运行 `adb shell svc wifi enable`（如使用蜂窝网，也运行 `adb shell svc data enable`）；待网络重新验证，确认仍是原曲且从原位置继续。重复一次，并在断网等待时手动点播放、切歌或切换模式，确认不会再由旧回调自动恢复。
3. 网络正常时让队列连续出现坏链接或无法解码的歌曲：前四次可跳歌，第五次暂停并显示连续失败提示；再手动选歌确认可以播放。用 `adb emu network speed` / `adb emu network delay` 也可辅助制造慢速或延迟网络，不能代替断网用例。
