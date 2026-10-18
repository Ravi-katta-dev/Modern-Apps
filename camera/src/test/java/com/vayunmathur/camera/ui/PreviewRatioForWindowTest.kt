package com.vayunmathur.camera.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Regression tests for #827: portrait crop ratios must flip in landscape windows. */
class PreviewRatioForWindowTest {
    @Test
    fun portraitWindowKeepsPortraitRatio() {
        assertEquals(3f / 4f, previewRatioForWindow(3f / 4f, 1080f, 2400f), 0.0001f)
    }

    @Test
    fun landscapeWindowUsesReciprocal() {
        // 4:3 still crop in a landscape phone window (2400x1080) fills wide, not a strip.
        assertEquals(4f / 3f, previewRatioForWindow(3f / 4f, 2400f, 1080f), 0.0001f)
    }

    @Test
    fun squareRatioUnaffectedByOrientation() {
        assertEquals(1f, previewRatioForWindow(1f, 2400f, 1080f), 0.0001f)
    }
}
