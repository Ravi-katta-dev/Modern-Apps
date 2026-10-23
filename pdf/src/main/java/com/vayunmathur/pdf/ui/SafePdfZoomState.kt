package com.vayunmathur.pdf.ui

import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * Pinch-zoom + horizontal pan state for the page viewer. Vertical scrolling lives
 * in [SafePdfScrollState] — a single document offset shared by finger drags,
 * pinch pans, the scrubber and programmatic jumps — so there is only one source
 * of vertical truth instead of a list scroll fighting a zoom pan.
 */
internal class SafePdfZoomState {
    var viewportSize by mutableStateOf(IntSize.Zero)
    var zoom by mutableFloatStateOf(1f)
    // Horizontal pan only; vertical is owned by SafePdfScrollState, so y stays 0.
    var pan by mutableStateOf(Offset.Zero)
    // Widest page seen so far (PDF points), used to raise the zoom cap for large
    // pages so their detail can be inspected. Monotonic across lazily-loaded pages.
    var maxPageWidthPts by mutableFloatStateOf(0f)
    // True while ≥2 fingers are down. The scrollable is a descendant of the
    // transformable, so it can otherwise claim a two-finger drag as a scroll
    // before the pinch is recognized; disabling scroll during multi-touch makes a
    // pinch always zoom.
    var multiTouch by mutableStateOf(false)

    fun onPageWidth(w: Float) {
        if (w > maxPageWidthPts) maxPageWidthPts = w
    }

    /** Route a transformable delta: zoom about the centroid, horizontal pan, vertical scroll. */
    fun onTransform(centroid: Offset, zoomChange: Float, panChange: Offset, scroll: SafePdfScrollState) {
        val zoomOld = zoom
        val maxZoom = maxZoomFor(maxPageWidthPts, viewportSize.width)
        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, maxZoom)
        if (newZoom != zoom) {
            zoom = newZoom
            scroll.zoomAround(centroid.y, zoomOld, newZoom)
        }
        val maxX = maxPanX(viewportSize.width, zoom)
        val x = (pan.x + panChange.x).coerceIn(-maxX, maxX)
        if (panChange.y != 0f) scroll.scrollByScreen(panChange.y, zoom)
        val next = Offset(x, 0f)
        if (next != pan) pan = next
    }

    /** Animate-free jump used by the double-tap tween in [SafePdfPageViewer]. */
    fun setZoomPan(z: Float, p: Offset) {
        zoom = z
        pan = Offset(p.x, 0f)
    }
}

@Composable
internal fun rememberSafePdfZoomState(scroll: SafePdfScrollState): Pair<SafePdfZoomState, TransformableState> {
    val state = remember { SafePdfZoomState() }
    val transform = rememberTransformableState { centroid, zoomChange, panChange, _ ->
        state.onTransform(centroid, zoomChange, panChange, scroll)
    }
    return state to transform
}
