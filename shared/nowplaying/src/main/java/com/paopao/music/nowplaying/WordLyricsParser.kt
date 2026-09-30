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
     * 把逐字行和已有的逐行歌词（带翻译）合成一份，**以逐字为准**。
     *
     * 两份歌词是分开下发的，同一句的起点常差几百毫秒到一两秒，句子切分也不总一样。
     * 以前按 600ms 配对、配不上的两边都留着，于是同一句上下排了两遍。现在：
     * - 每个逐字行找一个逐行歌词来借翻译：1 秒内最近的那句；或者 2 秒内文字相同的那句。
     *   时间和正文用逐字行的（扣光按逐字的字数走）。
     * - 没配上的逐行歌词，落在逐字时间段里的一律丢掉（那段逐字说了算）；
     *   段外的（开头的作词作曲、结尾的版权行）留着。
     *
     * 返回 null 表示逐字数据不可用。
     */
    fun <L> attach(
        lines: List<L>,
        wordLines: List<WordLyricLine>,
        timeOf: (L) -> Long,
        textOf: (L) -> String,
        withWords: (L, WordLyricLine) -> L,
        create: (WordLyricLine) -> L,
    ): List<L>? {
        if (wordLines.isEmpty()) return null
        if (lines.isEmpty()) return wordLines.map(create)
        val unused = lines.toMutableList()
        val merged = wordLines.map { word ->
            val near = unused.filter { kotlin.math.abs(timeOf(it) - word.timeMs) <= TRANSLATION_WINDOW_MS }
            val sameText = unused.filter {
                kotlin.math.abs(timeOf(it) - word.timeMs) <= SAME_TEXT_WINDOW_MS && normalize(textOf(it)) == normalize(word.text)
            }
            val match = sameText.minByOrNull { kotlin.math.abs(timeOf(it) - word.timeMs) }
                ?: near.minByOrNull { kotlin.math.abs(timeOf(it) - word.timeMs) }
            if (match != null) {
                unused.remove(match)
                withWords(match, word)
            } else {
                create(word)
            }
        }
        val first = wordLines.first().timeMs
        val last = wordLines.last().let { line -> line.words.maxOf { it.endMs }.coerceAtLeast(line.timeMs) }
        val outside = unused.filter { timeOf(it) < first || timeOf(it) > last }
        return (merged + outside).sortedBy(timeOf)
    }

    private const val TRANSLATION_WINDOW_MS = 1_000L
    private const val SAME_TEXT_WINDOW_MS = 2_000L

    private val ignorable = Regex("""[\s\p{P}\p{S}]""")
    private fun normalize(text: String) = text.replace(ignorable, "").lowercase()

    /**
     * 序列化成酷狗 KRC 文本交给桌面：`[行起点,行时长]<字偏移,字时长,0>字…`，偏移相对行起点。
     * 没有逐字的行整句当成一个字，时长到下一句开始（封顶 12 秒）。
     * 整份都没有逐字时返回空串——那样和 LRC 没区别，不必多给。
     */
    fun toKrc(lines: List<KrcSource>): String {
        val sorted = lines.filter { it.timeMs >= 0 && it.text.isNotBlank() }.sortedBy { it.timeMs }
        if (sorted.none { it.words.isNotEmpty() }) return ""
        return sorted.mapIndexed { index, line ->
            val start = line.timeMs
            val next = sorted.getOrNull(index + 1)?.timeMs
            val words = line.words.filter { it.text.isNotEmpty() && '\n' !in it.text && '\r' !in it.text }
            if (words.isNotEmpty()) {
                val lineStart = minOf(start, words.first().startMs)
                val end = maxOf(words.maxOf { it.endMs }, lineStart)
                buildString {
                    append('[').append(lineStart).append(',').append(end - lineStart).append(']')
                    for (word in words) {
                        append('<').append(word.startMs - lineStart).append(',')
                            .append((word.endMs - word.startMs).coerceAtLeast(0)).append(",0>").append(word.text)
                    }
                }
            } else {
                val end = if (next != null) minOf(next, start + MAX_PLAIN_LINE_MS) else start + LAST_PLAIN_LINE_MS
                val text = line.text.replace(Regex("[\\r\\n]+"), " ").replace(Regex("[<>]"), "").trim()
                "[$start,${(end - start).coerceAtLeast(0)}]<0,${(end - start).coerceAtLeast(0)},0>$text"
            }
        }.joinToString("\n")
    }

    private const val MAX_PLAIN_LINE_MS = 12_000L
    private const val LAST_PLAIN_LINE_MS = 6_000L
}

/** [WordLyricsParser.toKrc] 的输入：各插件自己的 LyricLine 映射过来。 */
data class KrcSource(val timeMs: Long, val text: String, val words: List<LyricWord>)

/** 媒体会话里放逐字歌词的键。键名带 lyric，桌面按「含 lyric 的键」收集；老桌面只认得 LRC 也不受影响。 */
const val METADATA_KEY_KRC_LYRIC = "com.paopao.music.KRC_LYRIC"
