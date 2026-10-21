package com.vayunmathur.photos.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Pinch-to-zoom gesture that drives the gallery grid column count.
 * Extracted from GalleryScreen.kt (FileLength: `ui` package files cap at 350 lines).
 */
internal fun Modifier.pinchToZoomColumns(getColumnCount: () -> Float, setColumnCount: (Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.size > 1) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        setColumnCount((getColumnCount() / zoom).coerceIn(2f, 8f))
                        event.changes.forEach { it.consume() }
                    }
                }
                if (event.changes.all { it.changedToUp() }) break
            }
        }
    }
