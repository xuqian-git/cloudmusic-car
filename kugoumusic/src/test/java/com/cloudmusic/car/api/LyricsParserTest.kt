package com.kugoumusic.car.api

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

    @Test
    fun decodesDownloadedLrcContent() {
        val source = "[00:01.20]first line\r\n[00:03.456]second line"
        val decoded = KuGouMusicApi.decodeLyricBytes(1, source.toByteArray())
        val lines = LyricsParser.merge(decoded, "")

        assertEquals(listOf(1_200L, 3_456L), lines.map { it.timeMs })
        assertEquals(listOf("first line", "second line"), lines.map { it.text })
    }

    @Test
    fun parsesKrcWordTimingLines() {
        val source = "[5490,4380]<0,380,0>词<380,420,0>：<800,520,0>方<1320,500,0>文<1820,500,0>山"

        val lines = LyricsParser.merge(source, "")

        assertEquals(listOf(LyricLine(5_490, "词：方文山", null)), lines)
    }
}
