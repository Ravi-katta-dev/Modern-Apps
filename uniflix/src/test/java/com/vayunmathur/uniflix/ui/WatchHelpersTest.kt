package com.vayunmathur.uniflix.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure player helpers: time labels and sidecar subtitle MIME inference. */
class WatchHelpersTest {

    @Test
    fun formats_minutes_and_seconds() {
        assertEquals("0:00", formatWatchTime(0L))
        assertEquals("1:05", formatWatchTime(65_000L))
        assertEquals("12:34", formatWatchTime(754_000L))
    }

    @Test
    fun formats_hours_without_dropping_minutes() {
        assertEquals("1:02:03", formatWatchTime(3_723_000L))
    }

    @Test
    fun clamps_negative_positions() {
        assertEquals("0:00", formatWatchTime(-500L))
    }

    @Test
    fun infers_vtt_by_default() {
        assertEquals("text/vtt", subtitleMime("https://subs.example.com/sub/en.vtt"))
        assertEquals("text/vtt", subtitleMime("https://subs.example.com/sub/en.vtt?token=abc"))
        assertEquals("text/vtt", subtitleMime("https://subs.example.com/sub/en"))
    }

    @Test
    fun infers_srt_and_ttml() {
        assertEquals("application/x-subrip", subtitleMime("https://subs.example.com/sub/en.srt"))
        assertEquals("application/ttml+xml", subtitleMime("https://subs.example.com/sub/en.ttml"))
    }
}
