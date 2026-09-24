package com.paopao.music.nowplaying

/** 逐字歌词的一行：行起点 + 逐字片段（绝对毫秒）。 */
data class WordLyricLine(val timeMs: Long, val text: String, val words: List<LyricWord>)

/**
 * 逐字歌词解析。
 *
 * - 酷狗 KRC：`[行起点,行时长]<字偏移,字时长,0>字…`，字偏移相对行起点。
 * - 网易云 YRC：`[行起点,行时长](字起点,字时长,0)字…`，字起点为歌曲内绝对时间；
 *   另有 `{"t":…}` 开头的元信息行，直接跳过。
 *
 * 任何一行的标记不完整都整行丢弃，绝不把时间标记当歌词显示。
 */
object WordLyricsParser {
    private val lineTag = Regex("""^\[(\d+),(\d+)]""")
    private val krcWord = Regex("""<(\d+),(\d+)(?:,-?\d+)?>""")
    private val yrcWord = Regex("""\((\d+),(\d+)(?:,-?\d+)?\)""")
    /** 残缺的时间标记（如 `<12,3` 或 `(1200,30`）。 */
    private val brokenTag = Regex("""[<>]|\(\d+,\d+""")
    private const val MAX_TIME_MS = 24 * 60 * 60 * 1000L

    fun parseKrc(content: String): List<WordLyricLine> = parse(content, krcWord, relative = true)

    fun parseYrc(content: String): List<WordLyricLine> = parse(content, yrcWord, relative = false)

    private fun parse(content: String, wordTag: Regex, relative: Boolean): List<WordLyricLine> =
        content.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val tag = lineTag.find(line) ?: return@mapNotNull null
            val start = tag.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_TIME_MS } ?: return@mapNotNull null
            val body = line.substring(tag.range.last + 1)
            val tags = wordTag.findAll(body).toList()
            if (tags.isEmpty() || tags.first().range.first != 0) return@mapNotNull null
            val words = ArrayList<LyricWord>(tags.size)
            for ((index, word) in tags.withIndex()) {
                val a = word.groupValues[1].toLongOrNull()?.takeIf { it <= MAX_TIME_MS } ?: return@mapNotNull null
                val length = word.groupValues[2].toLongOrNull()?.takeIf { it <= MAX_TIME_MS } ?: return@mapNotNull null
                val text = body.substring(word.range.last + 1, tags.getOrNull(index + 1)?.range?.first ?: body.length)
                if (brokenTag.containsMatchIn(text)) return@mapNotNull null
                if (text.isEmpty()) continue
                val wordStart = if (relative) start + a else a
                words += LyricWord(text, wordStart, wordStart + length)
            }
            if (words.isEmpty() || words.zipWithNext().any { (x, y) -> x.startMs > y.startMs }) return@mapNotNull null
            val text = words.joinToString("") { it.text }
            if (text.isBlank()) return@mapNotNull null
            WordLyricLine(start, text.trim(), words)
        }.sortedBy { it.timeMs }.toList()

    /**
     * 把逐字行按时间对齐到已有的逐行歌词上（逐行里带着翻译）。
     * 对不上时间的逐字行单独成行；返回 null 表示逐字数据不可用。
     */
    fun <L> attach(
        lines: List<L>,
        wordLines: List<WordLyricLine>,
        timeOf: (L) -> Long,
        withWords: (L, WordLyricLine) -> L,
        create: (WordLyricLine) -> L,
    ): List<L>? {
        if (wordLines.isEmpty()) return null
        if (lines.isEmpty()) return wordLines.map(create)
        val remaining = wordLines.toMutableList()
        val merged = lines.map { line ->
            val t = timeOf(line)
            val match = remaining.minByOrNull { kotlin.math.abs(it.timeMs - t) }
                ?.takeIf { kotlin.math.abs(it.timeMs - t) <= 600 }
            if (match != null) {
                remaining.remove(match)
                withWords(line, match)
            } else {
                line
            }
        }
        return (merged + remaining.map(create)).sortedBy(timeOf)
    }
}
