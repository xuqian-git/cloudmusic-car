package com.paopao.music.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordLyricsParserTest {
    @Test
    fun krcWordOffsetsAreRelativeToLine() {
        val lines = WordLyricsParser.parseKrc(
            """
            [id:$00000000]
            [1000,2000]<0,500,0>车<500,500,0>窗<1000,1000,0>外
            [4000,1000]<0,400,0>风<400,600,0>来
            """.trimIndent(),
        )
        assertEquals(2, lines.size)
        assertEquals("车窗外", lines[0].text)
        assertEquals(LyricWord("车", 1000, 1500), lines[0].words[0])
        assertEquals(LyricWord("外", 2000, 3000), lines[0].words[2])
        assertEquals(4400L, lines[1].words[1].startMs)
    }

    @Test
    fun yrcWordTimesAreAbsoluteAndMetaLinesSkipped() {
        val lines = WordLyricsParser.parseYrc(
            """
            {"t":0,"c":[{"tx":"作词: "}]}
            [16210,3460](16210,670,0)还(16880,410,0)没 (17290,500,0)好
            """.trimIndent(),
        )
        assertEquals(1, lines.size)
        assertEquals("还没 好", lines[0].text)
        assertEquals(LyricWord("没 ", 16880, 17290), lines[0].words[1])
    }

    @Test
    fun brokenTagsDropTheWholeLine() {
        val lines = WordLyricsParser.parseKrc("[1000,2000]<0,500,0>车<500,50窗\n[3000,500]<0,500,0>好")
        assertEquals(listOf("好"), lines.map { it.text })
    }

    @Test
    fun parenthesesInLyricsSurvive() {
        val lines = WordLyricsParser.parseYrc("[0,1000](0,500,0)(合)(500,500,0)唱")
        assertEquals("(合)唱", lines.single().text)
    }

    @Test
    fun attachKeepsTranslationAndAddsUnmatchedLines() {
        data class L(val t: Long, val text: String, val tr: String?, val words: List<LyricWord> = emptyList())
        val words = WordLyricsParser.parseYrc("[1000,500](1000,500,0)甲\n[9000,500](9000,500,0)乙")
        val merged = WordLyricsParser.attach(
            listOf(L(1200, "甲", "A")), words,
            timeOf = { it.t },
            textOf = { it.text },
            withWords = { l, w -> l.copy(words = w.words) },
            create = { L(it.timeMs, it.text, null, it.words) },
        )!!
        assertEquals(2, merged.size)
        assertEquals("A", merged[0].tr)
        assertTrue(merged[0].words.isNotEmpty())
        assertEquals("乙", merged[1].text)
        assertNull(WordLyricsParser.attach(listOf(L(0, "x", null)), emptyList(), { it.t }, { it.text }, { l, _ -> l }, { L(0, "", null) }))
    }

    @Test
    fun litCharactersCountsPartialWord() {
        val words = listOf(LyricWord("车窗", 0, 1000), LyricWord("外", 1000, 2000))
        assertEquals(0f, litCharacters(words, 0), 0.001f)
        assertEquals(1f, litCharacters(words, 500), 0.001f)
        assertEquals(2.5f, litCharacters(words, 1500), 0.001f)
        assertEquals(3f, litCharacters(words, 5000), 0.001f)
    }

    @Test
    fun attachDoesNotRepeatALineWhoseTimesDisagree() {
        data class L(val t: Long, val text: String, val tr: String?, val words: List<LyricWord> = emptyList())
        // 逐字和逐行同一句差了 1.4 秒；逐行多出一句开头的作词行、一句被切碎的行。
        val words = WordLyricsParser.parseYrc("[10000,2000](10000,1000,0)你(11000,1000,0)好\n[20000,1000](20000,1000,0)再见")
        val merged = WordLyricsParser.attach(
            listOf(L(0, "作词：某人", null), L(11400, "你好", "hello"), L(15000, "碎片", null), L(20300, "再见", "bye")),
            words,
            timeOf = { it.t },
            textOf = { it.text },
            withWords = { l, w -> l.copy(t = w.timeMs, text = w.text, words = w.words) },
            create = { L(it.timeMs, it.text, null, it.words) },
        )!!
        assertEquals(listOf("作词：某人", "你好", "再见"), merged.map { it.text })
        assertEquals(listOf(0L, 10000L, 20000L), merged.map { it.t })
        assertEquals("hello", merged[1].tr)
        assertEquals("bye", merged[2].tr)
    }

    @Test
    fun toKrcWritesRelativeWordOffsetsAndWholeLineForPlainLines() {
        val krc = WordLyricsParser.toKrc(
            listOf(
                KrcSource(0, "作词：某人", emptyList()),
                KrcSource(10000, "你好", listOf(LyricWord("你", 10000, 10500), LyricWord("好", 10600, 11000))),
            ),
        )
        assertEquals("[0,10000]<0,10000,0>作词：某人\n[10000,1000]<0,500,0>你<600,400,0>好", krc)
        val back = WordLyricsParser.parseKrc(krc)
        assertEquals(listOf("作词：某人", "你好"), back.map { it.text })
        assertEquals(10600L, back[1].words[1].startMs)
        assertEquals("", WordLyricsParser.toKrc(listOf(KrcSource(0, "只有整句", emptyList()))))
    }
}
