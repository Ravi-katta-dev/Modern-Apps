package com.vayunmathur.pdf.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

/** Letter-portrait fallback when a page's ratio isn't known yet. */
internal const val FALLBACK_PAGE_RATIO = 612f / 792f

/**
 * Single source of vertical scroll truth for the PDF viewer: one document offset
 * ([scrollPx]) shared by finger drags, pinch pans, the scrubber and programmatic
 * jumps, so no list scroll ever fights the zoom pan.
 *
 * Every page slot is always laid out (so the total height is real) while the
 * expensive decode only runs for slots inside [visibleRange] — place all the
 * boxes, render only the visible pages. Heights come from measured slots,
 * falling back to width/ratio estimates until a slot reports in.
 */
internal class SafePdfScrollState {
    var viewportSize by mutableStateOf(IntSize.Zero)
    var scrollPx by mutableFloatStateOf(0f)
    var pageCount by mutableIntStateOf(0)
    var ratioAt: (Int) -> Float? = { null }
    private val measuredHeights = mutableStateMapOf<Int, Int>()

    /** Cumulative page tops, plus the content height as the last entry. */
    private val tops: List<Float>
        get() {
            val w = viewportSize.width
            if (pageCount <= 0 || w <= 0) return emptyList()
            val out = ArrayList<Float>(pageCount + 1)
            var acc = 0f
            for (i in 0 until pageCount) {
                out.add(acc)
                acc += measuredHeights[i]?.toFloat()
                    ?: (w / (ratioAt(i)?.takeIf { it > 0f } ?: FALLBACK_PAGE_RATIO))
            }
            out.add(acc)
            return out
        }

    fun isReady(): Boolean = pageCount > 0 && viewportSize.height > 0

    fun setMeasuredHeight(index: Int, heightPx: Int) {
        if (heightPx <= 0 || index < 0 || index >= pageCount) return
        if (measuredHeights[index] != heightPx) measuredHeights[index] = heightPx
    }

    fun contentHeightPx(): Float = tops.lastOrNull() ?: 0f

    /** Scrollable range in document pixels at [zoom]. */
    fun range(zoom: Float): Float {
        val h = viewportSize.height.toFloat()
        if (h <= 0f) return 0f
        return (contentHeightPx() - h / zoom.coerceAtLeast(0.01f)).coerceAtLeast(0f)
    }

    fun scrollTo(v: Float, zoom: Float) {
        val clamped = v.coerceIn(0f, range(zoom))
        if (clamped != scrollPx) scrollPx = clamped
    }

    /** Finger/screen drag: content follows the finger. Returns consumed screen px. */
    fun scrollByScreen(dyScreenPx: Float, zoom: Float): Float {
        val z = zoom.coerceAtLeast(0.01f)
        val before = scrollPx
        scrollTo(scrollPx - dyScreenPx / z, zoom)
        return (before - scrollPx) * z
    }

    /** Keep the document point under [centroidY] fixed across a zoom change. */
    fun zoomAround(centroidY: Float, oldZoom: Float, newZoom: Float) {
        val docY = scrollPx + centroidY / oldZoom.coerceAtLeast(0.01f)
        scrollTo(docY - centroidY / newZoom.coerceAtLeast(0.01f), newZoom)
    }

    /** Fraction of the scrollable distance covered, 0 (top) to 1 (true bottom). */
    fun progress(zoom: Float): Float {
        val total = range(zoom)
        if (total <= 0f) return 0f
        return (scrollPx / total).coerceIn(0f, 1f)
    }

    fun pageTop(index: Int): Float {
        val t = tops
        if (t.isEmpty()) return 0f
        return t[index.coerceIn(0, t.size - 1)]
    }

    /** Page containing the viewport top (matches old firstVisibleItem semantics). */
    fun currentPage(): Int {
        val t = tops
        if (t.size < 2) return 0
        val count = t.size - 1
        val idx = t.binarySearch(scrollPx)
        return if (idx >= 0) idx.coerceAtMost(count - 1) else (-idx - 2).coerceIn(0, count - 1)
    }

    /**
     * Visible page range with half-screen overscan. Page-granular so readers
     * recompose only when the set of visible pages changes, not per pixel.
     */
    fun visibleRange(zoom: Float): IntRange {
        val t = tops
        if (t.size < 2) return 0..2
        val count = t.size - 1
        val winH = viewportSize.height.toFloat() / zoom.coerceAtLeast(0.01f)
        if (winH <= 0f) return 0..minOf(2, count - 1)
        val first = currentPage()
        var last = first
        while (last + 1 < count && t[last + 1] < scrollPx + winH * 1.5f) last++
        return (first - 1).coerceAtLeast(0)..last
    }

    fun scrollToPage(index: Int, zoom: Float) {
        if (pageCount <= 0) return
        scrollTo(pageTop(index.coerceIn(0, pageCount - 1)), zoom)
    }
}

@Composable
internal fun rememberSafePdfScrollState(): SafePdfScrollState = remember { SafePdfScrollState() }
