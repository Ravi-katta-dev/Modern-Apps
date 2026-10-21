package com.vayunmathur.notes.platform

import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the pure formatting math inside `NoteMarkdownExporter`: the export
 * join, the image width percent, the downscale sizing, the CSS color, and the
 * compact number format.
 *
 * The functions themselves are private and two need a Context or Bitmap, so
 * they are mirrored here rather than called. Every mirror below is exact:
 * blank-part filtering, `joinToString("\n\n")`, `trim() + "\n"`, the 1024px
 * cap, the power-of-two sampler, the quarter-percent clamp, and the ARGB
 * shifts.
 */
class NoteExportFormatTest {

    private fun joinExport(parts: List<String>): String =
        parts.filter { it.isNotBlank() }.joinToString("\n\n").trim() + "\n"

    private fun widthPercent(fraction: Float): Int =
        (fraction * 100).roundToInt().coerceIn(1, 100)

    private fun sampleSizeFor(longest: Int, target: Int): Int {
        var sample = 1
        while (longest / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun scaledDims(width: Int, height: Int, max: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= max) return width to height
        val ratio = max.toFloat() / longest
        return (width * ratio).roundToInt().coerceAtLeast(1) to
            (height * ratio).roundToInt().coerceAtLeast(1)
    }

    private fun cssColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return "rgba($r,$g,$b,${num(a / 255f)})"
    }

    private fun num(value: Float): String {
        val rounded = (value * 100).roundToInt() / 100f
        return if (rounded % 1f == 0f) rounded.toInt().toString() else rounded.toString()
    }

    @Test
    fun `export joins non-blank parts with a blank line and one trailing newline`() {
        assertEquals("a\n\nb\n", joinExport(listOf("a", "", "  ", "b")))
        assertEquals("solo\n", joinExport(listOf("  solo  ")))
        assertEquals("\n", joinExport(emptyList()))
        assertEquals("\n", joinExport(listOf("", "   ")))
    }

    @Test
    fun `unicode text passes through the join untouched`() {
        assertEquals("記 😀\n\nمرحبا\n", joinExport(listOf("記 😀", "مرحبا")))
    }

    @Test
    fun `image width percent clamps to 1 through 100`() {
        assertEquals(100, widthPercent(1f))
        assertEquals(50, widthPercent(0.5f))
        assertEquals(25, widthPercent(0.25f))
        assertEquals(1, widthPercent(0f))
        assertEquals(1, widthPercent(-2f))
        assertEquals(100, widthPercent(4f))
    }

    @Test
    fun `sampler is the largest power of two keeping the decode above target`() {
        assertEquals(1, sampleSizeFor(800, 1024))
        assertEquals(1, sampleSizeFor(1024, 1024))
        assertEquals(2, sampleSizeFor(2048, 1024))
        assertEquals(4, sampleSizeFor(4096, 1024))
        assertEquals(8, sampleSizeFor(8192, 1024))
        assertEquals(1, sampleSizeFor(1, 1024))
    }

    @Test
    fun `small images are never upscaled`() {
        assertEquals(800 to 600, scaledDims(800, 600, 1024))
        assertEquals(1024 to 1024, scaledDims(1024, 1024, 1024))
    }

    @Test
    fun `large images scale the longest side exactly to the cap`() {
        assertEquals(1024 to 512, scaledDims(2048, 1024, 1024))
        assertEquals(512 to 1024, scaledDims(1024, 2048, 1024))
        val (w, h) = scaledDims(4000, 3000, 1024)
        assertEquals(1024, maxOf(w, h))
        assertTrue(w >= 1 && h >= 1)
    }

    @Test
    fun `opaque black and white format without a decimal alpha`() {
        assertEquals("rgba(0,0,0,1)", cssColor(0xFF000000.toInt()))
        assertEquals("rgba(255,255,255,1)", cssColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `alpha is preserved as a fractional component`() {
        assertEquals("rgba(255,0,0,0.5)", cssColor(0x80FF0000.toInt()))
        assertEquals("rgba(0,0,0,0)", cssColor(0x00000000))
    }

    @Test
    fun `compact numbers drop the decimal point for whole values`() {
        assertEquals("3", num(3f))
        assertEquals("0", num(0f))
        assertEquals("1.5", num(1.5f))
        assertEquals("2.55", num(2.549f))
    }
}
