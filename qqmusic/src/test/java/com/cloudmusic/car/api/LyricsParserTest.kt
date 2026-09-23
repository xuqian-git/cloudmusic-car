package com.qqmusic.car.api

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsParserTest {
    @Test
    fun toLrc_formatsAndSortsLines() {
        val lines = listOf(
            LyricLine(125_006, "second", null),
            LyricLine(1_230, "first", "translation"),
        )

        assertEquals(
            "[00:01.230]first\n[02:05.006]second",
            LyricsParser.toLrc(lines),
        )
    }

    @Test
    fun toLrc_omitsEmptyAndNegativeLines() {
        val lines = listOf(
            LyricLine(-1, "invalid", null),
            LyricLine(0, "  ", null),
        )

        assertEquals("", LyricsParser.toLrc(lines))
    }

    @Test
    fun toLrc_keepsEachEntryOnOneLine() {
        val lines = listOf(LyricLine(61_001, " first\nline ", null))

        assertEquals("[01:01.001]first line", LyricsParser.toLrc(lines))
    }
}
