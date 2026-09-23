package com.qqmusic.car.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    private val chartCover = "http://y.gtimg.cn/music/photo_new/T003R300x300M000001l6zwH2HbWtj.jpg"

    @Test
    fun sized_usesSupportedQqCdnDimensions() {
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T003R150x150M000001l6zwH2HbWtj.jpg",
            chartCover.sized(96),
        )
        assertEquals(
            "https://y.gtimg.cn/music/photo_new/T003R500x500M000001l6zwH2HbWtj.jpg",
            chartCover.sized(400),
        )
    }

    @Test
    fun sized_preservesNonQqArtworkUrls() {
        assertEquals("https://example.com/R300x300.jpg", "http://example.com/R300x300.jpg".sized(400))
    }

    @Test
    fun qqFileFormat_mapsEachVisibleQualityToItsRealCdnFormat() {
        assertEquals("M500" to ".mp3", qqFileFormat("standard"))
        assertEquals("M800" to ".mp3", qqFileFormat("exhigh"))
        assertEquals("F000" to ".flac", qqFileFormat("lossless"))
        assertEquals("AI00" to ".flac", qqFileFormat("master"))
    }
}
