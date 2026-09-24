package com.kugoumusic.car.data

import com.paopao.music.nowplaying.LyricWord
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import com.kugoumusic.car.api.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MusicCacheStats(
    val audioBytes: Long = 0,
    val imageBytes: Long = 0,
    val lyricBytes: Long = 0,
) {
    val totalBytes: Long get() = audioBytes + imageBytes + lyricBytes
}

/** Persistent, bounded cache shared by playback, artwork and lyrics. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@kotlin.OptIn(ExperimentalCoilApi::class)
object MusicCache {
    private const val AUDIO_MAX_BYTES = 5L * 1024 * 1024 * 1024
    private const val IMAGE_MAX_BYTES = 256L * 1024 * 1024
    private const val LOW_SPACE_BYTES = 1L * 1024 * 1024 * 1024
    private const val TARGET_FREE_BYTES = 2L * 1024 * 1024 * 1024

    private lateinit var root: File
    private lateinit var lyricDir: File
    private lateinit var artworkLoader: ImageLoader
    private var initialized = false

    lateinit var audio: SimpleCache
        private set

    private val _stats = MutableStateFlow(MusicCacheStats())
    val stats: StateFlow<MusicCacheStats> = _stats.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val appContext = context.applicationContext
        root = File(appContext.cacheDir, "kugoumusic").apply { mkdirs() }
        lyricDir = File(root, "lyrics").apply { mkdirs() }
        audio = SimpleCache(
            File(root, "audio").apply { mkdirs() },
            LeastRecentlyUsedCacheEvictor(AUDIO_MAX_BYTES),
            StandaloneDatabaseProvider(appContext),
        )
        artworkLoader = ImageLoader.Builder(appContext)
            .okHttpClient(com.kugoumusic.car.api.KuGouMusicClient.http)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(root, "images"))
                    .maxSizeBytes(IMAGE_MAX_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
        initialized = true
        _stats.value = snapshot()
    }

    /**
     * 车机存储快满时的保护，每首歌开始加载前在加载线程调用：剩余空间不到 1GB 就从最久没听的整首删，
     * 删到剩 2GB 为止（正要放的这首不删）。
     */
    @Synchronized
    fun ensureDiskRoom(keepKey: String?) {
        if (!initialized) return
        val dir = File(root, "audio")
        if (dir.usableSpace >= LOW_SPACE_BYTES) return
        val oldestFirst = runCatching {
            audio.keys.toList()
                .map { key -> key to (audio.getCachedSpans(key).maxOfOrNull { it.lastTouchTimestamp } ?: 0L) }
                .sortedBy { it.second }
        }.getOrDefault(emptyList())
        for ((key, _) in oldestFirst) {
            if (dir.usableSpace >= TARGET_FREE_BYTES) break
            if (key != keepKey) runCatching { audio.removeResource(key) }
        }
        _stats.value = snapshot()
    }

    /**
     * 功能包卸载/升级时由运行时调用。SimpleCache 的目录锁记在桌面进程共享的类上，
     * 不释放的话同一进程里新装的包再开这个目录会直接失败。
     */
    @Synchronized
    fun close() {
        if (!initialized) return
        runCatching { audio.release() }
        initialized = false
    }

    fun imageLoader(context: Context): ImageLoader {
        init(context)
        return artworkLoader
    }

    fun readLyrics(trackId: Long): List<LyricLine>? = runCatching {
        val array = JSONArray(File(lyricDir, "$trackId.v2.json").readText())
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    LyricLine(
                        timeMs = item.getLong("time"),
                        text = item.getString("text"),
                        translation = item.optString("translation").takeIf { it.isNotEmpty() },
                        words = item.optJSONArray("words").toLyricWords(),
                    ),
                )
            }
        }.takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun writeLyrics(trackId: Long, lines: List<LyricLine>) {
        if (lines.isEmpty()) return
        val target = File(lyricDir, "$trackId.v2.json")
        val temp = File(lyricDir, "$trackId.tmp")
        val array = JSONArray().apply {
            lines.forEach { line ->
                put(
                    JSONObject()
                        .put("time", line.timeMs)
                        .put("text", line.text)
                        .put("translation", line.translation.orEmpty())
                        .apply { if (line.words.isNotEmpty()) put("words", line.words.toJson()) },
                )
            }
        }
        runCatching {
            temp.writeText(array.toString())
            check(temp.renameTo(target)) { "Unable to publish lyric cache" }
        }.onFailure { temp.delete() }
    }

    suspend fun refreshStats() = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext
        _stats.value = snapshot()
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        if (!initialized) return@withContext
        audio.keys.toList().forEach { key -> runCatching { audio.removeResource(key) } }
        artworkLoader.memoryCache?.clear()
        artworkLoader.diskCache?.clear()
        lyricDir.listFiles().orEmpty().forEach(File::delete)
        _stats.value = MusicCacheStats()
    }

    private fun snapshot() = MusicCacheStats(
        audioBytes = audio.cacheSpace,
        imageBytes = File(root, "images").sizeOnDisk(),
        lyricBytes = lyricDir.sizeOnDisk(),
    )
}

fun formatCacheSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun File.sizeOnDisk(): Long = when {
    isFile -> length()
    isDirectory -> listFiles().orEmpty().sumOf(File::sizeOnDisk)
    else -> 0L
}

/** 逐字片段存成 [文本, 起点, 终点] 三元组，省空间。 */
private fun List<LyricWord>.toJson(): JSONArray = JSONArray().apply {
    forEach { put(JSONArray().put(it.text).put(it.startMs).put(it.endMs)) }
}

private fun JSONArray?.toLyricWords(): List<LyricWord> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        val w = optJSONArray(i) ?: return@mapNotNull null
        LyricWord(w.optString(0), w.optLong(1), w.optLong(2)).takeIf { it.text.isNotEmpty() }
    }
}
