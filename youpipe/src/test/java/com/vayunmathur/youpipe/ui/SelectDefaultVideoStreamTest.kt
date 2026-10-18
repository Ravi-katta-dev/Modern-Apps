package com.vayunmathur.youpipe.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun stream(height: Int) = VideoStream(
    url = "https://example.com/$height",
    width = height * 16 / 9,
    height = height,
    bitrate = height * 1000,
    fps = 30,
    quality = "${height}p",
    codec = "avc",
    size = 0L,
)

class SelectDefaultVideoStreamTest {

    private val streams = listOf(stream(2160), stream(1080), stream(720), stream(480))

    @Test
    fun `1080p device picks 1080p not 4k`() {
        assertEquals(1080, selectDefaultVideoStream(streams, deviceHeightPx = 1080)?.height)
    }

    @Test
    fun `720p device picks 720p`() {
        assertEquals(720, selectDefaultVideoStream(streams, deviceHeightPx = 720)?.height)
    }

    @Test
    fun `4k device picks 4k`() {
        assertEquals(2160, selectDefaultVideoStream(streams, deviceHeightPx = 2160)?.height)
    }

    @Test
    fun `in-between device height picks next stream down`() {
        // 1440p phone: 1080p is the best that fits.
        assertEquals(1080, selectDefaultVideoStream(streams, deviceHeightPx = 1440)?.height)
    }

    @Test
    fun `tiny device falls back to lowest stream instead of nothing`() {
        assertEquals(480, selectDefaultVideoStream(streams, deviceHeightPx = 360)?.height)
    }

    @Test
    fun `unknown device height keeps old behavior of highest stream`() {
        assertEquals(2160, selectDefaultVideoStream(streams, deviceHeightPx = 0)?.height)
    }

    @Test
    fun `empty stream list returns null`() {
        assertNull(selectDefaultVideoStream(emptyList(), deviceHeightPx = 1080))
    }
}
