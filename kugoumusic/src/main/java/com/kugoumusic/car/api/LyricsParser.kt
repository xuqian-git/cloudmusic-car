package com.kugoumusic.car.api

object LyricsParser {
    private val timeTag = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")
    private val krcTimeTag = Regex("""^\[(\d+),(\d+)]""")
    private val krcWordTag = Regex("""<\d+,\d+,\d+>""")

    /** 解析 LRC：支持一行多个时间戳，以及 `.` / `:` 两种毫秒分隔符。 */
    fun parse(lrc: String): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            val matches = timeTag.findAll(line).toList()
            if (matches.isEmpty()) {
                val krc = krcTimeTag.find(line) ?: continue
                val text = line.substring(krc.range.last + 1).replace(krcWordTag, "").trim()
                result += krc.groupValues[1].toLong() to text
                continue
            }
            val text = line.substring(matches.last().range.last + 1).trim()
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fracStr = m.groupValues[3]
                val fracMs = if (fracStr.isEmpty()) 0L else when (fracStr.length) {
                    1 -> fracStr.toLong() * 100
                    2 -> fracStr.toLong() * 10
                    else -> fracStr.take(3).toLong()
                }
                result += (min * 60_000 + sec * 1000 + fracMs) to text
            }
        }
        return result.sortedBy { it.first }
    }

    /** 合并原文与翻译，丢弃空行。 */
    fun merge(lrc: String, translation: String): List<LyricLine> {
        val trans = parse(translation).filter { it.second.isNotBlank() }.toMap()
        return parse(lrc)
            .filter { it.second.isNotBlank() }
            .map { (time, text) -> LyricLine(time, text, trans[time]) }
    }

    /** 序列化为供系统媒体会话暴露的标准 LRC；翻译仍由应用内界面单独展示。 */
    fun toLrc(lines: List<LyricLine>): String = lines
        .asSequence()
        .filter { it.timeMs >= 0 && it.text.isNotBlank() }
        .sortedBy { it.timeMs }
        .joinToString("\n") { line ->
            val minutes = line.timeMs / 60_000
            val seconds = line.timeMs % 60_000 / 1_000
            val millis = line.timeMs % 1_000
            val text = line.text.replace(Regex("[\\r\\n]+"), " ").trim()
            "[%02d:%02d.%03d]%s".format(minutes, seconds, millis, text)
        }

    /** 二分查找当前行。 */
    fun activeIndex(lines: List<LyricLine>, positionMs: Long): Int {
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timeMs <= positionMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}
