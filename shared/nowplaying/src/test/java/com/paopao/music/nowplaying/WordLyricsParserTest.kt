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
            withWords = { l, w -> l.copy(words = w.words) },
            create = { L(it.timeMs, it.text, null, it.words) },
        )!!
        assertEquals(2, merged.size)
        assertEquals("A", merged[0].tr)
        assertTrue(merged[0].words.isNotEmpty())
        assertEquals("乙", merged[1].text)
        assertNull(WordLyricsParser.attach(listOf(L(0, "x", null)), emptyList(), { it.t }, { l, _ -> l }, { L(0, "", null) }))
    }

    @Test
    fun litCharactersCountsPartialWord() {
        val words = listOf(LyricWord("车窗", 0, 1000), LyricWord("外", 1000, 2000))
        assertEquals(0f, litCharacters(words, 0), 0.001f)
        assertEquals(1f, litCharacters(words, 500), 0.001f)
        assertEquals(2.5f, litCharacters(words, 1500), 0.001f)
        assertEquals(3f, litCharacters(words, 5000), 0.001f)
    }
}
