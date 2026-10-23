package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

/**
 * Draggable page-number scrubber for the right edge. The pill's y position tracks
 * the scroll fraction of the single [SafePdfScrollState] offset, and dragging it
 * moves that same offset — no list scroll to fight. Always visible.
 */
@Composable
internal fun PdfPageScroller(
    scroll: SafePdfScrollState,
    zoom: SafePdfZoomState,
    pageCount: Int,
) {
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var thumbHeightPx by remember { mutableIntStateOf(0) }

    val travelPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1).toFloat()
    val thumbOffsetPx = scroll.progress(zoom.zoom) * travelPx
    val pageH = scroll.contentHeightPx() / pageCount.coerceAtLeast(1)
    val currentPage = if (pageH > 0f) {
        (scroll.scrollPx / pageH).toInt().coerceIn(0, pageCount - 1) + 1
    } else {
        scroll.currentPage() + 1
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { trackHeightPx = it.height },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .padding(end = 8.dp)
                .background(Color(0xCC000000), MaterialTheme.shapes.large)
                .pointerInput(travelPx, pageCount, zoom.zoom) {
                    detectVerticalDragGestures(
                        onVerticalDrag = { _, dragAmount ->
                            val range = scroll.range(zoom.zoom)
                            scroll.scrollTo(scroll.scrollPx + dragAmount / travelPx * range, zoom.zoom)
                        },
                    )
                }
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .onSizeChanged { thumbHeightPx = it.height },
        ) {
            Text(
                text = "$currentPage / $pageCount",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
